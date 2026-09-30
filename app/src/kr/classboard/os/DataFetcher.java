package kr.classboard.os;

import android.util.Log;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Pulls external school data (Comcigan timetable, NEIS meals / schedule, weather, RSS notices)
 * and keeps a local cache so the board keeps working when the internet is down.
 */
public class DataFetcher {
    private static final String TAG = "DataFetcher";
    private final File file;
    private JSONObject data;
    private final Object lock = new Object();
    private volatile boolean running;
    private Runnable onChange;

    public DataFetcher(File file) {
        this.file = file;
        JSONObject d = null;
        String s = Util.readFile(file);
        if (s != null) {
            try {
                d = new JSONObject(s);
            } catch (Exception ignored) {
            }
        }
        data = d == null ? new JSONObject() : d;
    }

    public void setOnChange(Runnable r) {
        onChange = r;
    }

    public JSONObject get() {
        synchronized (lock) {
            return Util.copy(data);
        }
    }

    /** Full comcigan dataset (all classes) - used for teacher timetables. */
    public JSONObject comci() {
        synchronized (lock) {
            JSONObject c = data.optJSONObject("comciFull");
            return c == null ? null : Util.copy(c);
        }
    }

    private void set(String key, Object value) {
        synchronized (lock) {
            Util.put(data, key, value);
            try {
                Util.writeFileAtomic(file, data.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "save", e);
            }
        }
        if (onChange != null) onChange.run();
    }

    private void error(String key, Exception e) {
        synchronized (lock) {
            JSONObject errs = Util.obj(data, "errors");
            Util.put(errs, key, Util.jo("at", System.currentTimeMillis(), "message", String.valueOf(e.getMessage())));
        }
        Log.w(TAG, key, e);
    }

    private void clearError(String key) {
        synchronized (lock) {
            JSONObject errs = data.optJSONObject("errors");
            if (errs != null) errs.remove(key);
        }
    }

    private long age(String key) {
        synchronized (lock) {
            JSONObject o = data.optJSONObject(key);
            long at = o == null ? 0 : o.optLong("fetchedAt", 0);
            return System.currentTimeMillis() - at;
        }
    }

    private String sig(String key) {
        synchronized (lock) {
            JSONObject o = data.optJSONObject(key);
            return o == null ? "" : o.optString("sig");
        }
    }

    /**
     * @param config hub config (school, comci, lat/lon, feeds, neisKey)
     * @param cls    device class like "3-2" or empty
     * @param force  ignore cache ages
     */
    /** School-wide data this device can share with classroom devices. */
    public JSONObject shared() {
        synchronized (lock) {
            JSONObject o = new JSONObject();
            for (String k : new String[]{"comciFull", "meals", "schedule", "weather", "feeds"}) if (data.has(k)) Util.put(o, k, data.opt(k));
            return Util.copy(o);
        }
    }

