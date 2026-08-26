package com.maowu.gravitypet;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Continuous MiMo conversation using local VAD, cloud ASR, reasoning, and TTS. */
final class ContinuousConversationController {
    interface Listener {
        void showStatus(String text);
        void showUserMessage(String text);
        void showPetMessage(String text);
        void onConversationStopped();
    }

    private static final int SAMPLE_RATE = 16_000;
    private static final int ANALYSIS_FRAME_SAMPLES = 320; // 20 ms at 16 kHz
    private static final int NO_SPEECH_TIMEOUT_MS = 15_000;
    private static final int MAX_UTTERANCE_MS = 25_000;
    // Keep listening through normal pauses between clauses. 430 ms was short
    // enough to submit while the user was only taking a breath.
    private static final int END_SILENCE_MS = 1_000;
    private static final int MIN_SPEECH_MS = 220;
    private static final double MIN_START_RMS = 420.0;
    private static final double MIN_CONTINUATION_RMS = 160.0;
    private static final int PRE_ROLL_CHUNKS = 25;
    private static final int BARGE_IN_IGNORE_MS = 350;
    private static final int BARGE_IN_CONFIRM_MS = 260;
    private static final int BARGE_IN_CANDIDATE_GAP_MS = 120;
    private static final double MIN_BARGE_IN_RMS = 500.0;
    private static final String TAG = "XiaoBiGaVoice";

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService recordingExecutor = Executors.newSingleThreadExecutor();
    private volatile AudioRecord currentRecorder;
    private volatile boolean bargeInMonitorActive;
    private MediaPlayer currentPlayer;
    private File currentSpeechFile;
    private final ArrayDeque<String> speechQueue = new ArrayDeque<>();
    private final ArrayDeque<byte[]> audioQueue = new ArrayDeque<>();
    private boolean synthesisBusy;
    private boolean playbackBusy;
    private boolean replyStreamComplete;
    private volatile boolean active;
    private volatile boolean destroyed;
    private boolean submitted;
    private volatile int sessionGeneration;
    private int conversationSkinId;

    ContinuousConversationController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    boolean isActive() {
        return active;
    }

    void setConversationSkinId(int skinId) {
        if (!active) conversationSkinId = skinId;
    }

    void start() {
        if (active || destroyed) return;
        active = true;
        submitted = false;
        resetSpeechPipeline();
        sessionGeneration++;
        startListening(sessionGeneration);
    }

    void stop() {
        if (!active) return;
        active = false;
        sessionGeneration++;
        handler.removeCallbacksAndMessages(null);
        bargeInMonitorActive = false;
        closeRecorder();
        releasePlayer();
        resetSpeechPipeline();
        listener.onConversationStopped();
    }

    void shutdown() {
        destroyed = true;
        active = false;
        sessionGeneration++;
        handler.removeCallbacksAndMessages(null);
        bargeInMonitorActive = false;
        closeRecorder();
        releasePlayer();
        resetSpeechPipeline();
        recordingExecutor.shutdownNow();
    }

    private void startListening(int generation) {
        if (!isCurrent(generation) || currentRecorder != null || submitted) return;
        listener.showStatus("🎙 我在听…");
        recordingExecutor.execute(() -> recordOneUtterance(generation));
    }

    private void recordOneUtterance(int generation) {
        if (!isCurrent(generation)) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            postErrorAndStop(generation, "缺少麦克风权限");
            return;
        }

