package kr.classboard.os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLDecoder;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import kr.classboard.os.HttpServer.ApiException;
import kr.classboard.os.HttpServer.Request;
import kr.classboard.os.HttpServer.Response;

/**
 * School hub: the single source of truth for notices, alerts, class data and accounts.
 * Runs on the device whose role is "hub"; classroom devices mirror its public state.
 */
public class HubApi {
    private final Store store;
    private final File filesDir;
    private final Object revMonitor = new Object();
    private final Map<String, long[]> loginFails = new ConcurrentHashMap<>();
    private final Map<String, String[]> attTokens = new ConcurrentHashMap<>(); // cls -> [current, previous, rotatedAtMillis]
    private final Map<String, JSONObject> deviceSeen = new ConcurrentHashMap<>();
    private long publicRev = -1;
    private String publicCache;

    private static final String[] OFFICER_TYPES = {"quiet", "clean", "ready", "teacher", "notice"};

    public HubApi(Store store, File filesDir) {
        this.store = store;
        this.filesDir = new File(filesDir, "hubfiles");
        this.filesDir.mkdirs();
        store.addListener(() -> {
            synchronized (revMonitor) {
                revMonitor.notifyAll();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static ApiException err(int s, String m) {
        return new ApiException(s, m);
    }

    private JSONObject session(Request r) {
        String t = r.header("x-token");
        if (t == null || t.isEmpty()) t = r.param("token");
        if (t == null || t.isEmpty()) return null;
        final String tok = t;
        return store.read(root -> {
            JSONObject s = root.optJSONObject("sessions") == null ? null : root.optJSONObject("sessions").optJSONObject(tok);
            if (s == null || s.optLong("exp") < System.currentTimeMillis()) return null;
            return Util.copy(s);
        });
    }

    private JSONObject require(Request r, String... roles) throws ApiException {
        JSONObject s = session(r);
        if (s == null) throw err(401, "로그인이 필요합니다");
        String role = s.optString("role");
        for (String x : roles) if (x.equals(role)) return s;
        throw err(403, "권한이 없습니다");
    }

    private JSONObject requireStaff(Request r) throws ApiException {
        return require(r, "admin", "teacher");
    }

    private JSONObject requireDevice(Request r) throws ApiException {
        String key = r.header("x-device-key");
        if (key == null || key.isEmpty()) throw err(401, "기기 인증이 필요합니다");
        JSONObject d = store.read(root -> {
            JSONObject devs = root.optJSONObject("devices");
            if (devs == null) return null;
            Iterator<String> it = devs.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject x = devs.optJSONObject(id);
                if (x != null && key.equals(x.optString("key"))) return Util.jo("id", id, "name", x.optString("name"), "cls", x.optString("cls"));
            }
            return null;
        });
        if (d == null) throw err(401, "등록되지 않은 기기입니다");
        return d;
    }

    private static String clsOk(String cls) throws ApiException {
        if (cls == null || !cls.matches("\\d{1,2}-\\d{1,2}")) throw err(400, "학급 형식이 올바르지 않습니다 (예: 3-2)");
        return cls;
    }

    private static JSONObject classObj(JSONObject root, String cls) {
        return Util.obj(Util.obj(root, "classes"), cls);
    }

    private static JSONObject officerSettings(JSONObject cl) {
        JSONObject s = cl.optJSONObject("officerSettings");
        JSONObject d = Util.jo("enabled", true, "sound", true, "durationSec", 8, "cooldownSec", 30, "maxPer10min", 6,
                "windows", new JSONArray(), "types", new JSONArray(java.util.Arrays.asList(OFFICER_TYPES)));
        if (s != null) {
            Iterator<String> it = s.keys();
            while (it.hasNext()) {
                String k = it.next();
                Util.put(d, k, s.opt(k));
            }
        }
        return d;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private void checkLoginRate(String ip) throws ApiException {
        long[] f = loginFails.get(ip);
        if (f != null && f[0] >= 8 && now() - f[1] < 5 * 60_000L) throw err(429, "로그인 시도가 너무 많습니다. 5분 후 다시 시도하세요");
    }

    private void loginFailed(String ip) {
        long[] f = loginFails.get(ip);
        if (f == null || now() - f[1] > 5 * 60_000L) f = new long[]{0, now()};
        f[0]++;
        f[1] = now();
        loginFails.put(ip, f);
    }

    private String newSession(JSONObject sess) {
        String tok = Util.randomToken();
        Util.put(sess, "exp", now() + 30L * 24 * 3600_000L);
        store.write(root -> {
            JSONObject ss = Util.obj(root, "sessions");
            // purge expired sessions
            JSONArray names = ss.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                JSONObject x = ss.optJSONObject(names.optString(i));
                if (x == null || x.optLong("exp") < now()) ss.remove(names.optString(i));
            }
            Util.put(ss, tok, sess);
        });
        return tok;
    }

    // ------------------------------------------------------------------ public state

    private String buildPublic(JSONObject root) {
        JSONObject p = new JSONObject();
        String today = Util.today();
        for (String k : new String[]{"config", "notices", "alerts", "homework", "lessons", "exams", "events", "contacts", "rooms",
                "overrides", "lessonNow"}) {
            if (root.has(k)) Util.put(p, k, root.opt(k));
        }
        JSONObject cfg = p.optJSONObject("config");
        if (cfg != null) {
            cfg = Util.copy(cfg);
            cfg.remove("neisKey");
            Util.put(cfg, "hasNeisKey", root.optJSONObject("config").optString("neisKey").length() > 0);
            Util.put(p, "config", cfg);
        }
        Util.put(p, "setupDone", root.optJSONArray("users") != null && root.optJSONArray("users").length() > 0);

        JSONArray users = new JSONArray();
        JSONArray ru = root.optJSONArray("users");
        for (int i = 0; ru != null && i < ru.length(); i++) {
            JSONObject u = ru.optJSONObject(i);
            users.put(Util.jo("id", u.optString("id"), "name", u.optString("name"), "role", u.optString("role"), "classes", u.optJSONArray("classes")));
        }
        Util.put(p, "users", users);

        JSONObject classes = new JSONObject();
        JSONObject rc = root.optJSONObject("classes");
        JSONObject att = root.optJSONObject("attendance");
        JSONArray cn = rc == null ? null : rc.names();
        for (int i = 0; cn != null && i < cn.length(); i++) {
            String cls = cn.optString(i);
            JSONObject c = rc.optJSONObject(cls);
            JSONArray roster = c.optJSONArray("roster");
            JSONObject pc = Util.jo("officerSettings", officerSettings(c), "subjectInfo", c.optJSONObject("subjectInfo"),
                    "homeroom", c.optString("homeroom"), "room", c.optString("room"), "rosterSize", roster == null ? 0 : roster.length(),
                    "officerCount", c.optJSONArray("officers") == null ? 0 : c.optJSONArray("officers").length());
            JSONArray nums = new JSONArray();
            for (int j = 0; roster != null && j < roster.length(); j++) nums.put(roster.optJSONObject(j).optInt("no"));
            Util.put(pc, "numbers", nums);
            // attendance summary for today
            JSONObject day = att == null || att.optJSONObject(cls) == null ? null : att.optJSONObject(cls).optJSONObject(today);
            int present = 0, absent = 0, late = 0, early = 0, marked = 0;
            if (day != null) {
                Iterator<String> it = day.keys();
                while (it.hasNext()) {
                    JSONObject rec = day.optJSONObject(it.next());
                    if (rec == null) continue;
                    marked++;
                    switch (rec.optString("status")) {
                        case "present": present++; break;
                        case "absent": absent++; break;
                        case "late": late++; break;
                        case "early": early++; break;
                        default: break;
                    }
                }
            }
            Util.put(pc, "attToday", Util.jo("present", present, "absent", absent, "late", late, "early", early, "marked", marked,
                    "total", roster == null ? 0 : roster.length()));
            Util.put(classes, cls, pc);
        }
        Util.put(p, "classes", classes);

        // officer alerts without the identity of who pressed
        JSONArray ca = new JSONArray();
        JSONArray rca = root.optJSONArray("classAlerts");
        for (int i = 0; rca != null && i < rca.length(); i++) {
            JSONObject a = rca.optJSONObject(i);
            if (now() - a.optLong("at") > 3600_000L) continue;
            ca.put(Util.jo("id", a.optString("id"), "cls", a.optString("cls"), "type", a.optString("type"), "at", a.optLong("at")));
        }
        Util.put(p, "classAlerts", ca);

        // notice read counts
        JSONArray notices = p.optJSONArray("notices");
        if (notices != null) {
            JSONArray nn = new JSONArray();
            for (int i = 0; i < notices.length(); i++) {
                JSONObject n = Util.copy(notices.optJSONObject(i));
                JSONObject reads = n.optJSONObject("reads");
                JSONObject counts = new JSONObject();
                JSONArray rn = reads == null ? null : reads.names();
                for (int j = 0; rn != null && j < rn.length(); j++) Util.put(counts, rn.optString(j), reads.optJSONArray(rn.optString(j)).length());
                n.remove("reads");
                Util.put(n, "readCounts", counts);
                nn.put(n);
            }
            Util.put(p, "notices", nn);
        }

        JSONArray files = new JSONArray();
        JSONArray rf = root.optJSONArray("files");
        for (int i = 0; rf != null && i < rf.length(); i++) {
            JSONObject f = rf.optJSONObject(i);
            files.put(f);
        }
        Util.put(p, "files", files);

        JSONObject devices = new JSONObject();
        JSONObject rd = root.optJSONObject("devices");
        JSONArray dn = rd == null ? null : rd.names();
        for (int i = 0; dn != null && i < dn.length(); i++) {
            JSONObject d = rd.optJSONObject(dn.optString(i));
            Util.put(devices, dn.optString(i), Util.jo("name", d.optString("name"), "cls", d.optString("cls")));
        }
        Util.put(p, "devices", devices);
        return p.toString();
    }

    private Response state(Request r) throws Exception {
        long since = -2;
        try {
            if (r.param("since") != null) since = Long.parseLong(r.param("since"));
        } catch (NumberFormatException ignored) {
        }
        String devId = r.header("x-device-id");
        if (devId != null) deviceSeen.put(devId, Util.jo("at", now(), "ip", r.remoteIp, "name", r.header("x-device-name") == null ? "" : URLDecoder.decode(r.header("x-device-name"), "UTF-8")));
        if (since == store.rev() && r.param("wait") != null) {
            long deadline = now() + 20000;
            synchronized (revMonitor) {
                while (store.rev() == since && now() < deadline) revMonitor.wait(Math.max(1, deadline - now()));
            }
        }
        long rev = store.rev();
        if (rev == since) return Response.json(Util.jo("rev", rev, "same", true));
        String body;
        synchronized (this) {
            if (publicRev != rev || publicCache == null) {
                publicCache = store.read(this::buildPublic);
                publicRev = rev;
            }
            body = publicCache;
        }
        return Response.json("{\"rev\":" + rev + ",\"state\":" + body + "}");
    }

    // ------------------------------------------------------------------ routing

    public Response handle(Request r) throws Exception {
        String p = r.path;
        String m = r.method;
        switch (p) {
            case "/api/ping":
                return Response.json(Util.jo("hub", true, "rev", store.rev(), "school", store.read(root -> root.optJSONObject("config") == null ? "" :
                        root.optJSONObject("config").optJSONObject("school") == null ? "" : root.optJSONObject("config").optJSONObject("school").optString("name"))));
            case "/api/state":
                return state(r);
            case "/api/setup/init":
                return setupInit(r);
            case "/api/login":
                return login(r);
            case "/api/logout": {
                String t = r.header("x-token");
                if (t != null) store.write(root -> Util.obj(root, "sessions").remove(t));
                return Response.ok();
            }
            case "/api/me":
                return me(r);
            case "/api/me/pin":
                return changePin(r);
            case "/api/me/comci":
                return setComciTeacher(r);
            case "/api/admin/users":
                return "GET".equals(m) ? listUsers(r) : saveUser(r);
            case "/api/admin/config":
                return saveConfig(r);
            case "/api/admin/devices":
                return listDevices(r);
            case "/api/admin/emergency":
                return emergency(r);
            case "/api/admin/emergency/close":
                return emergencyClose(r);
            case "/api/admin/neis-search":
                requireSetupOrAdmin(r);
                return Response.json(Util.jo("items", Neis.searchSchool(r.param("q"), store.read(root -> root.optJSONObject("config") == null ? "" : root.optJSONObject("config").optString("neisKey")))));
            case "/api/admin/comci-search":
                requireSetupOrAdmin(r);
                return Response.json(Util.jo("items", Comcigan.search(r.param("q"))));
            case "/api/admin/geocode":
                requireSetupOrAdmin(r);
                return Response.json(Util.jo("items", DataFetcher.geocode(r.param("q"))));
            case "/api/officer/press":
                return officerPress(r);
            case "/api/officer/status":
                return officerStatus(r);
            case "/api/teacher/calls":
                return teacherCalls(r);
            case "/api/teacher/calls/ack":
                return teacherCallAck(r);
            case "/api/files":
                return upload(r);
            case "/api/device/hello":
                return deviceHello(r);
            case "/api/device/att-token":
                return attToken(r);
            case "/api/device/ack":
                return deviceAck(r);
            case "/api/student/roster":
                return studentRoster(r);
            case "/api/student/read":
                return studentRead(r);
            case "/api/student/attend":
                return studentAttend(r);
            default:
                break;
        }
        if (p.startsWith("/api/files/")) return download(r, p.substring("/api/files/".length()));
        if (p.startsWith("/api/admin/users/") && "DELETE".equals(m)) return deleteUser(r, p.substring("/api/admin/users/".length()));
        if (p.startsWith("/api/c/")) return crud(r, p.substring("/api/c/".length()));
        if (p.startsWith("/api/class/")) return classRoute(r, p.substring("/api/class/".length()));
        return null;
    }

    private void requireSetupOrAdmin(Request r) throws ApiException {
        boolean noUsers = store.read(root -> root.optJSONArray("users") == null || root.optJSONArray("users").length() == 0);
        if (noUsers && r.isLocal()) return;
        require(r, "admin");
    }

    // ------------------------------------------------------------------ accounts

    private Response setupInit(Request r) throws Exception {
        if (!r.isLocal()) throw err(403, "초기 설정은 허브 기기에서만 가능합니다");
        JSONObject b = r.json();
        String name = b.optString("name").trim();
        String pin = b.optString("pin");
        if (name.isEmpty() || pin.length() < 4) throw err(400, "이름과 4자리 이상 PIN을 입력하세요");
        final boolean[] done = {false};
        String salt = Util.randomId();
        String uid = Util.randomId();
        store.write(root -> {
            JSONArray users = Util.arr(root, "users");
            if (users.length() > 0) return;
            users.put(Util.jo("id", uid, "name", name, "role", "admin", "salt", salt, "pinHash", Util.hashPin(salt, pin), "classes", new JSONArray(), "createdAt", now()));
            done[0] = true;
        });
        if (!done[0]) throw err(409, "이미 관리자가 등록되어 있습니다");
        String tok = newSession(Util.jo("uid", uid, "role", "admin", "name", name));
        return Response.json(Util.jo("token", tok, "user", Util.jo("id", uid, "name", name, "role", "admin")));
    }

    private Response login(Request r) throws Exception {
        checkLoginRate(r.remoteIp);
        JSONObject b = r.json();
        String kind = b.optString("kind", "staff");
        String name = b.optString("name").trim();
        String pin = b.optString("pin");
        if ("officer".equals(kind)) {
            String cls = clsOk(b.optString("cls"));
            JSONObject off = store.read(root -> {
                JSONObject c = root.optJSONObject("classes") == null ? null : root.optJSONObject("classes").optJSONObject(cls);
                JSONArray os = c == null ? null : c.optJSONArray("officers");
                for (int i = 0; os != null && i < os.length(); i++) {
                    JSONObject o = os.optJSONObject(i);
                    if (o.optString("name").equals(name) && Util.hashPin(o.optString("salt"), pin).equals(o.optString("pinHash"))) return Util.copy(o);
                }
                return null;
            });
            if (off == null) {
                loginFailed(r.remoteIp);
                throw err(401, "학급, 이름 또는 PIN이 올바르지 않습니다");
            }
            String tok = newSession(Util.jo("role", "officer", "name", name, "cls", cls, "oid", off.optString("id"), "title", off.optString("title")));
            return Response.json(Util.jo("token", tok, "user", Util.jo("role", "officer", "name", name, "cls", cls, "title", off.optString("title"))));
        }
        JSONObject u = store.read(root -> {
            JSONArray users = root.optJSONArray("users");
            for (int i = 0; users != null && i < users.length(); i++) {
                JSONObject x = users.optJSONObject(i);
                if (x.optString("name").equals(name) && Util.hashPin(x.optString("salt"), pin).equals(x.optString("pinHash"))) return Util.copy(x);
            }
            return null;
        });
        if (u == null) {
            loginFailed(r.remoteIp);
            throw err(401, "이름 또는 PIN이 올바르지 않습니다");
        }
        String tok = newSession(Util.jo("uid", u.optString("id"), "role", u.optString("role"), "name", u.optString("name")));
        return Response.json(Util.jo("token", tok, "user", publicUser(u)));
    }

    private static JSONObject publicUser(JSONObject u) {
        return Util.jo("id", u.optString("id"), "name", u.optString("name"), "role", u.optString("role"),
                "classes", u.optJSONArray("classes") == null ? new JSONArray() : u.optJSONArray("classes"), "comciTeacher", u.optInt("comciTeacher", 0));
    }

    private Response me(Request r) throws ApiException {
        JSONObject s = session(r);
        if (s == null) throw err(401, "로그인이 필요합니다");
        if ("officer".equals(s.optString("role"))) return Response.json(Util.jo("user", Util.jo("role", "officer", "name", s.optString("name"), "cls", s.optString("cls"), "title", s.optString("title"))));
        JSONObject u = store.read(root -> {
            JSONObject x = Util.findById(Util.arr(root, "users"), s.optString("uid"));
            return x == null ? null : publicUser(x);
        });
        if (u == null) throw err(401, "계정이 삭제되었습니다");
        return Response.json(Util.jo("user", u));
    }

    private Response changePin(Request r) throws Exception {
        JSONObject s = requireStaff(r);
        JSONObject b = r.json();
        String np = b.optString("newPin");
        if (np.length() < 4) throw err(400, "PIN은 4자리 이상이어야 합니다");
        final boolean[] ok = {false};
        store.write(root -> {
            JSONObject u = Util.findById(Util.arr(root, "users"), s.optString("uid"));
            if (u != null && Util.hashPin(u.optString("salt"), b.optString("oldPin")).equals(u.optString("pinHash"))) {
                String salt = Util.randomId();
                Util.put(u, "salt", salt);
                Util.put(u, "pinHash", Util.hashPin(salt, np));
                ok[0] = true;
            }
        });
        if (!ok[0]) throw err(401, "기존 PIN이 올바르지 않습니다");
        return Response.ok();
    }

    private Response setComciTeacher(Request r) throws Exception {
        JSONObject s = requireStaff(r);
        int th = r.json().optInt("th", 0);
        store.write(root -> {
            JSONObject u = Util.findById(Util.arr(root, "users"), s.optString("uid"));
            if (u != null) Util.put(u, "comciTeacher", th);
        });
        return Response.ok();
    }

    private Response listUsers(Request r) throws ApiException {
        require(r, "admin");
        return Response.json(Util.jo("users", store.read(root -> {
            JSONArray out = new JSONArray();
            JSONArray users = Util.arr(root, "users");
            for (int i = 0; i < users.length(); i++) out.put(publicUser(users.optJSONObject(i)));
            return out;
        })));
    }

    private Response saveUser(Request r) throws Exception {
        require(r, "admin");
        JSONObject b = r.json();
        String name = b.optString("name").trim();
        String role = b.optString("role");
        if (name.isEmpty()) throw err(400, "이름을 입력하세요");
        if (!role.equals("admin") && !role.equals("teacher")) throw err(400, "역할이 올바르지 않습니다");
        String pin = b.optString("pin");
        String id = b.optString("id");
        if (id.isEmpty() && pin.length() < 4) throw err(400, "새 계정은 4자리 이상 PIN이 필요합니다");
        final String[] error = {null};
        store.write(root -> {
            JSONArray users = Util.arr(root, "users");
            for (int i = 0; i < users.length(); i++) {
                JSONObject x = users.optJSONObject(i);
                if (x.optString("name").equals(name) && !x.optString("id").equals(id)) {
                    error[0] = "같은 이름의 계정이 이미 있습니다";
                    return;
                }
            }
            JSONObject u = id.isEmpty() ? null : Util.findById(users, id);
            if (u == null) {
                u = Util.jo("id", Util.randomId(), "createdAt", now());
                users.put(u);
            }
            Util.put(u, "name", name);
            Util.put(u, "role", role);
            Util.put(u, "classes", b.optJSONArray("classes") == null ? new JSONArray() : b.optJSONArray("classes"));
            if (pin.length() >= 4) {
                String salt = Util.randomId();
                Util.put(u, "salt", salt);
                Util.put(u, "pinHash", Util.hashPin(salt, pin));
            }
        });
        if (error[0] != null) throw err(409, error[0]);
        return Response.ok();
    }

    private Response deleteUser(Request r, String id) throws ApiException {
        JSONObject s = require(r, "admin");
        if (id.equals(s.optString("uid"))) throw err(400, "자기 자신은 삭제할 수 없습니다");
        store.write(root -> {
            JSONArray users = Util.arr(root, "users");
            int i = Util.indexOfId(users, id);
            if (i >= 0) users.remove(i);
            JSONObject ss = Util.obj(root, "sessions");
            JSONArray names = ss.names();
            for (int j = 0; names != null && j < names.length(); j++) {
                if (id.equals(ss.optJSONObject(names.optString(j)).optString("uid"))) ss.remove(names.optString(j));
            }
        });
        return Response.ok();
    }

    private Response saveConfig(Request r) throws Exception {
        requireSetupOrAdmin(r);
        JSONObject b = r.json();
        String[] allowed = {"school", "comci", "periodMinutes", "bell", "lat", "lon", "locationName", "neisKey", "feeds", "displayName", "homepageUrl", "homepageBoards"};
        store.write(root -> {
            JSONObject cfg = Util.obj(root, "config");
            for (String k : allowed) {
                if (!b.has(k)) continue;
                Object v = b.opt(k);
                if (v == null || v == JSONObject.NULL) cfg.remove(k);
                else Util.put(cfg, k, v);
            }
        });
        return Response.ok();
    }

    private Response listDevices(Request r) throws ApiException {
        requireStaff(r);
        JSONObject out = store.read(root -> {
            JSONObject o = new JSONObject();
            JSONObject devs = root.optJSONObject("devices");
            JSONArray names = devs == null ? null : devs.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String id = names.optString(i);
                JSONObject d = devs.optJSONObject(id);
                JSONObject seen = deviceSeen.get(id);
                Util.put(o, id, Util.jo("name", d.optString("name"), "cls", d.optString("cls"), "registeredAt", d.optLong("registeredAt"),
                        "lastSeen", seen == null ? 0 : seen.optLong("at"), "ip", seen == null ? "" : seen.optString("ip")));
            }
            return o;
        });
        return Response.json(Util.jo("devices", out));
    }

    // ------------------------------------------------------------------ emergency

    private Response emergency(Request r) throws Exception {
        JSONObject s = require(r, "admin");
        JSONObject b = r.json();
        String text = b.optString("text").trim();
        if (text.isEmpty()) throw err(400, "안내 내용을 입력하세요");
        String target = b.optString("target", "all");
        JSONObject a = Util.jo("id", Util.randomId(), "title", b.optString("title", "긴급 안내").trim(), "text", text, "level", b.optString("level", "emergency"),
                "target", target, "at", now(), "author", s.optString("name"), "active", true, "acks", new JSONObject());
        store.write(root -> {
            JSONArray al = Util.arr(root, "alerts");
            al.put(a);
            Util.trim(al, 100);
        });
        return Response.json(a);
    }

    private Response emergencyClose(Request r) throws Exception {
        require(r, "admin");
        String id = r.json().optString("id");
        store.write(root -> {
            JSONObject a = Util.findById(Util.arr(root, "alerts"), id);
            if (a != null) {
                Util.put(a, "active", false);
                Util.put(a, "closedAt", now());
            }
        });
        return Response.ok();
    }

    private Response deviceAck(Request r) throws Exception {
        JSONObject d = requireDevice(r);
        String id = r.json().optString("alertId");
        store.write(root -> {
            JSONObject a = Util.findById(Util.arr(root, "alerts"), id);
            if (a != null) Util.put(Util.obj(a, "acks"), d.optString("id"), Util.jo("at", now(), "name", d.optString("name"), "cls", d.optString("cls")));
        });
        return Response.ok();
    }

    // ------------------------------------------------------------------ devices

    private Response deviceHello(Request r) throws Exception {
        JSONObject b = r.json();
        String id = b.optString("deviceId");
        if (id.isEmpty()) throw err(400, "deviceId 누락");
        String given = r.header("x-device-key");
        final String[] key = {null};
        final boolean[] denied = {false};
        store.write(root -> {
            JSONObject devs = Util.obj(root, "devices");
            JSONObject d = devs.optJSONObject(id);
            if (d != null && !d.optString("key").isEmpty() && !d.optString("key").equals(given)) {
                // Existing device re-registering without its key: only allowed from the hub itself.
                if (!r.isLocal()) {
                    denied[0] = true;
                    return;
                }
            }
            if (d == null) {
                d = Util.jo("registeredAt", now());
                Util.put(devs, id, d);
            }
            if (d.optString("key").isEmpty() || !d.optString("key").equals(given)) Util.put(d, "key", Util.randomToken());
            Util.put(d, "name", b.optString("name"));
            Util.put(d, "cls", b.optString("cls"));
            key[0] = d.optString("key");
        });
        if (denied[0]) throw err(403, "이미 등록된 기기 ID입니다. 관리자에게 문의하세요");
        return Response.json(Util.jo("deviceKey", key[0]));
    }

    private String[] rotateAtt(String cls) {
        String[] t = attTokens.get(cls);
        if (t == null || now() - Long.parseLong(t[2]) > 20000) {
            String prev = t == null ? "" : t[0];
            t = new String[]{Util.randomId().substring(0, 10), prev, String.valueOf(now())};
            attTokens.put(cls, t);
        }
        return t;
    }

    private Response attToken(Request r) throws ApiException {
        requireDevice(r);
        String cls = clsOk(r.param("cls"));
        String[] t = rotateAtt(cls);
        return Response.json(Util.jo("token", t[0], "expiresIn", 20 - (now() - Long.parseLong(t[2])) / 1000));
    }

    // ------------------------------------------------------------------ officers

    private Response officerStatus(Request r) throws ApiException {
        JSONObject s = require(r, "officer");
        String cls = s.optString("cls");
        return Response.json(store.read(root -> {
            JSONObject c = classObj(root, cls);
            JSONObject st = officerSettings(c);
            long blocked = c.optJSONObject("officerBlock") == null ? 0 : c.optJSONObject("officerBlock").optLong(s.optString("oid"));
            long last = c.optLong("lastOfficerPress");
            return Util.jo("settings", st, "blockedUntil", blocked, "cooldownUntil", last + st.optInt("cooldownSec") * 1000L, "now", now(),
                    "inWindow", inWindow(st.optJSONArray("windows")));
        }));
    }

    private static boolean inWindow(JSONArray windows) {
        if (windows == null || windows.length() == 0) return true;
        int nm = Util.nowMinutes();
        Calendar c = Calendar.getInstance(Util.KST);
        int dow = c.get(Calendar.DAY_OF_WEEK); // 1=Sunday
        for (int i = 0; i < windows.length(); i++) {
            JSONObject w = windows.optJSONObject(i);
            if (w == null) continue;
            JSONArray days = w.optJSONArray("days");
            if (days != null && days.length() > 0) {
                boolean dayOk = false;
                for (int j = 0; j < days.length(); j++) if (days.optInt(j) == dow) dayOk = true;
                if (!dayOk) continue;
            }
            int a = Util.parseHm(w.optString("from"));
            int b = Util.parseHm(w.optString("to"));
            if (a >= 0 && b >= 0 && nm >= a && nm < b) return true;
        }
        return false;
    }

    private Response officerPress(Request r) throws Exception {
        JSONObject s = require(r, "officer");
        String type = r.json().optString("type");
        boolean valid = false;
        for (String t : OFFICER_TYPES) if (t.equals(type)) valid = true;
        if (!valid) throw err(400, "알 수 없는 알림 종류");
        String cls = s.optString("cls");
        String oid = s.optString("oid");
        final String[] error = {null};
        final int[] status = {200};
        store.write(root -> {
            JSONObject c = classObj(root, cls);
            JSONObject st = officerSettings(c);
            // officer still exists?
            if (Util.findById(Util.arr(c, "officers"), oid) == null) { error[0] = "임원 등록이 해제되었습니다"; status[0] = 403; return; }
            if (!st.optBoolean("enabled", true)) { error[0] = "선생님이 임원 버튼을 비활성화했습니다"; status[0] = 403; return; }
            boolean typeOn = false;
            JSONArray types = st.optJSONArray("types");
            for (int i = 0; types != null && i < types.length(); i++) if (type.equals(types.optString(i))) typeOn = true;
            if (!typeOn) { error[0] = "이 버튼은 선생님이 비활성화했습니다"; status[0] = 403; return; }
            if (!inWindow(st.optJSONArray("windows"))) { error[0] = "지금은 사용 가능한 시간이 아닙니다"; status[0] = 403; return; }
            JSONObject block = Util.obj(c, "officerBlock");
            if (block.optLong(oid) > now()) { error[0] = "반복 사용으로 잠시 제한되었습니다"; status[0] = 429; return; }
            long cooldown = st.optInt("cooldownSec", 30) * 1000L;
            if (now() - c.optLong("lastOfficerPress") < cooldown) {
                error[0] = "잠시 후 다시 누를 수 있습니다 (" + ((cooldown - (now() - c.optLong("lastOfficerPress"))) / 1000 + 1) + "초)";
                status[0] = 429;
                return;
            }
            JSONArray log = Util.arr(root, "classAlerts");
            int recent = 0;
            for (int i = 0; i < log.length(); i++) {
                JSONObject x = log.optJSONObject(i);
                if (oid.equals(x.optString("oid")) && now() - x.optLong("at") < 10 * 60_000L) recent++;
            }
            if (recent >= st.optInt("maxPer10min", 6)) {
                Util.put(block, oid, now() + 10 * 60_000L);
                error[0] = "10분 동안 너무 많이 눌러 10분간 제한됩니다";
                status[0] = 429;
                return;
            }
            Util.put(c, "lastOfficerPress", now());
            JSONObject a = Util.jo("id", Util.randomId(), "cls", cls, "type", type, "at", now(), "oid", oid, "name", s.optString("name"), "title", s.optString("title"));
            log.put(a);
            Util.trim(log, 500);
            if ("teacher".equals(type)) {
                JSONArray calls = Util.arr(root, "teacherCalls");
                calls.put(Util.jo("id", a.optString("id"), "cls", cls, "at", now(), "by", s.optString("name"), "acked", false));
                Util.trim(calls, 200);
            }
        });
        if (error[0] != null) throw err(status[0], error[0]);
        return Response.ok();
    }

    private Response teacherCalls(Request r) throws ApiException {
        JSONObject s = requireStaff(r);
        JSONArray mine = store.read(root -> {
            JSONObject u = Util.findById(Util.arr(root, "users"), s.optString("uid"));
            JSONArray classes = u == null ? null : u.optJSONArray("classes");
            JSONArray out = new JSONArray();
            JSONArray calls = Util.arr(root, "teacherCalls");
            for (int i = calls.length() - 1; i >= 0; i--) {
                JSONObject c = calls.optJSONObject(i);
                if (now() - c.optLong("at") > 3 * 3600_000L) continue;
                boolean match = classes == null || classes.length() == 0;
                for (int j = 0; classes != null && j < classes.length(); j++) if (classes.optString(j).equals(c.optString("cls"))) match = true;
                if (match) out.put(c);
            }
            return out;
        });
        return Response.json(Util.jo("calls", mine));
    }

    private Response teacherCallAck(Request r) throws Exception {
        JSONObject s = requireStaff(r);
        String id = r.json().optString("id");
        store.write(root -> {
            JSONObject c = Util.findById(Util.arr(root, "teacherCalls"), id);
            if (c != null) {
                Util.put(c, "acked", true);
                Util.put(c, "ackedBy", s.optString("name"));
                Util.put(c, "ackedAt", now());
            }
        });
        return Response.ok();
    }

    // ------------------------------------------------------------------ generic collections

    private static final Map<String, String[]> COLLECTIONS = new HashMap<>();

    static {
        // collection -> {minimum role ("teacher" = teacher or admin, "admin")}
        COLLECTIONS.put("notices", new String[]{"teacher"});
        COLLECTIONS.put("homework", new String[]{"teacher"});
        COLLECTIONS.put("lessons", new String[]{"teacher"});
        COLLECTIONS.put("exams", new String[]{"teacher"});
        COLLECTIONS.put("events", new String[]{"teacher"});
        COLLECTIONS.put("overrides", new String[]{"teacher"});
        COLLECTIONS.put("files", new String[]{"teacher"});
        COLLECTIONS.put("contacts", new String[]{"admin"});
        COLLECTIONS.put("rooms", new String[]{"admin"});
    }

    private Response crud(Request r, String rest) throws Exception {
        String[] parts = rest.split("/");
        String coll = parts[0];
        String[] rule = COLLECTIONS.get(coll);
        if (rule == null) return null;
        JSONObject s = "admin".equals(rule[0]) ? require(r, "admin") : requireStaff(r);
        if ("DELETE".equals(r.method) && parts.length == 2) {
            String id = parts[1];
            store.write(root -> {
                JSONArray a = Util.arr(root, coll);
                int i = Util.indexOfId(a, id);
                if (i >= 0) a.remove(i);
            });
            if ("files".equals(coll)) new File(filesDir, Util.safeName(id)).delete();
            return Response.ok();
        }
        if (!"POST".equals(r.method)) throw err(405, "POST 또는 DELETE만 지원합니다");
        JSONObject item = r.json();
        if ("files".equals(coll)) throw err(400, "파일은 업로드 API를 사용하세요");
        validate(coll, item);
        String id = item.optString("id");
        final JSONObject[] saved = {null};
        store.write(root -> {
            JSONArray a = Util.arr(root, coll);
            JSONObject existing = id.isEmpty() ? null : Util.findById(a, id);
            JSONObject x = existing == null ? Util.jo("id", Util.randomId(), "createdAt", now(), "author", s.optString("name")) : existing;
            Iterator<String> it = item.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.equals("id") || k.equals("createdAt") || k.equals("author") || k.equals("reads") || k.equals("responses")) continue;
                Util.put(x, k, item.opt(k));
            }
            Util.put(x, "updatedAt", now());
            Util.put(x, "editor", s.optString("name"));
            if (existing == null) a.put(x);
            Util.trim(a, 2000);
            saved[0] = Util.copy(x);
        });
        return Response.json(saved[0]);
    }

