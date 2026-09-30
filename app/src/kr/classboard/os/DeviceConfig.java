package kr.classboard.os;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Per-device settings that are never synced to other devices. */
public class DeviceConfig {
    public static final int PORT = 8787;
    private final SharedPreferences sp;

    public DeviceConfig(Context c) {
        sp = c.getSharedPreferences("device", Context.MODE_PRIVATE);
        if (!sp.contains("deviceId")) sp.edit().putString("deviceId", Util.randomId()).apply();
    }

    public String deviceId() { return sp.getString("deviceId", ""); }

    /** "" (not set up yet), "hub" or "client". */
    public String role() { return sp.getString("role", ""); }

    public boolean isHub() { return "hub".equals(role()); }

    public String hubUrl() {
        return isHub() ? "http://127.0.0.1:" + PORT : sp.getString("hubUrl", "");
    }

    public String deviceKey() { return sp.getString("deviceKey", ""); }

    public void setDeviceKey(String k) { sp.edit().putString("deviceKey", k).apply(); }

    public String cls() { return sp.getString("cls", ""); }

    public String name() { return sp.getString("name", ""); }

    /** Whole local settings object (auto mode, power, launch rules ...). */
    public JSONObject settings() {
        try {
            return new JSONObject(sp.getString("settings", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public JSONObject toJson() {
        return Util.jo("deviceId", deviceId(), "role", role(), "hubUrl", sp.getString("hubUrl", ""), "cls", cls(), "name", name(),
                "hasKey", !deviceKey().isEmpty(), "settings", settings(), "port", PORT, "ip", Util.localIp());
    }

    public void update(JSONObject o) {
        SharedPreferences.Editor e = sp.edit();
        if (o.has("role")) e.putString("role", o.optString("role"));
        if (o.has("hubUrl")) {
            String u = o.optString("hubUrl").trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            if (!u.isEmpty() && !u.startsWith("http")) u = "http://" + u;
            if (!u.isEmpty() && !u.matches(".*:\\d+$")) u = u + ":" + PORT;
            if (!u.equals(sp.getString("hubUrl", ""))) e.putString("deviceKey", "");
            e.putString("hubUrl", u);
        }
        if (o.has("cls")) e.putString("cls", o.optString("cls"));
        if (o.has("name")) e.putString("name", o.optString("name"));
        if (o.has("settings")) e.putString("settings", o.optJSONObject("settings") == null ? "{}" : o.optJSONObject("settings").toString());
        e.apply();
    }
}
