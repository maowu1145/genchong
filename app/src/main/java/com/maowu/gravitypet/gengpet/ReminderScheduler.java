package com.maowu.gravitypet.gengpet;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.time.ZoneId;

public final class ReminderScheduler {
  private ReminderScheduler() {}

  public static final String WATER = "water", FRUIT = "fruit";

  public static boolean valid(String kind) {
    return WATER.equals(kind) || FRUIT.equals(kind);
  }

  public static ReminderRules.Config config(Context c, String kind) {
    SharedPreferences p = FeaturePreferences.prefs(c);
    String k = "reminder_" + kind + "_";
    ReminderRules.Config r = new ReminderRules.Config();
    r.enabled = p.getBoolean(k + "enabled", false);
    r.daily = p.getBoolean(k + "daily", FRUIT.equals(kind));
    r.background = p.getBoolean(k + "background", false);
    r.intervalMinutes = p.getInt(k + "interval", 60);
    r.dailyMinute = p.getInt(k + "time", 900);
    r.startMinute = p.getInt(k + "start", 480);
    r.endMinute = p.getInt(k + "end", 1320);
    r.quietStart = p.getInt(k + "quiet_start", 1320);
    r.quietEnd = p.getInt(k + "quiet_end", 480);
    r.snoozeMinutes = p.getInt(k + "snooze", 10);
    r.skipDate = p.getString(k + "skip", "");
    return r;
  }

  public static void save(Context c, String kind, ReminderRules.Config r) {
    if (!valid(kind)) throw new IllegalArgumentException("提醒类型无效");
    String k = "reminder_" + kind + "_";
    FeaturePreferences.prefs(c)
        .edit()
        .putBoolean(k + "enabled", r.enabled)
        .putBoolean(k + "daily", r.daily)
        .putBoolean(k + "background", r.background)
        .putInt(k + "interval", r.intervalMinutes)
        .putInt(k + "time", r.dailyMinute)
        .putInt(k + "start", r.startMinute)
        .putInt(k + "end", r.endMinute)
        .putInt(k + "quiet_start", r.quietStart)
        .putInt(k + "quiet_end", r.quietEnd)
        .putInt(k + "snooze", r.snoozeMinutes)
        .remove(k + "due")
        .remove(k + "pending")
        .remove(k + "snooze_due")
        .putLong(k + "revision", java.util.UUID.randomUUID().getMostSignificantBits())
        .commit();
    cancelNotification(c, kind);
    schedule(c, kind, false);
  }

  public static void reschedule(Context c) {
    schedule(c, WATER, true);
    schedule(c, FRUIT, true);
    ThursdayRules.refresh(c);
    deliverPendingFeedback(c);
  }

