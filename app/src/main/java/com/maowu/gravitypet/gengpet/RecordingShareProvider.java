package com.maowu.gravitypet.gengpet;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Read-only, explicit URI-grant exports of saved recordings; never exposes drafts or arbitrary
 * paths.
 */
public final class RecordingShareProvider extends ContentProvider {
  @Override
  public boolean onCreate() {
    return true;
  }

  public static Uri uri(android.content.Context c, long id) {
    return Uri.parse("content://" + c.getPackageName() + ".gengpet.recordings/" + id);
  }

  private NotesStore.Note note(Uri uri) throws FileNotFoundException {
    try (NotesStore store = new NotesStore(getContext())) {
      if (uri.getPathSegments().size() != 1) throw new IllegalArgumentException();
      NotesStore.Note n = store.get(Long.parseLong(uri.getLastPathSegment()));
      if (n == null || !n.isAudio()) throw new IllegalArgumentException();
      return n;
    } catch (Exception e) {
      throw new FileNotFoundException("录音不存在");
    }
  }

  @Override
  public String getType(Uri uri) {
    return "audio/mp4";
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("只读分享");
    try (NotesStore store = new NotesStore(getContext())) {
      return ParcelFileDescriptor.open(
          store.audioFile(note(uri)), ParcelFileDescriptor.MODE_READ_ONLY);
    } catch (Exception e) {
      throw new FileNotFoundException("无法读取录音");
    }
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    String[] columns =
        projection == null
            ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
            : projection;
    MatrixCursor cursor = new MatrixCursor(columns);
    try (NotesStore store = new NotesStore(getContext())) {
      NotesStore.Note n = note(uri);
      File f = store.audioFile(n);
      Object[] row = new Object[columns.length];
      for (int i = 0; i < columns.length; i++) {
        if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = n.title + ".m4a";
        else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = f.length();
      }
      cursor.addRow(row);
    } catch (Exception ignored) {
    }
    return cursor;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
