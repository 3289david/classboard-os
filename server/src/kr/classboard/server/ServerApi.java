package kr.classboard.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import kr.classboard.server.HttpServer.ApiException;
import kr.classboard.server.HttpServer.Request;
import kr.classboard.server.HttpServer.Response;

/**
 * Data server API. Read-only for boards: school data, the shared school config, homepage posts.
 * The only thing boards write is their own registration and a heartbeat.
 */
public class ServerApi {
    private final Store store;
    private final Store devices;
    private final DataFetcher fetcher;
    private final String statusKey;
    private final Object revMonitor = new Object();
    private final Map<String, JSONObject> seen = new ConcurrentHashMap<>();
    private final Map<String, Object[]> detailCache = new ConcurrentHashMap<>(); // nttId -> {time, json}
    private long lastPersist;

    private final DocService docs;
    private final java.io.File appFile;

    public ServerApi(Store store, Store devices, DataFetcher fetcher, String statusKey, java.io.File dataDir) {
        this.docs = new DocService(dataDir);
        this.appFile = new java.io.File(new java.io.File(dataDir, "app"), "ClassBoardOS.apk");
        this.store = store;
        this.devices = devices;
        this.fetcher = fetcher;
        this.statusKey = statusKey;
        // Load recent post details ahead of time so boards open them instantly.
        Thread t = new Thread(this::prefetchLoop, "detail-prefetch");
        t.setDaemon(true);
        t.start();
        store.addListener(() -> {
            synchronized (revMonitor) {
                revMonitor.notifyAll();
            }
        });
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    public Response handle(Request r) throws Exception {
        switch (r.path) {
            case "/":
            case "/status":
                return status(r);
            case "/api/ping":
                return Response.json(Util.jo("server", true, "hub", true, "version", BuildInfo.VERSION, "rev", store.rev(),
                        "school", store.read(root -> Util.obj(Util.obj(root, "config"), "school").optString("name"))));
            case "/api/state":
                return state(r);
            case "/admin/disconnect":
            case "/admin/allow":
                return admin(r);
            case "/admin/app":
                return appUpload(r);
            case "/api/app/info":
                return appInfo();
            case "/api/app/apk":
                return appApk();
            case "/api/shared":
                return shared(r);
            case "/api/device/hello":
                return hello(r);
            case "/api/homepage/detail":
                return homepageDetail(r);
            case "/api/doc/info":
                return docInfo(r);
            case "/api/doc/page":
                return docPage(r);
            case "/api/doc/upload":
                return docUpload(r);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ boards

    private boolean blocked(String id) {
        if (id == null || id.isEmpty()) return false;
        return devices.read(root -> root.optJSONObject("_blocked") != null && root.optJSONObject("_blocked").has(id));
    }

    private static ApiException gone() {
        return new ApiException(410, "이 전자칠판은 학교 서버에서 연결 해제되었습니다");
    }

    private void touch(Request r) throws Exception {
        String id = r.header("x-device-id");
        if (id == null || id.isEmpty() || id.length() > 64) return;
        if (blocked(id)) throw gone();
        String name = r.header("x-device-name");
        String ver = r.header("x-app-version");
        seen.put(id, Util.jo("at", now(), "name", name == null ? "" : URLDecoder.decode(name, "UTF-8"), "version", ver == null ? "" : ver));
        if (now() - lastPersist > 5 * 60_000L) {
            lastPersist = now();
            // Persist last-seen times occasionally (does not change the state revision boards watch).
            devices.write(root -> {
                for (Map.Entry<String, JSONObject> e : seen.entrySet()) {
                    JSONObject d = Util.obj(root, e.getKey());
                    Util.put(d, "lastSeen", e.getValue().optLong("at"));
                    if (!e.getValue().optString("version").isEmpty()) Util.put(d, "version", e.getValue().optString("version"));
                }
            });
        }
    }

    private Response state(Request r) throws Exception {
        touch(r);
        long since = -2;
        try {
            if (r.param("since") != null) since = Long.parseLong(r.param("since"));
        } catch (NumberFormatException ignored) {
        }
        if (since == store.rev() && r.param("wait") != null) {
            long deadline = now() + 20000;
            synchronized (revMonitor) {
                while (store.rev() == since && now() < deadline) revMonitor.wait(Math.max(1, deadline - now()));
            }
        }
        long rev = store.rev();
        if (rev == since) return Response.json(Util.jo("rev", rev, "same", true));
        JSONObject state = store.read(root -> {
            JSONObject cfg = Util.copy(Util.obj(root, "config"));
            cfg.remove("neisKey");
            return Util.jo("config", cfg, "contacts", root.optJSONArray("contacts") == null ? new JSONArray() : root.optJSONArray("contacts"),
                    "setupDone", true, "server", true);
        });
        return Response.json(Util.jo("rev", rev, "state", state));
    }

    private Response hello(Request r) throws Exception {
        JSONObject b = r.json();
        String id = b.optString("deviceId");
        if (id.isEmpty() || id.length() > 64 || !id.matches("[A-Za-z0-9_-]+")) throw new ApiException(400, "deviceId 누락");
        if (blocked(id)) throw gone();
        final String[] key = {null};
        devices.write(root -> {
            JSONObject d = Util.obj(root, id);
            if (d.optString("key").isEmpty()) {
                Util.put(d, "key", Util.randomToken());
                Util.put(d, "registeredAt", now());
            }
            Util.put(d, "name", b.optString("name").trim());
            Util.put(d, "cls", b.optString("cls"));
            Util.put(d, "version", b.optString("version"));
            Util.put(d, "lastSeen", now());
            key[0] = d.optString("key");
        });
        return Response.json(Util.jo("deviceKey", key[0]));
    }

    // ------------------------------------------------------------------ school-wide data

    /**
     * Whole-school data for boards plus what the server already worked out (merged calendar events).
     * ?since=ver answers {same:true} when nothing changed, so a board's minute poll is a few bytes.
     */
    private Response shared(Request r) {
        JSONObject o = fetcher.shared();
        long ver = 0;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            JSONObject v = o.optJSONObject(it.next());
            if (v != null) ver = ver * 31 + v.optLong("fetchedAt");
        }
        String v = Long.toHexString(ver);
        if (v.equals(r.param("since"))) return Response.json(Util.jo("same", true, "ver", v));
        JSONObject sched = o.optJSONObject("schedule");
        Util.put(o, "events", Util.jo("fetchedAt", sched == null ? 0 : sched.optLong("fetchedAt"), "items", Derived.events(sched)));
        Util.put(o, "latest", latestPosts(o.optJSONObject("homepage")));
        Util.put(o, "ver", v);
        return Response.json(o);
    }

    /** Newest posts across all boards (pinned notices older than 30 days left out), newest first. */
    private static JSONArray latestPosts(JSONObject hp) {
        java.util.List<JSONObject> all = new java.util.ArrayList<>();
        String cutoff = java.time.LocalDate.now().minusDays(30).toString();
        JSONArray boards = hp == null ? null : hp.optJSONArray("boards");
        for (int i = 0; boards != null && i < boards.length(); i++) {
            JSONObject b = boards.optJSONObject(i);
            JSONArray items = b.optJSONArray("items");
            for (int k = 0; items != null && k < items.length(); k++) {
                JSONObject it = Util.copy(items.optJSONObject(k));
                if (it.optBoolean("pinned") && it.optString("date").compareTo(cutoff) < 0) continue;
                Util.put(it, "board", b.optString("name"));
                Util.put(it, "menuId", b.optString("menuId"));
                all.add(it);
            }
        }
        all.sort((x, y) -> y.optString("date").compareTo(x.optString("date")));
        return new JSONArray(all.subList(0, Math.min(30, all.size())));
    }

    // ------------------------------------------------------------------ homepage posts

    private static final long DETAIL_TTL = 6 * 3600_000L;

    // ------------------------------------------------------------------ attachments as page images

    private Response docInfo(Request r) throws Exception {
        String id = r.param("id");
        if (id != null) {
            if (!id.matches("[0-9a-f]{24}")) throw new ApiException(400, "잘못된 문서");
            return Response.json(docs.status(id, null, null));
        }
        String url = r.param("url"), name = r.param("name");
        if (url == null || !DocService.allowedUrl(url)) throw new ApiException(400, "학교 홈페이지의 첨부파일만 열 수 있습니다");
        if (name == null || !DocService.supported(name)) throw new ApiException(415, "미리 볼 수 없는 파일 형식입니다");
        return Response.json(docs.status(DocService.id(url), name, () -> Util.httpGetBytes(url, 60000)));
    }

    private Response docPage(Request r) throws Exception {
        String id = r.param("id");
        int p;
        try {
            p = Integer.parseInt(r.param("p"));
        } catch (Exception e) {
            throw new ApiException(400, "쪽 번호");
        }
        if (id == null || !id.matches("[0-9a-f]{24}")) throw new ApiException(400, "잘못된 문서");
        java.io.File f = docs.page(id, p);
        if (f == null) throw new ApiException(404, "없는 쪽입니다");
        Response res = Response.file(f, "image/jpeg");
        res.headers.put("Cache-Control", "max-age=86400");
        return res;
    }

    /** A board sends a file from its USB drive or storage to be shown as pages (HWP needs the server). */
    private Response docUpload(Request r) throws Exception {
        String name = URLDecoder.decode(r.header("x-filename") == null ? "" : r.header("x-filename"), "UTF-8");
        if (!DocService.supported(name)) throw new ApiException(415, "미리 볼 수 없는 파일 형식입니다");
        byte[] b = r.body();
        if (b.length == 0) throw new ApiException(400, "빈 파일입니다");
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        String id = Util.hex(md.digest(b)).substring(0, 24);
        return Response.json(docs.status(id, name, () -> b));
    }

    private void prefetchLoop() {
        try {
            Thread.sleep(20_000);
        } catch (InterruptedException e) {
            return;
        }
        while (true) {
            try {
                JSONObject hp = fetcher.get().optJSONObject("homepage");
                String base = hp == null ? "" : hp.optString("base");
                JSONArray boards = hp == null ? null : hp.optJSONArray("boards");
                for (int i = 0; !base.isEmpty() && boards != null && i < boards.length(); i++) {
                    JSONObject b = boards.optJSONObject(i);
                    JSONArray items = b.optJSONArray("items");
                    for (int k = 0; items != null && k < Math.min(10, items.length()); k++) {
                        JSONObject p = items.optJSONObject(k);
                        Object[] c = detailCache.get(p.optString("nttId"));
                        if (c != null && now() - (Long) c[0] < DETAIL_TTL) continue;
                        try {
                            JSONObject d = SchoolHomepage.detail(base, b.optString("menuId"), p.optString("bbsId"), p.optString("nttId"), p.optBoolean("sen"));
                            detailCache.put(p.optString("nttId"), new Object[]{now(), d});
                        } catch (Exception e) {
                            L.w("Api", "prefetch " + p.optString("nttId"), e);
                        }
                        // convert the newest posts' attachments too, so they open as pages right away
                        JSONObject dd = (JSONObject) detailCache.get(p.optString("nttId"))[1];
                        JSONArray fs = k < 5 ? dd.optJSONArray("files") : null;
                        for (int x = 0; fs != null && x < fs.length(); x++) {
                            JSONObject f = fs.optJSONObject(x);
                            String fu = f.optString("url");
                            if (DocService.supported(f.optString("name")) && f.optLong("size") < 20L * 1024 * 1024 && DocService.allowedUrl(fu)) {
                                docs.status(DocService.id(fu), f.optString("name"), () -> Util.httpGetBytes(fu, 60000));
                            }
                        }
                        Thread.sleep(400); // be gentle with the school homepage
                    }
                }
                Thread.sleep(10 * 60_000L);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                L.w("Api", "prefetch loop", e);
            }
        }
    }

    private Response homepageDetail(Request r) throws Exception {
        JSONObject hp = fetcher.get().optJSONObject("homepage");
        String base = hp == null ? "" : hp.optString("base");
        if (base.isEmpty()) throw new ApiException(404, "학교 홈페이지가 연결되지 않았습니다");
        String menuId = r.param("menuId"), bbsId = r.param("bbsId"), nttId = r.param("nttId");
        if (menuId == null || !menuId.matches("\\d+") || bbsId == null || !bbsId.matches("[A-Za-z0-9_]+") || nttId == null || !nttId.matches("\\d+")) {
            throw new ApiException(400, "잘못된 게시물 요청");
        }
        boolean sen = "1".equals(r.param("sen"));
        Object[] cached = detailCache.get(nttId);
        if (cached != null && now() - (Long) cached[0] < DETAIL_TTL) return Response.json(cached[1]);
        try {
            JSONObject d = SchoolHomepage.detail(base, menuId, bbsId, nttId, sen);
            if (detailCache.size() > 800) detailCache.clear();
            detailCache.put(nttId, new Object[]{now(), d});
            return Response.json(d);
        } catch (java.io.IOException e) {
            // keep serving an older copy rather than failing
            if (cached != null) return Response.json(cached[1]);
            throw new ApiException(502, "학교 홈페이지에 연결할 수 없습니다: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ admin (status page buttons)

    private static java.util.Map<String, String> form(Request r) throws Exception {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        String body = "POST".equals(r.method) ? r.bodyString() : "";
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
        }
        return m;
    }

    /** 연결 해제 / 다시 허용 from the status page. Needs STATUS_KEY, so only the server's owner can do it. */
    private Response admin(Request r) throws Exception {
        if (!"POST".equals(r.method)) throw new ApiException(405, "POST만 됩니다");
        java.util.Map<String, String> f = form(r);
        if (statusKey.isEmpty()) throw new ApiException(403, "STATUS_KEY를 설정해야 전자칠판 연결을 해제할 수 있습니다");
        if (!statusKey.equals(f.get("key"))) throw new ApiException(401, "상태 페이지 키가 맞지 않습니다");
        String id = f.get("id");
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new ApiException(400, "전자칠판 ID");
        boolean disconnect = r.path.endsWith("disconnect");
        devices.write(root -> {
            JSONObject blockedList = Util.obj(root, "_blocked");
            if (disconnect) {
                JSONObject d = root.optJSONObject(id);
                Util.put(blockedList, id, Util.jo("name", d == null ? "" : d.optString("name"), "cls", d == null ? "" : d.optString("cls"), "at", now()));
                root.remove(id);
            } else {
                blockedList.remove(id);
            }
        });
        if (disconnect) seen.remove(id);
        Response res = Response.bytes(new byte[0], "text/plain");
        res.status = 303;
        res.headers.put("Location", "/?key=" + java.net.URLEncoder.encode(statusKey, "UTF-8"));
        return res;
    }

    // ------------------------------------------------------------------ board app updates

    private JSONObject appMeta() throws Exception {
        if (!appFile.isFile()) return null;
        java.io.File meta = new java.io.File(appFile.getPath() + ".json");
        String m = Util.readFile(meta);
        if (m != null) {
            JSONObject o = new JSONObject(m);
            if (o.optLong("size") == appFile.length() && o.optLong("mtime") == appFile.lastModified()) return o;
        }
        // a file copied into data/app by hand: work out its hash once
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = new java.io.FileInputStream(appFile)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        JSONObject o = Util.jo("sha256", Util.hex(md.digest()), "size", appFile.length(), "mtime", appFile.lastModified(), "uploadedAt", appFile.lastModified());
        Util.writeFileAtomic(meta, o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return o;
    }

    private Response appInfo() throws Exception {
        JSONObject o = appMeta();
        if (o == null) throw new ApiException(404, "올려 둔 전자칠판 앱이 없습니다");
        return Response.json(Util.jo("sha256", o.optString("sha256"), "size", o.optLong("size"), "uploadedAt", o.optLong("uploadedAt")));
    }

    private Response appApk() throws Exception {
        if (!appFile.isFile()) throw new ApiException(404, "올려 둔 전자칠판 앱이 없습니다");
        Response res = Response.file(appFile, "application/vnd.android.package-archive");
        res.headers.put("Content-Disposition", "attachment; filename=ClassBoardOS.apk");
        return res;
    }

    /** New board app from the status page (STATUS_KEY). Boards pick it up and install it themselves. */
    private Response appUpload(Request r) throws Exception {
        if (!"POST".equals(r.method)) throw new ApiException(405, "POST만 됩니다");
        if (statusKey.isEmpty()) throw new ApiException(403, "STATUS_KEY를 설정해야 앱을 올릴 수 있습니다");
        if (!statusKey.equals(r.param("key"))) throw new ApiException(401, "상태 페이지 키가 맞지 않습니다");
        byte[] b = r.body();
        if (!saveApp(b, "", 0)) throw new ApiException(400, "APK 파일이 아닙니다");
        return Response.json(Util.jo("ok", true, "size", b.length));
    }

    /** Keep a new board app (from the status page or the latest GitHub release). */
    boolean saveApp(byte[] b, String tag, long assetId) throws Exception {
        if (b.length < 1024 || b[0] != 'P' || b[1] != 'K') return false;
        synchronized (appFile) {
            appFile.getParentFile().mkdirs();
            Util.writeFileAtomic(appFile, b);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            JSONObject o = Util.jo("sha256", Util.hex(md.digest(b)), "size", (long) b.length, "mtime", appFile.lastModified(), "uploadedAt", now(),
                    "tag", tag, "assetId", assetId);
            Util.writeFileAtomic(new java.io.File(appFile.getPath() + ".json"), o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return true;
    }

    long appAssetId() {
        try {
            JSONObject m = appMeta();
            return m == null ? -1 : m.optLong("assetId", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    private String appSection(boolean canAdmin) {
        JSONObject m;
        try {
            m = appMeta();
        } catch (Exception e) {
            m = null;
        }
        String info = m == null ? "올려 둔 앱이 없습니다." : "전자칠판에 줄 앱: " + (m.optString("tag").isEmpty() ? "직접 올림" : "GitHub " + m.optString("tag")) + " · "
                + (m.optLong("size") / 1024) + "KB · " + ago(m.optLong("uploadedAt"));
        info += "\nGitHub 자동 확인: " + GitHubApp.state + (GitHubApp.lastCheck > 0 ? " · " + ago(GitHubApp.lastCheck) + " 확인" : "");
        String form = !canAdmin ? "<p class=n>앱을 올리려면 서버에 STATUS_KEY를 설정하세요.</p>"
                : "<p style='margin:10px 0 0'><input type=file id=apk accept='.apk' onclick='busy=true' onchange='up(this)'> <span id=upmsg class=n></span></p>"
                + "<script>function up(i){var f=i.files[0];if(!f)return;busy=true;var m=document.getElementById('upmsg');m.textContent='올리는 중...';"
                + "fetch('/admin/app?key='+encodeURIComponent('" + esc(statusKey).replace("'", "") + "'),{method:'POST',body:f}).then(function(r){return r.json()})"
                + ".then(function(d){if(d.ok){m.textContent='올렸습니다. 전자칠판이 30분 안에 받아서 설치합니다.';setTimeout(function(){location.reload()},2500)}else{m.textContent=d.error;busy=false}})"
                + ".catch(function(e){m.textContent=String(e);busy=false})}</script>";
        return "<section><h2>전자칠판 앱 업데이트</h2><p style='margin:0;white-space:pre-line'>" + esc(info) + "</p>" + form
                + "<p class=n>서버가 30분마다 GitHub의 최신 릴리스를 확인해 새 ClassBoardOS.apk를 받아 둡니다. 전자칠판은 더 새 버전일 때만, 수업 시간이 아닐 때 설치합니다. 직접 올려도 됩니다.</p></section>";
    }

    // ------------------------------------------------------------------ status page

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String ago(long t) {
        if (t <= 0) return "-";
        long s = (now() - t) / 1000;
        if (s < 60) return "방금";
        if (s < 3600) return (s / 60) + "분 전";
        if (s < 86400) return (s / 3600) + "시간 전";
        return (s / 86400) + "일 전";
    }

    private Response status(Request r) {
        if (!statusKey.isEmpty() && !statusKey.equals(r.param("key"))) {
            Response res = Response.bytes(ServerMain.utf8("<!doctype html><meta charset=utf-8><p>상태 페이지 키가 필요합니다.</p>"), "text/html; charset=utf-8");
            res.status = 401;
            return res;
        }
        JSONObject d = fetcher.get();
        JSONObject cfg = store.read(root -> Util.copy(Util.obj(root, "config")));
        JSONObject errors = d.optJSONObject("errors");
        StringBuilder rows = new StringBuilder();
        String[][] items = {{"comciFull", "시간표 (컴시간)"}, {"meals", "급식 (NEIS)"}, {"schedule", "학사일정 (NEIS)"}, {"weather", "날씨"}, {"homepage", "학교 홈페이지 게시판"}};
        for (String[] it : items) {
            JSONObject x = d.optJSONObject(it[0]);
            rows.append("<tr><td>").append(it[1]).append("</td><td>").append(x == null ? "아직 없음" : ago(x.optLong("fetchedAt"))).append("</td></tr>");
        }
        StringBuilder errs = new StringBuilder();
        Iterator<String> ek = errors == null ? null : errors.keys();
        while (ek != null && ek.hasNext()) {
            String k = ek.next();
            errs.append("<li><b>").append(esc(k)).append("</b>: ").append(esc(errors.optJSONObject(k).optString("message"))).append("</li>");
        }
        StringBuilder devs = new StringBuilder();
        boolean canAdmin = !statusKey.isEmpty();
        String keyField = "<input type=hidden name=key value='" + esc(statusKey) + "'>";
        JSONObject all = devices.snapshot();
        JSONArray names = all.names();
        int online = 0;
        for (int i = 0; names != null && i < names.length(); i++) {
            String id = names.optString(i);
            if (id.startsWith("_")) continue;
            JSONObject dv = all.optJSONObject(id);
            JSONObject s = seen.get(id);
            long last = Math.max(dv.optLong("lastSeen"), s == null ? 0 : s.optLong("at"));
            boolean on = now() - last < 60_000L;
            if (on) online++;
            String cls = dv.optString("cls");
            devs.append("<tr><td>").append(esc(dv.optString("name"))).append("</td><td>").append(cls.isEmpty() ? "-" : esc(cls.replace("-", "학년 ") + "반"))
                    .append("</td><td>").append(esc(s != null && !s.optString("version").isEmpty() ? s.optString("version") : dv.optString("version"))).append("</td><td class=").append(on ? "on" : "off").append(">")
                    .append(on ? "연결됨" : ago(last)).append("</td><td>");
            if (canAdmin) {
                devs.append("<form method=post action='/admin/disconnect' onsubmit=\"return confirm('")
                        .append(esc(dv.optString("name")).replace("'", "")).append(" 전자칠판의 연결을 해제할까요? 이 칠판은 학교 서버에서 데이터를 받지 못합니다.')\">")
                        .append(keyField).append("<input type=hidden name=id value='").append(esc(id)).append("'><button class=danger>연결 해제</button></form>");
            }
            devs.append("</td></tr>");
        }
        StringBuilder blockedRows = new StringBuilder();
        JSONObject bl = all.optJSONObject("_blocked");
        JSONArray bn = bl == null ? null : bl.names();
        for (int i = 0; bn != null && i < bn.length(); i++) {
            String id = bn.optString(i);
            JSONObject b = bl.optJSONObject(id);
            String cls = b.optString("cls");
            blockedRows.append("<tr><td>").append(esc(b.optString("name"))).append("</td><td>").append(cls.isEmpty() ? "-" : esc(cls.replace("-", "학년 ") + "반"))
                    .append("</td><td>").append(ago(b.optLong("at"))).append(" 해제</td><td>")
                    .append(canAdmin ? "<form method=post action='/admin/allow'>" + keyField + "<input type=hidden name=id value='" + esc(id) + "'><button>다시 허용</button></form>" : "")
                    .append("</td></tr>");
        }
        String school = Util.obj(cfg, "school").optString("name");
        String html = "<!doctype html><html lang=ko><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + esc(school) + " 전자칠판 서버</title><style>"
                + "body{font-family:'Pretendard','Noto Sans KR','Malgun Gothic',system-ui,sans-serif;background:#0e1116;color:#eef2f7;margin:0;padding:32px}"
                + "h1{margin:0 0 4px;font-size:26px}p.s{color:#a9b4c3;margin:0 0 24px}section{background:#1a2029;border-radius:14px;padding:18px 20px;margin-bottom:16px;max-width:760px}"
                + "h2{font-size:16px;color:#a9b4c3;margin:0 0 10px}table{width:100%;border-collapse:collapse}td,th{padding:8px 6px;border-bottom:1px solid #2a3340;text-align:left;font-size:14px}"
                + ".on{color:#3ecf8e}.off{color:#748196}li{margin:4px 0;color:#ff8a80}"
                + "form{margin:0}button{font:inherit;font-size:13px;padding:6px 12px;border-radius:8px;border:0;background:#2a3340;color:#eef2f7;cursor:pointer}"
                + "button.danger{background:#5a2328;color:#ffb4ae}button:hover{filter:brightness(1.2)}p.n{color:#748196;font-size:13px;margin:10px 0 0}</style></head><body>"
                + "<h1>" + esc(school) + " 전자칠판 데이터 서버</h1><p class=s>버전 " + BuildInfo.VERSION + " · 전자칠판 " + online + "대 연결됨 · 30초마다 새로고침</p>"
                + "<section><h2>데이터</h2><table>" + rows + "<tr><td>첨부파일 변환 (HWP · 오피스)</td><td>" + esc(OfficeSetup.state) + "</td></tr></table>" + (errs.length() > 0 ? "<h2 style='margin-top:14px'>최근 오류</h2><ul>" + errs + "</ul>" : "") + "</section>"
                + "<section><h2>전자칠판</h2><table><tr><th>이름</th><th>학급</th><th>버전</th><th>상태</th><th></th></tr>" + devs + "</table>"
                + (canAdmin ? "" : "<p class=n>연결 해제 버튼을 쓰려면 서버에 STATUS_KEY를 설정하세요.</p>") + "</section>"
                + (blockedRows.length() > 0 ? "<section><h2>연결 해제된 전자칠판</h2><table>" + blockedRows + "</table></section>" : "")
                + appSection(canAdmin)
                + "<script>var busy=false;setInterval(function(){if(!busy)location.reload()},30000);</script>"
                + "</body></html>";
        return Response.bytes(ServerMain.utf8(html), "text/html; charset=utf-8");
    }
}