    private static void validate(String coll, JSONObject it) throws ApiException {
        switch (coll) {
            case "notices":
                if (it.optString("title").trim().isEmpty()) throw err(400, "제목을 입력하세요");
                String scope = it.optString("scope", "school");
                if (!scope.equals("school") && !scope.equals("grade") && !scope.equals("class")) throw err(400, "공지 범위가 올바르지 않습니다");
                if (scope.equals("class")) clsOk(it.optString("target"));
                if (scope.equals("grade") && !it.optString("target").matches("\\d{1,2}")) throw err(400, "학년을 입력하세요");
                break;
            case "homework":
                clsOk(it.optString("cls"));
                if (it.optString("text").trim().isEmpty()) throw err(400, "내용을 입력하세요");
                if (!it.optString("date").matches("\\d{4}-\\d{2}-\\d{2}")) throw err(400, "날짜를 입력하세요");
                break;
            case "lessons":
                clsOk(it.optString("cls"));
                if (!it.optString("date").matches("\\d{4}-\\d{2}-\\d{2}")) throw err(400, "날짜를 입력하세요");
                if (it.optInt("period", 0) < 1) throw err(400, "교시를 입력하세요");
                break;
            case "overrides":
                clsOk(it.optString("cls"));
                if (!it.optString("date").matches("\\d{4}-\\d{2}-\\d{2}")) throw err(400, "날짜를 입력하세요");
                if (it.optInt("period", 0) < 1) throw err(400, "교시를 입력하세요");
                break;
            case "exams":
            case "events":
                if (!it.optString("date").matches("\\d{4}-\\d{2}-\\d{2}")) throw err(400, "날짜를 입력하세요");
                if (it.optString(coll.equals("exams") ? "subject" : "title").trim().isEmpty()) throw err(400, coll.equals("exams") ? "과목을 입력하세요" : "제목을 입력하세요");
                break;
            case "contacts":
                if (it.optString("name").trim().isEmpty()) throw err(400, "이름(부서명)을 입력하세요");
                break;
            case "rooms":
                if (it.optString("name").trim().isEmpty()) throw err(400, "교실 이름을 입력하세요");
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ class-private data

    private Response classRoute(Request r, String rest) throws Exception {
        String[] parts = rest.split("/");
        if (parts.length < 2) return null;
        String cls = clsOk(parts[0]);
        String action = parts[1];
        JSONObject s = requireStaff(r);
        switch (action) {
            case "private":
                return Response.json(store.read(root -> {
                    JSONObject c = classObj(root, cls);
                    JSONArray officers = new JSONArray();
                    JSONArray os = c.optJSONArray("officers");
                    for (int i = 0; os != null && i < os.length(); i++) {
                        JSONObject o = os.optJSONObject(i);
                        officers.put(Util.jo("id", o.optString("id"), "name", o.optString("name"), "title", o.optString("title"),
                                "blockedUntil", c.optJSONObject("officerBlock") == null ? 0 : c.optJSONObject("officerBlock").optLong(o.optString("id"))));
                    }
                    JSONArray log = new JSONArray();
                    JSONArray ca = Util.arr(root, "classAlerts");
                    for (int i = ca.length() - 1; i >= 0 && log.length() < 100; i--) {
                        JSONObject a = ca.optJSONObject(i);
                        if (cls.equals(a.optString("cls"))) log.put(a);
                    }
                    JSONObject att = root.optJSONObject("attendance") == null ? new JSONObject() : root.optJSONObject("attendance").optJSONObject(cls);
                    JSONObject reads = new JSONObject();
                    JSONArray ns = Util.arr(root, "notices");
                    for (int i = 0; i < ns.length(); i++) {
                        JSONObject n = ns.optJSONObject(i);
                        JSONObject rd = n.optJSONObject("reads");
                        if (rd != null && rd.optJSONArray(cls) != null) Util.put(reads, n.optString("id"), rd.optJSONArray(cls));
                    }
                    return Util.jo("roster", c.optJSONArray("roster") == null ? new JSONArray() : c.optJSONArray("roster"), "officers", officers,
                            "officerSettings", officerSettings(c), "officerLog", log, "attendance", att == null ? new JSONObject() : att,
                            "noticeReads", reads,
                            "subjectInfo", c.optJSONObject("subjectInfo") == null ? new JSONObject() : c.optJSONObject("subjectInfo"),
                            "homeroom", c.optString("homeroom"), "room", c.optString("room"));
                }));
            case "roster": {
                JSONArray students = r.json().optJSONArray("students");
                if (students == null) throw err(400, "학생 목록이 필요합니다");
                JSONArray clean = new JSONArray();
                java.util.HashSet<Integer> seen = new java.util.HashSet<>();
                for (int i = 0; i < students.length(); i++) {
                    JSONObject st = students.optJSONObject(i);
                    if (st == null) continue;
                    int no = st.optInt("no", 0);
                    if (no <= 0 || !seen.add(no)) continue;
                    clean.put(Util.jo("no", no, "name", st.optString("name").trim()));
                }
                store.write(root -> Util.put(classObj(root, cls), "roster", clean));
                return Response.ok();
            }
            case "officers": {
                JSONArray list = r.json().optJSONArray("officers");
                if (list == null) throw err(400, "임원 목록이 필요합니다");
                final String[] error = {null};
                store.write(root -> {
                    JSONObject c = classObj(root, cls);
                    JSONArray old = Util.arr(c, "officers");
                    JSONArray next = new JSONArray();
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject o = list.optJSONObject(i);
                        if (o == null || o.optString("name").trim().isEmpty()) continue;
                        JSONObject prev = o.optString("id").isEmpty() ? null : Util.findById(old, o.optString("id"));
                        JSONObject n = prev == null ? Util.jo("id", Util.randomId()) : Util.copy(prev);
                        Util.put(n, "name", o.optString("name").trim());
                        Util.put(n, "title", o.optString("title", "회장"));
                        String pin = o.optString("pin");
                        if (pin.length() >= 4) {
                            String salt = Util.randomId();
                            Util.put(n, "salt", salt);
                            Util.put(n, "pinHash", Util.hashPin(salt, pin));
                        } else if (prev == null) {
                            error[0] = o.optString("name") + ": 새 임원은 4자리 이상 PIN이 필요합니다";
                            return;
                        }
                        next.put(n);
                    }
                    Util.put(c, "officers", next);
                    // revoke sessions of removed officers
                    JSONObject ss = Util.obj(root, "sessions");
                    JSONArray names = ss.names();
                    for (int j = 0; names != null && j < names.length(); j++) {
                        JSONObject x = ss.optJSONObject(names.optString(j));
                        if ("officer".equals(x.optString("role")) && cls.equals(x.optString("cls")) && Util.findById(next, x.optString("oid")) == null) ss.remove(names.optString(j));
                    }
                });
                if (error[0] != null) throw err(400, error[0]);
                return Response.ok();
            }
            case "officer-settings": {
                JSONObject b = r.json();
                store.write(root -> {
                    JSONObject c = classObj(root, cls);
                    JSONObject st = Util.obj(c, "officerSettings");
                    for (String k : new String[]{"enabled", "sound", "durationSec", "cooldownSec", "maxPer10min", "windows", "types"}) if (b.has(k)) Util.put(st, k, b.opt(k));
                    if (b.optBoolean("clearBlocks")) c.remove("officerBlock");
                });
                return Response.ok();
            }
            case "info": {
                JSONObject b = r.json();
                store.write(root -> {
                    JSONObject c = classObj(root, cls);
                    if (b.has("subjectInfo")) Util.put(c, "subjectInfo", b.optJSONObject("subjectInfo"));
                    if (b.has("homeroom")) Util.put(c, "homeroom", b.optString("homeroom"));
                    if (b.has("room")) Util.put(c, "room", b.optString("room"));
                });
                return Response.ok();
            }
            case "attendance": {
                JSONObject b = r.json();
                String date = b.optString("date", Util.today());
                if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) throw err(400, "날짜 형식 오류");
                JSONArray items = b.optJSONArray("items");
                if (items == null) items = new JSONArray().put(b);
                final JSONArray fItems = items;
                store.write(root -> {
                    JSONObject day = Util.obj(Util.obj(Util.obj(root, "attendance"), cls), date);
                    for (int i = 0; i < fItems.length(); i++) {
                        JSONObject it = fItems.optJSONObject(i);
                        String no = String.valueOf(it.optInt("no"));
                        String st = it.optString("status");
                        if (st.isEmpty()) day.remove(no);
                        else Util.put(day, no, Util.jo("status", st, "note", it.optString("note"), "by", s.optString("name"), "at", now()));
                    }
                });
                return Response.ok();
            }
            case "lesson-now": {
                JSONObject b = r.json();
                store.write(root -> {
                    JSONObject ln = Util.obj(root, "lessonNow");
                    if (b.optString("lessonId").isEmpty()) ln.remove(cls);
                    else Util.put(ln, cls, Util.jo("id", b.optString("lessonId"), "at", now(), "by", s.optString("name")));
                });
                return Response.ok();
            }
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ students (no account, class roster numbers)

    private Response studentRoster(Request r) throws ApiException {
        String cls = clsOk(r.param("cls"));
        return Response.json(store.read(root -> {
            JSONObject c = root.optJSONObject("classes") == null ? null : root.optJSONObject("classes").optJSONObject(cls);
            JSONArray nums = new JSONArray();
            JSONArray roster = c == null ? null : c.optJSONArray("roster");
            for (int i = 0; roster != null && i < roster.length(); i++) nums.put(roster.optJSONObject(i).optInt("no"));
            return Util.jo("numbers", nums);
        }));
    }

    private static boolean inRoster(JSONObject root, String cls, int no) {
        JSONObject c = root.optJSONObject("classes") == null ? null : root.optJSONObject("classes").optJSONObject(cls);
        JSONArray roster = c == null ? null : c.optJSONArray("roster");
        for (int i = 0; roster != null && i < roster.length(); i++) if (roster.optJSONObject(i).optInt("no") == no) return true;
        return false;
    }

    private Response studentRead(Request r) throws Exception {
        JSONObject b = r.json();
        String cls = clsOk(b.optString("cls"));
        int no = b.optInt("no");
        String id = b.optString("noticeId");
        final String[] error = {null};
        store.write(root -> {
            if (!inRoster(root, cls, no)) { error[0] = "학급 명단에 없는 번호입니다"; return; }
            JSONObject n = Util.findById(Util.arr(root, "notices"), id);
            if (n == null) { error[0] = "공지를 찾을 수 없습니다"; return; }
            JSONArray list = Util.arr(Util.obj(n, "reads"), cls);
            for (int i = 0; i < list.length(); i++) if (list.optInt(i) == no) return;
            list.put(no);
        });
        if (error[0] != null) throw err(400, error[0]);
        return Response.ok();
    }

    private Response studentAttend(Request r) throws Exception {
        JSONObject b = r.json();
        String cls = clsOk(b.optString("cls"));
        int no = b.optInt("no");
        String t = b.optString("t");
        String[] cur = attTokens.get(cls);
        if (cur == null || t.isEmpty() || !(t.equals(cur[0]) || t.equals(cur[1]))) throw err(403, "QR 코드가 만료되었습니다. 전자칠판의 QR을 다시 스캔하세요");
        final String[] result = {null};
        store.write(root -> {
            if (!inRoster(root, cls, no)) { result[0] = "notinroster"; return; }
            JSONObject day = Util.obj(Util.obj(Util.obj(root, "attendance"), cls), Util.today());
            JSONObject rec = day.optJSONObject(String.valueOf(no));
            if (rec != null) { result[0] = "already:" + rec.optString("status"); return; }
            Util.put(day, String.valueOf(no), Util.jo("status", "present", "note", "QR", "by", "QR", "at", now(), "ip", r.remoteIp));
            result[0] = "ok";
        });
        if ("notinroster".equals(result[0])) throw err(400, "학급 명단에 없는 번호입니다");
        return Response.json(Util.jo("result", result[0]));
    }

    // ------------------------------------------------------------------ files

    private Response upload(Request r) throws Exception {
        if (!"PUT".equals(r.method) && !"POST".equals(r.method)) throw err(405, "PUT으로 업로드하세요");
        String fname = r.header("x-filename");
        fname = fname == null ? "file" : Util.safeName(URLDecoder.decode(fname, "UTF-8"));
        if (r.contentLength <= 0) throw err(400, "빈 파일입니다");
        if (r.contentLength > 300L * 1024 * 1024) throw err(413, "파일이 너무 큽니다 (최대 300MB)");
        JSONObject s = requireStaff(r);
        JSONObject meta = Util.jo("kind", "material", "cls", r.param("cls") == null ? "" : r.param("cls"), "owner", s.optString("name"));
        String id = Util.randomId();
        File out = new File(filesDir, id);
        long n;
        try (FileOutputStream fo = new FileOutputStream(out)) {
            n = Util.copy(r.in, fo, r.contentLength);
        }
        if (n != r.contentLength) {
            out.delete();
            throw err(400, "업로드가 중간에 끊겼습니다");
        }
        Util.put(meta, "id", id);
        Util.put(meta, "name", fname);
        Util.put(meta, "size", n);
        Util.put(meta, "mime", Util.mimeFor(fname));
        Util.put(meta, "at", now());
        store.write(root -> Util.arr(root, "files").put(meta));
        return Response.json(meta);
    }

    private Response download(Request r, String id) throws Exception {
        JSONObject meta = store.read(root -> {
            JSONObject f = Util.findById(Util.arr(root, "files"), id);
            return f == null ? null : Util.copy(f);
        });
        if (meta == null) throw err(404, "파일이 없습니다");
        File f = new File(filesDir, Util.safeName(id));
        if (!f.exists()) throw err(404, "파일이 삭제되었습니다");
        Response res = Response.file(f, meta.optString("mime", "application/octet-stream"));
        String disp = r.param("inline") != null ? "inline" : "attachment";
        res.headers.put("Content-Disposition", disp + "; filename*=UTF-8''" + java.net.URLEncoder.encode(meta.optString("name"), "UTF-8").replace("+", "%20"));
        res.headers.put("Cache-Control", "private, max-age=86400");
        return res;
    }

    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
