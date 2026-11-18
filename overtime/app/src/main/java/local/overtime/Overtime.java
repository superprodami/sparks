package local.overtime;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

final class Overtime {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final LocalTime REFRESH_TIME = LocalTime.of(9, 0);
    static final LocalTime PUNCH_REMINDER_TIME = LocalTime.of(20, 30);
    static final int MISSING = -1;
    static final int MINUTES_PER_DAY = 24 * 60;
    static final int OVERNIGHT_CUTOFF = 4 * 60;

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    static YearMonth period(LocalDate date) {
        return YearMonth.from(date).plusMonths(date.getDayOfMonth() >= 26 ? 1 : 0);
    }

    static LocalDate start(YearMonth period) {
        return period.minusMonths(1).atDay(26);
    }

    static LocalDate end(YearMonth period) {
        return period.atDay(25);
    }

    static YearMonth quarterStart(YearMonth period) {
        return period.minusMonths((period.getMonthValue() - 1) % 3);
    }

    static YearMonth shiftQuarter(YearMonth month, YearMonth current, int direction) {
        YearMonth next = month.plusMonths(3L * direction);
        if (quarterStart(next).isAfter(quarterStart(current))) {
            return month;
        }
        return next.isAfter(current) ? current : next;
    }

    static int remaining(LocalDate today) {
        return (int) ChronoUnit.DAYS.between(today, end(period(today)));
    }

    static ZonedDateTime nextReminder(ZonedDateTime now, LocalTime time) {
        ZonedDateTime local = now.withZoneSameInstant(ZONE);
        ZonedDateTime target = local.toLocalDate().atTime(time).atZone(ZONE);
        return target.isAfter(local) ? target : target.plusDays(1);
    }

    static ZonedDateTime nextPunchReminder(ZonedDateTime now, LocalTime time) {
        ZonedDateTime target = nextReminder(now, time);
        while (ChinaWorkCalendar.day(target.toLocalDate()).rest) {
            target = target.plusDays(1);
        }
        return target;
    }

    static boolean earlyDeparture(int minutes) {
        return minutes >= 0 && minutes < OVERNIGHT_CUTOFF;
    }

    static int departureOnWorkday(int minutes) {
        return earlyDeparture(minutes) ? MINUTES_PER_DAY + minutes : minutes;
    }

    static int minutes(LocalDate date, int arrival, int departure) {
        boolean restDay = ChinaWorkCalendar.day(date).rest;
        if (departure == MISSING || departure < arrival || (restDay && arrival == MISSING)) {
            return MISSING;
        }
        int worked;
        if (restDay) {
            worked = departure - arrival
                    - overlap(arrival, departure, 12 * 60, 13 * 60)
                    - overlap(arrival, departure, 18 * 60, 18 * 60 + 30);
        } else {
            worked = departure - Math.max(arrival, 18 * 60 + 30);
        }
        return worked >= 60 ? worked / 30 * 30 : 0;
    }

    private static int overlap(int from, int to, int breakStart, int breakEnd) {
        return Math.max(0, Math.min(to, breakEnd) - Math.max(from, breakStart));
    }
}
