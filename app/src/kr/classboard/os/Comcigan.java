package kr.classboard.os;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for the Comcigan timetable service (comci.net:4082).
 * Key names inside the payload are obfuscated and change over time, so they are
 * discovered from the service's own JavaScript on every refresh.
 */
public final class Comcigan {
    private static final String BASE = "http://comci.net:4082";
    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    private static class Routes {
        String searchPath;   // "/36179?17384l"
        String dataPath;     // "/36179"
        String prefix;       // "73629_"
        String kOrig, kDaily, kTeacher, kSubject, kRoom, kUpdated;
    }

    private static Routes cached;
    private static long cachedAt;

    private static String find(String src, String regex, String def) {
        Matcher m = Pattern.compile(regex).matcher(src);
        return m.find() ? m.group(1) : def;
    }

    private static synchronized Routes routes() throws IOException {
        if (cached != null && System.currentTimeMillis() - cachedAt < 3600_000L) return cached;
        String st = Util.httpGet(BASE + "/st", EUC_KR, 15000);
        Routes r = new Routes();
        r.searchPath = find(st, "url:'\\.(/\\d+\\?[^']*)'", null);
        r.dataPath = find(st, "var sc3='\\.(/\\d+)\\?'", null);
        r.prefix = find(st, "sc_data\\('([^']*)'", null);
        r.kOrig = find(st, "원자료=Q자료\\(자료\\.(자료\\d+)", null);
        r.kDaily = find(st, "일일자료=Q자료\\(자료\\.(자료\\d+)", null);
        r.kTeacher = find(st, "성명=Q성명\\(자료\\.(자료\\d+)", null);
        r.kSubject = find(st, "과목명=Q과목명\\(자료\\.(자료\\d+)", null);
        r.kRoom = find(st, "var m3=자료\\.(자료\\d+)", null);
        r.kUpdated = find(st, "수정일: '\\+H시간표\\.(자료\\d+)", null);
        if (r.dataPath == null && r.searchPath != null) r.dataPath = r.searchPath.substring(0, r.searchPath.indexOf('?'));
        if (r.searchPath == null || r.dataPath == null || r.prefix == null || r.kDaily == null || r.kTeacher == null || r.kSubject == null) {
            throw new IOException("컴시간 서비스 구조를 해석하지 못했습니다");
        }
        cached = r;
        cachedAt = System.currentTimeMillis();
        return r;
    }

    private static String cleanJson(String s) {
        int end = s.lastIndexOf('}');
        return end >= 0 ? s.substring(0, end + 1) : s;
    }

