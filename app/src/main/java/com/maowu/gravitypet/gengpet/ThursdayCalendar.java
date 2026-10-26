package com.maowu.gravitypet.gengpet;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;

public final class ThursdayCalendar {
  private ThursdayCalendar() {}

  public static boolean isThursday(Clock clock) {
    return LocalDate.now(clock).getDayOfWeek() == DayOfWeek.THURSDAY;
  }

  public static int daysUntil(Clock clock) {
    return (4 - LocalDate.now(clock).getDayOfWeek().getValue() + 7) % 7;
  }

  public static String key(Clock clock) {
    return LocalDate.now(clock).toString();
  }

  public static String status(Clock clock) {
    String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    return "今天"
        + names[LocalDate.now(clock).getDayOfWeek().getValue() - 1]
        + (isThursday(clock) ? "，星期四到啦！" : "，距离周四还有 " + daysUntil(clock) + " 天");
  }
}