        int minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(4096, minBytes > 0 ? minBytes : 4096);
        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes * 2);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("麦克风初始化失败");
            }
            currentRecorder = recorder;
            recorder.startRecording();

            // Analyze small, fixed-duration frames. The old buffer-sized frame
            // was about 128 ms on this device and hid quiet syllables inside a
            // coarse average.
            short[] samples = new short[ANALYSIS_FRAME_SAMPLES];
            ArrayDeque<byte[]> preRoll = new ArrayDeque<>();
            ByteArrayOutputStream speech = new ByteArrayOutputStream();
            long listenStarted = System.nanoTime();
            long speechStarted = 0L;
            long lastVoice = 0L;
            double noiseFloor = 180.0;
            boolean heardSpeech = false;

            while (isCurrent(generation)) {
                int count = recorder.read(samples, 0, samples.length);
                if (count <= 0) continue;
                long now = System.nanoTime();
                byte[] pcm = shortsToBytes(samples, count);
                double rms = calculateRms(samples, count);
                // Use hysteresis: require a clear onset, then retain speech at
                // a much lower threshold so quiet syllables and trailing words
                // are not mistaken for the end of an utterance.
                double startThreshold = Math.max(MIN_START_RMS, noiseFloor * 2.2);
                double continuationThreshold = Math.max(
                        MIN_CONTINUATION_RMS, noiseFloor * 1.35);
                double threshold = heardSpeech ? continuationThreshold : startThreshold;
                boolean voice = rms >= threshold;

                if (!heardSpeech) {
                    if (!voice) noiseFloor = noiseFloor * 0.94 + rms * 0.06;
                    preRoll.addLast(pcm);
                    while (preRoll.size() > PRE_ROLL_CHUNKS) preRoll.removeFirst();
                    if (voice) {
                        heardSpeech = true;
                        speechStarted = now;
                        lastVoice = now;
                        for (byte[] chunk : preRoll) speech.write(chunk);
                        preRoll.clear();
                        handler.post(() -> {
                            if (isCurrent(generation)) listener.showStatus("🎙 正在听你说…");
                        });
                    } else if (elapsedMillis(listenStarted, now) >= NO_SPEECH_TIMEOUT_MS) {
                        postNoSpeech(generation);
                        return;
                    }
                } else {
                    speech.write(pcm);
                    if (voice) lastVoice = now;
                    long speechMs = elapsedMillis(speechStarted, now);
                    long silenceMs = elapsedMillis(lastVoice, now);
                    if (speechMs >= MAX_UTTERANCE_MS
                            || (speechMs >= MIN_SPEECH_MS && silenceMs >= END_SILENCE_MS)) {
                        Log.i(TAG, "endpoint speechMs=" + speechMs
                                + " silenceMs=" + silenceMs
                                + " rms=" + Math.round(rms)
                                + " continuationThreshold=" + Math.round(continuationThreshold)
                                + " noiseFloor=" + Math.round(noiseFloor));
                        byte[] wav = makeWav(speech.toByteArray());
                        handler.post(() -> transcribe(generation, wav));
                        return;
                    }
                }
            }
        } catch (Exception error) {
            if (isCurrent(generation)) {
                postErrorAndStop(generation, "无法开始录音：" + safeMessage(error));
            }
        } finally {
            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) {}
                recorder.release();
            }
            if (currentRecorder == recorder) currentRecorder = null;
        }
    }

    private void transcribe(int generation, byte[] wav) {
        if (!isCurrent(generation) || submitted) return;
        submitted = true;
        listener.showStatus("正在识别…");
        MimoAsrClient.transcribe(context, wav, new MimoAsrClient.Callback() {
            @Override
            public void onSuccess(String text) {
                if (!isCurrent(generation)) return;
                submitRecognizedText(generation, text);
            }

            @Override
            public void onError(String message) {
                if (!isCurrent(generation)) return;
                submitted = false;
                listener.showPetMessage("语音识别失败：" + message);
                listener.showStatus("🎙 我还在听…");
                handler.postDelayed(() -> startListening(generation), 300L);
            }
        });
    }

    private void submitRecognizedText(int generation, String text) {
        if (!isCurrent(generation) || text == null || text.trim().isEmpty()) return;
        String recognized = text.trim();
        listener.showUserMessage(recognized);
        listener.showStatus("小鼻嘎正在思考…");
        resetSpeechPipeline();
        AiClient.chat(context, conversationSkinId, recognized, new AiClient.Callback() {
            @Override
            public void onSentence(String sentence) {
                if (!isCurrent(generation)) return;
                enqueueSpeech(generation, sentence);
            }

            @Override
            public void onSuccess(String reply) {
                if (!isCurrent(generation)) return;
                listener.showPetMessage(reply);
                replyStreamComplete = true;
                finishReplyIfReady(generation);
            }

            @Override
            public void onError(String message) {
                if (!isCurrent(generation)) return;
                listener.showPetMessage("对话失败：" + message);
                replyStreamComplete = true;
                finishReplyIfReady(generation);
            }
        });
    }

    private void enqueueSpeech(int generation, String sentence) {
        if (!isCurrent(generation) || sentence == null || sentence.trim().isEmpty()) return;
        speechQueue.addLast(sentence.trim());
        pumpSpeechPipeline(generation);
    }

    private void pumpSpeechPipeline(int generation) {
        if (!isCurrent(generation)) return;
        playNextAudio(generation);
        if (synthesisBusy || speechQueue.isEmpty()) {
            finishReplyIfReady(generation);
            return;
        }
        String sentence = speechQueue.removeFirst();
        synthesisBusy = true;
        listener.showStatus("小鼻嘎正在说话…");
        MimoTtsClient.synthesize(context, sentence, new MimoTtsClient.Callback() {
            @Override
            public void onSuccess(byte[] wavData) {
                if (!isCurrent(generation)) return;
                synthesisBusy = false;
                audioQueue.addLast(wavData);
                playNextAudio(generation);
                // Synthesize the following sentence while this one is playing.
                pumpSpeechPipeline(generation);
            }

            @Override
            public void onError(String message) {
                if (!isCurrent(generation)) return;
                synthesisBusy = false;
                listener.showStatus("这一句声音生成失败，继续播放后续…");
                pumpSpeechPipeline(generation);
            }
        });
    }

    private void playNextAudio(int generation) {
        if (!isCurrent(generation) || playbackBusy || audioQueue.isEmpty()) return;
        playbackBusy = true;
        playSpeech(generation, audioQueue.removeFirst());
    }

    private void playSpeech(int generation, byte[] wavData) {
        if (!isCurrent(generation)) return;
        releasePlayer();
        try {
            File file = File.createTempFile("xiaobiga_reply_", ".wav", context.getCacheDir());
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(wavData);
            }
            currentSpeechFile = file;
            MediaPlayer player = new MediaPlayer();
            currentPlayer = player;
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(prepared -> {
                prepared.start();
                startBargeInMonitor(generation);
            });
            player.setOnCompletionListener(completed -> finishSpeechSegment(generation));
            player.setOnErrorListener((failed, what, extra) -> {
                finishSpeechSegment(generation);
                return true;
            });
            player.prepareAsync();
        } catch (Exception error) {
            finishSpeechSegment(generation);
        }
    }

    private void finishSpeechSegment(int generation) {
        releasePlayer();
        if (!isCurrent(generation)) return;
        playbackBusy = false;
        playNextAudio(generation);
        pumpSpeechPipeline(generation);
    }

    private void finishReplyIfReady(int generation) {
        if (!isCurrent(generation) || !replyStreamComplete
                || synthesisBusy || playbackBusy
                || !speechQueue.isEmpty() || !audioQueue.isEmpty()) return;
        stopBargeInMonitor();
        submitted = false;
        listener.showStatus("");
        handler.postDelayed(() -> startListening(generation), 120L);
    }

    private void resetSpeechPipeline() {
        speechQueue.clear();
        audioQueue.clear();
        synthesisBusy = false;
        playbackBusy = false;
        replyStreamComplete = false;
    }

    private void startBargeInMonitor(int generation) {
        if (!isCurrent(generation) || bargeInMonitorActive || currentRecorder != null) return;
        bargeInMonitorActive = true;
        recordingExecutor.execute(() -> monitorBargeIn(generation));
    }

    /**
     * Monitors the microphone while speech is playing. Once real near-end
     * speech survives echo cancellation for long enough, this same recorder is
     * promoted into the next utterance so the words used to interrupt are not
     * discarded.
     */
    private void monitorBargeIn(int originalGeneration) {
        if (!isCurrent(originalGeneration) || !bargeInMonitorActive) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        int minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(4096, minBytes > 0 ? minBytes : 4096);
        AudioRecord recorder = null;
        AcousticEchoCanceler echoCanceler = null;
        NoiseSuppressor noiseSuppressor = null;
        int captureGeneration = originalGeneration;
        boolean interrupted = false;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes * 2);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) return;
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(recorder.getAudioSessionId());
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(recorder.getAudioSessionId());
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }
            currentRecorder = recorder;
            recorder.startRecording();

            short[] samples = new short[ANALYSIS_FRAME_SAMPLES];
            ArrayDeque<byte[]> preRoll = new ArrayDeque<>();
            ByteArrayOutputStream speech = new ByteArrayOutputStream();
            long monitorStarted = System.nanoTime();
            long candidateStarted = 0L;
            long lastCandidateVoice = 0L;
            long speechStarted = 0L;
            long lastVoice = 0L;
            double noiseFloor = 180.0;

            while (active && !destroyed && bargeInMonitorActive
                    && captureGeneration == sessionGeneration) {
                int count = recorder.read(samples, 0, samples.length);
                if (count <= 0) continue;
                long now = System.nanoTime();
                byte[] pcm = shortsToBytes(samples, count);
                double rms = calculateRms(samples, count);

                if (!interrupted) {
                    preRoll.addLast(pcm);
                    while (preRoll.size() > PRE_ROLL_CHUNKS) preRoll.removeFirst();
                    long monitoringMs = elapsedMillis(monitorStarted, now);
                    if (monitoringMs < BARGE_IN_IGNORE_MS) {
                        noiseFloor = noiseFloor * 0.90 + rms * 0.10;
                        continue;
                    }

                    double threshold = Math.max(MIN_BARGE_IN_RMS, noiseFloor * 2.25);
                    boolean voice = rms >= threshold;
                    if (voice) {
                        if (candidateStarted == 0L) candidateStarted = now;
                        lastCandidateVoice = now;
                    } else {
                        if (candidateStarted == 0L) {
                            noiseFloor = noiseFloor * 0.98 + rms * 0.02;
                        } else if (elapsedMillis(lastCandidateVoice, now)
                                >= BARGE_IN_CANDIDATE_GAP_MS) {
                            candidateStarted = 0L;
                            lastCandidateVoice = 0L;
                        }
                    }

                    if (candidateStarted != 0L
                            && elapsedMillis(candidateStarted, now) >= BARGE_IN_CONFIRM_MS) {
                        synchronized (this) {
                            if (!isCurrent(originalGeneration)) return;
                            sessionGeneration++;
                            captureGeneration = sessionGeneration;
                        }
                        interrupted = true;
                        speechStarted = now;
                        lastVoice = now;
                        for (byte[] chunk : preRoll) speech.write(chunk);
                        preRoll.clear();
                        int newGeneration = captureGeneration;
                        Log.i(TAG, "barge-in detected rms=" + Math.round(rms)
                                + " threshold=" + Math.round(threshold)
                                + " aec=" + (echoCanceler != null && echoCanceler.getEnabled()));
                        handler.post(() -> acceptBargeIn(newGeneration));
                    }
                } else {
                    speech.write(pcm);
                    double continuationThreshold = Math.max(
                            MIN_CONTINUATION_RMS, noiseFloor * 1.35);
                    if (rms >= continuationThreshold) lastVoice = now;
                    long speechMs = elapsedMillis(speechStarted, now);
                    long silenceMs = elapsedMillis(lastVoice, now);
                    if (speechMs >= MAX_UTTERANCE_MS
                            || (speechMs >= MIN_SPEECH_MS && silenceMs >= END_SILENCE_MS)) {
                        byte[] wav = makeWav(speech.toByteArray());
                        int newGeneration = captureGeneration;
                        bargeInMonitorActive = false;
                        handler.post(() -> transcribe(newGeneration, wav));
                        return;
                    }
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "barge-in monitor stopped", error);
        } finally {
            bargeInMonitorActive = false;
            if (echoCanceler != null) echoCanceler.release();
            if (noiseSuppressor != null) noiseSuppressor.release();
            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) {}
                recorder.release();
            }
            if (currentRecorder == recorder) currentRecorder = null;
        }
    }

    private void acceptBargeIn(int generation) {
        if (!isCurrent(generation)) return;
        releasePlayer();
        resetSpeechPipeline();
        submitted = false;
        listener.showStatus("🎙 正在听你说…");
    }

    private void stopBargeInMonitor() {
        if (!bargeInMonitorActive) return;
        bargeInMonitorActive = false;
        closeRecorder();
    }

    private void releasePlayer() {
        MediaPlayer player = currentPlayer;
        currentPlayer = null;
        if (player != null) {
            try { player.stop(); } catch (RuntimeException ignored) {}
            player.release();
        }
        File file = currentSpeechFile;
        currentSpeechFile = null;
        if (file != null && file.exists()) file.delete();
    }

    private void postNoSpeech(int generation) {
        handler.post(() -> {
            if (!isCurrent(generation)) return;
            listener.showStatus("没听到声音，再说一次吧…");
            handler.postDelayed(() -> startListening(generation), 400L);
        });
    }

    private void postErrorAndStop(int generation, String message) {
        handler.post(() -> {
            if (!isCurrent(generation)) return;
            listener.showPetMessage(message);
            active = false;
            sessionGeneration++;
            listener.onConversationStopped();
        });
    }

    private void closeRecorder() {
        AudioRecord recorder = currentRecorder;
        currentRecorder = null;
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) {}
        }
    }

    private boolean isCurrent(int generation) {
        return active && !destroyed && generation == sessionGeneration;
    }

    private static long elapsedMillis(long start, long end) {
        return (end - start) / 1_000_000L;
    }

    private static double calculateRms(short[] samples, int count) {
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            double value = samples[i];
            sum += value * value;
        }
        return Math.sqrt(sum / Math.max(1, count));
    }

    private static byte[] shortsToBytes(short[] samples, int count) {
        ByteBuffer buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) buffer.putShort(samples[i]);
        return buffer.array();
    }

    private static byte[] makeWav(byte[] pcm) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(pcm.length + 44);
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt(36 + pcm.length);
        header.put(new byte[]{'W', 'A', 'V', 'E'});
        header.put(new byte[]{'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(SAMPLE_RATE);
        header.putInt(SAMPLE_RATE * 2);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt(pcm.length);
        output.write(header.array());
        output.write(pcm);
        return output.toByteArray();
    }

    private static String safeMessage(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "未知错误" : message;
    }
}
