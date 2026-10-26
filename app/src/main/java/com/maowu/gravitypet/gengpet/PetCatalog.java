package com.maowu.gravitypet.gengpet;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Approved local sprites only. Callers must not mutate or recycle shared bitmaps. */
public final class PetCatalog {
  private static final String TAG = "PetCatalog";
  // All 45 approved sprites occupy 33 MiB at native ARGB_8888 resolution.
  // This fixed 40 MiB budget fits them without frame-by-frame eviction/redecoding.
  public static final int BITMAP_CACHE_LIMIT_BYTES = 40 * 1024 * 1024;
  private static final Object BITMAP_LOCK = new Object();
  private static final LruCache<String, Bitmap> BITMAPS =
      new LruCache<String, Bitmap>(BITMAP_CACHE_LIMIT_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
          return value.getAllocationByteCount();
        }
        // Never recycle on eviction: a visible View may still hold the bitmap.
      };
  private static final Set<String> FAILED_PATHS = new HashSet<>();
  private static volatile Catalog catalog;
  // One ordered pending request per catalog entry; all views subscribe to that request.
  private static final Map<String, PendingPreload> WARMING = new LinkedHashMap<>();
  private static boolean loaderScheduled;
  private static final ThreadPoolExecutor LOADER =
      new ThreadPoolExecutor(
          1,
          1,
          15,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(6),
          runnable -> {
            Thread thread = new Thread(runnable, "gengpet-sprites");
            thread.setDaemon(true);
            return thread;
          });

  static {
    LOADER.allowCoreThreadTimeOut(true);
  }

  private PetCatalog() {}

  private static final class PendingPreload {
    final Context context;
    final Entry entry;
    final List<Runnable> callbacks = new ArrayList<>();

    PendingPreload(Context context, Entry entry, Runnable callback) {
      this.context = context;
      this.entry = entry;
      if (callback != null) callbacks.add(callback);
    }
  }

  /** Normalized point on the full PNG canvas, not on its trimmed visible bounds. */
  public static final class Anchor {
    public final float x, y;

    private Anchor(float x, float y) {
      this.x = x;
      this.y = y;
    }
  }

  public static final class Entry {
    public final String id, name, description, feature, idle;
    public final Map<String, String> states;
    public final List<String> animationFrames;
    public final int frameDurationMs;
    public final float animationAnchorY;
    public final boolean functional;

    // Additive contract: static and animated canvases have different foot anchors.
    public final float anchorX, anchorY, animationAnchorX;
    public final Map<String, Anchor> stateAnchors;
    public final int canvasWidth, canvasHeight, animationWidth, animationHeight;
    public final boolean animationLoop;
    public final List<String> animationStates;

    private Entry(JSONObject json) throws JSONException {
      id = json.getString("id");
      if (!id.matches("[a-z][a-z0-9_]*")) throw new JSONException("Invalid character ID");
      name = json.getString("name");
      description = json.getString("description");
      feature = json.getString("feature");
      functional = json.optBoolean("functional", true);
      idle = spritePath(json.getString("idle"), id);
      JSONObject rawStates = json.getJSONObject("states");
      Map<String, String> paths = new LinkedHashMap<>();
      Iterator<String> keys = rawStates.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        paths.put(key, spritePath(rawStates.getString(key), id));
      }
      if (!paths.containsValue(idle)) throw new JSONException("Idle is not a registered state");
      states = Collections.unmodifiableMap(paths);
      Anchor anchor = readAnchor(json.getJSONObject("anchor"));
      anchorX = anchor.x;
      anchorY = anchor.y;
      Map<String, Anchor> anchors = new LinkedHashMap<>();
      JSONObject rawAnchors = json.optJSONObject("stateAnchors");
      for (String state : paths.keySet()) {
        JSONObject value = rawAnchors == null ? null : rawAnchors.optJSONObject(state);
        anchors.put(state, value == null ? anchor : readAnchor(value));
      }
      stateAnchors = Collections.unmodifiableMap(anchors);
      JSONObject canvas = json.getJSONObject("canvas");
      canvasWidth = positiveDimension(canvas.getInt("width"));
      canvasHeight = positiveDimension(canvas.getInt("height"));
      List<String> frames = new ArrayList<>();
      JSONArray rawFrames = json.optJSONArray("animationFrames");
      if (rawFrames != null) {
        if (rawFrames.length() > 32) throw new JSONException("Too many animation frames");
        for (int i = 0; i < rawFrames.length(); i++) {
          frames.add(spritePath(rawFrames.getString(i), id));
        }
      }
      animationFrames = Collections.unmodifiableList(frames);
      frameDurationMs = json.optInt("frameDurationMs", 100);
      if (frameDurationMs < 16 || frameDurationMs > 10000) {
        throw new JSONException("Invalid frame duration");
      }
      animationAnchorX = normalized(json.optDouble("animationAnchorX", anchorX));
      animationAnchorY = normalized(json.optDouble("animationAnchorY", anchorY));
      animationLoop = json.optBoolean("animationLoop", false);
      JSONObject animationCanvas = json.optJSONObject("animationCanvas");
      animationWidth =
          animationCanvas == null
              ? canvasWidth
              : positiveDimension(animationCanvas.getInt("width"));
      animationHeight =
          animationCanvas == null
              ? canvasHeight
              : positiveDimension(animationCanvas.getInt("height"));
      List<String> animatedStates = new ArrayList<>();
      JSONArray rawAnimatedStates = json.optJSONArray("animationStates");
      if (rawAnimatedStates != null) {
        for (int i = 0; i < rawAnimatedStates.length(); i++) {
          String state = rawAnimatedStates.getString(i);
          if (!paths.containsKey(state)) throw new JSONException("Unknown animation state");
          animatedStates.add(state);
        }
      }
      animationStates = Collections.unmodifiableList(animatedStates);
    }

    public String statePath(String state) {
      String path = state == null ? null : states.get(state);
      return path == null ? idle : path;
    }

    public Anchor stateAnchor(String state) {
      Anchor anchor = stateAnchors.get(state);
      return anchor == null ? new Anchor(anchorX, anchorY) : anchor;
    }

    /** Comfort/paused modes must additionally suppress animation at the renderer. */
    public boolean animatesState(String state) {
      return !animationFrames.isEmpty() && animationStates.contains(state);
    }

    /** elapsedMs is the time since the current animation began. */
    public String animationFrame(long elapsedMs) {
      if (animationFrames.isEmpty()) return idle;
      long frame = Math.max(0, elapsedMs) / frameDurationMs;
      int index =
          animationLoop
              ? (int) (frame % animationFrames.size())
              : (int) Math.min(frame, animationFrames.size() - 1L);
      return animationFrames.get(index);
    }
  }

  private static final class Catalog {
    final List<Entry> entries;
    final Map<String, Entry> byId;
    final Set<String> paths;

    Catalog(List<Entry> entries, Map<String, Entry> byId, Set<String> paths) {
      this.entries = Collections.unmodifiableList(entries);
      this.byId = Collections.unmodifiableMap(byId);
      this.paths = Collections.unmodifiableSet(paths);
    }
  }

  public static List<Entry> all(Context context) {
    return load(context).entries;
  }

  public static Entry get(Context context, String id) {
    return id == null ? null : load(context).byId.get(id);
  }

  private static Catalog load(Context context) {
    Catalog result = catalog;
    if (result != null) return result;
    synchronized (PetCatalog.class) {
      if (catalog != null) return catalog;
      List<Entry> entries = new ArrayList<>();
      Map<String, Entry> byId = new LinkedHashMap<>();
      Set<String> paths = new HashSet<>();
      try (InputStream input = context.getAssets().open("pets/catalog.json")) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
          bytes.write(buffer, 0, count);
          if (bytes.size() > 256 * 1024) throw new IOException("Catalog too large");
        }
        JSONObject root = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        if (root.getInt("schemaVersion") != 1)
          throw new JSONException("Unsupported catalog schema");
        JSONArray characters = root.getJSONArray("characters");
        if (characters.length() > 64) throw new JSONException("Catalog entry limit exceeded");
        for (int i = 0; i < characters.length(); i++) {
          try {
            Entry entry = new Entry(characters.getJSONObject(i));
            if (byId.containsKey(entry.id)) throw new JSONException("Duplicate character ID");
            entries.add(entry);
            byId.put(entry.id, entry);
            paths.addAll(entry.states.values());
            paths.addAll(entry.animationFrames);
          } catch (JSONException invalidEntry) {
            Log.e(TAG, "Ignoring invalid catalog entry " + i, invalidEntry);
          }
        }
      } catch (IOException | JSONException error) {
        Log.e(TAG, "Cannot read pet catalog", error);
      }
      catalog = new Catalog(entries, byId, paths);
      return catalog;
    }
  }

  /**
   * Shared native-resolution ARGB_8888 bitmap. The first cache miss decodes once; animation users
   * should call preload before playback, then retain returned frames. Null is returned for
   * unregistered, missing or invalid images. Failed paths are remembered, so a missing image cannot
   * repeatedly trigger decoding in onDraw.
   */
  public static Bitmap bitmap(Context context, String assetRelativePath) {
    if (assetRelativePath == null || !load(context).paths.contains(assetRelativePath)) return null;
    synchronized (BITMAP_LOCK) {
      Bitmap cached = BITMAPS.get(assetRelativePath);
      if (cached != null && !cached.isRecycled()) return cached;
      if (FAILED_PATHS.contains(assetRelativePath)) return null;
      try {
        AssetManager assets = context.getAssets();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = assets.open(assetRelativePath)) {
          BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0
            || bounds.outHeight <= 0
            || bounds.outWidth > 512
            || bounds.outHeight > 512) {
          throw new IOException("Unsupported sprite dimensions");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        options.inMutable = false;
        Bitmap decoded;
        try (InputStream input = assets.open(assetRelativePath)) {
          decoded = BitmapFactory.decodeStream(input, null, options);
        }
        if (decoded == null) throw new IOException("Invalid sprite PNG");
        // Do not threshold Alpha, trim the canvas, or normalize to the old 300px size.
        BITMAPS.put(assetRelativePath, decoded);
        return decoded;
      } catch (IOException | RuntimeException error) {
        FAILED_PATHS.add(assetRelativePath);
        Log.e(TAG, "Cannot load sprite " + assetRelativePath, error);
        return null;
      }
    }
  }

  /**
   * Warm one character's states/frames away from the UI thread. Every valid call's optional onReady
   * is posted exactly once to the main thread after its load attempt, including duplicate
   * subscriptions and fully cached entries. Completion is not an image-success assertion: bitmap()
   * can still return null for a missing asset. Returns false only for an invalid/unregistered
   * entry. Callbacks are always posted, never invoked inline while a View constructor is still
   * running.
   */
  public static boolean preload(Context context, Entry entry, Runnable onReady) {
    if (entry == null || get(context, entry.id) != entry) return false;
    synchronized (WARMING) {
      PendingPreload pending = WARMING.get(entry.id);
      if (pending != null) {
        if (onReady != null) pending.callbacks.add(onReady);
        return true;
      }
      if (fullyCached(entry)) {
        postReady(onReady);
        return true;
      }
      Context app = context.getApplicationContext();
      WARMING.put(entry.id, new PendingPreload(app == null ? context : app, entry, onReady));
      if (loaderScheduled) return true;
      loaderScheduled = true;
    }
    // Only one drain is submitted regardless of the number of characters/views.
    // Pending entries stay in WARMING, bounded by the catalog's 64-entry limit;
    // filling the executor queue cannot discard callbacks or run decoding on UI.
    try {
      LOADER.execute(PetCatalog::drainPreloads);
    } catch (java.util.concurrent.RejectedExecutionException busy) {
      // A rejected drain retains all subscriptions. The shared scheduled flag
      // permits only one recovery worker, even during a burst of requests.
      Thread recovery = new Thread(PetCatalog::drainPreloads, "gengpet-sprites-recovery");
      recovery.setDaemon(true);
      recovery.start();
    }
    return true;
  }

  private static boolean fullyCached(Entry entry) {
    // LruCache.get is synchronized internally. Do not take the decoder's I/O lock
    // here: subscribing a new UI view must not wait for another image to decode.
    for (String path : entry.states.values()) if (!hasCachedBitmap(path)) return false;
    for (String path : entry.animationFrames) if (!hasCachedBitmap(path)) return false;
    return true;
  }

  private static boolean hasCachedBitmap(String path) {
    Bitmap bitmap = BITMAPS.get(path);
    return bitmap != null && !bitmap.isRecycled();
  }

  private static void drainPreloads() {
    while (true) {
      PendingPreload pending;
      synchronized (WARMING) {
        if (WARMING.isEmpty()) {
          loaderScheduled = false;
          return;
        }
        pending = WARMING.values().iterator().next();
      }
      try {
        for (String path : pending.entry.states.values()) bitmap(pending.context, path);
        for (String path : pending.entry.animationFrames) bitmap(pending.context, path);
      } catch (RuntimeException error) {
        Log.e(TAG, "Sprite preload failed for " + pending.entry.id, error);
      } finally {
        List<Runnable> callbacks;
        synchronized (WARMING) {
          WARMING.remove(pending.entry.id);
          callbacks = new ArrayList<>(pending.callbacks);
        }
        for (Runnable callback : callbacks) postReady(callback);
      }
    }
  }

  private static void postReady(Runnable callback) {
    if (callback == null) return;
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              try {
                callback.run();
              } catch (RuntimeException error) {
                // One disposed/broken subscriber must not prevent other views becoming ready.
                Log.e(TAG, "Sprite ready callback failed", error);
              }
            });
  }

  /** Use after stopping rendering, e.g. on memory pressure. In-use frames stay valid. */
  public static void clearBitmapCache() {
    synchronized (BITMAP_LOCK) {
      BITMAPS.evictAll();
      FAILED_PATHS.clear();
    }
  }

  public static int cachedBitmapBytes() {
    synchronized (BITMAP_LOCK) {
      return BITMAPS.size();
    }
  }

  private static String spritePath(String path, String id) throws JSONException {
    if (!path.startsWith("pets/" + id + "/")
        || !path.endsWith(".png")
        || path.contains("..")
        || path.contains("\\")
        || path.contains("//")
        || path.contains("_mask")) throw new JSONException("Invalid sprite path");
    return path;
  }

  private static int positiveDimension(int value) throws JSONException {
    if (value <= 0 || value > 512) throw new JSONException("Invalid canvas size");
    return value;
  }

  private static float normalized(double value) throws JSONException {
    if (Double.isNaN(value) || Double.isInfinite(value) || value < 0 || value > 1) {
      throw new JSONException("Anchor is outside canvas");
    }
    return (float) value;
  }

  private static Anchor readAnchor(JSONObject json) throws JSONException {
    return new Anchor(normalized(json.getDouble("x")), normalized(json.getDouble("y")));
  }
}
