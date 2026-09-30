package kr.classboard.os;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** JavaScript interface exposing device controls to the board UI. */
public class NativeBridge {
    private final Activity act;
    private final SharedPreferences prefs;

    NativeBridge(Activity act) {
        this.act = act;
        this.prefs = act.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    private static String err(String m) {
        return Util.jo("error", m).toString();
    }

    private static String ok() {
        return "{\"ok\":true}";
    }

    // ---------------------------------------------------------------- apps

    @JavascriptInterface
    public String apps() {
        PackageManager pm = act.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        List<JSONObject> items = new ArrayList<>();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(act.getPackageName())) continue;
            items.add(Util.jo("pkg", pkg, "label", String.valueOf(ri.loadLabel(pm))));
        }
        Collections.sort(items, (a, b) -> a.optString("label").compareTo(b.optString("label")));
        JSONArray a = new JSONArray();
        for (JSONObject o : items) a.put(o);
        return a.toString();
    }

    private void remember(String pkg) {
        try {
            JSONArray r = new JSONArray(prefs.getString("recent", "[]"));
            JSONArray n = new JSONArray().put(pkg);
            for (int i = 0; i < r.length() && n.length() < 12; i++) if (!pkg.equals(r.optString(i))) n.put(r.optString(i));
            prefs.edit().putString("recent", n.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public String recent() {
        return prefs.getString("recent", "[]");
    }

    @JavascriptInterface
    public String launch(String pkg) {
        Intent i = act.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return err("앱을 찾을 수 없습니다");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        act.runOnUiThread(() -> act.startActivity(i));
        remember(pkg);
        return ok();
    }

    /** Opens the app next to the board in split-screen (requires the accessibility service to enter split mode). */
    @JavascriptInterface
    public String launchSplit(String pkg) {
        Intent i = act.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return err("앱을 찾을 수 없습니다");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        remember(pkg);
        if (act.isInMultiWindowMode()) {
            act.runOnUiThread(() -> act.startActivity(i));
            return ok();
        }
        BoardAccessibility svc = BoardAccessibility.get();
        if (svc == null) return err("화면 분할을 사용하려면 설정에서 '교실 OS' 접근성 서비스를 켜 주세요");
        act.runOnUiThread(() -> {
            svc.toggleSplit();
            act.getWindow().getDecorView().postDelayed(() -> act.startActivity(i), 900);
        });
        return ok();
    }

    /** Android navigation keys shown on the board's bottom bar. */
    @JavascriptInterface
    public String nav(String action) {
        CoreService cs = CoreService.get();
        if (cs == null) return err("서비스가 실행 중이 아닙니다");
        BoardAccessibility a = BoardAccessibility.get();
        if (("back".equals(action) || "recents".equals(action)) && a == null) {
            return Util.jo("ok", false, "noA11y", true).toString();
        }
        cs.nav(action);
        return ok();
    }

    @JavascriptInterface
    public String exitSplit() {
        BoardAccessibility svc = BoardAccessibility.get();
        if (svc == null) return err("접근성 서비스가 꺼져 있습니다");
        act.runOnUiThread(svc::toggleSplit);
        return ok();
    }

    // ---------------------------------------------------------------- system status

    @JavascriptInterface
    public String sys() {
        JSONObject o = new JSONObject();
        AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        Util.put(o, "volume", Util.jo("cur", am.getStreamVolume(AudioManager.STREAM_MUSIC), "max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                "muted", am.isStreamMute(AudioManager.STREAM_MUSIC)));
        int br = -1, mode = -1;
        try {
            br = Settings.System.getInt(act.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            mode = Settings.System.getInt(act.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE);
        } catch (Exception ignored) {
        }
        Util.put(o, "brightness", Util.jo("value", br, "auto", mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC, "canWrite", Settings.System.canWrite(act)));
        Util.put(o, "network", network());
        Util.put(o, "bluetooth", bluetooth());
        Util.put(o, "storage", storage());
        Intent bat = act.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            boolean present = bat.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false);
            Util.put(o, "battery", Util.jo("present", present, "level", lvl < 0 ? -1 : lvl * 100 / Math.max(1, scale), "plugged", plugged != 0));
        }
        Util.put(o, "device", Util.jo("model", Build.MANUFACTURER + " " + Build.MODEL, "android", Build.VERSION.RELEASE, "sdk", Build.VERSION.SDK_INT,
                "multiWindow", act.isInMultiWindowMode(), "memo", CoreService.get() != null && CoreService.get().overlays().memoShown(),
                "recording", CaptureService.isRecording()));
        return o.toString();
    }

    @SuppressLint("MissingPermission")
    private JSONObject network() {
        JSONObject o = new JSONObject();
        ConnectivityManager cm = (ConnectivityManager) act.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        Util.put(o, "connected", nc != null);
        if (nc != null) {
            String t = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi" : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular" : "other";
            Util.put(o, "transport", t);
            Util.put(o, "internet", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            Util.put(o, "downKbps", nc.getLinkDownstreamBandwidthKbps());
        }
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        if (lp != null) Util.put(o, "iface", lp.getInterfaceName());
        Util.put(o, "ip", Util.localIp());
        WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            Util.put(o, "wifiEnabled", wm.isWifiEnabled());
            @SuppressWarnings("deprecation") WifiInfo wi = wm.getConnectionInfo();
            if (wi != null && wi.getNetworkId() != -1) {
                String ssid = wi.getSSID();
                if (ssid != null) ssid = ssid.replace("\"", "");
                Util.put(o, "ssid", "<unknown ssid>".equals(ssid) ? "" : ssid);
                Util.put(o, "rssi", wi.getRssi());
                @SuppressWarnings("deprecation") int level = WifiManager.calculateSignalLevel(wi.getRssi(), 5);
                Util.put(o, "level", level);
                Util.put(o, "linkMbps", wi.getLinkSpeed());
                Util.put(o, "freqMHz", wi.getFrequency());
            }
        }
        return o;
    }

    @SuppressLint("MissingPermission")
    private JSONObject bluetooth() {
        JSONObject o = new JSONObject();
        BluetoothManager bm = (BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        Util.put(o, "supported", ad != null);
        if (ad == null) return o;
        boolean perm = Build.VERSION.SDK_INT < 31 || act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        Util.put(o, "permission", perm);
        if (!perm) return o;
        try {
            Util.put(o, "enabled", ad.isEnabled());
            Util.put(o, "name", ad.getName());
            JSONArray devs = new JSONArray();
            for (BluetoothDevice d : ad.getBondedDevices()) {
                boolean connected = false;
                try {
                    connected = (boolean) d.getClass().getMethod("isConnected").invoke(d);
                } catch (Exception ignored) {
                }
                int cls = d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getMajorDeviceClass();
                devs.put(Util.jo("name", d.getName(), "address", d.getAddress(), "connected", connected, "major", cls));
            }
            Util.put(o, "bonded", devs);
        } catch (SecurityException e) {
            Util.put(o, "permission", false);
        }
        return o;
    }

    private JSONArray storage() {
        JSONArray a = new JSONArray();
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        a.put(Util.jo("label", "내부 저장공간", "total", fs.getTotalBytes(), "free", fs.getAvailableBytes(), "removable", false));
        StorageManager sm = (StorageManager) act.getSystemService(Context.STORAGE_SERVICE);
        List<StorageVolume> vols = sm.getStorageVolumes();
        for (int i = 0; i < vols.size(); i++) {
            StorageVolume v = vols.get(i);
            if (v.isPrimary()) continue;
            JSONObject o = Util.jo("label", v.getDescription(act), "removable", v.isRemovable(), "state", v.getState(), "index", i, "uuid", v.getUuid());
            if (Build.VERSION.SDK_INT >= 30 && v.getDirectory() != null) {
                try {
                    StatFs vf = new StatFs(v.getDirectory().getPath());
                    Util.put(o, "total", vf.getTotalBytes());
                    Util.put(o, "free", vf.getAvailableBytes());
                } catch (Exception ignored) {
                }
            }
            a.put(o);
        }
        return a;
    }

    @JavascriptInterface
    public String setVolume(int v) {
        AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(v, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))), AudioManager.FLAG_SHOW_UI);
        return ok();
    }

    @JavascriptInterface
    public String setBrightness(int v) {
        final int val = Math.max(1, Math.min(255, v));
        act.runOnUiThread(() -> {
            WindowManager.LayoutParams lp = act.getWindow().getAttributes();
            lp.screenBrightness = val / 255f;
            act.getWindow().setAttributes(lp);
        });
        if (Settings.System.canWrite(act)) {
            Settings.System.putInt(act.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(act.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, val);
            return ok();
        }
        return Util.jo("ok", true, "windowOnly", true).toString();
    }

    @JavascriptInterface
    public String setAutoBrightness(boolean on) {
        if (!Settings.System.canWrite(act)) return err("시스템 설정 변경 권한이 필요합니다");
        Settings.System.putInt(act.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                on ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        if (on) act.runOnUiThread(() -> {
            WindowManager.LayoutParams lp = act.getWindow().getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            act.getWindow().setAttributes(lp);
        });
        return ok();
    }

    // ---------------------------------------------------------------- settings & permissions

    @JavascriptInterface
    public String open(String which) {
        Intent i;
        String pkgUri = "package:" + act.getPackageName();
        switch (which) {
            case "wifi":
                i = new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS);
                break;
            case "internet":
                i = new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIRELESS_SETTINGS);
                break;
            case "bluetooth": i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS); break;
            case "display": i = new Intent(Settings.ACTION_DISPLAY_SETTINGS); break;
            case "sound": i = new Intent(Settings.ACTION_SOUND_SETTINGS); break;
            case "storage": i = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS); break;
            case "settings": i = new Intent(Settings.ACTION_SETTINGS); break;
            case "home": i = new Intent(Settings.ACTION_HOME_SETTINGS); break;
            case "date": i = new Intent(Settings.ACTION_DATE_SETTINGS); break;
            case "overlay": i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse(pkgUri)); break;
            case "writeSettings": i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse(pkgUri)); break;
            case "accessibility": i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS); break;
            case "appInfo": i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(pkgUri)); break;
            case "deviceAdmin":
                i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(act, AdminReceiver.class));
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "수업 종료 후 자동 절전(화면 끄기)에 사용합니다.");
                break;
            case "notifications": return request(new String[]{"android.permission.POST_NOTIFICATIONS"});
            case "location": return request(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
            case "bluetoothPerm": return request(new String[]{"android.permission.BLUETOOTH_CONNECT"});
            case "microphone": return request(new String[]{Manifest.permission.RECORD_AUDIO});
            default: return err("알 수 없는 설정");
        }
        final Intent fi = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        act.runOnUiThread(() -> {
            try {
                act.startActivity(fi);
            } catch (Exception e) {
                try {
                    act.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
            }
        });
        return ok();
    }

    private String request(String[] perms) {
        act.runOnUiThread(() -> act.requestPermissions(perms, MainActivity.REQ_PERMS));
        return ok();
    }

    // ---------------------------------------------------------------- tools

    @JavascriptInterface
    public String memo() {
        CoreService cs = CoreService.get();
        if (cs == null) return err("서비스가 실행 중이 아닙니다");
        if (!cs.overlays().allowed()) return err("화면 위 메모를 쓰려면 '다른 앱 위에 표시' 권한이 필요합니다");
        cs.overlays().toggleMemo();
        return ok();
    }

    @JavascriptInterface
    public String capture(boolean record) {
        if (record && CaptureService.isRecording()) {
            act.startService(new Intent(act, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            return Util.jo("ok", true, "stopped", true).toString();
        }
        act.runOnUiThread(() -> act.startActivity(new Intent(act, CaptureActivity.class).putExtra("record", record)));
        return ok();
    }

    @JavascriptInterface
    public void toast(String m) {
        act.runOnUiThread(() -> Toast.makeText(act, m, Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void keepOn(boolean on) {
        MainActivity.setKeepOn(on);
    }

    @JavascriptInterface
    public void sleepNow() {
        CoreService cs = CoreService.get();
        if (cs != null) cs.sleepScreen();
    }

    @JavascriptInterface
    public String acks() {
        CoreService cs = CoreService.get();
        return cs == null ? "[]" : cs.localAcks().toString();
    }

    @JavascriptInterface
    public void chime(String kind) {
        Chime.play(kind);
    }

    // ---------------------------------------------------------------- files (USB / SAF)

    @JavascriptInterface
    public String pickFolder(int volumeIndex) {
        Intent i = null;
        if (Build.VERSION.SDK_INT >= 29 && volumeIndex >= 0) {
            StorageManager sm = (StorageManager) act.getSystemService(Context.STORAGE_SERVICE);
            List<StorageVolume> vols = sm.getStorageVolumes();
            if (volumeIndex < vols.size()) i = vols.get(volumeIndex).createOpenDocumentTreeIntent();
        }
        if (i == null) i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        final Intent fi = i;
        act.runOnUiThread(() -> {
            try {
                act.startActivityForResult(fi, MainActivity.REQ_TREE);
            } catch (Exception e) {
                Toast.makeText(act, "파일 선택기를 열 수 없습니다", Toast.LENGTH_SHORT).show();
            }
        });
        return ok();
    }

    @JavascriptInterface
    public String trees() {
        JSONArray a = new JSONArray();
        for (UriPermission p : act.getContentResolver().getPersistedUriPermissions()) {
            Uri u = p.getUri();
            String name = u.getLastPathSegment();
            a.put(Util.jo("uri", u.toString(), "name", name == null ? u.toString() : name));
        }
        return a.toString();
    }

    @JavascriptInterface
    public String forgetTree(String uri) {
        try {
            act.getContentResolver().releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        return ok();
    }

    /** @param treeUri persisted tree; @param docId document id inside it ("" for the root) */
    @JavascriptInterface
    public String listDir(String treeUri, String docId) {
        try {
            Uri tree = Uri.parse(treeUri);
            String id = docId == null || docId.isEmpty() ? DocumentsContract.getTreeDocumentId(tree) : docId;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            JSONArray a = new JSONArray();
            try (Cursor c = act.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String mime = c.getString(2);
                    a.put(Util.jo("id", c.getString(0), "name", c.getString(1), "mime", mime, "size", c.getLong(3), "modified", c.getLong(4),
                            "dir", DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)));
                }
            }
            return Util.jo("root", DocumentsContract.getTreeDocumentId(tree), "id", id, "items", a).toString();
        } catch (Exception e) {
            return err("폴더를 읽을 수 없습니다: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public String openDoc(String treeUri, String docId, String mime) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), docId);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(doc, mime == null || mime.isEmpty() ? "*/*" : mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.runOnUiThread(() -> {
                try {
                    act.startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(act, "이 파일을 열 수 있는 앱이 없습니다", Toast.LENGTH_LONG).show();
                }
            });
            return ok();
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    /** Copy a USB document to the hub as lesson material (runs in background, reports via onNative('upload')). */
    @JavascriptInterface
    public String uploadDoc(String treeUri, String docId, String name, String token, String cls) {
        new Thread(() -> {
            try {
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), docId);
                long size = -1;
                try (Cursor c = act.getContentResolver().query(doc, new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) size = c.getLong(0);
                }
                HttpURLConnection con = (HttpURLConnection) new URL("http://127.0.0.1:" + DeviceConfig.PORT + "/api/files?kind=material&cls=" + (cls == null ? "" : cls)).openConnection();
                con.setRequestMethod("PUT");
                con.setDoOutput(true);
                if (size >= 0) con.setFixedLengthStreamingMode(size);
                con.setRequestProperty("X-Token", token);
                con.setRequestProperty("X-Filename", java.net.URLEncoder.encode(name, "UTF-8"));
                try (InputStream in = act.getContentResolver().openInputStream(doc); OutputStream out = con.getOutputStream()) {
                    Util.copy(in, out, -1);
                }
                int code = con.getResponseCode();
                InputStream rin = code >= 400 ? con.getErrorStream() : con.getInputStream();
                String body = rin == null ? "{}" : new String(Util.readAll(rin), "UTF-8");
                MainActivity.notifyWeb("upload", Util.jo("ok", code < 400, "body", body, "name", name).toString());
            } catch (Exception e) {
                MainActivity.notifyWeb("upload", Util.jo("ok", false, "error", String.valueOf(e.getMessage()), "name", name).toString());
            }
        }).start();
        return ok();
    }

    /** Download a hub file (lesson material) to cache and open it with a viewer app. */
    @JavascriptInterface
    public String openRemote(String fileId, String name, String mime) {
        new Thread(() -> {
            try {
                File dir = new File(act.getCacheDir(), "open");
                dir.mkdirs();
                File f = new File(dir, Util.safeName(fileId + "_" + name));
                if (!f.exists()) {
                    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + DeviceConfig.PORT + "/api/files/" + fileId).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(60000);
                    if (c.getResponseCode() != 200) throw new Exception("다운로드 실패 (" + c.getResponseCode() + ")");
                    File tmp = new File(dir, f.getName() + ".part");
                    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                        Util.copy(in, out, -1);
                    }
                    if (!tmp.renameTo(f)) throw new Exception("저장 실패");
                }
                Uri u = FilesProvider.uriFor(act, f);
                String type = mime == null || mime.isEmpty() ? Util.mimeFor(name).split(";")[0] : mime.split(";")[0];
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, type)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                act.runOnUiThread(() -> {
                    try {
                        act.startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(act, "이 파일 형식(" + type + ")을 열 수 있는 앱이 없습니다", Toast.LENGTH_LONG).show();
                    }
                });
                MainActivity.notifyWeb("opened", Util.jo("ok", true, "name", name).toString());
            } catch (Exception e) {
                MainActivity.notifyWeb("opened", Util.jo("ok", false, "error", String.valueOf(e.getMessage()), "name", name).toString());
            }
        }).start();
        return ok();
    }

    @JavascriptInterface
    public String openUrl(String url) {
        if (url == null || !(url.toLowerCase(Locale.ROOT).startsWith("http://") || url.toLowerCase(Locale.ROOT).startsWith("https://"))) return err("http(s) 링크만 열 수 있습니다");
        act.runOnUiThread(() -> {
            try {
                act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(act, "웹 브라우저가 없습니다", Toast.LENGTH_LONG).show();
            }
        });
        return ok();
    }

    @JavascriptInterface
    public void reload() {
        act.runOnUiThread(act::recreate);
    }
}
