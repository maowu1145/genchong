package com.maowu.gravitypet;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Extracts an adaptive bass beat envelope from the system output mix. */
final class MusicBeatDetector {
    interface Listener {
        void onBeat(float strength);
    }

    private static final String TAG = "XiaoBiGaMusic";
    private static final long MIN_BEAT_INTERVAL_MS = 260L;
    private final AudioManager audioManager;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Visualizer visualizer;
    private double bassAverage;
    private double waveformAverage;
    private long lastBeatMillis;
    private long lastDiagnosticMillis;

    MusicBeatDetector(Context context, Listener listener) {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
    }

    void start() {
        if (visualizer != null) return;
        try {
            Visualizer next = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = Math.max(range[0], Math.min(512, range[1]));
            next.setCaptureSize(captureSize);
            // Normalized mode is more reliable on OEMs that report a very
            // small output-mix signal at ordinary media volume.
            next.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            int rate = Math.min(25_000, Visualizer.getMaxCaptureRate());
            next.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer,
                                                  byte[] waveform, int samplingRate) {
                    analyzeWaveform(waveform);
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer,
                                             byte[] fft, int samplingRate) {
                    analyzeFft(fft);
                }
            }, rate, true, true);
            next.setEnabled(true);
            visualizer = next;
            Log.i(TAG, "System music beat detector enabled");
        } catch (RuntimeException error) {
            Log.w(TAG, "System output visualization is unavailable", error);
            release();
        }
    }

    private void analyzeFft(byte[] fft) {
        if (fft == null || fft.length < 12 || audioManager == null
                || !audioManager.isMusicActive()) {
            bassAverage *= 0.92;
            return;
        }
        int lastBin = Math.min(18, fft.length / 2 - 1);
        double sum = 0.0;
        int bins = 0;
        for (int bin = 2; bin <= lastBin; bin++) {
            int index = bin * 2;
            double real = fft[index];
            double imaginary = fft[index + 1];
            sum += Math.hypot(real, imaginary);
            bins++;
        }
        if (bins == 0) return;
        double energy = sum / bins;
        if (bassAverage <= 0.1) bassAverage = energy;
        double ratio = energy / Math.max(1.0, bassAverage);
        long now = android.os.SystemClock.uptimeMillis();
        boolean beat = energy >= 2.5 && ratio >= 1.16
                && now - lastBeatMillis >= MIN_BEAT_INTERVAL_MS;
        // Slow adaptation preserves contrast between a kick and the surrounding music.
        bassAverage = bassAverage * 0.96 + energy * 0.04;
        logDiagnostic(now, energy, bassAverage, ratio);
        if (!beat) return;
        lastBeatMillis = now;
        float strength = (float) Math.max(0.18, Math.min(1.0, (ratio - 1.25) / 1.2));
        mainHandler.post(() -> listener.onBeat(strength));
    }

    private void analyzeWaveform(byte[] waveform) {
        if (waveform == null || waveform.length == 0 || audioManager == null
                || !audioManager.isMusicActive()) {
            waveformAverage *= 0.92;
            return;
        }
        double sum = 0.0;
        for (byte sample : waveform) {
            double centered = (sample & 0xff) - 128.0;
            sum += centered * centered;
        }
        double rms = Math.sqrt(sum / waveform.length);
        if (waveformAverage <= 0.1) waveformAverage = rms;
        double ratio = rms / Math.max(0.8, waveformAverage);
        waveformAverage = waveformAverage * 0.96 + rms * 0.04;
        long now = android.os.SystemClock.uptimeMillis();
        // Some Xiaomi audio stacks return almost empty FFT bins but a valid
        // waveform. This path preserves beat response on those devices.
        if (rms >= 2.0 && ratio >= 1.18
                && now - lastBeatMillis >= MIN_BEAT_INTERVAL_MS) {
            lastBeatMillis = now;
            float strength = (float) Math.max(0.16,
                    Math.min(1.0, (ratio - 1.08) / 0.85));
            mainHandler.post(() -> listener.onBeat(strength));
        }
    }

    private void logDiagnostic(long now, double energy, double average, double ratio) {
        if (now - lastDiagnosticMillis < 2_000L) return;
        lastDiagnosticMillis = now;
        Log.i(TAG, "musicActive=" + (audioManager != null && audioManager.isMusicActive())
                + " bass=" + Math.round(energy * 10.0) / 10.0
                + " average=" + Math.round(average * 10.0) / 10.0
                + " ratio=" + Math.round(ratio * 100.0) / 100.0
                + " waveform=" + Math.round(waveformAverage * 10.0) / 10.0);
    }

    void release() {
        Visualizer current = visualizer;
        visualizer = null;
        if (current != null) {
            try { current.setEnabled(false); } catch (RuntimeException ignored) { }
            current.release();
        }
        mainHandler.removeCallbacksAndMessages(null);
    }
}
