package kr.classboard.os;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Always-on service: local web server, hub, sync, data fetching, class-period automation and alert overlays. */
public class CoreService extends Service {
    private static final String TAG = "CoreService";
    private static volatile CoreService instance;

    public static CoreService get() {
        return instance;
    }

    private DeviceConfig cfg;
    private HttpServer server;
    private Router router;
    private SyncClient sync;
    private DataFetcher fetcher;
    private Overlays overlays;
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<String> seenClassAlerts = new HashSet<>();
    private final Set<String> ackedAlerts = new HashSet<>();
    private boolean firstStateSeen;
    private volatile boolean dataKicked;
    private String lastSegment = "";
    private String lastPowerState = "";
    private PowerManager.WakeLock wakeLock;

    public static void start(Context c) {
        Intent i = new Intent(c, CoreService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startInForeground();
        cfg = new DeviceConfig(this);
        overlays = new Overlays(this);
        fetcher = new DataFetcher(new File(getFilesDir(), "data.json"));
        sync = new SyncClient(cfg, getFilesDir());
        router = new Router(this, cfg, sync, fetcher);
        server = new HttpServer(router);
        try {
            server.start("127.0.0.1", DeviceConfig.PORT);
        } catch (Exception e) {
            Log.e(TAG, "server start", e);
        }
        sync.addListener(this::onState);
        sync.start();
        fetcher.setOnChange(() -> MainActivity.notifyWeb("data", "{}"));
        exec.scheduleWithFixedDelay(() -> refreshData(false), 3, 60, TimeUnit.SECONDS);
        exec.scheduleWithFixedDelay(this::tick, 5, 10, TimeUnit.SECONDS);
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("core", "중동중학교 전자칠판 실행 상태", NotificationManager.IMPORTANCE_MIN));
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "core")
                .setContentTitle("중동중학교 전자칠판 실행 중")
                .setContentText("학교 서버 연결 및 알림 수신")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (server != null) server.stop();
        if (sync != null) sync.stop();
        exec.shutdownNow();
        super.onDestroy();
    }

    public DeviceConfig config() {
        return cfg;
    }

    public Overlays overlays() {
        return overlays;
    }

    public void onDeviceConfigChanged(boolean serverChanged) {
        dataKicked = false;
        sync.reRegister(serverChanged);
        MainActivity.notifyWeb("device", "{}");
        refreshData(false);
    }

    public void refreshData(boolean force) {
        exec.execute(() -> {
            JSONObject st = sync.state();
            JSONObject config = st == null ? null : st.optJSONObject("config");
            // Before the first contact with the server the built-in school settings are used,
            // so a board can show 중동중학교 data straight from the internet if the server is down.
            if (config == null) config = SchoolPreset.config();
            fetcher.refresh(config, cfg.cls(), force, sync.base());
        });
    }

    // ------------------------------------------------------------------ alerts

    private boolean targetsMe(String target) {
        if (target == null || target.isEmpty() || "all".equals(target)) return true;
        String cls = cfg.cls();
        if (target.contains("-")) return target.equals(cls);
        return cls.startsWith(target + "-");
    }

    private void onState() {
        MainActivity.notifyWeb("state", "{}");
        JSONObject st = sync.state();
        if (st == null) return;
        if (!dataKicked && st.optJSONObject("config") != null) {
            // First state from the hub: fetch timetable / meals now instead of waiting for the next cycle.
            dataKicked = true;
            refreshData(false);
        }
        boolean foreground = MainActivity.isForeground();
        // Emergency alerts: stay on screen until acknowledged on this board.
        JSONArray alerts = st.optJSONArray("alerts");
        String activeId = null;
        JSONObject active = null;
        for (int i = 0; alerts != null && i < alerts.length(); i++) {
            JSONObject a = alerts.optJSONObject(i);
            if (!a.optBoolean("active") || !targetsMe(a.optString("target"))) continue;
            JSONObject acks = a.optJSONObject("acks");
            if ((acks != null && acks.has(cfg.deviceId())) || ackedAlerts.contains(a.optString("id"))) continue;
            active = a;
            activeId = a.optString("id");
        }
        if (active != null) {
            String time = Util.fmt("M월 d일 HH:mm").format(new java.util.Date(active.optLong("at")));
            final String id = activeId;
            if (!seenClassAlerts.contains("E" + id)) {
                seenClassAlerts.add("E" + id);
                Chime.play("urgent");
                wakeScreen();
            }
            if (!foreground) overlays.showEmergency(id, active.optString("title"), active.optString("text"), time + " · " + active.optString("author"), this::ackAlert);
        } else {
            overlays.hideEmergency(null);
        }

        // Officer class alerts for this classroom.
        JSONArray ca = st.optJSONArray("classAlerts");
        JSONObject settings = st.optJSONObject("classes") == null || st.optJSONObject("classes").optJSONObject(cfg.cls()) == null ? null
                : st.optJSONObject("classes").optJSONObject(cfg.cls()).optJSONObject("officerSettings");
        for (int i = 0; ca != null && i < ca.length(); i++) {
            JSONObject a = ca.optJSONObject(i);
            String id = a.optString("id");
            if (seenClassAlerts.contains(id)) continue;
            seenClassAlerts.add(id);
            if (!firstStateSeen) continue; // do not replay old alerts after a restart
            if (!cfg.cls().equals(a.optString("cls"))) continue;
            if (System.currentTimeMillis() - a.optLong("at") > 60_000L) continue;
            boolean sound = settings == null || settings.optBoolean("sound", true);
            int secs = settings == null ? 8 : settings.optInt("durationSec", 8);
            boolean quiet = "quiet".equals(a.optString("type"));
            if (sound) Chime.play(quiet ? "quiet" : "soft");
            if (!foreground) {
                String[] t = classAlertText(a.optString("type"));
                overlays.showClassAlert(t[0], t[1], Integer.parseInt(t[2]), secs, quiet);
            }
        }
        firstStateSeen = true;
    }

    /** Title, subtitle and accent colour for officer alert types. */
    public static String[] classAlertText(String type) {
        switch (type) {
            case "quiet": return new String[]{"조용히 해주세요", "수업이 곧 시작됩니다", String.valueOf(0xFF3F51B5)};
            case "clean": return new String[]{"청소 시작", "각자 맡은 청소 구역으로 이동해 주세요", String.valueOf(0xFF2E7D32)};
            case "ready": return new String[]{"수업 준비", "교과서와 준비물을 책상 위에 꺼내 주세요", String.valueOf(0xFFEF6C00)};
            case "teacher": return new String[]{"선생님을 호출했습니다", "담당 선생님께 알림을 보냈습니다", String.valueOf(0xFF6A1B9A)};
            case "notice": return new String[]{"전달사항 있습니다", "임원의 안내를 들어 주세요", String.valueOf(0xFF00838F)};
            default: return new String[]{"학급 알림", "", String.valueOf(0xFF455A64)};
        }
    }

    /** Re-evaluate alerts, e.g. when the board goes to the background so native overlays take over. */
    public void recheckAlerts() {
        exec.execute(this::onState);
    }

    public void ackAlert(String id) {
        ackedAlerts.add(id);
        overlays.hideEmergency(id);
        exec.execute(() -> {
            try {
                sync.postJson(sync.base() + "/api/device/ack", Util.jo("alertId", id), null);
            } catch (Exception e) {
                Log.w(TAG, "ack", e);
            }
        });
        MainActivity.notifyWeb("state", "{}");
    }

    public JSONArray localAcks() {
        JSONArray a = new JSONArray();
        for (String s : ackedAlerts) a.put(s);
        return a;
    }

    // ------------------------------------------------------------------ period automation

    private void tick() {
        try {
            JSONObject st = sync.state();
            JSONObject config = st == null ? null : st.optJSONObject("config");
            JSONObject settings = cfg.settings();
            powerTick(settings);
            if (config == null) return;
            JSONObject data = fetcher.get();
            JSONObject full = data.optJSONObject("comciFull");
            JSONArray times = full == null ? null : full.optJSONArray("times");
            List<Periods.Slot> slots = Periods.slots(config, times);
            if (slots.isEmpty() || !Periods.isWeekday()) {
                lastSegment = "";
                return;
            }
            int periodsToday = -1;
            if (full != null && cfg.cls().contains("-")) {
                JSONArray weeks = new JSONArray();
                JSONArray fw = full.optJSONArray("weeks");
                for (int i = 0; fw != null && i < fw.length(); i++) {
                    JSONObject w = fw.optJSONObject(i);
                    JSONObject cl = w.optJSONObject("classes");
                    weeks.put(Util.jo("dates", w.optJSONArray("dates"), "days", cl == null ? null : cl.optJSONArray(cfg.cls())));
                }
                periodsToday = Periods.periodsToday(weeks);
            }
            if (periodsToday == 0) {
                lastSegment = "";
                return; // no classes today (holiday)
            }
            int now = Util.nowMinutes();
            String seg = "none";
            int period = 0;
            for (Periods.Slot s : slots) {
                if (periodsToday > 0 && s.period > periodsToday) break;
                if (now >= s.start && now < s.end) {
                    seg = "class" + s.period;
                    period = s.period;
                    break;
                }
                if (now < s.start) {
                    seg = "break" + s.period;
                    break;
                }
                seg = "after";
            }
            if (!seg.equals(lastSegment)) {
                String prev = lastSegment;
                lastSegment = seg;
                if (prev.isEmpty()) return; // first observation after start
                if (prev.startsWith("class")) onClassEnd(settings);
                if (seg.startsWith("class")) onClassStart(settings, st, period);
                MainActivity.notifyWeb("segment", Util.jo("segment", seg, "prev", prev).toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "tick", e);
        }
    }

    private void onClassStart(JSONObject settings, JSONObject st, int period) {
        JSONArray rules = settings.optJSONArray("launchRules");
        int dow = Calendar.getInstance(Util.KST).get(Calendar.DAY_OF_WEEK);
        String pkg = null;
        for (int i = 0; rules != null && i < rules.length(); i++) {
            JSONObject r = rules.optJSONObject(i);
            if (r == null) continue;
            int rp = r.optInt("period", 0);
            int rd = r.optInt("dow", 0);
            if ((rp == 0 || rp == period) && (rd == 0 || rd == dow)) pkg = r.optString("pkg");
        }
        // Lesson screens can name an app to open as well.
        JSONArray lessons = st.optJSONArray("lessons");
        for (int i = 0; lessons != null && i < lessons.length(); i++) {
            JSONObject l = lessons.optJSONObject(i);
            if (cfg.cls().equals(l.optString("cls")) && Util.today().equals(l.optString("date")) && l.optInt("period") == period
                    && !l.optString("app").isEmpty()) pkg = l.optString("app");
        }
        if (pkg != null && !pkg.isEmpty()) {
            Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
            if (li != null) {
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(li);
                } catch (Exception e) {
                    Log.w(TAG, "auto launch", e);
                }
            }
        } else {
            bringHome();
        }
    }

    private void onClassEnd(JSONObject settings) {
        if (!settings.optBoolean("resetOnEnd", true)) return;
        overlays.hideAll();
        File tmp = new File(getCacheDir(), "open");
        File[] fs = tmp.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        bringHome();
        MainActivity.notifyWeb("reset", "{}");
    }

    /** Handles the Android-style Back / Home / Recents / Memo keys (floating bar and board bar). */
    public void nav(String action) {
        BoardAccessibility a = BoardAccessibility.get();
        switch (action) {
            case "home":
                bringHome();
                break;
            case "memo":
                overlays.toggleMemo();
                break;
            case "back":
            case "recents":
            case "notifications":
                if (a != null && a.global(action)) break;
                if ("recents".equals(action)) {
                    bringHome();
                    MainActivity.notifyWeb("recents", "{}");
                } else {
                    ui.post(() -> android.widget.Toast.makeText(this, "뒤로 · 최근 앱 버튼은 설정에서 '중동중학교' 접근성 서비스를 켜야 동작합니다", android.widget.Toast.LENGTH_LONG).show());
                }
                break;
            default:
                break;
        }
    }

    public void onBoardPaused() {
        if (cfg.settings().optBoolean("floatingNav", true)) overlays.showNav(this::nav);
    }

    public void onBoardResumed() {
        overlays.hideNav();
    }

    private void bringHome() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "bring home", e);
        }
    }

    // ------------------------------------------------------------------ power

    private void powerTick(JSONObject settings) {
        JSONObject p = settings.optJSONObject("power");
        if (p == null || !p.optBoolean("enabled")) {
            if (!"on".equals(lastPowerState)) {
                lastPowerState = "on";
                MainActivity.setKeepOn(true);
            }
            return;
        }
        int on = Util.parseHm(p.optString("on", "07:30"));
        int off = Util.parseHm(p.optString("off", "17:30"));
        int now = Util.nowMinutes();
        boolean weekend = !Periods.isWeekday();
        boolean awake = on >= 0 && off >= 0 && now >= on && now < off && !(weekend && !p.optBoolean("weekends"));
        String state = awake ? "on" : "off";
        if (state.equals(lastPowerState)) return;
        boolean first = lastPowerState.isEmpty();
        lastPowerState = state;
        if (awake) {
            MainActivity.setKeepOn(true);
            if (!first) {
                wakeScreen();
                bringHome();
            }
        } else {
            MainActivity.setKeepOn(false);
            if (!first) sleepScreen();
        }
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "classboard:wake");
            wakeLock.acquire(10_000);
        } catch (Exception e) {
            Log.w(TAG, "wake", e);
        }
    }

    public void sleepScreen() {
        ui.post(() -> {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, AdminReceiver.class);
            if (dpm != null && dpm.isAdminActive(admin)) {
                try {
                    dpm.lockNow();
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "lockNow", e);
                }
            }
            // Without device admin: let the system screen timeout turn the display off.
            MainActivity.setKeepOn(false);
            MainActivity.notifyWeb("sleep", "{}");
        });
    }

    // ------------------------------------------------------------------ permissions

    public JSONObject permissionStatus() {
        PackageManager pm = getPackageManager();
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        String acc = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        boolean isHome = ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
        return Util.jo(
                "overlay", Settings.canDrawOverlays(this),
                "writeSettings", Settings.System.canWrite(this),
                "accessibility", acc != null && acc.contains(getPackageName()),
                "deviceAdmin", dpm != null && dpm.isAdminActive(new ComponentName(this, AdminReceiver.class)),
                "notifications", Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                "location", checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                "bluetooth", Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                "microphone", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                "defaultHome", isHome);
    }
}
