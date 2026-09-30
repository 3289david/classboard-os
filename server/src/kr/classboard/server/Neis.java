package kr.classboard.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** NEIS Open API (open.neis.go.kr) client: school info, meals, academic schedule, timetable fallback. */
public final class Neis {
    private static final String BASE = "https://open.neis.go.kr/hub/";

    /** Total count reported by the last call to rows() on this thread. */
    private static final ThreadLocal<Integer> LAST_TOTAL = new ThreadLocal<>();

    /**
     * Rows for a date range. Without an API key NEIS returns at most 5 rows per request and ignores paging,
     * so truncated ranges are split in half until every part fits.
     */
    private static JSONArray rangeRows(String service, String query, String fromParam, String toParam, String from, String to, String key) throws Exception {
        JSONArray r = rows(service, query + "&" + fromParam + "=" + from + "&" + toParam + "=" + to, key);
        Integer total = LAST_TOTAL.get();
        if (total == null || r.length() >= total || from.equals(to)) return r;
        java.text.SimpleDateFormat f = Util.fmt("yyyyMMdd");
        long a = f.parse(from).getTime(), b = f.parse(to).getTime();
        String mid = f.format(new java.util.Date(a + (b - a) / 2 / 86400000L * 86400000L));
        java.util.Calendar c = java.util.Calendar.getInstance(Util.KST);
        c.setTime(f.parse(mid));
        c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        String next = f.format(c.getTime());
        JSONArray out = rangeRows(service, query, fromParam, toParam, from, mid, key);
        JSONArray second = rangeRows(service, query, fromParam, toParam, next, to, key);
        for (int i = 0; i < second.length(); i++) out.put(second.get(i));
        return out;
    }

