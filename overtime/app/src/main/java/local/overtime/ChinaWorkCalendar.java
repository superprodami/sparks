package local.overtime;

import java.time.DayOfWeek;
import java.time.LocalDate;

final class ChinaWorkCalendar {
    enum Day {
        WORKDAY("工作日", false),
        MAKEUP("调休上班", false),
        WEEKEND("节假日", true),
        HOLIDAY("节假日", true);

        final String label;
        final boolean rest;

        Day(String label, boolean rest) {
            this.label = label;
            this.rest = rest;
        }
    }

    // State Council notices: 国办发明电〔2024〕12号, 〔2025〕7号, 〔2026〕10号.
    private static final int FIRST_YEAR = 2025;
    private static final int[][] HOLIDAYS = {
        {101, 101, 128, 204, 404, 406, 501, 505, 531, 602, 1001, 1008},
        {101, 103, 215, 223, 404, 406, 501, 505, 619, 621, 925, 927, 1001, 1007},
        {101, 103, 205, 213, 403, 405, 501, 505, 609, 609, 915, 915, 1001, 1007}
    };
    private static final int[][] MAKEUP_DAYS = {
        {126, 208, 427, 928, 1011},
        {104, 214, 228, 509, 920, 1010},
        {131, 214, 508, 926, 1009}
    };

    static Day day(LocalDate date) {
        int yearIndex = date.getYear() - FIRST_YEAR;
        if (yearIndex >= 0 && yearIndex < HOLIDAYS.length) {
            int monthDay = date.getMonthValue() * 100 + date.getDayOfMonth();
            for (int day : MAKEUP_DAYS[yearIndex]) {
                if (monthDay == day) {
                    return Day.MAKEUP;
                }
            }
            int[] holidays = HOLIDAYS[yearIndex];
            for (int index = 0; index < holidays.length; index += 2) {
                if (monthDay >= holidays[index] && monthDay <= holidays[index + 1]) {
                    return Day.HOLIDAY;
                }
            }
        }
        DayOfWeek weekday = date.getDayOfWeek();
        return weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY ? Day.WEEKEND : Day.WORKDAY;
    }
}
