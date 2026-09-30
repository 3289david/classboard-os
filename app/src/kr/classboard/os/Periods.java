package kr.classboard.os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Bell schedule: manual bell times from config, otherwise Comcigan start times + lesson length. */
public final class Periods {
    private Periods() {}

    public static final class Slot {
        public final int period, start, end;

        Slot(int p, int s, int e) {
            period = p;
            start = s;
            end = e;
        }
    }

    public static int defaultMinutes(JSONObject config) {
        int m = config == null ? 0 : config.optInt("periodMinutes", 0);
        if (m > 0) return m;
        String kind = config == null || config.optJSONObject("school") == null ? "" : config.optJSONObject("school").optString("kind");
        if (kind.contains("초")) return 40;
        if (kind.contains("중")) return 45;
        return 50;
    }

    public static List<Slot> slots(JSONObject config, JSONArray comciTimes) {
        List<Slot> out = new ArrayList<>();
        JSONArray bell = config == null ? null : config.optJSONArray("bell");
        if (bell != null && bell.length() > 0) {
            for (int i = 0; i < bell.length(); i++) {
                JSONObject b = bell.optJSONObject(i);
                if (b == null) continue;
                int s = Util.parseHm(b.optString("start"));
                int e = Util.parseHm(b.optString("end"));
                if (s >= 0 && e > s) out.add(new Slot(i + 1, s, e));
            }
            return out;
        }
        int len = defaultMinutes(config);
        for (int i = 0; comciTimes != null && i < comciTimes.length(); i++) {
            int s = Util.parseHm(comciTimes.optString(i));
            if (s >= 0) out.add(new Slot(i + 1, s, s + len));
        }
        return out;
    }

    /** Periods that exist today for the class (from the timetable days array for this week). */
    public static int periodsToday(JSONArray weeks) {
        String today = Util.today();
        for (int w = 0; weeks != null && w < weeks.length(); w++) {
            JSONObject wk = weeks.optJSONObject(w);
            JSONArray dates = wk.optJSONArray("dates");
            JSONArray days = wk.optJSONArray("days");
            for (int d = 0; dates != null && d < dates.length(); d++) {
                if (today.equals(dates.optString(d))) {
                    JSONArray ps = days == null ? null : days.optJSONArray(d);
                    int max = 0;
                    for (int k = 0; ps != null && k < ps.length(); k++) {
                        JSONObject e = ps.optJSONObject(k);
                        if (!e.optBoolean("cancel")) max = Math.max(max, e.optInt("p"));
                    }
                    return max;
                }
            }
        }
        return -1; // unknown
    }

    public static boolean isWeekday() {
        int d = Calendar.getInstance(Util.KST).get(Calendar.DAY_OF_WEEK);
        return d != Calendar.SATURDAY && d != Calendar.SUNDAY;
    }
}
