package kr.classboard.os;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

import kr.classboard.os.HttpServer.Request;
import kr.classboard.os.HttpServer.Response;

/**
 * Mirrors the hub's public state on every device (including the hub itself) using long polling,
 * and proxies API calls from this device to the hub.
 */
public class SyncClient {
    private final DeviceConfig cfg;
    private final File cacheFile;
    private final Object monitor = new Object();
    private volatile JSONObject state;
    private volatile long rev = -1;
    private volatile boolean online;
    private volatile long lastOk;
    private volatile String lastError = "";
    private volatile boolean running;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public SyncClient(DeviceConfig cfg, File dir) {
        this.cfg = cfg;
        this.cacheFile = new File(dir, "mirror.json");
        String s = Util.readFile(cacheFile);
        if (s != null) {
            try {
                JSONObject o = new JSONObject(s);
                state = o.optJSONObject("state");
                rev = o.optLong("rev", -1);
            } catch (Exception ignored) {
            }
        }
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public JSONObject state() {
        return state;
    }

    public long rev() {
        return rev;
    }

    public boolean online() {
        return online;
    }

    private volatile boolean useHttp;
    private int httpsFailures;

    /**
     * Server base URL. If the https address keeps failing (e.g. no certificate yet), the same host is
     * tried over plain http; a successful https request switches back.
     */
    public String base() {
        String u = cfg.hubUrl();
        return useHttp && u.startsWith("https://") ? "http://" + u.substring(8) : u;
    }

    private void noteFailure() {
        if (!cfg.hubUrl().startsWith("https://")) return;
        if (++httpsFailures >= 2) {
            useHttp = !useHttp;
            httpsFailures = 0;
        }
    }

    public JSONObject status() {
        return Util.jo("online", online, "lastOk", lastOk, "error", lastError, "rev", rev, "hubUrl", base());
    }

    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::loop, "sync");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
    }

    private void loop() {
        while (running) {
            String hub = base();
            if (hub.isEmpty()) {
                sleep(2000);
                continue;
            }
            try {
                ensureRegistered(hub);
                // until the first successful contact, ask without waiting so the link status is known right away
                HttpURLConnection c = (HttpURLConnection) new URL(hub + "/api/state?" + (online ? "wait=1&" : "") + "since=" + rev).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(30000);
                c.setRequestProperty("X-Device-Id", cfg.deviceId());
                c.setRequestProperty("X-Device-Name", URLEncoder.encode(cfg.name(), "UTF-8"));
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                String body;
                try (InputStream in = c.getInputStream()) {
                    body = new String(Util.readAll(in), StandardCharsets.UTF_8);
                }
                c.disconnect();
                JSONObject o = new JSONObject(body);
                boolean cameOnline = !online;
                online = true;
                if (cameOnline) wake();
                httpsFailures = 0;
                lastOk = System.currentTimeMillis();
                lastError = "";
                long newRev = o.optLong("rev", -1);
                if (!o.optBoolean("same") && o.has("state")) {
                    state = o.getJSONObject("state");
                    rev = newRev;
                    Util.writeFileAtomic(cacheFile, Util.jo("rev", rev, "state", state).toString().getBytes(StandardCharsets.UTF_8));
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                    for (Runnable l : listeners) l.run();
                } else if (newRev < rev) {
                    // Hub was reset; force a full refresh next time.
                    rev = -1;
                }
            } catch (Exception e) {
                if (online) Log.w("Sync", "hub unreachable", e);
                boolean wentOffline = online;
                online = false;
                if (wentOffline) wake();
                lastError = String.valueOf(e.getMessage());
                noteFailure();
                for (Runnable l : listeners) l.run();
                sleep(3000);
            }
        }
    }

    private void wake() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    private void ensureRegistered(String hub) throws Exception {
        if (!cfg.deviceKey().isEmpty()) return;
        JSONObject body = Util.jo("deviceId", cfg.deviceId(), "name", cfg.name(), "cls", cfg.cls());
        JSONObject res = postJson(hub + "/api/device/hello", body, null);
        String key = res.optString("deviceKey");
        if (!key.isEmpty()) cfg.setDeviceKey(key);
    }

    /** Send updated name / class to the hub (keeps the existing device key), and reset the mirror if the hub changed. */
    public void reRegister(boolean hubChanged) {
        if (hubChanged) {
            rev = -1;
            state = null;
            online = false;
            lastOk = 0;
            wake();
        }
        new Thread(() -> {
            try {
                String hub = base();
                if (hub.isEmpty()) return;
                JSONObject body = Util.jo("deviceId", cfg.deviceId(), "name", cfg.name(), "cls", cfg.cls());
                JSONObject res = postJson(hub + "/api/device/hello", body, null);
                String key = res.optString("deviceKey");
                if (!key.isEmpty()) cfg.setDeviceKey(key);
            } catch (Exception e) {
                Log.w("Sync", "register", e);
            }
        }).start();
    }

    public JSONObject postJson(String url, JSONObject body, String deviceKey) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (deviceKey != null) c.setRequestProperty("X-Device-Key", deviceKey);
        if (!cfg.deviceKey().isEmpty() && deviceKey == null) c.setRequestProperty("X-Device-Key", cfg.deviceKey());
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String s = in == null ? "{}" : new String(Util.readAll(in), StandardCharsets.UTF_8);
        c.disconnect();
        JSONObject o = s.trim().startsWith("{") ? new JSONObject(s) : new JSONObject();
        if (code >= 400) throw new Exception(o.optString("error", "HTTP " + code));
        return o;
    }

    public JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        if (!cfg.deviceKey().isEmpty()) c.setRequestProperty("X-Device-Key", cfg.deviceKey());
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String s = in == null ? "{}" : new String(Util.readAll(in), StandardCharsets.UTF_8);
        c.disconnect();
        JSONObject o = s.trim().startsWith("{") ? new JSONObject(s) : new JSONObject();
        if (code >= 400) throw new Exception(o.optString("error", "HTTP " + code));
        return o;
    }

    /** Serve /api/state from the local mirror (with the same long-poll semantics as the hub). */
    public Response serveState(Request r) throws Exception {
        long since = -2;
        try {
            if (r.param("since") != null) since = Long.parseLong(r.param("since"));
        } catch (NumberFormatException ignored) {
        }
        if (since == rev && r.param("wait") != null) {
            long deadline = System.currentTimeMillis() + 20000;
            boolean wasOnline = online;
            synchronized (monitor) {
                // also wake when the server link comes up or drops, so the board's status stays current
                while (rev == since && online == wasOnline && System.currentTimeMillis() < deadline) monitor.wait(Math.max(1, deadline - System.currentTimeMillis()));
            }
        }
        if (state == null) return Response.json(Util.jo("rev", -1, "offline", true, "state", JSONObject.NULL));
        if (since == rev) return Response.json(Util.jo("rev", rev, "same", true, "online", online));
        return Response.json("{\"rev\":" + rev + ",\"online\":" + online + ",\"state\":" + state + "}");
    }

    /** Forward a request to the hub, streaming bodies both ways. */
    public Response proxy(Request r) throws Exception {
        String hub = base();
        if (hub.isEmpty()) return Response.error(503, "허브가 설정되지 않았습니다");
        String target = hub + r.path + (r.rawQuery.isEmpty() ? "" : "?" + r.rawQuery);
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(60000);
        c.setRequestMethod(r.method);
        for (String h : new String[]{"content-type", "x-token", "x-filename"}) {
            String v = r.header(h);
            if (v != null) c.setRequestProperty(h, v);
        }
        if (r.contentLength > 0) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(r.contentLength);
            try (OutputStream o = c.getOutputStream()) {
                Util.copy(r.in, o, r.contentLength);
            }
        }
        int code;
        try {
            code = c.getResponseCode();
        } catch (Exception e) {
            return Response.error(502, "허브에 연결할 수 없습니다: " + e.getMessage());
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        Response res = new Response();
        res.status = code;
        res.type = c.getContentType() == null ? "application/octet-stream" : c.getContentType();
        String disp = c.getHeaderField("Content-Disposition");
        if (disp != null) res.headers.put("Content-Disposition", disp);
        long len = c.getContentLengthLong();
        if (in == null) {
            res.body = new byte[0];
        } else if (len >= 0) {
            res.stream = in;
            res.length = len;
        } else {
            res.body = Util.readAll(in);
        }
        return res;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
