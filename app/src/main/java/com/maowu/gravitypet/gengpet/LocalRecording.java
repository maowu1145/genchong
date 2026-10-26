package com.maowu.gravitypet.gengpet;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;

/** Visible-Activity microphone owner. The Activity MUST call stop on pause. */
final class LocalRecording {
  interface Listener {
    void tick(long millis, int amplitude);

    void finished(File file, long millis, String message);
  }

  private final Context context;
  private final Listener listener;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private MediaRecorder recorder;
  private File draft;
  private long began;
  private AudioFocusRequest focus;

  LocalRecording(Context c, Listener l) {
    context = c;
    listener = l;
  }

  boolean active() {
    return recorder != null;
  }

  private final Runnable meter =
      new Runnable() {
        public void run() {
          if (recorder == null) return;
          long elapsed = SystemClock.elapsedRealtime() - began;
          int amplitude = 0;
          try {
            amplitude = recorder.getMaxAmplitude();
          } catch (RuntimeException ignored) {
          }
          listener.tick(elapsed, amplitude);
          if (elapsed >= 300000) stop(false, "已到 5 分钟上限");
          else handler.postDelayed(this, 250);
        }
      };

  void start(File file) throws IOException {
    if (active()) throw new IOException("已有录音正在进行");
    if (file.getParentFile().getUsableSpace() < 8 * 1024 * 1024L)
      throw new IOException("空间不足，请先清理至少 8 MB 空间");
    AudioManager audio = context.getSystemService(AudioManager.class);
    focus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setOnAudioFocusChangeListener(
                change -> {
                  if (change < 0) stop(false, "录音被其他音频中断，已保留可用内容");
                },
                handler)
            .build();
    if (audio != null
        && audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      focus = null;
      throw new IOException("麦克风或音频正忙，请稍后重试");
    }
    draft = file;
    MediaRecorder candidate = null;
    try {
      candidate = new MediaRecorder();
      candidate.setAudioSource(MediaRecorder.AudioSource.MIC);
      candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      candidate.setAudioEncodingBitRate(64000);
      candidate.setAudioSamplingRate(44100);
      candidate.setOutputFile(file.getAbsolutePath());
      candidate.setOnErrorListener((mr, what, extra) -> stop(false, "录音中断；可用内容会保留"));
      candidate.prepare();
      candidate.start();
      recorder = candidate;
      began = SystemClock.elapsedRealtime();
      handler.post(meter);
    } catch (IOException | RuntimeException e) {
      releaseRecorder(candidate);
      releaseFocus();
      file.delete();
      draft = null;
      throw new IOException("无法开始录音：检查麦克风权限、占用和空间", e);
    }
  }

  void stop(boolean discard, String message) {
    MediaRecorder current = recorder;
    if (current == null) return;
    recorder = null;
    handler.removeCallbacks(meter);
    long duration = SystemClock.elapsedRealtime() - began;
    boolean valid = true;
    try {
      current.stop();
    } catch (RuntimeException e) {
      valid = false;
    } finally {
      releaseRecorder(current);
      releaseFocus();
    }
    File result = draft;
    draft = null;
    if (discard || result == null || result.length() < 32) {
      if (result != null && result.exists() && !result.delete()) {
        listener.finished(result, duration, "草稿暂时无法删除，已保留供你重试");
        return;
      }
      listener.finished(null, 0, discard ? "已取消录音" : "录音过短或已中断，未生成可播放音频");
    } else listener.finished(result, duration, valid ? message : "录音异常结束，非空草稿已保留；可试听、恢复或删除");
  }

  private static void releaseRecorder(MediaRecorder recorder) {
    if (recorder != null)
      try {
        recorder.release();
      } catch (RuntimeException ignored) {
      }
  }

  private void releaseFocus() {
    AudioManager audio = context.getSystemService(AudioManager.class);
    if (audio != null && focus != null)
      try {
        audio.abandonAudioFocusRequest(focus);
      } catch (RuntimeException ignored) {
      }
    focus = null;
  }
}
