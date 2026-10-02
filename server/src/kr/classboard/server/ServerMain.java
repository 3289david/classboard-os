package kr.classboard.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 중동중학교 전자칠판 데이터 서버 (jdmsosserver.krl.kr).
 *
 * Runs 24/7 and does the internet work for every classroom board: Comcigan timetable, NEIS meals and
 * schedule, weather, and the school homepage boards. Boards mirror /api/state and pull /api/shared,
 * so they keep working from their cache when the server or the internet is down.
 *
 * Environment:
 *   PORT        HTTP port (default 8080)
 *   DATA_DIR    where server.json / data.json / devices.json are kept (default ./data)
 *   NEIS_KEY    NEIS Open API key (optional; otherwise read from secrets/neis.key in the working directory)
 *   STATUS_KEY  if set, the status page at / requires ?key=STATUS_KEY
 */
public final class ServerMain {
    private ServerMain() {}

    static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    public static void main(String[] args) throws Exception {
        // A host with half-working IPv6 makes every request to the school homepage wait for the IPv6 attempt to time out.
        System.setProperty("java.net.preferIPv4Stack", "true");
        int port = Integer.parseInt(env("PORT", "8080"));
        File dir = new File(env("DATA_DIR", "data"));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);

        String key = env("NEIS_KEY", "");
        if (key.isEmpty()) {
            String f = Util.readFile(new File("secrets/neis.key"));
            key = f == null ? "" : f.trim();
        }

        Store store = new Store(new File(dir, "server.json"));
        applyPreset(store, new File(dir, "config.json"));
        Store devices = new Store(new File(dir, "devices.json"));
        DataFetcher fetcher = new DataFetcher(new File(dir, "data.json"));
        OfficeSetup.start(dir); // LibreOffice + HWP import for attachments, installed automatically when missing
        ServerApi api = new ServerApi(store, devices, fetcher, env("STATUS_KEY", ""), dir);
        // new board app from the latest GitHub release -> boards install it themselves
        GitHubApp.start(new GitHubApp.Sink() {
            @Override
            public boolean save(byte[] apk, String tag, long assetId) throws Exception {
                return api.saveApp(apk, tag, assetId);
            }

            @Override
            public long currentAssetId() {
                return api.appAssetId();
            }
        });

        HttpServer http = new HttpServer(api::handle);
        http.start(port);
        System.err.println("중동중학교 전자칠판 데이터 서버 " + BuildInfo.VERSION + " - port " + port + ", data " + dir.getAbsolutePath()
                + (key.isEmpty() ? " (NEIS 인증키 없음)" : " (NEIS 인증키 사용)"));

        final String neisKey = key;
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        exec.scheduleWithFixedDelay(() -> {
            try {
                JSONObject config = store.read(root -> Util.copy(Util.obj(root, "config")));
                Util.put(config, "neisKey", neisKey);
                fetcher.refresh(config, null, false, null);
            } catch (Throwable t) {
                L.w("Server", "refresh", t);
            }
        }, 1, 60, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            store.saveNow();
            devices.saveNow();
            http.stop();
        }));
    }

    /** Built-in school (중동중학교), with optional overrides from DATA_DIR/config.json. */
    static void applyPreset(Store store, File overrideFile) {
        JSONObject preset = SchoolPreset.config();
        JSONArray contacts = (JSONArray) preset.remove("contacts");
        JSONObject override = null;
        String o = Util.readFile(overrideFile);
        if (o != null) {
            try {
                override = new JSONObject(o);
            } catch (Exception e) {
                L.w("Server", "config.json is not valid JSON - ignored", e);
            }
        }
        final JSONObject ov = override;
        store.write(root -> {
            JSONObject cfg = new JSONObject();
            Iterator<String> it = preset.keys();
            while (it.hasNext()) {
                String k = it.next();
                Util.put(cfg, k, preset.opt(k));
            }
            if (ov != null) {
                Iterator<String> oi = ov.keys();
                while (oi.hasNext()) {
                    String k = oi.next();
                    if (!"contacts".equals(k)) Util.put(cfg, k, ov.opt(k));
                }
            }
            Util.put(root, "config", cfg);
            JSONArray c = ov != null && ov.optJSONArray("contacts") != null ? ov.optJSONArray("contacts") : contacts;
            JSONArray withIds = new JSONArray();
            for (int i = 0; c != null && i < c.length(); i++) {
                JSONObject x = Util.copy(c.optJSONObject(i));
                Util.put(x, "id", "c" + i);
                withIds.put(x);
            }
            Util.put(root, "contacts", withIds);
        });
        store.saveNow();
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
