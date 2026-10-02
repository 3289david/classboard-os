package kr.classboard.os;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Per-device settings of this classroom board. Every board talks to the school data server. */
public class DeviceConfig {
    public static final int PORT = 8787;
    /** 중동중학교 전자칠판 데이터 서버 */
    public static final String SERVER = "https://jdmsosserver.krl.kr";
    private final SharedPreferences sp;

    public DeviceConfig(Context c) {
        sp = c.getSharedPreferences("device", Context.MODE_PRIVATE);
        if (!sp.contains("deviceId")) sp.edit().putString("deviceId", Util.randomId()).apply();
    }

    public String deviceId() { return sp.getString("deviceId", ""); }

    /** First-run setup (class + installer PIN) done. */
    public boolean isSetUp() { return sp.getBoolean("setUp", false); }

    public boolean isHub() { return false; }

    public String role() { return isSetUp() ? "client" : ""; }

    /** Data server address; the built-in server unless overridden in 설정 → 서버 (for testing). */
    public String hubUrl() {
        String u = sp.getString("serverUrl", "");
        return u.isEmpty() ? SERVER : u;
    }

    public String deviceKey() { return sp.getString("deviceKey", ""); }

    public void setDeviceKey(String k) { sp.edit().putString("deviceKey", k).apply(); }

    public String cls() { return sp.getString("cls", ""); }

    public String name() { return sp.getString("name", ""); }

    public JSONObject settings() {
        try {
            return new JSONObject(sp.getString("settings", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Home-screen preferences anyone at the board may change: dock apps, search engine, lite mode. */
    public JSONObject home() {
        try {
            return new JSONObject(sp.getString("home", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public void setHome(JSONObject o) {
        JSONObject h = home();
        org.json.JSONArray dock = o.optJSONArray("dock");
        if (dock != null) {
            org.json.JSONArray clean = new org.json.JSONArray();
            for (int i = 0; i < dock.length() && clean.length() < 6; i++) {
                String id = dock.optString(i);
                if (id.matches("[a-z]{2,20}|pkg:[A-Za-z0-9_.]{1,200}")) clean.put(id);
            }
            Util.put(h, "dock", clean);
        }
        if (o.has("search") && o.optString("search").matches("naver|google|daum")) Util.put(h, "search", o.optString("search"));
        if (o.has("lite") && o.optString("lite").matches("auto|on|off")) Util.put(h, "lite", o.optString("lite"));
        if (o.has("preClass")) Util.put(h, "preClass", o.optBoolean("preClass"));
        if (o.has("preClassSound")) Util.put(h, "preClassSound", o.optBoolean("preClassSound"));
        if (o.has("preClassMin")) Util.put(h, "preClassMin", Math.max(1, Math.min(10, o.optInt("preClassMin", 2))));
        sp.edit().putString("home", h.toString()).apply();
    }

    // ---------------------------------------------------------------- installer PIN (protects 설정 → 관리)

    public boolean hasPin() { return !sp.getString("pinHash", "").isEmpty(); }

    public boolean checkPin(String pin) {
        if (pin == null || !hasPin()) return false;
        return Util.hashPin(sp.getString("pinSalt", ""), pin).equals(sp.getString("pinHash", ""));
    }

    public void setPin(String pin) {
        String salt = Util.randomId();
        sp.edit().putString("pinSalt", salt).putString("pinHash", Util.hashPin(salt, pin)).apply();
    }

    public JSONObject toJson() {
        return Util.jo("deviceId", deviceId(), "role", role(), "setUp", isSetUp(), "hubUrl", hubUrl(), "defaultServer", SERVER,
                "serverOverride", !sp.getString("serverUrl", "").isEmpty(), "cls", cls(), "name", name(), "hasPin", hasPin(),
                "hasKey", !deviceKey().isEmpty(), "settings", settings(), "home", home(), "port", PORT, "ip", Util.localIp());
    }

    /** @return true when the server address changed */
    public boolean update(JSONObject o) {
        SharedPreferences.Editor e = sp.edit();
        boolean serverChanged = false;
        if (o.has("serverUrl")) {
            String u = o.optString("serverUrl").trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            if (!u.isEmpty() && !u.startsWith("http")) u = "https://" + u;
            if (!u.equals(sp.getString("serverUrl", ""))) {
                serverChanged = true;
                e.putString("deviceKey", "");
            }
            e.putString("serverUrl", u);
        }
        if (o.has("cls")) e.putString("cls", o.optString("cls"));
        if (o.has("name")) e.putString("name", o.optString("name"));
        if (o.has("settings")) e.putString("settings", o.optJSONObject("settings") == null ? "{}" : o.optJSONObject("settings").toString());
        if (o.optBoolean("setUp")) e.putBoolean("setUp", true);
        e.apply();
        return serverChanged;
    }
}
