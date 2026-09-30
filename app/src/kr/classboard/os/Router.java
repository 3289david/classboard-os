package kr.classboard.os;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import kr.classboard.os.HttpServer.ApiException;
import kr.classboard.os.HttpServer.Request;
import kr.classboard.os.HttpServer.Response;

/** Routes: static web UI, device-local API (/api/local), hub API or proxy to hub. */
public class Router implements HttpServer.Handler {
    private final Context ctx;
    private final DeviceConfig cfg;
    private final SyncClient sync;
    private final DataFetcher fetcher;
    private final LanBus lan;
    private volatile HubApi hub;
    private final Map<String, byte[]> iconCache = new ConcurrentHashMap<>();
    private final CoreService core;

    public Router(CoreService core, DeviceConfig cfg, SyncClient sync, DataFetcher fetcher, LanBus lan) {
        this.core = core;
        this.ctx = core.getApplicationContext();
        this.cfg = cfg;
        this.sync = sync;
        this.fetcher = fetcher;
        this.lan = lan;
    }

    public void setHub(HubApi h) {
        hub = h;
    }

    @Override
    public Response handle(Request r) throws Exception {
        String p = r.path;
        if (p.startsWith("/api/local/")) return local(r, p.substring("/api/local/".length()));
        if (p.startsWith("/api/")) {
            HubApi h = hub;
            if (h != null && "/api/shared".equals(p)) return Response.json(fetcher.shared());
            if (h != null && "/api/homepage/detail".equals(p)) return homepageDetail(r);
            if (h != null) return h.handle(r);
            if ("/api/state".equals(p)) return sync.serveState(r);
            return sync.proxy(r);
        }
        return staticFile(r);
    }

    // ------------------------------------------------------------------ static

    private Response staticFile(Request r) throws Exception {
        String p = r.path;
        if (p.equals("/") || p.isEmpty()) {
            Response res = new Response();
            res.status = 302;
            res.body = new byte[0];
            res.headers.put("Location", "/m/");
            return res;
        }
        if (p.endsWith("/")) p = p + "index.html";
        if (p.contains("..")) return Response.error(400, "bad path");
        try {
            InputStream in = ctx.getAssets().open("web" + p);
            Response res = Response.bytes(Util.readAll(in), Util.mimeFor(p));
            in.close();
            res.headers.put("Cache-Control", "no-cache");
            return res;
        } catch (java.io.IOException e) {
            return Response.error(404, "없는 파일입니다");
        }
    }

    // ------------------------------------------------------------------ device-local API

    private void requireLocalOrAdmin(Request r) throws Exception {
        if (r.isLocal()) return;
        requireAdminToken(r);
    }

    private void requireAdminToken(Request r) throws Exception {
        String tok = r.header("x-token");
        if (tok == null || tok.isEmpty()) throw new ApiException(401, "관리자 로그인이 필요합니다");
        JSONObject me;
        HubApi h = hub;
        if (h != null) {
            Request q = new Request();
            q.method = "GET";
            q.path = "/api/me";
            q.headers.put("x-token", tok);
            Response res = h.handle(q);
            if (res.status != 200) throw new ApiException(401, "관리자 로그인이 필요합니다");
            me = new JSONObject(new String(res.body, "UTF-8"));
        } else {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(cfg.hubUrl() + "/api/me").openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setRequestProperty("X-Token", tok);
            if (c.getResponseCode() != 200) throw new ApiException(401, "관리자 로그인이 필요합니다");
            me = new JSONObject(new String(Util.readAll(c.getInputStream()), "UTF-8"));
        }
        if (!"admin".equals(me.optJSONObject("user") == null ? "" : me.optJSONObject("user").optString("role")))
            throw new ApiException(403, "관리자만 변경할 수 있습니다");
    }