    /** Search schools by name. Returns [{region, name, code}]. */
    public static JSONArray search(String name) throws Exception {
        Routes r = routes();
        String url = BASE + r.searchPath + URLEncoder.encode(name, "EUC-KR");
        JSONObject o = new JSONObject(cleanJson(Util.httpGet(url, StandardCharsets.UTF_8, 15000)));
        JSONArray list = o.optJSONArray("학교검색");
        JSONArray out = new JSONArray();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONArray row = list.optJSONArray(i);
            if (row == null || row.length() < 4) continue;
            int code = row.optInt(3);
            if (code <= 0) continue;
            out.put(Util.jo("region", row.optString(1), "name", row.optString(2), "code", code));
        }
        return out;
    }

    private static JSONObject fetchRaw(Routes r, int code, int week) throws Exception {
        String arg = r.prefix + code + "_0_" + week;
        String b64 = Base64.encodeToString(arg.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String body = Util.httpGet(BASE + r.dataPath + "?" + b64, StandardCharsets.UTF_8, 20000);
        body = cleanJson(body);
        if (body.length() < 18) throw new IOException("컴시간 데이터가 비어 있습니다");
        return new JSONObject(body);
    }

    /** Port of splitData(): split into 3-digit groups from the right, lowest group first. */
    private static long[] splitData(String s) {
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return new long[]{0, 0, 0};
        List<Long> groups = new ArrayList<>();
        int end = digits.length();
        while (end > 0) {
            int start = Math.max(0, end - 3);
            groups.add(Long.parseLong(digits.substring(start, end)));
            end = start;
        }
        long[] out = new long[]{0, 0, 0};
        for (int i = 0; i < Math.min(3, groups.size()); i++) out[i] = groups.get(i);
        return out;
    }

    private static class Cell {
        int th;
        int sb;
        String group = "";
        boolean changedMark;
    }

    private static Cell decode(Object v, int split) {
        Cell c = new Cell();
        if (v == null || v == JSONObject.NULL) return c;
        String s = String.valueOf(v);
        c.changedMark = s.startsWith(">");
        long[] ma = splitData(c.changedMark ? s.substring(1) : s);
        c.th = (int) ma[0];
        long sb = ma[1] + ma[2] * 1000;
        if (split != 100) {
            long t = sb / split;
            if (t >= 1 && t <= 26) c.group = ((char) (t + 64)) + "_";
            sb = sb % split;
        }
        c.sb = (int) sb;
        return c;
    }

    private static Object at(JSONObject root, String key, int g, int c, int d, int p) {
        if (key == null) return null;
        JSONArray a = root.optJSONArray(key);
        if (a == null) return null;
        JSONArray ga = a.optJSONArray(g);
        if (ga == null) return null;
        JSONArray ca = ga.optJSONArray(c);
        if (ca == null) return null;
        JSONArray da = ca.optJSONArray(d);
        if (da == null) return null;
        return da.opt(p);
    }

    private static String teacherName(JSONArray teachers, int th) {
        if (teachers == null || th <= 0 || th >= teachers.length()) return "";
        String n = teachers.optString(th, "");
        boolean keepMask = (n.contains("*") && !n.startsWith("*") && !n.endsWith("*")) || (n.length() == 2 && n.charAt(1) == '*');
        return keepMask ? n : n.replaceFirst("\\*", "");
    }

    private static String subjectName(JSONArray subjects, int sb) {
        if (subjects == null || sb <= 0 || sb >= subjects.length()) return "";
        return subjects.optString(sb, "");
    }

    /** Fetch and normalise the timetable of a whole school for the listed weeks. */
    public static JSONObject fetch(int code) throws Exception {
        Routes r = routes();
        JSONObject first = fetchRaw(r, code, 1);
        List<Integer> weeks = new ArrayList<>();
        JSONArray days = first.optJSONArray("일자자료");
        if (days != null) {
            for (int i = 0; i < days.length() && weeks.size() < 2; i++) {
                JSONArray d = days.optJSONArray(i);
                if (d != null) weeks.add(d.optInt(0));
            }
        }
        if (weeks.isEmpty()) weeks.add(1);

        JSONObject out = new JSONObject();
        Util.put(out, "fetchedAt", System.currentTimeMillis());
        Util.put(out, "code", code);
        Util.put(out, "schoolName", first.optString("학교명"));
        Util.put(out, "region", first.optString("지역명"));
        Util.put(out, "updated", r.kUpdated == null ? "" : first.optString(r.kUpdated));
        JSONArray times = new JSONArray();
        JSONArray rawTimes = first.optJSONArray("일과시간");
        if (rawTimes != null) {
            for (int i = 0; i < rawTimes.length(); i++) {
                String t = rawTimes.optString(i);
                Matcher m = Pattern.compile("\\((\\d{1,2}:\\d{2})\\)").matcher(t);
                times.put(m.find() ? m.group(1) : "");
            }
        }
        Util.put(out, "times", times);
        JSONArray teachersOut = new JSONArray();
        JSONArray teachers = first.optJSONArray(r.kTeacher);
        if (teachers != null) for (int i = 0; i < teachers.length(); i++) teachersOut.put(teacherName(teachers, i));
        Util.put(out, "teachers", teachersOut);
        JSONArray subjects = first.optJSONArray(r.kSubject);
        Util.put(out, "subjects", subjects == null ? new JSONArray() : subjects);

        JSONObject classCounts = new JSONObject();
        JSONArray cc = first.optJSONArray("학급수");
        JSONArray vc = first.optJSONArray("가상학급수");
        if (cc != null) {
            for (int g = 1; g < cc.length(); g++) {
                int n = cc.optInt(g) - (vc == null ? 0 : vc.optInt(g));
                if (n > 0) Util.put(classCounts, String.valueOf(g), n);
            }
        }
        Util.put(out, "classCounts", classCounts);

        JSONArray weeksOut = new JSONArray();
        for (int w : weeks) {
            JSONObject raw = w == 1 ? first : fetchRaw(r, code, w);
            weeksOut.put(normaliseWeek(raw, r, w, classCounts));
        }
        Util.put(out, "weeks", weeksOut);
        return out;
    }

    private static JSONObject normaliseWeek(JSONObject raw, Routes r, int w, JSONObject classCounts) throws Exception {
        int split = raw.has("분리") ? raw.optInt("분리", 100) : 100;
        boolean changeFlag = raw.optInt("변경알림") == 1;
        boolean rooms = raw.optInt("강의실") == 1 && r.kRoom != null;
        JSONArray teachers = raw.optJSONArray(r.kTeacher);
        JSONArray subjects = raw.optJSONArray(r.kSubject);
        JSONArray perDay = raw.optJSONArray("요일별시수");
        String start = raw.optString("시작일");
        SimpleDateFormat f = Util.fmt("yyyy-MM-dd");
        Date startDate = f.parse(start);
        Calendar cal = Calendar.getInstance(Util.KST);
        cal.setTime(startDate);

        JSONObject week = new JSONObject();
        Util.put(week, "r", w);
        Util.put(week, "start", start);
        JSONArray dates = new JSONArray();
        for (int d = 1; d <= 6; d++) {
            dates.put(f.format(cal.getTime()));
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        Util.put(week, "dates", dates);
        JSONObject classes = new JSONObject();
        JSONArray names = classCounts.names();
        for (int gi = 0; names != null && gi < names.length(); gi++) {
            int g = Integer.parseInt(names.getString(gi));
            int count = classCounts.optInt(names.getString(gi));
            JSONArray dayCounts = perDay == null ? null : perDay.optJSONArray(g);
            for (int c = 1; c <= count; c++) {
                JSONArray daysOut = new JSONArray();
                for (int d = 1; d <= 6; d++) {
                    JSONArray periods = new JSONArray();
                    Object n1o = at(raw, r.kDaily, g, c, d, 0);
                    int n1 = n1o == null ? 0 : (int) splitData(String.valueOf(n1o))[0];
                    int n0 = dayCounts == null ? 0 : dayCounts.optInt(d);
                    for (int p = 1; p <= 8; p++) {
                        Object dv = at(raw, r.kDaily, g, c, d, p);
                        Object ov = at(raw, r.kOrig, g, c, d, p);
                        Cell dc = decode(dv, split);
                        if (dc.th > 0 || dc.sb > 0) {
                            JSONObject e = new JSONObject();
                            Util.put(e, "p", p);
                            Util.put(e, "s", subjectName(subjects, dc.sb));
                            Util.put(e, "t", teacherName(teachers, dc.th));
                            Util.put(e, "th", dc.th);
                            if (!dc.group.isEmpty()) Util.put(e, "grp", dc.group.substring(0, 1));
                            boolean changed = changeFlag ? dc.changedMark : !String.valueOf(dv).equals(String.valueOf(ov));
                            if (changed) {
                                Util.put(e, "ch", true);
                                Cell oc = decode(ov, split);
                                if (oc.th > 0 || oc.sb > 0) {
                                    Util.put(e, "os", subjectName(subjects, oc.sb));
                                    Util.put(e, "ot", teacherName(teachers, oc.th));
                                }
                            }
                            if (rooms) {
                                Object rv = at(raw, r.kRoom, g, c, d, p);
                                String rs = rv == null ? "" : String.valueOf(rv);
                                int u = rs.indexOf('_');
                                if (u > 0) {
                                    try {
                                        if (Integer.parseInt(rs.substring(0, u).trim()) > 0) Util.put(e, "room", rs.substring(u + 1));
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                            }
                            periods.put(e);
                        } else if (p > n1 && p <= n0) {
                            periods.put(Util.jo("p", p, "s", "", "cancel", true));
                        }
                    }
                    daysOut.put(periods);
                }
                Util.put(classes, g + "-" + c, daysOut);
            }
        }
        Util.put(week, "classes", classes);
        return week;
    }
}
