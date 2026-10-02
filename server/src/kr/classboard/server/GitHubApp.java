package kr.classboard.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Watches the project's latest GitHub release and fetches its ClassBoardOS.apk, so boards update themselves
 * after a release is published - nobody has to upload the APK by hand.
 * GITHUB_REPO (default 3289david/classboard-os), GITHUB_TOKEN (only needed for a private repository),
 * AUTO_APP_UPDATE=0 turns it off.
 */
final class GitHubApp {
    interface Sink {
        /** Store a new board app; returns false if it is not usable. */
        boolean save(byte[] apk, String tag, long assetId) throws Exception;

        long currentAssetId();
    }

    static volatile String state = "확인 전";
    static volatile long lastCheck;
    static volatile String lastTag = "";

    static void start(Sink sink) {
        if ("0".equals(System.getenv("AUTO_APP_UPDATE"))) {
            state = "꺼짐 (AUTO_APP_UPDATE=0)";
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                return;
            }
            while (true) {
                check(sink);
                try {
                    Thread.sleep(30 * 60_000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "github-app");
        t.setDaemon(true);
        t.start();
    }

    static synchronized void check(Sink sink) {
        String repo = System.getenv("GITHUB_REPO");
        if (repo == null || !repo.matches("[\\w.-]+/[\\w.-]+")) repo = "3289david/classboard-os";
        lastCheck = System.currentTimeMillis();
        try {
            JSONObject rel = new JSONObject(new String(get("https://api.github.com/repos/" + repo + "/releases/latest", "application/vnd.github+json"),
                    java.nio.charset.StandardCharsets.UTF_8));
            lastTag = rel.optString("tag_name");
            JSONArray assets = rel.optJSONArray("assets");
            JSONObject apk = null;
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a.optString("name").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    apk = a;
                    break;
                }
            }
            if (apk == null) {
                state = lastTag + " 릴리스에 APK가 없습니다";
                return;
            }
            long id = apk.optLong("id");
            if (id == sink.currentAssetId()) {
                state = lastTag + " (최신)";
                return;
            }
            state = lastTag + " 받는 중";
            byte[] b = get(apk.optString("browser_download_url"), "application/octet-stream");
            if (apk.optLong("size") > 0 && b.length != apk.optLong("size")) throw new IOException("받은 크기가 다릅니다");
            state = sink.save(b, lastTag, id) ? lastTag + " 받음 · 전자칠판이 곧 설치" : lastTag + " APK가 올바르지 않습니다";
        } catch (Exception e) {
            state = "확인 실패: " + e.getMessage();
            L.w("GitHub", "release check", e);
        }
    }

    private static byte[] get(String url, String accept) throws IOException {
        String token = System.getenv("GITHUB_TOKEN");
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "jdms-server");
            c.setRequestProperty("Accept", accept);
            // the token goes to GitHub only, never to the storage host it redirects to
            if (token != null && !token.isEmpty() && new URL(url).getHost().endsWith("github.com")) c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
                byte[] b = in == null ? new byte[0] : Util.readAll(in);
                if (code >= 400) throw new IOException("HTTP " + code);
                return b;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("너무 많은 이동");
    }
}
