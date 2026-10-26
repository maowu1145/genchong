package com.maowu.gravitypet.gengpet;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Pure local-time scheduling, shared by the UI, receiver and deterministic tests. */
public final class ReminderRules {
  private ReminderRules() {}

  public static final long MAX_PENDING_AGE_MS = 2 * 60 * 60 * 1000L;

  public static boolean fresh(long due, long now) {
    return due > 0 && now >= due && now - due <= MAX_PENDING_AGE_MS;
  }

  public static final class Config {
    public boolean enabled, daily, background;
    public int intervalMinutes = 60, dailyMinute = 900, startMinute = 480, endMinute = 1320;
    public int quietStart = 1320, quietEnd = 480, snoozeMinutes = 10;
    public String skipDate = "";
  }

  public static boolean inWindow(int minute, int start, int end) {
    if (start == end) return true;
    return start < end ? minute >= start && minute < end : minute >= start || minute < end;
  }

  public static boolean quiet(int minute, int start, int end) {
    return start != end && inWindow(minute, start, end);
  }

  public static boolean allowed(Config c, ZonedDateTime at) {
    int minute = at.getHour() * 60 + at.getMinute();
    return c.enabled
        && !at.toLocalDate().toString().equals(c.skipDate)
        && inWindow(minute, c.startMinute, c.endMinute)
        && !quiet(minute, c.quietStart, c.quietEnd);
  }

  /** First scheduled slot strictly after now. Intervals anchor at each window start. */
  public static long next(Config c, long now, ZoneId zone) {
    if (!c.enabled) return -1;
    ZonedDateTime current = Instant.ofEpochMilli(now).atZone(zone);
    LocalDate first = current.toLocalDate().minusDays(1);
    long best = Long.MAX_VALUE;
    for (int day = 0; day < 10; day++) {
      LocalDate date = first.plusDays(day);
      if (c.daily) {
        ZonedDateTime slot =
            date.atStartOfDay(zone).withHour(c.dailyMinute / 60).withMinute(c.dailyMinute % 60);
        if (slot.toInstant().toEpochMilli() > now && allowed(c, slot))
          best = Math.min(best, slot.toInstant().toEpochMilli());
      } else {
        ZonedDateTime start =
            date.atStartOfDay(zone).withHour(c.startMinute / 60).withMinute(c.startMinute % 60);
        int span = (c.endMinute - c.startMinute + 1440) % 1440;
        if (span == 0) span = 1440;
        for (int minute = 0; minute < span; minute += Math.max(15, c.intervalMinutes)) {
          ZonedDateTime slot = start.plusMinutes(minute);
          if (slot.toInstant().toEpochMilli() > now && allowed(c, slot))
            best = Math.min(best, slot.toInstant().toEpochMilli());
        }
      }
    }
    return best == Long.MAX_VALUE ? -1 : best;
  }

  /** Snooze moves to the first allowed minute rather than waking during quiet hours. */
  public static long snooze(Config c, long now, ZoneId zone) {
    ZonedDateTime at = Instant.ofEpochMilli(now).atZone(zone).plusMinutes(c.snoozeMinutes);
    if (at.getSecond() != 0 || at.getNano() != 0) at = at.plusMinutes(1);
    at = at.withSecond(0).withNano(0);
    for (int i = 0; i < 8 * 1440; i++, at = at.plusMinutes(1))
      if (allowed(c, at)) return at.toInstant().toEpochMilli();
    return -1;
  }
}