    /** Adopt newer school-wide data from the hub so only the hub needs to call external services. */
    private void pullFromHub(String hubUrl) {
        try {
            JSONObject o = new JSONObject(Util.httpGet(hubUrl + "/api/shared", java.nio.charset.StandardCharsets.UTF_8, 20000));
            boolean changed = false;
            synchronized (lock) {
                JSONArray names = o.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    String k = names.getString(i);
                    JSONObject theirs = o.optJSONObject(k);
                    JSONObject mine = data.optJSONObject(k);
                    if (theirs == null) continue;
                    if (mine == null || theirs.optLong("fetchedAt") > mine.optLong("fetchedAt")) {
                        Util.put(data, k, theirs);
                        changed = true;
                    }
                }
                if (changed) Util.writeFileAtomic(file, data.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (changed && onChange != null) onChange.run();
        } catch (Exception e) {
            Log.d(TAG, "hub shared data unavailable", e);
        }
    }

    public void refresh(JSONObject config, String cls, boolean force, String hubUrl) {
        synchronized (this) {
            if (running) return;
            running = true;
        }
        try {
            if (hubUrl != null && !hubUrl.isEmpty()) {
                pullFromHub(hubUrl);
                force = false; // the hub already refreshes; fall back to direct fetches only when its data is stale
            }
            int nowMin = Util.nowMinutes();
            boolean schoolHours = nowMin >= 6 * 60 + 30 && nowMin <= 18 * 60;
            JSONObject school = config.optJSONObject("school");
            JSONObject comci = config.optJSONObject("comci");
            String key = config.optString("neisKey", "");

            String comciSig = comci == null ? "" : String.valueOf(comci.optInt("code"));
            if (comci != null && comci.optInt("code") > 0) {
                long maxAge = schoolHours ? 10 * 60_000L : 60 * 60_000L;
                if (force || age("comciFull") > maxAge || !comciSig.equals(sig("comciFull"))) {
                    try {
                        JSONObject full = Comcigan.fetch(comci.optInt("code"));
                        Util.put(full, "sig", comciSig);
                        clearError("comci");
                        set("comciFull", full);
                    } catch (Exception e) {
                        error("comci", e);
                    }
                }
            } else if (school != null && !school.optString("code").isEmpty() && cls != null && cls.contains("-")) {
                // Fallback: NEIS timetable for this device's class when Comcigan is not configured.
                String s2 = school.optString("code") + "/" + cls;
                if (force || age("neisTimetable") > 60 * 60_000L || !s2.equals(sig("neisTimetable"))) {
                    try {
                        String[] gc = cls.split("-");
                        JSONArray dates = new JSONArray();
                        for (int i = 0; i < 14; i++) dates.put(Util.dateOffset(i));
                        JSONArray days = Neis.timetable(school.optString("kind"), school.optString("atpt"), school.optString("code"),
                                Integer.parseInt(gc[0]), Integer.parseInt(gc[1]), dates, key);
                        clearError("timetable");
                        set("neisTimetable", Util.jo("fetchedAt", System.currentTimeMillis(), "sig", s2, "dates", dates, "days", days));
                    } catch (Exception e) {
                        error("timetable", e);
                    }
                }
            }

            if (school != null && !school.optString("code").isEmpty()) {
                String ssig = school.optString("atpt") + "/" + school.optString("code");
                if (force || age("meals") > 3 * 3600_000L || !ssig.equals(sig("meals"))) {
                    try {
                        JSONObject days = Neis.meals(school.optString("atpt"), school.optString("code"),
                                Util.ymd(Util.dateOffset(-1)), Util.ymd(Util.dateOffset(14)), key);
                        clearError("meals");
                        set("meals", Util.jo("fetchedAt", System.currentTimeMillis(), "sig", ssig, "days", days));
                    } catch (Exception e) {
                        error("meals", e);
                    }
                }
                if (force || age("schedule") > 6 * 3600_000L || !ssig.equals(sig("schedule"))) {
                    try {
                        JSONArray items = Neis.schedule(school.optString("atpt"), school.optString("code"),
                                Util.ymd(Util.dateOffset(-31)), Util.ymd(Util.dateOffset(200)), key);
                        clearError("schedule");
                        set("schedule", Util.jo("fetchedAt", System.currentTimeMillis(), "sig", ssig, "items", items));
                    } catch (Exception e) {
                        error("schedule", e);
                    }
                }
            }

            double lat = config.optDouble("lat", Double.NaN);
            double lon = config.optDouble("lon", Double.NaN);
            if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                String wsig = lat + "," + lon;
                if (force || age("weather") > 30 * 60_000L || !wsig.equals(sig("weather"))) {
                    try {
                        JSONObject w = weather(lat, lon);
                        Util.put(w, "sig", wsig);
                        clearError("weather");
                        set("weather", w);
                    } catch (Exception e) {
                        error("weather", e);
                    }
                }
            }

            JSONArray feeds = config.optJSONArray("feeds");
            if (feeds != null && feeds.length() > 0) {
                String fsig = feeds.toString();
                if (force || age("feeds") > 15 * 60_000L || !fsig.equals(sig("feeds"))) {
                    JSONArray items = new JSONArray();
                    StringBuilder errs = new StringBuilder();
                    for (int i = 0; i < feeds.length(); i++) {
                        JSONObject f = feeds.optJSONObject(i);
                        if (f == null) continue;
                        try {
                            JSONArray it = rss(f.optString("url"), f.optString("name"));
                            for (int j = 0; j < it.length(); j++) items.put(it.get(j));
                        } catch (Exception e) {
                            errs.append(f.optString("name")).append(": ").append(e.getMessage()).append('\n');
                        }
                    }
                    if (errs.length() > 0) error("feeds", new Exception(errs.toString().trim()));
                    else clearError("feeds");
                    set("feeds", Util.jo("fetchedAt", System.currentTimeMillis(), "sig", fsig, "items", items));
                }
            }
        } finally {
            synchronized (this) {
                running = false;
            }
        }
    }

    static JSONObject weather(double lat, double lon) throws Exception {
        String q = "latitude=" + lat + "&longitude=" + lon + "&timezone=Asia%2FSeoul";
        JSONObject f = new JSONObject(Util.httpGet("https://api.open-meteo.com/v1/forecast?" + q
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=3"));
        JSONObject out = Util.jo("fetchedAt", System.currentTimeMillis(), "current", f.optJSONObject("current"), "daily", f.optJSONObject("daily"));
        try {
            JSONObject a = new JSONObject(Util.httpGet("https://air-quality-api.open-meteo.com/v1/air-quality?" + q + "&current=pm10,pm2_5"));
            Util.put(out, "air", a.optJSONObject("current"));
        } catch (Exception e) {
            Log.w(TAG, "air", e);
        }
        return out;
    }

    /** Geocode a Korean address / place name through OpenStreetMap Nominatim. */
    static JSONArray geocode(String q) throws Exception {
        String body = Util.httpGet("https://nominatim.openstreetmap.org/search?format=json&limit=5&countrycodes=kr&q=" + URLEncoder.encode(q, "UTF-8"));
        JSONArray a = new JSONArray(body);
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            out.put(Util.jo("lat", Double.parseDouble(x.optString("lat")), "lon", Double.parseDouble(x.optString("lon")), "name", x.optString("display_name")));
        }
        return out;
    }

    static JSONArray rss(String url, String source) throws Exception {
        byte[] b = Util.httpGetBytes(url, 15000);
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new ByteArrayInputStream(b), null);
        JSONArray items = new JSONArray();
        JSONObject cur = null;
        String tag = null;
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                tag = p.getName();
                if ("item".equals(tag) || "entry".equals(tag)) cur = Util.jo("source", source, "title", "", "link", "", "date", "");
                else if (cur != null && "link".equals(tag) && p.getAttributeValue(null, "href") != null) Util.put(cur, "link", p.getAttributeValue(null, "href"));
            } else if (ev == XmlPullParser.TEXT && cur != null && tag != null) {
                String t = p.getText().trim();
                if (t.isEmpty()) continue;
                switch (tag) {
                    case "title": Util.put(cur, "title", cur.optString("title") + t); break;
                    case "link": Util.put(cur, "link", t); break;
                    case "pubDate": case "published": case "updated": case "dc:date": Util.put(cur, "date", t); break;
                    default: break;
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String n = p.getName();
                if (("item".equals(n) || "entry".equals(n)) && cur != null) {
                    items.put(cur);
                    cur = null;
                    if (items.length() >= 30) break;
                }
                tag = null;
            }
        }
        return items;
    }
}
