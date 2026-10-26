package com.maowu.gravitypet.gengpet;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** All persisted instances have independent identity; business schedules are separate. */
public final class PetStore {
  private PetStore() {}

  public static final int MAX_INSTANCES = 6;

  public static SharedPreferences prefs(Context c) {
    return c.getApplicationContext().getSharedPreferences("gengpet", Context.MODE_PRIVATE);
  }

  public static final class Instance {
    public String uid, characterId, mood = "sweating", moodText = "";
    public float x = .5f, y = .35f, sizeDp = 88f, softness = .5f;
    public boolean gravity = true, pinned = false, muted = true;

    public Instance() {}

    public Instance copy() {
      Instance p = new Instance();
      p.uid = uid;
      p.characterId = characterId;
      p.mood = mood;
      p.moodText = moodText;
      p.x = x;
      p.y = y;
      p.sizeDp = sizeDp;
      p.softness = softness;
      p.gravity = gravity;
      p.pinned = pinned;
      p.muted = muted;
      return p;
    }
  }

  private static float finite(double n, float fallback, float min, float max) {
    return Double.isFinite(n) ? Math.max(min, Math.min(max, (float) n)) : fallback;
  }

  public static synchronized ArrayList<Instance> loadInstances(Context c) {
    ArrayList<Instance> out = new ArrayList<>();
    try {
      JSONArray all = new JSONArray(prefs(c).getString("instances", "[]"));
      for (int i = 0; i < all.length() && out.size() < MAX_INSTANCES; i++) {
        JSONObject j = all.getJSONObject(i);
        String id = j.optString("characterId", "");
        if (PetCatalog.get(c, id) == null) continue;
        Instance p = new Instance();
        p.characterId = id;
        p.uid = j.optString("uid", "");
        if (p.uid.isEmpty()) p.uid = UUID.randomUUID().toString();
        boolean duplicate = false;
        for (Instance old : out) if (old.uid.equals(p.uid)) duplicate = true;
        if (duplicate) continue;
        p.x = finite(j.optDouble("x", .5), .5f, 0, 1);
        p.y = finite(j.optDouble("y", .35), .35f, 0, 1);
        p.sizeDp = finite(j.optDouble("sizeDp", 88), 88, 56, 144);
        p.softness = finite(j.optDouble("softness", .5), .5f, 0, 1);
        p.gravity = j.optBoolean("gravity", true);
        p.pinned = j.optBoolean("pinned", false);
        p.muted = j.optBoolean("muted", true);
        p.mood = j.optString("mood", "sweating");
        p.moodText = j.optString("moodText", "");
        out.add(p);
      }
    } catch (Exception e) {
      Log.w("PetStore", "Cannot read saved instances", e);
    }
    return out;
  }

  public static synchronized void saveInstances(Context c, List<Instance> instances) {
    JSONArray out = new JSONArray();
    try {
      for (Instance p : instances) {
        if (out.length() >= MAX_INSTANCES) break;
        if (p == null || p.uid == null || PetCatalog.get(c, p.characterId) == null) continue;
        JSONObject j = new JSONObject();
        j.put("uid", p.uid);
        j.put("characterId", p.characterId);
        j.put("x", finite(p.x, .5f, 0, 1));
        j.put("y", finite(p.y, .35f, 0, 1));
        j.put("sizeDp", finite(p.sizeDp, 88, 56, 144));
        j.put("softness", finite(p.softness, .5f, 0, 1));
        j.put("gravity", p.gravity);
        j.put("pinned", p.pinned);
        j.put("muted", p.muted);
        j.put("mood", p.mood);
        j.put("moodText", p.moodText);
        out.put(j);
      }
      prefs(c).edit().putString("instances", out.toString()).apply();
    } catch (Exception e) {
      Log.e("PetStore", "Save failed", e);
    }
  }

  public static synchronized Instance find(Context c, String uid) {
    if (uid == null) return null;
    for (Instance p : loadInstances(c)) if (uid.equals(p.uid)) return p;
    return null;
  }

  public static synchronized Instance add(Context c, String id) {
    ArrayList<Instance> all = loadInstances(c);
    if (all.size() >= MAX_INSTANCES || PetCatalog.get(c, id) == null) return null;
    Instance p = new Instance();
    p.uid = UUID.randomUUID().toString();
    p.characterId = id;
    SharedPreferences s = prefs(c);
    p.sizeDp = s.getFloat("default_size_" + id, 88);
    p.softness = s.getFloat("default_softness_" + id, .5f);
    p.gravity = s.getBoolean("default_gravity_" + id, true);
    p.muted = s.getBoolean("default_muted_" + id, true);
    p.pinned = s.getBoolean("default_pinned_" + id, false);
    if ("sweat_emoji".equals(id)) {
      p.mood = s.getString("mood_default", "sweating");
      p.moodText = s.getString("mood_text_default", "");
    }
    p.x = .25f + all.size() * .09f;
    p.y = .25f + all.size() * .035f;
    all.add(p);
    saveInstances(c, all);
    return p;
  }

  public static synchronized void update(Context c, Instance instance) {
    if (instance == null) return;
    ArrayList<Instance> all = loadInstances(c);
    for (int i = 0; i < all.size(); i++)
      if (all.get(i).uid.equals(instance.uid)) {
        all.set(i, instance);
        saveInstances(c, all);
        return;
      }
  }

  public static synchronized void remove(Context c, String uid) {
    ArrayList<Instance> all = loadInstances(c);
    all.removeIf(p -> p.uid.equals(uid));
    saveInstances(c, all);
  }

  public static synchronized void clear(Context c) {
    prefs(c).edit().putString("instances", "[]").apply();
  }
}
