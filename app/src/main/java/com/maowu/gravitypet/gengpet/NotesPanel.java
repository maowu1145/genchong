package com.maowu.gravitypet.gengpet;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.*;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;

/** Notes UI owns no service; every recording is local and explicitly started by the user. */
final class NotesPanel {
  private final FeatureActivity activity;
  private final NotesStore store;
  private final LocalRecording recording;
  private LinearLayout root;
  private TextView recordStatus;
  private EditText title, body;
  private long editingId = 0, previewDuration = 0;
  private File preview;
  private MediaPlayer player;
  private boolean pausing, recorderView, paused;

  NotesPanel(FeatureActivity activity) {
    this.activity = activity;
    store = new NotesStore(activity);
    recording =
        new LocalRecording(
            activity,
            new LocalRecording.Listener() {
              public void tick(long millis, int amplitude) {
                StringBuilder meter = new StringBuilder();
                for (int i = 0; i < Math.min(10, amplitude / 3277); i++) meter.append('▮');
                if (recordStatus != null)
                  recordStatus.setText(
                      "正在录音 "
                          + seconds(millis)
                          + " / 5:00\n音量 "
                          + meter
                          + (millis >= 270000 ? "\n还剩不到 30 秒" : ""));
              }

              public void finished(File file, long millis, String message) {
                preview = file;
                previewDuration = millis;
                if (!pausing) showRecorder();
                FeatureUi.message(activity, message);
              }
            });
  }

  void show(LinearLayout parent) {
    root = parent;
    showHistory("");
  }

  private void clear() {
    root.removeAllViews();
    title = null;
    body = null;
    recordStatus = null;
    recorderView = false;
  }

  private static String seconds(long ms) {
    return (ms / 60000) + ":" + String.format(java.util.Locale.ROOT, "%02d", (ms / 1000) % 60);
  }

  private void showHistory(String query) {
    stopPlayback();
    clear();
    FeatureUi.text(activity, root, "随手记 · 都保存在本机", 22);
    LinearLayout buttons = FeatureUi.row(activity, root);
    FeatureUi.rowButton(activity, buttons, "写文字", () -> edit(null));
    FeatureUi.rowButton(activity, buttons, "录语音", this::showRecorder);
    if (!FeaturePreferences.prefs(activity).getString("note_draft_body", "").isEmpty())
      FeatureUi.button(activity, root, "继续上次未保存的文字", () -> editDraft());
    try {
      for (File draft : store.drafts()) {
        FeatureUi.button(
            activity,
            root,
            "恢复录音草稿 · " + DateFormat.getDateTimeInstance().format(new Date(draft.lastModified())),
            () -> {
              preview = draft;
              previewDuration = duration(draft);
              showRecorder();
            });
      }
    } catch (Exception e) {
      FeatureUi.text(activity, root, "草稿读取失败：" + e.getMessage(), 14);
    }
    EditText search = FeatureUi.input(activity, root, "搜索标题或正文", query, 200);
    LinearLayout list = new LinearLayout(activity);
    list.setOrientation(LinearLayout.VERTICAL);
    root.addView(list);
    Runnable render =
        () -> {
          list.removeAllViews();
          try {
            java.util.ArrayList<NotesStore.Note> notes = store.list(search.getText().toString());
            if (notes.isEmpty()) FeatureUi.text(activity, list, "还没有匹配的记录", 16);
            for (NotesStore.Note n : notes) {
              FeatureUi.button(
                  activity,
                  list,
                  (n.isAudio() ? "语音 · " : "文字 · ")
                      + n.title
                      + "\n"
                      + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                          .format(new Date(n.updated))
                      + (n.isAudio() ? " · " + seconds(n.duration) : ""),
                  () -> detail(n));
            }
          } catch (Exception e) {
            FeatureUi.message(activity, "读取失败：" + e.getMessage());
          }
        };
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            render.run();
          }