    private static JSONArray rows(String service, String query, String key) throws Exception {
        JSONArray all = new JSONArray();
        LAST_TOTAL.set(0);
        for (int page = 1; page <= 5; page++) {
            String url = BASE + service + "?Type=json&pIndex=" + page + "&pSize=1000" + query + (key == null || key.isEmpty() ? "" : "&KEY=" + URLEncoder.encode(key, "UTF-8"));
            JSONObject o = new JSONObject(Util.httpGet(url));
            JSONArray top = o.optJSONArray(service);
            if (top == null) {
                JSONObject res = o.optJSONObject("RESULT");
                String code = res == null ? "" : res.optString("CODE");
                if ("INFO-200".equals(code)) return all; // no data
                throw new Exception("NEIS " + service + ": " + (res == null ? "응답 오류" : res.optString("MESSAGE")));
            }
            int total = 0;
            JSONArray head = top.optJSONObject(0) == null ? null : top.optJSONObject(0).optJSONArray("head");
            if (head != null && head.optJSONObject(0) != null) total = head.optJSONObject(0).optInt("list_total_count");
            LAST_TOTAL.set(total);
            JSONArray r = top.optJSONObject(1) == null ? null : top.optJSONObject(1).optJSONArray("row");
            if (r == null) break;
            for (int i = 0; i < r.length(); i++) all.put(r.get(i));
            if (all.length() >= total || r.length() < 1000) break;
        }
        return all;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    public static JSONArray searchSchool(String name, String key) throws Exception {
        JSONArray r = rows("schoolInfo", "&SCHUL_NM=" + enc(name), key);
        JSONArray out = new JSONArray();
        for (int i = 0; i < r.length() && i < 50; i++) {
            JSONObject x = r.getJSONObject(i);
            out.put(Util.jo(
                    "atpt", x.optString("ATPT_OFCDC_SC_CODE"),
                    "atptName", x.optString("ATPT_OFCDC_SC_NM"),
                    "code", x.optString("SD_SCHUL_CODE"),
                    "name", x.optString("SCHUL_NM"),
                    "kind", x.optString("SCHUL_KND_SC_NM"),
                    "address", (x.optString("ORG_RDNMA") + " " + x.optString("ORG_RDNDA").replace(",", "")).trim(),
                    "tel", x.optString("ORG_TELNO"),
                    "homepage", x.optString("HMPG_ADRES"),
                    "region", x.optString("LCTN_SC_NM")));
        }
        return out;
    }

    private static final Pattern ALLERGY = Pattern.compile("\\(([0-9.\\s]+)\\)\\s*$");

    /** Meals for [fromYmd, toYmd] grouped by ISO date. */
    public static JSONObject meals(String atpt, String code, String fromYmd, String toYmd, String key) throws Exception {
        JSONArray r = rangeRows("mealServiceDietInfo", "&ATPT_OFCDC_SC_CODE=" + atpt + "&SD_SCHUL_CODE=" + code, "MLSV_FROM_YMD", "MLSV_TO_YMD", fromYmd, toYmd, key);
        JSONObject days = new JSONObject();
        for (int i = 0; i < r.length(); i++) {
            JSONObject x = r.getJSONObject(i);
            String date = Util.isoFromYmd(x.optString("MLSV_YMD"));
            JSONArray dishes = new JSONArray();
            for (String d : x.optString("DDISH_NM").split("<br\\s*/?>")) {
                String t = d.trim();
                if (t.isEmpty()) continue;
                JSONArray al = new JSONArray();
                Matcher m = ALLERGY.matcher(t);
                if (m.find()) {
                    for (String n : m.group(1).split("\\.")) {
                        n = n.trim();
                        if (!n.isEmpty()) al.put(Integer.parseInt(n));
                    }
                    t = t.substring(0, m.start()).trim();
                }
                dishes.put(Util.jo("name", t, "al", al));
            }
            JSONObject meal = Util.jo(
                    "type", x.optString("MMEAL_SC_NM"),
                    "code", x.optInt("MMEAL_SC_CODE"),
                    "dishes", dishes,
                    "kcal", x.optString("CAL_INFO"),
                    "origin", x.optString("ORPLC_INFO").replaceAll("<br\\s*/?>", "\n"),
                    "nutrition", x.optString("NTR_INFO").replaceAll("<br\\s*/?>", "\n"),
                    "count", x.optDouble("MLSV_FGR", 0));
            Util.arr(days, date).put(meal);
        }
        return days;
    }

    public static JSONArray schedule(String atpt, String code, String fromYmd, String toYmd, String key) throws Exception {
        JSONArray r = rangeRows("SchoolSchedule", "&ATPT_OFCDC_SC_CODE=" + atpt + "&SD_SCHUL_CODE=" + code, "AA_FROM_YMD", "AA_TO_YMD", fromYmd, toYmd, key);
        JSONArray out = new JSONArray();
        String[] gk = {"ONE_GRADE_EVENT_YN", "TW_GRADE_EVENT_YN", "THREE_GRADE_EVENT_YN", "FR_GRADE_EVENT_YN", "FIV_GRADE_EVENT_YN", "SIX_GRADE_EVENT_YN"};
        for (int i = 0; i < r.length(); i++) {
            JSONObject x = r.getJSONObject(i);
            String name = x.optString("EVENT_NM").trim();
            if (name.isEmpty()) continue;
            JSONArray grades = new JSONArray();
            for (int g = 0; g < gk.length; g++) if ("Y".equals(x.optString(gk[g]))) grades.put(g + 1);
            out.put(Util.jo(
                    "date", Util.isoFromYmd(x.optString("AA_YMD")),
                    "name", name,
                    "detail", x.optString("EVENT_CNTNT"),
                    "dayType", x.optString("SBTR_DD_SC_NM"),
                    "grades", grades));
        }
        return out;
    }

    /** NEIS timetable fallback for one class, in the same shape as Comcigan weeks. */
    public static JSONArray timetable(String kind, String atpt, String code, int grade, int cls, JSONArray dates, String key) throws Exception {
        String svc = kind.contains("초") ? "elsTimetable" : kind.contains("중") ? "misTimetable" : "hisTimetable";
        String from = Util.ymd(dates.getString(0));
        String to = Util.ymd(dates.getString(dates.length() - 1));
        JSONArray r = rangeRows(svc, "&ATPT_OFCDC_SC_CODE=" + atpt + "&SD_SCHUL_CODE=" + code + "&GRADE=" + grade + "&CLASS_NM=" + cls, "TI_FROM_YMD", "TI_TO_YMD", from, to, key);
        JSONArray days = new JSONArray();
        for (int i = 0; i < dates.length(); i++) days.put(new JSONArray());
        for (int i = 0; i < r.length(); i++) {
            JSONObject x = r.getJSONObject(i);
            String date = Util.isoFromYmd(x.optString("ALL_TI_YMD"));
            for (int d = 0; d < dates.length(); d++) {
                if (dates.getString(d).equals(date)) {
                    JSONObject e = Util.jo("p", x.optInt("PERIO"), "s", x.optString("ITRT_CNTNT").replaceFirst("^-", "").trim(), "t", "");
                    String room = x.optString("CLRM_NM", "");
                    if (!room.isEmpty()) Util.put(e, "room", room);
                    days.getJSONArray(d).put(e);
                }
            }
        }
        return days;
    }
}
