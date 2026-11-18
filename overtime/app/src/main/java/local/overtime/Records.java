package local.overtime;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

final class Records {
    private static final Object LOCK = new Object();
    private static final int LEGACY_BASE = Overtime.MINUTES_PER_DAY + 1;
    static final class Punch {
        final int arrival;
        final int departure;

        Punch(int arrival, int departure) {
            this.arrival = arrival;
            this.departure = departure;
        }
    }

    private final SharedPreferences preferences;

    Records(Context context) {
        preferences = context.getSharedPreferences("punches", Context.MODE_PRIVATE);
    }

    NavigableMap<LocalDate, Punch> all() {
        synchronized (LOCK) {
            return load();
        }
    }

    private NavigableMap<LocalDate, Punch> load() {
        NavigableMap<LocalDate, Punch> result = new TreeMap<>();
        preferences.getAll().forEach((date, value) -> {
            LocalDate day = LocalDate.parse(date);
            if (value instanceof String) {
                String[] saved = ((String) value).split(":");
                result.put(day, new Punch(Integer.parseInt(saved[0]), Integer.parseInt(saved[1])));
                return;
            }
            int encoded = (Integer) value;
            int arrival = encoded / LEGACY_BASE - 1;
            int departure = Overtime.departureOnWorkday(encoded % LEGACY_BASE - 1);
            if (arrival >= 0 || departure >= 0) {
                result.put(day, new Punch(arrival, departure));
            }
        });
        return result;
    }

    void save(LocalDate date, Punch punch) {
        synchronized (LOCK) {
            write(Collections.singletonMap(date, punch), true);
        }
    }

    boolean needsBoundaryRepair(LocalDate from) {
        synchronized (LOCK) {
            return legacyOvernight(preferences.getAll().get(from.minusDays(1).toString()));
        }
    }

    int applyOa(NavigableMap<LocalDate, Punch> rawPunches, LocalDate from, LocalDate through) {
        synchronized (LOCK) {
            NavigableMap<LocalDate, Punch> local = load();
            Map<String, ?> stored = preferences.getAll();
            Map<LocalDate, Punch> updates = new TreeMap<>();
            NavigableMap<LocalDate, Punch> selected = rawPunches.subMap(from, true, through, true);
            selected.forEach((day, punch) -> {
                if (Overtime.earlyDeparture(punch.departure)) {
                    repairPreviousDay(rawPunches, local, stored, updates, day, punch);
                }
            });
            int days = 0;
            for (Map.Entry<LocalDate, Punch> entry : selected.entrySet()) {
                LocalDate day = entry.getKey();
                Punch punch = entry.getValue();
                if (punch.arrival >= 0 || punch.departure >= 0) {
                    Punch previous = updates.containsKey(day) ? updates.get(day) : local.get(day);
                    Object saved = stored.get(day.toString());
                    boolean manualOvernight = saved instanceof String && ((String) saved).endsWith(":manual");
                    updates.put(day, mergeOa(previous, punch.arrival, Overtime.departureOnWorkday(punch.departure),
                            manualOvernight));
                    days++;
                }
            }
            write(updates, false);
            return days;
        }
    }

    private static boolean legacyOvernight(Object saved) {
        return saved instanceof String && ((String) saved).split(":").length == 2;
    }

    private static void repairPreviousDay(NavigableMap<LocalDate, Punch> raw, Map<LocalDate, Punch> local,
            Map<String, ?> stored, Map<LocalDate, Punch> updates, LocalDate day, Punch punch) {
        LocalDate previousDay = day.minusDays(1);
        if (!legacyOvernight(stored.get(previousDay.toString()))) {
            return;
        }
        Punch cached = local.get(previousDay);
        Punch oldDay = local.get(day);
        Punch oaPrevious = raw.get(previousDay);
        int overnight = Overtime.departureOnWorkday(punch.departure);
        Object current = stored.get(day.toString());
        boolean confirmedToday = current instanceof String && ((String) current).endsWith(":" + overnight + ":oa");
        // The old importer left a matching arrival with no departure on the workday
        // and copied the overnight departure to the preceding date.
        if (oldDay == null || punch.arrival < 0 || oldDay.arrival != punch.arrival
                || (oldDay.departure >= 0 && !confirmedToday) || cached.departure != overnight
                || oaPrevious == null || cached.arrival != oaPrevious.arrival) {
            return;
        }
        int correct = Overtime.departureOnWorkday(oaPrevious.departure);
        updates.put(previousDay, cached.arrival < 0 && correct < 0 ? null : new Punch(cached.arrival, correct));
    }

    private static Punch mergeOa(Punch old, int oaArrival, int oaDeparture, boolean manualOvernight) {
        int arrival = oaArrival;
        int departure = oaDeparture;
        if (old != null) {
            arrival = arrival < 0 ? old.arrival : arrival;
            if (departure < 0 || (departure < Overtime.MINUTES_PER_DAY && manualOvernight)) {
                departure = old.departure;
            }
            if (departure >= 0 && departure < arrival && (oaArrival < 0 || oaDeparture < 0)) {
                arrival = oaArrival;
                departure = oaDeparture;
            }
        }
        return new Punch(arrival, departure);
    }

    private void write(Map<LocalDate, Punch> punches, boolean manual) {
        SharedPreferences.Editor editor = preferences.edit();
        Map<String, ?> stored = preferences.getAll();
        punches.forEach((date, punch) -> {
            String key = date.toString();
            if (punch == null) {
                editor.remove(key);
            } else if (punch.departure >= Overtime.MINUTES_PER_DAY) {
                String source = manual ? "manual" : "oa";
                Object old = stored.get(key);
                String times = punch.arrival + ":" + punch.departure;
                if (!manual && legacyOvernight(old) && ((String) old).endsWith(":" + punch.departure)) {
                    editor.putString(key, times);
                    return;
                }
                if (!manual && old instanceof String && ((String) old).endsWith(":" + punch.departure + ":manual")) {
                    source = "manual";
                }
                editor.putString(key, times + ":" + source);
            } else {
                int encoded = (punch.arrival + 1) * LEGACY_BASE + punch.departure + 1;
                editor.putInt(key, encoded);
            }
        });
        editor.apply();
    }

    void delete(LocalDate date) {
        synchronized (LOCK) {
            preferences.edit().remove(date.toString()).apply();
        }
    }
}
