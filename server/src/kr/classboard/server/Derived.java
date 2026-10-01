package kr.classboard.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Work the boards would otherwise do themselves: classifying and merging the NEIS school calendar.
 * Boards receive the result together with the school-wide data and only render it.
 */
final class Derived {
    private Derived() {}

    private static final Pattern SKIP = Pattern.compile("^(토요휴업일|토요일)$");
    private static final Pattern HOLIDAY = Pattern.compile("공휴일|대체|설날|추석|성탄|어린이날|현충일|광복절|개천절|한글날|삼일절|석가");

    static String kind(String name, String dayType) {
        String n = name == null ? "" : name;
        String t = dayType == null ? "" : dayType;
        // 수능 is not this school's exam; for a middle school it is a closure day or a notice
        if (n.contains("수학능력") || n.contains("수능")) return t.contains("휴업") ? "휴업일" : "행사";
        if (n.matches(".*(고사|시험|평가원|모의|학력평가).*")) return "시험";
        if (n.contains("수행")) return "수행평가";
        if (n.contains("방학")) return "방학";
        if (n.contains("재량") || n.contains("휴업") || t.contains("휴업")) return "휴업일";
        if (n.contains("입학")) return "입학";
        if (n.contains("졸업")) return "졸업";
        if (HOLIDAY.matcher(n).find() || "공휴일".equals(t)) return "공휴일";
        return "행사";
    }

    /** NEIS schedule items -> [{date, endDate, name, kind, grades}], one entry per multi-day event. */
    static JSONArray events(JSONObject schedule) {
        JSONArray items = schedule == null ? null : schedule.optJSONArray("items");
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject e = items.optJSONObject(i);
            if (e == null || SKIP.matcher(e.optString("name")).find()) continue;
            String date = e.optString("date");
            String name = e.optString("name");
            JSONObject prev = null;
            for (JSONObject m : out) {
                String end = m.optString("endDate", m.optString("date"));
                if (m.optString("name").equals(name) && end.compareTo(date) < 0 && plusDays(end, 3).compareTo(date) >= 0) {
                    prev = m;
                    break;
                }
            }
            if (prev != null) {
                Util.put(prev, "endDate", date);
                Set<Integer> g = new LinkedHashSet<>();
                addAll(g, prev.optJSONArray("grades"));
                addAll(g, e.optJSONArray("grades"));
                Util.put(prev, "grades", new JSONArray(g));
                continue;
            }
            out.add(Util.jo("date", date, "name", name, "kind", kind(name, e.optString("dayType")),
                    "grades", e.optJSONArray("grades") == null ? new JSONArray() : e.optJSONArray("grades")));
        }
        out.sort((a, b) -> a.optString("date").compareTo(b.optString("date")));
        return new JSONArray(out);
    }

    private static void addAll(Set<Integer> s, JSONArray a) {
        for (int i = 0; a != null && i < a.length(); i++) s.add(a.optInt(i));
    }

    private static String plusDays(String iso, int n) {
        try {
            return LocalDate.parse(iso).plusDays(n).toString();
        } catch (Exception e) {
            return iso;
        }
    }
}
