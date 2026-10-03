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
    private final Map<String, byte[]> iconCache = new ConcurrentHashMap<>();
    private final CoreService core;

    public Router(CoreService core, DeviceConfig cfg, SyncClient sync, DataFetcher fetcher) {
        this.core = core;
        this.ctx = core.getApplicationContext();
        this.cfg = cfg;
        this.sync = sync;
        this.fetcher = fetcher;
    }

    @Override
    public Response handle(Request r) throws Exception {
        String p = r.path;
        if (p.startsWith("/api/local/")) return local(r, p.substring("/api/local/".length()));
        if (p.startsWith("/api/")) {
            if ("/api/state".equals(p)) return sync.serveState(r);
            if ("/api/homepage/detail".equals(p)) return postDetail(r);
            return sync.proxy(r);
        }
        return staticFile(r);
    }

    // ------------------------------------------------------------------ homepage posts

    /**
     * Post detail: the school server (which keeps recent posts ready), then the school homepage directly,
     * then the copy this board saved last time. Opened posts are kept on disk so they open instantly.
     */
    private Response postDetail(Request r) throws Exception {
        String menuId = r.param("menuId"), bbsId = r.param("bbsId"), nttId = r.param("nttId");
        if (menuId == null || !menuId.matches("\\d+") || bbsId == null || !bbsId.matches("[A-Za-z0-9_]+") || nttId == null || !nttId.matches("\\d+")) {
            throw new ApiException(400, "잘못된 게시물 요청");
        }
        boolean sen = "1".equals(r.param("sen"));
        long tIn = System.currentTimeMillis();
        java.io.File dir = new java.io.File(ctx.getFilesDir(), "posts");
        java.io.File f = new java.io.File(dir, nttId + ".json");
        String saved = Util.readFile(f);
        if (saved != null && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L) return Response.json(saved);
        // Ask the school server; if it has not answered in 2.5 s (a post it has not loaded yet can take
        // it 15 s), fetch the post directly as well and use whichever arrives first.
        final String q = "menuId=" + menuId + "&bbsId=" + bbsId + "&nttId=" + nttId + "&sen=" + (sen ? 1 : 0);
        final String base0;
        {
            JSONObject hp = fetcher.get().optJSONObject("homepage");
            String b0 = hp == null ? "" : hp.optString("base");
            base0 = b0.isEmpty() ? SchoolHomepage.base(SchoolPreset.config().optJSONObject("school").optString("homepage")) : b0;
        }
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.ExecutorCompletionService<String[]> cs = new java.util.concurrent.ExecutorCompletionService<>(ex);
        long t0 = System.currentTimeMillis();
        cs.submit(() -> {
            String b1 = Util.httpGet(sync.base() + "/api/homepage/detail?" + q, java.nio.charset.StandardCharsets.UTF_8, 20000);
            new JSONObject(b1); // must be a post, not an error page
            return new String[]{b1, "server"};
        });
        int pending = 1;
        boolean directStarted = false;
        String body = null, src = "";
        Exception last = null;
        try {
            while (pending > 0 && body == null) {
                long left = 22000 - (System.currentTimeMillis() - t0);
                if (left <= 0) break;
                java.util.concurrent.Future<String[]> fut = cs.poll(directStarted ? left : Math.min(left, 2500), java.util.concurrent.TimeUnit.MILLISECONDS);
                if (fut == null) {
                    if (!directStarted) {
                        directStarted = true;
                        pending++;
                        cs.submit(() -> new String[]{SchoolHomepage.detail(base0, menuId, bbsId, nttId, sen).toString(), "direct"});
                    }
                    continue;
                }
                pending--;
                try {
                    String[] got = fut.get();
                    body = got[0];
                    src = got[1];
                } catch (java.util.concurrent.ExecutionException e) {
                    last = e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                    if (!directStarted) {
                        // server failed outright: go direct now
                        directStarted = true;
                        pending++;
                        cs.submit(() -> new String[]{SchoolHomepage.detail(base0, menuId, bbsId, nttId, sen).toString(), "direct"});
                    }
                }
            }
        } finally {
            ex.shutdownNow();
        }
        String trace = (System.currentTimeMillis() - t0) + "ms (prep " + (t0 - tIn) + "ms)" + (last == null ? "" : " " + last);
        if (body == null) {
            if (saved != null) return Response.json(saved);
            throw new ApiException(502, "게시물을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요." + (last == null ? "" : " (" + last.getMessage() + ")"));
        }
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            Util.writeFileAtomic(f, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.io.File[] all = dir.listFiles();
            if (all != null && all.length > 200) {
                java.util.Arrays.sort(all, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
                for (int i = 0; i < all.length - 150; i++) all[i].delete();
            }
        } catch (Exception ignored) {
        }
        Response res = Response.json(body);
        res.headers.put("X-Source", src + "; " + trace.replaceAll("[\r\n]", " "));
        return res;
    }

    // ------------------------------------------------------------------ saved boards (칠판 판서)

    /** Board drawings saved per lesson on this device: list (GET), one (GET ?key=), save (POST ?key=). */
    private Response boards(Request r) throws Exception {
        if (!r.isLocal()) throw new ApiException(403, "기기에서만 사용할 수 있습니다");
        java.io.File dir = new java.io.File(ctx.getFilesDir(), "boards");
        if (!dir.isDirectory()) dir.mkdirs();
        java.io.File index = new java.io.File(dir, "index.json");
        String key = r.param("key");
        if (key != null && !key.matches("[0-9A-Za-z_-]{1,40}")) throw new ApiException(400, "잘못된 판서 이름");
        synchronized (this) {
            JSONObject idx;
            try {
                String s = Util.readFile(index);
                idx = s == null ? new JSONObject() : new JSONObject(s);
            } catch (Exception e) {
                idx = new JSONObject();
            }
            if ("POST".equals(r.method)) {
                if (key == null) throw new ApiException(400, "판서 이름 누락");
                byte[] body = r.body();
                if (body.length > 8 * 1024 * 1024) throw new ApiException(413, "판서가 너무 큽니다");
                JSONObject b = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
                Util.writeFileAtomic(new java.io.File(dir, key + ".json"), body);
                Util.put(idx, key, Util.jo("date", b.optString("date"), "label", b.optString("label"), "pages", b.optInt("pageCount"),
                        "strokes", b.optInt("strokeCount"), "savedAt", System.currentTimeMillis()));
                // keep the newest 60
                JSONArray names = idx.names();
                if (names != null && names.length() > 60) {
                    java.util.List<String> ks = new java.util.ArrayList<>();
                    for (int i = 0; i < names.length(); i++) ks.add(names.optString(i));
                    final JSONObject fi = idx;
                    ks.sort((x, y) -> Long.compare(fi.optJSONObject(x).optLong("savedAt"), fi.optJSONObject(y).optLong("savedAt")));
                    for (int i = 0; i < ks.size() - 60; i++) {
                        idx.remove(ks.get(i));
                        new java.io.File(dir, ks.get(i) + ".json").delete();
                    }
                }
                Util.writeFileAtomic(index, idx.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return Response.ok();
            }
            if (key != null) {
                String s = Util.readFile(new java.io.File(dir, key + ".json"));
                if (s == null) throw new ApiException(404, "저장된 판서가 없습니다");
                return Response.json(s);
            }
            JSONArray out = new JSONArray();
            JSONArray names = idx.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                JSONObject m = Util.copy(idx.optJSONObject(names.optString(i)));
                Util.put(m, "key", names.optString(i));
                out.put(m);
            }
            return Response.json(Util.jo("items", out));
        }
    }

    // ------------------------------------------------------------------ documents

    /** PDF shown on the board itself when the school server cannot render it (Android's built-in renderer). */
    private Response pdfInfo(Request r) throws Exception {
        if (!r.isLocal()) throw new ApiException(403, "기기에서만 사용할 수 있습니다");
        java.io.File pdf;
        String key;
        if (r.param("path") != null) {
            pdf = LocalFiles.checked(r.param("path"));
            key = Integer.toHexString((pdf.getPath() + pdf.lastModified()).hashCode());
        } else {
            String url = r.param("url");
            if (url == null || !url.startsWith("http")) throw new ApiException(400, "주소가 없습니다");
            key = Integer.toHexString(url.hashCode());
            pdf = new java.io.File(new java.io.File(ctx.getCacheDir(), "docs"), key + ".pdf");
            if (!pdf.isFile()) {
                pdf.getParentFile().mkdirs();
                Util.writeFileAtomic(pdf, Util.httpGetBytes(url, 60000));
            }
        }
        pdfFiles.put(key, pdf);
        try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
             android.graphics.pdf.PdfRenderer pr = new android.graphics.pdf.PdfRenderer(fd)) {
            return Response.json(Util.jo("status", "ready", "key", key, "pages", Math.min(80, pr.getPageCount()), "total", pr.getPageCount()));
        } catch (Exception e) {
            throw new ApiException(415, "PDF를 열 수 없습니다: " + e.getMessage());
        }
    }

    private final Map<String, java.io.File> pdfFiles = new ConcurrentHashMap<>();

    private synchronized Response pdfPage(Request r) throws Exception {
        java.io.File pdf = pdfFiles.get(r.param("key") == null ? "" : r.param("key"));
        if (pdf == null) throw new ApiException(404, "문서를 다시 열어 주세요");
        int p = Integer.parseInt(r.param("p"));
        java.io.File out = new java.io.File(new java.io.File(ctx.getCacheDir(), "docs"), r.param("key") + "-" + p + ".jpg");
        if (!out.isFile()) {
            out.getParentFile().mkdirs();
            try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                 android.graphics.pdf.PdfRenderer pr = new android.graphics.pdf.PdfRenderer(fd);
                 android.graphics.pdf.PdfRenderer.Page pg = pr.openPage(p - 1)) {
                // ~1400 px on the long side: sharp on a board, small enough for 4 GB devices
                float scale = 1400f / Math.max(pg.getWidth(), pg.getHeight());
                Bitmap bm = Bitmap.createBitmap(Math.round(pg.getWidth() * scale), Math.round(pg.getHeight() * scale), Bitmap.Config.ARGB_8888);
                bm.eraseColor(android.graphics.Color.WHITE);
                pg.render(bm, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(out)) {
                    bm.compress(Bitmap.CompressFormat.JPEG, 82, o);
                }
                bm.recycle();
            }
        }
        Response res = Response.bytes(Util.readAll(new java.io.FileInputStream(out)), "image/jpeg");
        res.headers.put("Cache-Control", "max-age=3600");
        return res;
    }

    /** A file from this board's storage or USB, sent to the school server to be turned into pages (HWP, Office). */
    private Response docUpload(Request r) throws Exception {
        if (!r.isLocal()) throw new ApiException(403, "기기에서만 사용할 수 있습니다");
        java.io.File f = LocalFiles.checked(r.param("path"));
        if (!f.isFile() || f.length() > 30L * 1024 * 1024) throw new ApiException(400, "30MB 이하의 파일만 볼 수 있습니다");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(sync.base() + "/api/doc/upload").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(60000);
        c.setFixedLengthStreamingMode(f.length());
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setRequestProperty("X-Filename", java.net.URLEncoder.encode(f.getName(), "UTF-8"));
        try (InputStream in = new java.io.FileInputStream(f); java.io.OutputStream o = c.getOutputStream()) {
            Util.copy(in, o, -1);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "{}" : new String(Util.readAll(in), java.nio.charset.StandardCharsets.UTF_8);
        if (code >= 400) throw new ApiException(code, new JSONObject(body).optString("error", "학교 서버가 파일을 받지 못했습니다"));
        return Response.json(body);
    }

    // ------------------------------------------------------------------ static

    private Response staticFile(Request r) throws Exception {
        String p = r.path;
        if (p.equals("/") || p.isEmpty()) {
            Response res = new Response();
            res.status = 302;
            res.body = new byte[0];
            res.headers.put("Location", "/board/");
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

    /** 관리 settings need the installer PIN (sent as X-Pin by the board UI). */
    private void requirePin(Request r) throws Exception {
        if (!r.isLocal()) throw new ApiException(403, "기기에서만 사용할 수 있습니다");
        if (!cfg.checkPin(r.header("x-pin"))) throw new ApiException(401, "PIN이 올바르지 않습니다");
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
                requirePin(r);
                JSONObject b = r.json();
                if (b.has("pin") && b.optString("pin").length() >= 4) cfg.setPin(b.optString("pin"));
                core.onDeviceConfigChanged(cfg.update(b));
                return Response.ok();
            }
            case "setup": {
                // First run on this board: class, name and the installer PIN. Only once, only locally.
                if (!r.isLocal()) throw new ApiException(403, "기기에서만 사용할 수 있습니다");
                if (cfg.isSetUp()) throw new ApiException(409, "이미 설정된 기기입니다");
                JSONObject b = r.json();
                if (b.optString("pin").length() < 4) throw new ApiException(400, "PIN은 4자리 이상이어야 합니다");
                cfg.setPin(b.optString("pin"));
                cfg.update(Util.jo("cls", b.optString("cls"), "name", b.optString("name"), "setUp", true));
                core.onDeviceConfigChanged(true);
                return Response.ok();
            }
            case "pdf/info":
                return pdfInfo(r);
            case "pdf/page":
                return pdfPage(r);
            case "docupload":
                return docUpload(r);
            case "thumb": {
                if (!r.isLocal()) throw new ApiException(403, "기기에서만 사용할 수 있습니다");
                Response res = Response.bytes(LocalFiles.thumb(r.param("path")), "image/jpeg");
                res.headers.put("Cache-Control", "max-age=3600");
                return res;
            }
            case "boards":
                return boards(r);
            case "update": {
                UpdateManager u = core.updates();
                if (u == null) throw new ApiException(503, "서비스 준비 중");
                if ("POST".equals(r.method)) {
                    if (!r.isLocal()) throw new ApiException(403, "기기에서만 사용할 수 있습니다");
                    new Thread(() -> u.check(true, core.inClass())).start();
                }
                return Response.json(u.status());
            }
            case "home": {
                if ("GET".equals(r.method)) return Response.json(cfg.home());
                if (!r.isLocal()) throw new ApiException(403, "기기에서만 사용할 수 있습니다");
                cfg.setHome(r.json());
                return Response.json(cfg.home());
            }
            case "pin":
                requirePin(r);
                return Response.ok();
            case "settings": {
                if ("GET".equals(r.method)) return Response.json(cfg.settings());
                requirePin(r);
                cfg.update(Util.jo("settings", r.json()));
                core.onDeviceConfigChanged(false);
                return Response.ok();
            }
            case "probe": {
                if (!r.isLocal()) throw new ApiException(403, "기기에서만 사용할 수 있습니다");
                String url = r.param("url");
                if (url == null || url.trim().isEmpty()) url = cfg.hubUrl();
                if (!url.startsWith("http")) url = "https://" + url;
                try {
                    return Response.json(Util.jo("ok", true, "url", url, "info", sync.getJson(url + "/api/ping")));
                } catch (Exception e) {
                    return Response.json(Util.jo("ok", false, "url", url, "error", String.valueOf(e.getMessage())));
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
        for (String k : new String[]{"meals", "schedule", "weather", "homepage", "events", "latest", "errors"}) if (d.has(k)) Util.put(out, k, d.opt(k));
        return out;
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
            if (d instanceof android.graphics.drawable.AdaptiveIconDrawable) {
                // Same rounded-square shape as the school apps, whatever mask the system uses.
                android.graphics.drawable.AdaptiveIconDrawable a = (android.graphics.drawable.AdaptiveIconDrawable) d;
                bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(bm);
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(0, 0, size, size, size * 0.23f, size * 0.23f, android.graphics.Path.Direction.CW);
                cv.clipPath(clip);
                int o = size / 4; // adaptive layers are 1.5x the visible area
                for (Drawable layer : new Drawable[]{a.getBackground(), a.getForeground()}) {
                    if (layer == null) continue;
                    layer.setBounds(-o, -o, size + o, size + o);
                    layer.draw(cv);
                }
            } else if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
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
