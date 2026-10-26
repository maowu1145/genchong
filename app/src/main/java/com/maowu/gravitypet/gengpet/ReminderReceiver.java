package com.maowu.gravitypet.gengpet;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.time.Instant;
import java.time.ZoneId;

public final class ReminderReceiver extends BroadcastReceiver {
  public static final String CHANNEL = "gengpet_life_reminders";

  public static boolean notificationsAllowed(Context c) {
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    if (nm == null
        || !nm.areNotificationsEnabled()
        || (Build.VERSION.SDK_INT >= 33
            && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)) return false;
    NotificationChannel channel = nm.getNotificationChannel(CHANNEL);
    return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
  }

  @Override
  public void onReceive(Context c, Intent intent) {
    String action = intent.getAction();
    if (Intent.ACTION_BOOT_COMPLETED.equals(action)
        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
      ReminderScheduler.reschedule(c);
      return;
    }
    if (Intent.ACTION_DATE_CHANGED.equals(action)) {
      ReminderScheduler.reschedule(c);
      return;
    }
    if (Intent.ACTION_TIME_CHANGED.equals(action)
        || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
      ReminderScheduler.clockChanged(c);
      return;
    }
    String kind = intent.getStringExtra("kind");
    if (!ReminderScheduler.valid(kind)) return;
    if ("gengpet.REMINDER_RESPONSE".equals(action)) {
      // Old notification actions cannot act on a newer reminder.
      if (intent.getLongExtra("due", 0)
          != FeaturePreferences.prefs(c).getLong("reminder_" + kind + "_pending", -1)) return;
      ReminderScheduler.respond(c, kind, intent.getStringExtra("response"));
      return;
    }
    if (!"gengpet.REMINDER".equals(action)) return;
    SharedPreferences p = FeaturePreferences.prefs(c);
    String k = "reminder_" + kind + "_";
    long due = intent.getLongExtra("due", 0), now = System.currentTimeMillis();
    ReminderRules.Config r = ReminderScheduler.config(c, kind);
    if (!r.enabled
        || due <= 0
        || p.getLong(k + "due", 0) != due
        || p.getLong(k + "delivered", 0) == due
        || intent.getLongExtra("revision", -1) != p.getLong(k + "revision", 0)) return;
    p.edit().putLong(k + "delivered", due).remove(k + "snooze_due").remove(k + "due").commit();
    // Drop obsolete alarms rather than replaying a backlog after sleep or a clock jump.
    if (ReminderRules.fresh(due, now)
        && ReminderRules.allowed(r, Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))) {
      p.edit().putLong(k + "pending", due).apply();
      String title = ReminderScheduler.WATER.equals(kind) ? "喝水休息一下" : "记得吃点水果";
      ReminderScheduler.deliverPendingFeedback(c);
      if (r.background && notificationsAllowed(c)) notifyReminder(c, kind, due, title);
    }
    ReminderScheduler.schedule(c, kind, false);
  }

  private static PendingIntent response(Context c, String kind, long due, String response) {
    Intent i =
        new Intent(c, ReminderReceiver.class)
            .setAction("gengpet.REMINDER_RESPONSE")
            .setData(android.net.Uri.parse("gengpet://reminder/" + kind + "/" + response))
            .putExtra("kind", kind)
            .putExtra("due", due)
            .putExtra("response", response);
    return PendingIntent.getBroadcast(
        c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private static void notifyReminder(Context c, String kind, long due, String title) {
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    NotificationChannel channel =
        new NotificationChannel(CHANNEL, "喝水和水果提醒", NotificationManager.IMPORTANCE_DEFAULT);
    channel.setDescription("由你开启的本地生活提醒，系统节电可能延迟");
    nm.createNotificationChannel(channel);
    Intent open = new Intent(c, FeatureActivity.class).putExtra("characterId", "doro");
    Notification n =
        new Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText("可以记住、稍后或今天不再提醒")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(
                Math.max(1, ReminderRules.MAX_PENDING_AGE_MS - (System.currentTimeMillis() - due)))
            .setContentIntent(
                PendingIntent.getActivity(
                    c,
                    4200,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
            .addAction(
                new Notification.Action.Builder(null, "记住了", response(c, kind, due, "done"))
                    .build())
            .addAction(
                new Notification.Action.Builder(null, "稍后", response(c, kind, due, "snooze"))
                    .build())
            .addAction(
                new Notification.Action.Builder(null, "今天不再", response(c, kind, due, "today"))
                    .build())
            .build();
    try {
      nm.notify(ReminderScheduler.notificationId(kind), n);
    } catch (SecurityException ignored) {
    }
  }
}