    private Response local(Request r, String action) throws Exception {
        switch (action) {
            case "device": {
                if ("GET".equals(r.method)) {
                    JSONObject o = cfg.toJson();
                    Util.put(o, "sync", sync.status());
                    Util.put(o, "perms", core.permissionStatus());
                    Util.put(o, "version", BuildInfo.VERSION);
                    return Response.json(o);
                }
                // First-time setup is allowed from the device itself; afterwards an admin token is required.
                if (!cfg.role().isEmpty()) requireAdminToken(r);
                else if (!r.isLocal()) throw new ApiException(403, "초기 설정은 기기에서만 가능합니다");
                JSONObject b = r.json();
                String oldHub = cfg.hubUrl();
                String oldRole = cfg.role();
                cfg.update(b);
                core.onDeviceConfigChanged(!oldRole.equals(cfg.role()), !oldHub.equals(cfg.hubUrl()));
                return Response.ok();
            }
            case "settings": {
                // Board-level operational settings (auto mode, power, launch rules). Local or admin.
                requireLocalOrAdmin(r);
                if ("GET".equals(r.method)) return Response.json(cfg.settings());
                JSONObject b = r.json();
                cfg.update(Util.jo("settings", b));
                core.onDeviceConfigChanged(false, false);
                return Response.ok();
            }
            case "discover":
                return Response.json(Util.jo("hubs", lan.hubs()));
            case "probe": {
                String url = r.param("url");
                if (url == null) throw new ApiException(400, "url 누락");
                if (!url.startsWith("http")) url = "http://" + url;
                if (!url.matches(".*:\\d+$")) url = url + ":" + DeviceConfig.PORT;
                try {
                    return Response.json(Util.jo("ok", true, "url", url, "info", sync.getJson(url + "/api/ping")));
                } catch (Exception e) {
                    return Response.json(Util.jo("ok", false, "error", e.getMessage()));
                }
            }
            case "data":
                return Response.json(dataSlice(r.param("cls") == null ? cfg.cls() : r.param("cls")));
            case "refresh":
                core.refreshData(true);
                return Response.ok();
            case "comci/teachers":
                return Response.json(comciTeachers());
            case "comci/teacher":
                return Response.json(comciTeacher(Integer.parseInt(r.param("th") == null ? "0" : r.param("th"))));
            case "comci/classes": {
                JSONObject c = fetcher.comci();
                return Response.json(Util.jo("classCounts", c == null ? new JSONObject() : c.optJSONObject("classCounts")));
            }
            case "icon":
                return icon(r.param("pkg"));
            case "att-token": {
                if (!r.isLocal()) throw new ApiException(403, "기기에서만 사용할 수 있습니다");
                String cls = r.param("cls") == null ? cfg.cls() : r.param("cls");
                JSONObject t = sync.getJson(cfg.hubUrl() + "/api/device/att-token?cls=" + cls);
                return Response.json(t);
            }
            case "ack": {
                if (!r.isLocal()) throw new ApiException(403, "기기에서만 사용할 수 있습니다");
                String id = r.json().optString("alertId");
                core.ackAlert(id);
                return Response.ok();
            }
            default:
                return null;
        }
    }

    private JSONObject dataSlice(String cls) {
        JSONObject d = fetcher.get();
        JSONObject out = new JSONObject();
        JSONObject full = d.optJSONObject("comciFull");
        if (full != null) {
            JSONArray weeks = new JSONArray();
            JSONArray fw = full.optJSONArray("weeks");
            for (int i = 0; fw != null && i < fw.length(); i++) {
                JSONObject w = fw.optJSONObject(i);
                JSONObject classes = w.optJSONObject("classes");
                weeks.put(Util.jo("start", w.optString("start"), "dates", w.optJSONArray("dates"),
                        "days", classes == null || cls == null ? null : classes.optJSONArray(cls)));
            }
            Util.put(out, "timetable", Util.jo("source", "comcigan", "fetchedAt", full.optLong("fetchedAt"), "updated", full.optString("updated"),
                    "schoolName", full.optString("schoolName"), "times", full.optJSONArray("times"), "classCounts", full.optJSONObject("classCounts"), "weeks", weeks,
                    "homeroom", full.optJSONObject("homerooms") == null || cls == null ? "" : full.optJSONObject("homerooms").optString(cls)));
        } else if (d.optJSONObject("neisTimetable") != null) {
            JSONObject n = d.optJSONObject("neisTimetable");
            Util.put(out, "timetable", Util.jo("source", "neis", "fetchedAt", n.optLong("fetchedAt"), "times", new JSONArray(),
                    "weeks", new JSONArray().put(Util.jo("start", n.optJSONArray("dates") == null ? "" : n.optJSONArray("dates").optString(0), "dates", n.optJSONArray("dates"), "days", n.optJSONArray("days")))));
        }
        for (String k : new String[]{"meals", "schedule", "weather", "feeds", "homepage", "errors"}) if (d.has(k)) Util.put(out, k, d.opt(k));
        return out;
    }

