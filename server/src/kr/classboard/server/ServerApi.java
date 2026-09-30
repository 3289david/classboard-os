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

    public ServerApi(Store store, Store devices, DataFetcher fetcher, String statusKey) {
        this.store = store;
        this.devices = devices;
        this.fetcher = fetcher;
        this.statusKey = statusKey;
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
            case "/api/shared":
                return Response.json(fetcher.shared());
            case "/api/device/hello":
                return hello(r);
            case "/api/homepage/detail":
                return homepageDetail(r);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ boards

    private void touch(Request r) throws Exception {
        String id = r.header("x-device-id");
        if (id == null || id.isEmpty() || id.length() > 64) return;
        String name = r.header("x-device-name");
        seen.put(id, Util.jo("at", now(), "name", name == null ? "" : URLDecoder.decode(name, "UTF-8")));
        if (now() - lastPersist > 5 * 60_000L) {
            lastPersist = now();
            // Persist last-seen times occasionally (does not change the state revision boards watch).
            devices.write(root -> {
                for (Map.Entry<String, JSONObject> e : seen.entrySet()) {
                    JSONObject d = Util.obj(root, e.getKey());
                    Util.put(d, "lastSeen", e.getValue().optLong("at"));
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

    // ------------------------------------------------------------------ homepage posts

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
        if (cached != null && now() - (Long) cached[0] < 30 * 60_000L) return Response.json(cached[1]);
        try {
            JSONObject d = SchoolHomepage.detail(base, menuId, bbsId, nttId, sen);
            if (detailCache.size() > 500) detailCache.clear();
            detailCache.put(nttId, new Object[]{now(), d});
            return Response.json(d);
        } catch (java.io.IOException e) {
            throw new ApiException(502, "학교 홈페이지에 연결할 수 없습니다: " + e.getMessage());
        }
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
                    .append("</td><td>").append(esc(dv.optString("version"))).append("</td><td class=").append(on ? "on" : "off").append(">")
                    .append(on ? "연결됨" : ago(last)).append("</td></tr>");
        }
        String school = Util.obj(cfg, "school").optString("name");
        String html = "<!doctype html><html lang=ko><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + esc(school) + " 전자칠판 서버</title><meta http-equiv=refresh content=30><style>"
                + "body{font-family:'Pretendard','Noto Sans KR','Malgun Gothic',system-ui,sans-serif;background:#0e1116;color:#eef2f7;margin:0;padding:32px}"
                + "h1{margin:0 0 4px;font-size:26px}p.s{color:#a9b4c3;margin:0 0 24px}section{background:#1a2029;border-radius:14px;padding:18px 20px;margin-bottom:16px;max-width:760px}"
                + "h2{font-size:16px;color:#a9b4c3;margin:0 0 10px}table{width:100%;border-collapse:collapse}td,th{padding:8px 6px;border-bottom:1px solid #2a3340;text-align:left;font-size:14px}"
                + ".on{color:#3ecf8e}.off{color:#748196}li{margin:4px 0;color:#ff8a80}</style></head><body>"
                + "<h1>" + esc(school) + " 전자칠판 데이터 서버</h1><p class=s>버전 " + BuildInfo.VERSION + " · 전자칠판 " + online + "대 연결됨 · 30초마다 새로고침</p>"
                + "<section><h2>데이터</h2><table>" + rows + "</table>" + (errs.length() > 0 ? "<h2 style='margin-top:14px'>최근 오류</h2><ul>" + errs + "</ul>" : "") + "</section>"
                + "<section><h2>전자칠판</h2><table><tr><th>이름</th><th>학급</th><th>버전</th><th>상태</th></tr>" + devs + "</table></section>"
                + "</body></html>";
        return Response.bytes(ServerMain.utf8(html), "text/html; charset=utf-8");
    }
}
