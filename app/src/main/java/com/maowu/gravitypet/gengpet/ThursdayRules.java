package com.maowu.gravitypet.gengpet;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.Clock;

/** Call refresh on overlay resume/date/timezone changes; midnight never wakes the device. */
public final class ThursdayRules {
  private ThursdayRules() {}

  public static String status() {
    return ThursdayCalendar.status(Clock.systemDefaultZone());
  }

  public static boolean isThursday() {
    return ThursdayCalendar.isThursday(Clock.systemDefaultZone());
  }

  public static boolean quiet(Context c) {
    return FeaturePreferences.prefs(c).getLong("thursday_quiet_until", 0)
        > System.currentTimeMillis();
  }

  public static String state(Context c) {
    return !isThursday() ? "idle" : quiet(c) ? "calm" : "excited";
  }

  public static synchronized boolean claimCelebration(Context c) {
    if (!isThursday() || quiet(c)) return false;
    String day = ThursdayCalendar.key(Clock.systemDefaultZone());
    SharedPreferences p = FeaturePreferences.prefs(c);
    java.util.Set<String> days =
        new java.util.HashSet<>(
            p.getStringSet("thursday_celebrated_days", java.util.Collections.emptySet()));
    if (days.contains(day) || day.equals(p.getString("thursday_celebrated", ""))) return false;
    days.add(day);
    // Shared across all instances; recorded before emitting feedback.
    return p.edit()
        .putString("thursday_celebrated", day)
        .putStringSet("thursday_celebrated_days", days)
        .commit();
  }

  public static void refresh(Context c) {
    // The visible overlay owns the claim. A startup/background reschedule must not
    // consume the day's first appearance when all saved pets are currently stopped.
    FeaturePreferences.reload(c);
  }

  public static void quietForHour(Context c) {
    FeaturePreferences.prefs(c)
        .edit()
        .putLong("thursday_quiet_until", System.currentTimeMillis() + 3600000L)
        .apply();
    FeaturePreferences.feedback(c, "thursday_bucket_cat", "", "calm", "安静一会");
    FeaturePreferences.reload(c);
  }
}
