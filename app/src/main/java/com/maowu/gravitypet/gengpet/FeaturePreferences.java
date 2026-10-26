package com.maowu.gravitypet.gengpet;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Central keys and small cross-component tool messages. No service is started here. */
public final class FeaturePreferences {
  private FeaturePreferences() {}

  public static final String FEEDBACK = "com.maowu.gengpet.FEEDBACK";
  public static final String RELOAD = "com.maowu.gengpet.RELOAD_SETTINGS";
  public static final String[] MOODS = {
    "sweating", "happy", "sad", "angry", "speechless", "sleepy", "shy", "shocked"
  };
  public static final String[] MOOD_NAMES = {"流汗", "开心", "委屈", "生气", "无语", "困倦", "害羞", "震惊"};

  public static SharedPreferences prefs(Context c) {
    return PetStore.prefs(c);
  }

  public static void reload(Context c) {
    c.sendBroadcast(new Intent(RELOAD).setPackage(c.getPackageName()));
  }

  public static void feedback(
      Context c, String character, String instance, String state, String text) {
    c.sendBroadcast(
        new Intent(FEEDBACK)
            .setPackage(c.getPackageName())
            .putExtra("characterId", character)
            .putExtra("instanceId", instance == null ? "" : instance)
            .putExtra("state", state)
            .putExtra("text", text));
  }
}
