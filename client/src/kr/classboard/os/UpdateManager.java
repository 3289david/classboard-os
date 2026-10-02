package kr.classboard.os;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Board app updates from the school server: checks /api/app/info, downloads the APK when it changed,
 * and installs it when it is a newer version of this app - never during a lesson.
 * On Android 12+ updates after the first one install without a confirmation tap.
 */
final class UpdateManager {
    private static final String TAG = "Update";
    private final Context ctx;
    private final DeviceConfig cfg;
    private final SyncClient sync;
    private volatile String state = "확인 전";
    private volatile long lastCheck;
    private volatile File pending;
    private volatile int pendingVersion;

    UpdateManager(Context ctx, DeviceConfig cfg, SyncClient sync) {
        this.ctx = ctx.getApplicationContext();
        this.cfg = cfg;
        this.sync = sync;
    }

    JSONObject status() {
        return Util.jo("state", state, "lastCheck", lastCheck, "auto", cfg.home().optBoolean("autoUpdate", true),
                "canInstall", canInstall(), "pendingVersion", pendingVersion);
    }

    boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || ctx.getPackageManager().canRequestPackageInstalls();
    }

    private int myVersion() {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return (int) (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Exception e) {
            return 0;
        }
    }

    /** @param inClass a lesson is running: download, but install later */
    synchronized void check(boolean force, boolean inClass) {
        if (!force && !cfg.home().optBoolean("autoUpdate", true)) {
            state = "자동 업데이트 꺼짐";
            return;
        }
        lastCheck = System.currentTimeMillis();
        try {
            if (pending != null && pending.isFile() && pendingVersion > myVersion()) {
                if (inClass) {
                    state = "새 버전 준비됨 · 쉬는 시간에 설치";
                    return;
                }
                install(pending, pendingVersion);
                return;
            }
            JSONObject info = sync.getJson(sync.base() + "/api/app/info");
            String sha = info.optString("sha256");
            if (sha.isEmpty()) {
                state = "서버에 올린 앱 없음";
                return;
            }
            if (sha.equals(cfg.prefs().getString("updateSha", "")) && !force) {
                state = "최신 버전";
                return;
            }
            state = "새 앱 받는 중";
            File apk = new File(ctx.getCacheDir(), "update.apk");
            download(sync.base() + "/api/app/apk", apk, sha);
            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            if (pi == null || !ctx.getPackageName().equals(pi.packageName)) {
                remember(sha);
                state = "서버의 파일이 이 앱이 아닙니다";
                apk.delete();
                return;
            }
            int v = (int) (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
            if (v <= myVersion()) {
                remember(sha);
                state = "최신 버전";
                apk.delete();
                return;
            }
            pending = apk;
            pendingVersion = v;
            remember(sha);
            if (inClass) {
                state = "새 버전 " + pi.versionName + " 준비됨 · 쉬는 시간에 설치";
                return;
            }
            install(apk, v);
        } catch (Exception e) {
            state = "확인 실패: " + e.getMessage();
            L.w(TAG, "check", e);
        }
    }

    private void remember(String sha) {
        cfg.prefs().edit().putString("updateSha", sha).apply();
    }

    private static void download(String url, File out, String sha) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        File tmp = new File(out.getPath() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream o = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                o.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
        if (!Util.hex(md.digest()).equalsIgnoreCase(sha)) {
            tmp.delete();
            throw new Exception("받은 파일이 손상되었습니다");
        }
        if (!tmp.renameTo(out)) throw new Exception("저장 실패");
    }

    private void install(File apk, int version) throws Exception {
        if (!canInstall()) {
            state = "설치하려면 '앱 설치 허용' 권한이 필요합니다 (설정 → 권한)";
            MainActivity.notifyWeb("update", Util.jo("need", "installApps").toString());
            return;
        }
        state = "새 버전 설치 중";
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("app.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                s.fsync(o);
            }
            Intent done = new Intent(ctx, UpdateReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent cb = PendingIntent.getBroadcast(ctx, 7, done, flags);
            MainActivity.notifyWeb("update", Util.jo("installing", version).toString());
            s.commit(cb.getIntentSender());
        }
    }

    void setState(String s) {
        state = s;
    }
}
