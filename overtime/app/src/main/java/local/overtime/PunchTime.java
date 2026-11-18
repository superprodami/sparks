package local.overtime;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PunchTime {
    private static final Pattern CLOCK = Pattern.compile("([01]?[0-9]|2[0-3])\\s*:\\s*([0-5][0-9])");

    static int parse(String value) {
        Matcher clock = CLOCK.matcher(value.trim().replace('：', ':'));
        return clock.matches() ? Integer.parseInt(clock.group(1)) * 60 + Integer.parseInt(clock.group(2))
                : Overtime.MISSING;
    }

    static String display(int minutes) {
        if (minutes < 0) {
            return "—";
        }
        String prefix = minutes >= Overtime.MINUTES_PER_DAY ? "次日 " : "";
        return prefix + String.format(Locale.ROOT, "%02d:%02d", (minutes / 60) % 24, minutes % 60);
    }
}
