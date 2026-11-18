package local.overtime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

final class OaResult {
    final NavigableMap<LocalDate, Records.Punch> punches = new TreeMap<>();
    final LocalDate from;
    final LocalDate through;

    OaResult(JSONObject page, LocalDate today) throws JSONException, IOException {
        from = page.isNull("from") ? null : LocalDate.parse(page.getString("from"));
        through = page.isNull("through") ? null : LocalDate.parse(page.getString("through"));
        JSONArray rows = page.getJSONArray("rows");
        for (int index = 0; index < rows.length(); index++) {
            JSONArray row = rows.getJSONArray(index);
            LocalDate date = LocalDate.parse(row.getString(0));
            if (date.isAfter(today)) {
                continue;
            }
            int arrival = clock(row.getString(1));
            int departure = clock(row.getString(2));
            Records.Punch previous = punches.putIfAbsent(date, new Records.Punch(arrival, departure));
            if (previous != null && (previous.arrival != arrival || previous.departure != departure)) {
                throw new IOException(date + " 有两组不同的 OA 刷卡时间。");
            }
        }
    }

    boolean covers(LocalDate date) {
        return punches.containsKey(date) || (from != null && through != null
                && !date.isBefore(from) && !date.isAfter(through));
    }

    int apply(Records records, LocalDate from, LocalDate through) {
        return records.applyOa(punches, from, through);
    }

    String describeImport(LocalDate from, LocalDate through, int days, boolean refresh) {
        LocalDate first = null;
        LocalDate last = null;
        for (Map.Entry<LocalDate, Records.Punch> entry : punches.subMap(from, true, through, true).entrySet()) {
            Records.Punch punch = entry.getValue();
            if (punch.arrival >= 0 || punch.departure >= 0) {
                if (first == null) {
                    first = entry.getKey();
                }
                last = entry.getKey();
            }
        }
        if (first == null) {
            return span(from, through) + " 无打卡记录";
        }
        return (refresh ? "已刷新 " : "已导入 ") + span(first, last) + " · " + days + " 天打卡";
    }

    private static String span(LocalDate from, LocalDate through) {
        return from.equals(through) ? from.toString() : from + " 至 " + through;
    }

    private static int clock(String value) throws IOException {
        if (value.isEmpty() || value.equals("-") || value.equals("--") || value.equals("缺卡")) {
            return Overtime.MISSING;
        }
        int minutes = PunchTime.parse(value);
        if (minutes < 0) {
            throw new IOException("OA 返回了无法识别的刷卡时间。");
        }
        return minutes;
    }
}