    /** One post of the school homepage (fetched by the hub so classroom boards only need the LAN). */
    private Response homepageDetail(Request r) throws Exception {
        JSONObject hp = fetcher.get().optJSONObject("homepage");
        String base = hp == null ? "" : hp.optString("base");
        if (base.isEmpty()) throw new ApiException(404, "학교 홈페이지가 연결되지 않았습니다");
        String menuId = r.param("menuId"), bbsId = r.param("bbsId"), nttId = r.param("nttId");
        if (menuId == null || !menuId.matches("\\d+") || bbsId == null || !bbsId.matches("[A-Za-z0-9_]+") || nttId == null || !nttId.matches("\\d+")) {
            throw new ApiException(400, "잘못된 게시물 요청");
        }
        try {
            return Response.json(SchoolHomepage.detail(base, menuId, bbsId, nttId, "1".equals(r.param("sen"))));
        } catch (java.io.IOException e) {
            throw new ApiException(502, "학교 홈페이지에 연결할 수 없습니다: " + e.getMessage());
        }
    }

    private JSONObject comciTeachers() {
        JSONObject c = fetcher.comci();
        JSONArray out = new JSONArray();
        if (c != null) {
            JSONArray t = c.optJSONArray("teachers");
            // subjects taught per teacher, from the first week
            Map<Integer, java.util.TreeSet<String>> subj = new java.util.HashMap<>();
            JSONArray weeks = c.optJSONArray("weeks");
            JSONObject classes = weeks == null || weeks.optJSONObject(0) == null ? null : weeks.optJSONObject(0).optJSONObject("classes");
            JSONArray names = classes == null ? null : classes.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                JSONArray days = classes.optJSONArray(names.optString(i));
                for (int d = 0; days != null && d < days.length(); d++) {
                    JSONArray ps = days.optJSONArray(d);
                    for (int k = 0; ps != null && k < ps.length(); k++) {
                        JSONObject e = ps.optJSONObject(k);
                        int th = e.optInt("th");
                        if (th > 0 && !e.optString("s").isEmpty()) {
                            java.util.TreeSet<String> set = subj.get(th);
                            if (set == null) subj.put(th, set = new java.util.TreeSet<>());
                            set.add(e.optString("s"));
                        }
                    }
                }
            }
            for (int i = 1; t != null && i < t.length(); i++) {
                String n = t.optString(i);
                if (n.isEmpty()) continue;
                java.util.TreeSet<String> set = subj.get(i);
                out.put(Util.jo("th", i, "name", n, "subjects", set == null ? "" : android.text.TextUtils.join(", ", set)));
            }
        }
        return Util.jo("teachers", out);
    }

    private JSONObject comciTeacher(int th) {
        JSONObject c = fetcher.comci();
        JSONArray items = new JSONArray();
        if (c != null && th > 0) {
            JSONArray weeks = c.optJSONArray("weeks");
            for (int w = 0; weeks != null && w < weeks.length(); w++) {
                JSONObject wk = weeks.optJSONObject(w);
                JSONArray dates = wk.optJSONArray("dates");
                JSONObject classes = wk.optJSONObject("classes");
                JSONArray names = classes == null ? null : classes.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    String cls = names.optString(i);
                    JSONArray days = classes.optJSONArray(cls);
                    for (int d = 0; days != null && d < days.length(); d++) {
                        JSONArray ps = days.optJSONArray(d);
                        for (int k = 0; ps != null && k < ps.length(); k++) {
                            JSONObject e = ps.optJSONObject(k);
                            if (e.optInt("th") == th) {
                                items.put(Util.jo("date", dates == null ? "" : dates.optString(d), "p", e.optInt("p"), "cls", cls, "s", e.optString("s"),
                                        "room", e.optString("room"), "ch", e.optBoolean("ch")));
                            }
                        }
                    }
                }
            }
        }
        return Util.jo("times", c == null ? new JSONArray() : c.optJSONArray("times"), "items", items,
                "name", c == null || c.optJSONArray("teachers") == null ? "" : c.optJSONArray("teachers").optString(th));
    }

    private Response icon(String pkg) throws Exception {
        if (pkg == null) return Response.error(400, "pkg 누락");
        byte[] b = iconCache.get(pkg);
        if (b == null) {
            PackageManager pm = ctx.getPackageManager();
            Drawable d;
            try {
                d = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                return Response.error(404, "앱 없음");
            }
            int size = 144;
            Bitmap bm;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                bm = Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), size, size, true);
            } else {
                bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(bm);
                d.setBounds(0, 0, size, size);
                d.draw(cv);
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
            b = bo.toByteArray();
            iconCache.put(pkg, b);
        }
        Response res = Response.bytes(b, "image/png");
        res.headers.put("Cache-Control", "max-age=86400");
        return res;
    }
}
