package kr.classboard.os;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class Util {
    private Util() {}

    public static final TimeZone KST = TimeZone.getTimeZone("Asia/Seoul");
    private static final SecureRandom RNG = new SecureRandom();

    public static String randomId() {
        byte[] b = new byte[9];
        RNG.nextBytes(b);
        return hex(b);
    }

    public static String randomToken() {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        return hex(b);
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String hashPin(String salt, String pin) {
        return sha256(salt + ":" + pin);
    }

    public static SimpleDateFormat fmt(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.KOREA);
        f.setTimeZone(KST);
        return f;
    }

    public static String today() {
        return fmt("yyyy-MM-dd").format(new Date());
    }

    public static String dateOffset(int days) {
        Calendar c = Calendar.getInstance(KST);
        c.add(Calendar.DAY_OF_MONTH, days);
        return fmt("yyyy-MM-dd").format(c.getTime());
    }

    public static String ymd(String isoDate) {
        return isoDate.replace("-", "");
    }

    public static String isoFromYmd(String ymd) {
        if (ymd == null || ymd.length() != 8) return ymd;
        return ymd.substring(0, 4) + "-" + ymd.substring(4, 6) + "-" + ymd.substring(6, 8);
    }

    /** Minutes since midnight in KST. */
    public static int nowMinutes() {
        Calendar c = Calendar.getInstance(KST);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    public static int parseHm(String hm) {
        if (hm == null) return -1;
        String[] p = hm.trim().split(":");
        if (p.length != 2) return -1;
        try {
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    public static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[65536];
        long total = 0;
        while (limit < 0 || total < limit) {
            int want = limit < 0 ? buf.length : (int) Math.min(buf.length, limit - total);
            int n = in.read(buf, 0, want);
            if (n < 0) break;
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    public static String readFile(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public static void writeFileAtomic(File f, byte[] data) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("rename failed: " + f);
        }
    }

    public static byte[] httpGetBytes(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ClassBoardOS/1.0");
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] b = in == null ? new byte[0] : readAll(in);
            if (code >= 400) throw new IOException("HTTP " + code + " " + url);
            return b;
        } finally {
            c.disconnect();
        }
    }

    public static String httpGet(String url, Charset cs, int timeoutMs) throws IOException {
        return new String(httpGetBytes(url, timeoutMs), cs);
    }

    public static String httpGet(String url) throws IOException {
        return httpGet(url, StandardCharsets.UTF_8, 15000);
    }

    public static JSONArray arr(JSONObject o, String k) {
        JSONArray a = o.optJSONArray(k);
        if (a == null) {
            a = new JSONArray();
            put(o, k, a);
        }
        return a;
    }

    public static JSONObject obj(JSONObject o, String k) {
        JSONObject x = o.optJSONObject(k);
        if (x == null) {
            x = new JSONObject();
            put(o, k, x);
        }
        return x;
    }

    public static JSONObject put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    public static JSONObject jo(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, String.valueOf(kv[i]), kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]);
        return o;
    }

    public static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public static int indexOfId(JSONArray a, String id) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && id.equals(x.optString("id"))) return i;
        }
        return -1;
    }

    public static JSONObject findById(JSONArray a, String id) {
        int i = indexOfId(a, id);
        return i < 0 ? null : a.optJSONObject(i);
    }

    /** Keep at most max newest entries (entries are appended in chronological order). */
    public static void trim(JSONArray a, int max) {
        while (a.length() > max) a.remove(0);
    }

    public static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("en")) return a.getHostAddress();
                    }
                }
            }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".hwp")) return "application/x-hwp";
        if (n.endsWith(".hwpx")) return "application/hwp+zip";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    public static String safeName(String name) {
        if (name == null || name.isEmpty()) return "file";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        return s.length() > 120 ? s.substring(s.length() - 120) : s;
    }
}
