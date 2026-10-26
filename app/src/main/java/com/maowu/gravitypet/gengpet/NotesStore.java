package com.maowu.gravitypet.gengpet;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

/** Private SQLite metadata. Audio is finalized before its row is committed. */
public final class NotesStore extends SQLiteOpenHelper {
  public static final class Note {
    public long id, created, updated, duration;
    public String title, body, audio;

    public boolean isAudio() {
      return audio != null && !audio.isEmpty();
    }
  }

  private final Context context;

  public NotesStore(Context c) {
    super(c.getApplicationContext(), "gengpet_notes.db", null, 1);
    context = c.getApplicationContext();
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE notes (_id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,body TEXT"
            + " NOT NULL DEFAULT '',audio TEXT NOT NULL DEFAULT '',duration INTEGER NOT NULL"
            + " DEFAULT 0,created INTEGER NOT NULL,updated INTEGER NOT NULL)");
    db.execSQL("CREATE UNIQUE INDEX audio_unique ON notes(audio) WHERE audio <> ''");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

  public File audioDirectory() throws IOException {
    File dir = new File(context.getFilesDir(), "recordings");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建录音目录，请检查剩余空间");
    return dir;
  }

  public File newDraft() throws IOException {
    return new File(audioDirectory(), "draft-" + UUID.randomUUID() + ".m4a");
  }

  public File audioFile(Note n) throws IOException {
    if (!n.isAudio() || !n.audio.matches("[A-Za-z0-9-]+\\.m4a")) throw new IOException("录音路径无效");
    return new File(audioDirectory(), n.audio);
  }

  public long saveText(long id, String title, String body) {
    body = body.trim();
    if (body.isEmpty()) throw new IllegalArgumentException("先写一点内容再保存");
    if (body.length() > 5000) throw new IllegalArgumentException("单条最多 5000 字");
    ContentValues v = new ContentValues();
    v.put("title", title(title, body));
    v.put("body", body);
    v.put("updated", System.currentTimeMillis());
    SQLiteDatabase db = getWritableDatabase();
    if (id > 0) {
      if (db.update("notes", v, "_id=? AND audio=''", new String[] {Long.toString(id)}) != 1)
        throw new IllegalArgumentException("记录已不存在");
      return id;
    }
    v.put("created", System.currentTimeMillis());
    return db.insertOrThrow("notes", null, v);
  }

  private static String title(String title, String fallback) {
    String value = title.trim().isEmpty() ? fallback.replace('\n', ' ').trim() : title.trim();
    return value.substring(0, Math.min(80, value.length()));
  }

  public long saveAudio(File draft, String title, long duration) throws IOException {
    if (draft == null || !draft.isFile() || draft.length() < 32) throw new IOException("没有可保存的录音");
    File finalFile = new File(audioDirectory(), "audio-" + UUID.randomUUID() + ".m4a");
    if (!draft.renameTo(finalFile)) throw new IOException("保存录音失败，草稿仍保留在本机");
    ContentValues v = new ContentValues();
    v.put("title", title(title, "语音记录"));
    v.put("body", "");
    v.put("audio", finalFile.getName());
    v.put("duration", duration);
    v.put("created", System.currentTimeMillis());
    v.put("updated", System.currentTimeMillis());
    try {
      return getWritableDatabase().insertOrThrow("notes", null, v);
    } catch (RuntimeException e) {
      finalFile.renameTo(draft);
      throw e;
    }
  }

  public void rename(long id, String name) {
    if (name.trim().isEmpty()) throw new IllegalArgumentException("名称不能为空");
    ContentValues v = new ContentValues();
    v.put("title", title(name, name));
    v.put("updated", System.currentTimeMillis());
    getWritableDatabase().update("notes", v, "_id=?", new String[] {Long.toString(id)});
  }

  public ArrayList<Note> list(String search) {
    ArrayList<Note> result = new ArrayList<>();
    String escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    try (Cursor c =
        getReadableDatabase()
            .query(
                "notes",
                null,
                "title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\'",
                new String[] {"%" + escaped + "%", "%" + escaped + "%"},
                null,
                null,
                "updated DESC, _id DESC")) {
      while (c.moveToNext()) result.add(read(c));
    }
    return result;
  }

  public Note get(long id) {
    try (Cursor c =
        getReadableDatabase()
            .query("notes", null, "_id=?", new String[] {Long.toString(id)}, null, null, null)) {
      return c.moveToFirst() ? read(c) : null;
    }
  }

  private static Note read(Cursor c) {
    Note n = new Note();
    n.id = c.getLong(c.getColumnIndexOrThrow("_id"));
    n.title = c.getString(c.getColumnIndexOrThrow("title"));
    n.body = c.getString(c.getColumnIndexOrThrow("body"));
    n.audio = c.getString(c.getColumnIndexOrThrow("audio"));
    n.duration = c.getLong(c.getColumnIndexOrThrow("duration"));
    n.created = c.getLong(c.getColumnIndexOrThrow("created"));
    n.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
    return n;
  }

  public void delete(Note n) throws IOException {
    // Delete metadata only after the private audio file is removed.
    if (n.isAudio()) {
      File f = audioFile(n);
      if (f.exists() && !f.delete()) throw new IOException("暂时无法删除录音，请停止播放后重试");
    }
    getWritableDatabase().delete("notes", "_id=?", new String[] {Long.toString(n.id)});
    if (n.isAudio())
      context.revokeUriPermission(
          RecordingShareProvider.uri(context, n.id),
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }

  /** Completed files survive process death; invalid drafts remain explicit recovery choices. */
  public File[] drafts() throws IOException {
    java.util.HashSet<String> saved = new java.util.HashSet<>();
    try (Cursor cursor =
        getReadableDatabase().rawQuery("SELECT audio FROM notes WHERE audio <> ''", null)) {
      while (cursor.moveToNext()) saved.add(cursor.getString(0));
    }
    File[] files =
        audioDirectory()
            .listFiles(
                (d, n) ->
                    (n.startsWith("draft-") || n.startsWith("audio-"))
                        && n.endsWith(".m4a")
                        && !saved.contains(n));
    return files == null ? new File[0] : files;
  }
}
