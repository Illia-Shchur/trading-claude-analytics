package com.tradinganalytics.core.compute;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** Recurring full-day NYSE holidays; exceptional closures remain explicit fixtures. */
final class UsEquityMarketCalendar {

    private UsEquityMarketCalendar() {
    }

    static boolean isRecurringHoliday(LocalDate date) {
        int year = date.getYear();
        return date.equals(observedNewYearsDay(year))
                || date.equals(nthWeekday(year, 1, DayOfWeek.MONDAY, 3))
                || date.equals(nthWeekday(year, 2, DayOfWeek.MONDAY, 3))
                || date.equals(easterSunday(year).minusDays(2))
                || date.equals(lastWeekday(year, 5, DayOfWeek.MONDAY))
                || (year >= 2022 && date.equals(observedFixedHoliday(year, 6, 19)))
                || date.equals(observedFixedHoliday(year, 7, 4))
                || date.equals(nthWeekday(year, 9, DayOfWeek.MONDAY, 1))
                || date.equals(nthWeekday(year, 11, DayOfWeek.THURSDAY, 4))
                || date.equals(observedFixedHoliday(year, 12, 25));
    }

    private static LocalDate observedNewYearsDay(int year) {
        LocalDate holiday = LocalDate.of(year, 1, 1);
        return holiday.getDayOfWeek() == DayOfWeek.SUNDAY ? holiday.plusDays(1) : holiday;
    }

    private static LocalDate observedFixedHoliday(int year, int month, int day) {
        LocalDate holiday = LocalDate.of(year, month, day);
        return switch (holiday.getDayOfWeek()) {
            case SATURDAY -> holiday.minusDays(1);
            case SUNDAY -> holiday.plusDays(1);
            default -> holiday;
        };
    }

    private static LocalDate nthWeekday(int year, int month, DayOfWeek weekday, int ordinal) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(ordinal, weekday));
    }

    private static LocalDate lastWeekday(int year, int month, DayOfWeek weekday) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(weekday));
    }

    private static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }
}