  /** On screen-on/resume, emit only the latest still-relevant pending business reminder. */
  public static void deliverPendingFeedback(Context c) {
    android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
    if (pm == null || !pm.isInteractive()) return;
    SharedPreferences p = FeaturePreferences.prefs(c);
    String latest = null;
    long latestDue = 0, now = System.currentTimeMillis();
    for (String kind : new String[] {WATER, FRUIT}) {
      String k = "reminder_" + kind + "_";
      long due = p.getLong(k + "pending", 0);
      ReminderRules.Config r = config(c, kind);
      if (due > 0 && !ReminderRules.fresh(due, now)) {
        p.edit().remove(k + "pending").apply();
        cancelNotification(c, kind);
        continue;
      }
      if (due > latestDue
          && ReminderRules.fresh(due, now)
          && ReminderRules.allowed(
              r, java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))) {
        latest = kind;
        latestDue = due;
      }
    }
    if (latest == null || p.getLong("reminder_latest_feedback", 0) >= latestDue) return;
    // No running overlay means retain the pending feedback for a later visible resume.
    if (!GengOverlayService.canReceiveFeedback("doro")) return;
    p.edit().putLong("reminder_latest_feedback", latestDue).apply();
    FeaturePreferences.feedback(c, "doro", "", latest, WATER.equals(latest) ? "喝水休息一下" : "记得吃点水果");
  }

  /** Clock/timezone changes must discard old wall-clock triggers. */
  public static void clockChanged(Context c) {
    SharedPreferences.Editor edit =
        FeaturePreferences.prefs(c).edit().remove("reminder_latest_feedback");
    for (String kind : new String[] {WATER, FRUIT}) {
      String k = "reminder_" + kind + "_";
      edit.remove(k + "due")
          .remove(k + "snooze_due")
          .remove(k + "pending")
          .remove(k + "delivered")
          .putLong(k + "revision", java.util.UUID.randomUUID().getMostSignificantBits());
      cancelNotification(c, kind);
    }
    edit.commit();
    reschedule(c);
  }

  private static PendingIntent alarm(Context c, String kind, long due) {
    Intent i =
        new Intent(c, ReminderReceiver.class)
            .setAction("gengpet.REMINDER")
            .putExtra("kind", kind)
            .putExtra("due", due)
            .putExtra(
                "revision",
                FeaturePreferences.prefs(c).getLong("reminder_" + kind + "_revision", 0));
    return PendingIntent.getBroadcast(
        c,
        WATER.equals(kind) ? 4101 : 4102,
        i,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  static void schedule(Context c, String kind, boolean preserve) {
    String k = "reminder_" + kind + "_";
    SharedPreferences p = FeaturePreferences.prefs(c);
    ReminderRules.Config r = config(c, kind);
    long now = System.currentTimeMillis();
    AlarmManager am = c.getSystemService(AlarmManager.class);
    if (am == null) return;
    long previous = p.getLong(k + "due", 0);
    am.cancel(alarm(c, kind, 0));
    if (!r.enabled) {
      p.edit().remove(k + "due").remove(k + "pending").remove(k + "snooze_due").apply();
      cancelNotification(c, kind);
      return;
    }
    if (preserve
        && ReminderRules.fresh(previous, now)
        && p.getLong(k + "delivered", 0) != previous
        && ReminderRules.allowed(
            r, java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))) {
      // Startup may run before a delayed alarm broadcast. Keep one useful reminder
      // before replacing the trigger, rather than silently losing it or replaying a queue.
      p.edit()
          .putLong(k + "pending", previous)
          .putLong(k + "delivered", previous)
          .remove(k + "snooze_due")
          .commit();
    }
    long snooze = p.getLong(k + "snooze_due", 0);
    long due =
        preserve && previous > now ? previous : ReminderRules.next(r, now, ZoneId.systemDefault());
    if (snooze > now) due = snooze;
    if (due <= now) {
      p.edit().remove(k + "due").apply();
      return;
    }
    p.edit().putLong(k + "due", due).commit();
    // Inexact alarm, no special exact-alarm access, and no background service start.
    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, alarm(c, kind, due));
  }

  public static long nextDue(Context c, String kind) {
    return FeaturePreferences.prefs(c).getLong("reminder_" + kind + "_due", 0);
  }

  public static void respond(Context c, String kind, String action) {
    if (!valid(kind)
        || !("done".equals(action) || "snooze".equals(action) || "today".equals(action))) return;
    String k = "reminder_" + kind + "_";
    SharedPreferences p = FeaturePreferences.prefs(c);
    if (!config(c, kind).enabled
        || !ReminderRules.fresh(p.getLong(k + "pending", 0), System.currentTimeMillis())) {
      p.edit().remove(k + "pending").apply();
      cancelNotification(c, kind);
      return;
    }
    SharedPreferences.Editor e = p.edit().remove(k + "pending");
    if ("snooze".equals(action))
      e.putLong(
          k + "snooze_due",
          ReminderRules.snooze(
              config(c, kind), System.currentTimeMillis(), ZoneId.systemDefault()));
    else e.remove(k + "snooze_due");
    if ("today".equals(action)) e.putString(k + "skip", java.time.LocalDate.now().toString());
    e.remove(k + "due").commit();
    cancelNotification(c, kind);
    schedule(c, kind, false);
    FeaturePreferences.feedback(
        c, "doro", "", "satisfied", "snooze".equals(action) ? "稍后再提醒" : "记住啦");
  }

  static int notificationId(String kind) {
    return WATER.equals(kind) ? 4201 : 4202;
  }

  static void cancelNotification(Context c, String kind) {
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    if (nm != null) nm.cancel(notificationId(kind));
  }
}