          public void afterTextChanged(Editable e) {}
        });
    render.run();
  }

  private void editDraft() {
    long id = FeaturePreferences.prefs(activity).getLong("note_draft_id", 0);
    NotesStore.Note n = id > 0 ? store.get(id) : null;
    edit(n);
    title.setText(FeaturePreferences.prefs(activity).getString("note_draft_title", ""));
    body.setText(FeaturePreferences.prefs(activity).getString("note_draft_body", ""));
  }

  private void edit(NotesStore.Note note) {
    clear();
    editingId = note == null ? 0 : note.id;
    FeatureUi.text(activity, root, "文字记录", 22);
    title = FeatureUi.input(activity, root, "标题（可不填）", note == null ? "" : note.title, 80);
    body =
        FeatureUi.input(activity, root, "写下想记住的事，最多 5000 字", note == null ? "" : note.body, 5000);
    body.setMinLines(6);
    body.setGravity(android.view.Gravity.TOP);
    FeatureUi.button(
        activity,
        root,
        "保存",
        () -> {
          try {
            store.saveText(editingId, title.getText().toString(), body.getText().toString());
            clearTextDraft();
            FeaturePreferences.feedback(
                activity, "grudge_panda", activity.instanceId, "saved", "记下了");
            showHistory("");
          } catch (Exception e) {
            FeatureUi.message(activity, "保存失败：" + e.getMessage());
          }
        });
    FeatureUi.button(
        activity,
        root,
        "取消",
        () -> {
          clearTextDraft();
          showHistory("");
        });
    FeaturePreferences.feedback(activity, "grudge_panda", activity.instanceId, "writing", "");
  }

  private void clearTextDraft() {
    FeaturePreferences.prefs(activity)
        .edit()
        .remove("note_draft_title")
        .remove("note_draft_body")
        .remove("note_draft_id")
        .apply();
  }

  private void detail(NotesStore.Note n) {
    clear();
    FeatureUi.text(activity, root, n.title, 22);
    if (n.isAudio()) {
      FeatureUi.text(activity, root, "录音时长 " + seconds(n.duration), 16);
      FeatureUi.button(
          activity,
          root,
          "播放 / 从头播放",
          () -> {
            try {
              play(store.audioFile(n));
            } catch (Exception e) {
              FeatureUi.message(activity, e.getMessage());
            }
          });
      FeatureUi.button(activity, root, "停止播放", this::stopPlayback);
    } else {
      FeatureUi.text(activity, root, n.body, 17);
      FeatureUi.button(activity, root, "编辑文字", () -> edit(n));
    }
    FeatureUi.button(
        activity,
        root,
        "改名",
        () -> {
          EditText name = new EditText(activity);
          name.setText(n.title);
          name.setTextColor(UiKit.INK);
          new AlertDialog.Builder(activity)
              .setTitle("修改名称")
              .setView(name)
              .setNegativeButton("取消", null)
              .setPositiveButton(
                  "保存",
                  (d, w) -> {
                    try {
                      store.rename(n.id, name.getText().toString());
                      detail(store.get(n.id));
                    } catch (Exception e) {
                      FeatureUi.message(activity, e.getMessage());
                    }
                  })
              .show();
        });
    FeatureUi.button(activity, root, "系统分享", () -> share(n));
    FeatureUi.button(
        activity,
        root,
        "删除记录",
        () ->
            new AlertDialog.Builder(activity)
                .setTitle("删除这条记录？")
                .setMessage("保存的文字或录音将从本机删除。")
                .setNegativeButton("保留", null)
                .setPositiveButton(
                    "删除",
                    (d, w) -> {
                      try {
                        stopPlayback();
                        store.delete(n);
                        FeaturePreferences.feedback(
                            activity, "grudge_panda", activity.instanceId, "tearing", "删掉了");
                        showHistory("");
                      } catch (Exception e) {
                        FeatureUi.message(activity, e.getMessage());
                      }
                    })
                .show());
    FeatureUi.button(activity, root, "返回记录", () -> showHistory(""));
  }

  private void share(NotesStore.Note n) {
    Intent send = new Intent(Intent.ACTION_SEND);
    if (n.isAudio()) {
      android.net.Uri uri = RecordingShareProvider.uri(activity, n.id);
      send.setType("audio/mp4")
          .putExtra(Intent.EXTRA_STREAM, uri)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      send.setClipData(ClipData.newRawUri(n.title, uri));
    } else send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, n.title + "\n\n" + n.body);
    try {
      activity.startActivity(Intent.createChooser(send, "分享这条记录"));
    } catch (Exception e) {
      FeatureUi.message(activity, "没有可用的分享应用");
    }
  }

  private void showRecorder() {
    clear();
    recorderView = true;
    FeatureUi.text(activity, root, "本地语音记录", 22);
    FeatureUi.text(activity, root, "录音仅在此界面可见时继续。离开、锁屏或被中断时，自动结束并保存可用音频；最长 5 分钟。", 15);
    recordStatus =
        FeatureUi.text(
            activity,
            root,
            recording.active()
                ? "正在录音…"
                : preview != null ? "待保存录音 · " + seconds(previewDuration) : "尚未开始",
            18);
    if (recording.active()) {
      FeatureUi.button(activity, root, "停止并试听", () -> recording.stop(false, "录音已停止，可试听后保存"));
      FeatureUi.button(activity, root, "取消本次录音", () -> recording.stop(true, "已取消"));
    } else if (preview != null) {
      title = FeatureUi.input(activity, root, "录音名称（可不填）", "", 80);
      FeatureUi.button(activity, root, "试听", () -> play(preview));
      FeatureUi.button(activity, root, "停止播放", this::stopPlayback);
      FeatureUi.button(
          activity,
          root,
          "保存录音",
          () -> {
            if (savePreview(title.getText().toString())) showHistory("");
          });
      FeatureUi.button(
          activity,
          root,
          "删除草稿 / 重录",
          () ->
              new AlertDialog.Builder(activity)
                  .setTitle("删除这次录音草稿？")
                  .setNegativeButton("保留", null)
                  .setPositiveButton(
                      "删除",
                      (d, w) -> {
                        stopPlayback();
                        if (preview != null && !preview.delete()) {
                          FeatureUi.message(activity, "草稿删除失败");
                          return;
                        }
                        preview = null;
                        showRecorder();
                      })
                  .show());
    } else {
      FeatureUi.button(
          activity,
          root,
          "开始录音",
          () -> {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
              activity.requestPermissions(
                  new String[] {Manifest.permission.RECORD_AUDIO}, FeatureActivity.MIC_PERMISSION);
              return;
            }
            try {
              stopPlayback();
              recording.start(store.newDraft());
              FeaturePreferences.feedback(
                  activity, "grudge_panda", activity.instanceId, "recording", "正在记录");
              showRecorder();
            } catch (Exception e) {
              FeatureUi.message(activity, e.getMessage());
            }
          });
      FeatureUi.button(
          activity,
          root,
          "麦克风权限设置",
          () ->
              activity.startActivity(
                  new Intent(
                      android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                      android.net.Uri.parse("package:" + activity.getPackageName()))));
    }
    FeatureUi.button(
        activity,
        root,
        "返回记录",
        () -> {
          if (recording.active()) recording.stop(false, "已结束录音");
          if (preview != null && !savePreview("录音")) return;
          showHistory("");
        });
  }

  private boolean savePreview(String name) {
    if (preview == null) return true;
    try {
      long actual = duration(preview);
      if (actual <= 0) throw new java.io.IOException("音频尚未完成或损坏，可保留草稿后重试或删除");
      store.saveAudio(preview, name, actual);
      preview = null;
      FeaturePreferences.feedback(activity, "grudge_panda", activity.instanceId, "saved", "录音记下了");
      return true;
    } catch (Exception e) {
      FeatureUi.message(activity, "保存失败，草稿仍保留：" + e.getMessage());
      return false;
    }
  }

  private long duration(File file) {
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
      retriever.setDataSource(file.getAbsolutePath());
      String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
      return Long.parseLong(value == null ? "0" : value);
    } catch (Exception e) {
      return 0;
    } finally {
      try {
        retriever.release();
      } catch (Exception ignored) {
      }
    }
  }

  private void play(File file) {
    stopPlayback();
    try {
      player = new MediaPlayer();
      player.setDataSource(file.getAbsolutePath());
      player.setOnCompletionListener(p -> stopPlayback());
      player.setOnErrorListener(
          (p, w, e) -> {
            stopPlayback();
            FeatureUi.message(activity, "录音无法播放");
            return true;
          });
      player.prepare();
      player.start();
    } catch (Exception e) {
      stopPlayback();
      FeatureUi.message(activity, "无法播放这段录音");
    }
  }

  private void stopPlayback() {
    if (player != null) {
      try {
        player.release();
      } catch (Exception ignored) {
      }
      player = null;
    }
  }

  void pause() {
    if (paused) return;
    paused = true;
    pausing = true;
    if (body != null && title != null)
      FeaturePreferences.prefs(activity)
          .edit()
          .putLong("note_draft_id", editingId)
          .putString("note_draft_title", title.getText().toString())
          .putString("note_draft_body", body.getText().toString())
          .commit();
    recording.stop(false, "界面已离开，录音已结束");
    if (preview != null) savePreview(title == null ? "中断保存的录音" : title.getText().toString());
    stopPlayback();
    pausing = false;
  }

  void resume() {
    paused = false;
    if (recorderView) showRecorder();
  }

  void close() {
    pause();
    store.close();
  }
}
