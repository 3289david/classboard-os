package kr.classboard.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads notice boards (공지사항, 가정통신문) from school homepages built on the education-office
 * homepage platform used by Seoul schools (*.sen.ms.kr, *.sen.hs.kr, *.sen.es.kr ...).
 * Boards are rendered by POST /dggb/module/board/selectBoardListAjax.do and need the session
 * cookie issued when the board page is opened first.
 */
public final class SchoolHomepage {
    private SchoolHomepage() {}

    private static final String UA = "Mozilla/5.0 (Linux; Android) ClassBoardOS/1.0";
    private static final Pattern MENU = Pattern.compile("href=\"/(\\d+)/subMenu\\.do\"[^>]*>\\s*(?:<[^>]+>\\s*)*([^<]{1,40})");
    private static final Pattern INPUT = Pattern.compile("<input[^>]*?name=\"([^\"]+)\"[^>]*?value=\"([^\"]*)\"");
    private static final Pattern ROW = Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL);
    // Two row styles: fnView('bbsId','nttId') for school boards, fnSenView('nttId') for boards shared from the education office.
    private static final Pattern VIEW = Pattern.compile("fnView\\('([^']+)',\\s*'(\\d+)'\\)[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern SEN_VIEW = Pattern.compile("fnSenView\\('(\\d+)'\\)[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern TD = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern TOTAL = Pattern.compile("총\\s*([0-9,]+)\\s*건");

    /** Session-scoped HTTP client that keeps cookies between requests. */
    private static final class Client {
        private final Map<String, String> cookies = new LinkedHashMap<>();

        String request(String url, String form) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            if (!cookies.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : cookies.entrySet()) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(e.getKey()).append('=').append(e.getValue());
                }
                c.setRequestProperty("Cookie", sb.toString());
            }
            if (form != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(form.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            List<String> set = c.getHeaderFields().get("Set-Cookie");
            if (set != null) {
                for (String h : set) {
                    String kv = h.split(";", 2)[0];
                    int eq = kv.indexOf('=');
                    if (eq > 0) cookies.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : new String(Util.readAll(in), StandardCharsets.UTF_8);
            c.disconnect();
            if (code >= 400) throw new IOException("HTTP " + code + " " + url);
            return body;
        }
    }

    /** "joongdong.sen.ms.kr" / "http://joongdong.sen.ms.kr/" -> "https://joongdong.sen.ms.kr" */
    public static String base(String homepage) {
        if (homepage == null) return "";
        String h = homepage.trim();
        if (h.isEmpty()) return "";
        h = h.replaceFirst("^(?i)https?://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        return "https://" + h.toLowerCase(Locale.ROOT);
    }

    private static String text(String html) {
        return Util.htmlToText(html);
    }

    private static String form(Map<String, String> m) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    /** School logo shown in the homepage header (an image whose alt text ends with "로고"). */
    public static String logo(String base) throws IOException {
        String html = new Client().request(base + "/", null);
        Matcher m = Pattern.compile("<img[^>]*src=\"([^\"]+)\"[^>]*alt=\"[^\"]*로고\"").matcher(html);
        if (!m.find()) return "";
        String src = m.group(1).replaceAll(";jsessionid=[^?]*", "");
        return src.startsWith("/") ? base + src : src;
    }

    /** Find notice-type boards in the site menu. Returns [{menuId, name}]. */
    public static JSONArray discover(String base) throws IOException {
        String html = new Client().request(base + "/", null);
        Map<String, String> found = new LinkedHashMap<>();
        Matcher m = MENU.matcher(html);
        while (m.find()) {
            String name = m.group(2).replaceAll("\\s+", " ").trim();
            if (name.isEmpty()) continue;
            if ((name.contains("가정통신문") || name.equals("공지사항") || name.equals("학교 공지") || name.equals("학교공지")) && !found.containsKey(m.group(1))) {
                found.put(m.group(1), name);
            }
        }
        JSONArray out = new JSONArray();
        for (Map.Entry<String, String> e : found.entrySet()) out.put(Util.jo("menuId", e.getKey(), "name", e.getValue()));
        return out;
    }

    private static Map<String, String> boardForm(String page) {
        Map<String, String> f = new LinkedHashMap<>();
        int i = page.indexOf("id=\"boardFrm\"");
        if (i < 0) return f;
        int end = page.indexOf("</form>", i);
        Matcher m = INPUT.matcher(page.substring(i, end < 0 ? Math.min(page.length(), i + 4000) : end));
        while (m.find()) f.put(m.group(1), m.group(2));
        return f;
    }

    /** Latest posts of one board. */
    public static JSONObject board(String base, String menuId, String name, int count) throws IOException {
        Client c = new Client();
        String page = c.request(base + "/" + menuId + "/subMenu.do", null);
        Map<String, String> f = boardForm(page);
        if (!f.containsKey("bbsId")) throw new IOException(name + ": 게시판 형식을 해석하지 못했습니다");
        f.put("customRecordCountPerPage", String.valueOf(count));
        f.put("pageIndex", "1");
        String list = c.request(base + "/dggb/module/board/selectBoardListAjax.do", form(f));
        JSONArray items = new JSONArray();
        Matcher r = ROW.matcher(list);
        while (r.find()) {
            String row = r.group(1);
            Matcher v = VIEW.matcher(row);
            Matcher sv = SEN_VIEW.matcher(row);
            String bbsId, nttId, title;
            boolean sen = false;
            if (v.find()) {
                bbsId = v.group(1);
                nttId = v.group(2);
                title = text(v.group(3));
            } else if (sv.find()) {
                bbsId = f.get("bbsId");
                nttId = sv.group(1);
                title = text(sv.group(2));
                sen = true;
            } else {
                continue;
            }
            java.util.ArrayList<String> tds = new java.util.ArrayList<>();
            Matcher td = TD.matcher(row);
            while (td.find()) tds.add(text(td.group(1)));
            String date = "";
            for (String t : tds) if (t.matches("\\d{4}-\\d{2}-\\d{2}")) date = t;
            items.put(Util.jo(
                    "bbsId", bbsId, "nttId", nttId, "sen", sen, "title", title, "date", date,
                    "author", tds.size() > 2 ? tds.get(2) : "",
                    "pinned", row.contains("flag_notice"),
                    "file", row.contains("첨부파일")));
        }
        Matcher t = TOTAL.matcher(list);
        return Util.jo("menuId", menuId, "name", name, "bbsId", f.get("bbsId"), "bbsTyCode", f.get("bbsTyCode"),
                "total", t.find() ? Integer.parseInt(t.group(1).replace(",", "")) : items.length(), "items", items,
                "url", base + "/" + menuId + "/subMenu.do");
    }

    /** Body text, images and attachments of one post. */
    public static JSONObject detail(String base, String menuId, String bbsId, String nttId, boolean sen) throws IOException {
        Client c = new Client();
        String page = c.request(base + "/" + menuId + "/subMenu.do", null);
        Map<String, String> f = boardForm(page);
        f.put("bbsId", bbsId);
        f.put("nttId", nttId);
        String html = c.request(base + (sen ? "/dggb/module/board/selectBoardSenDetailAjax.do" : "/dggb/module/board/selectBoardDetailAjax.do"), form(f));

        String content = "";
        int ci = html.indexOf("<div class=\"content\">");
        if (ci >= 0) {
            int ce = html.indexOf("</td>", ci);
            content = html.substring(ci, ce < 0 ? html.length() : ce);
        }
        JSONArray images = new JSONArray();
        Matcher im = Pattern.compile("<img[^>]*src=\"([^\"]+)\"").matcher(content);
        while (im.find()) {
            String src = im.group(1);
            if (src.startsWith("/")) src = base + src;
            if (src.startsWith("http")) images.put(src);
        }
        String body = text(content.replaceAll("(?i)<img[^>]*>", ""));

        JSONArray files = new JSONArray();
        Matcher fm = Pattern.compile("serverFileObj\\[\"name\"\\]\\s*=\\s*\"([^\"]*)\";\\s*serverFileObj\\[\"size\"\\]\\s*=\\s*\"(\\d*)\";\\s*serverFileObj\\[\"atchFileId\"\\]\\s*=\\s*\"([^\"]+)\";\\s*serverFileObj\\[\"fileSn\"\\]\\s*=\\s*\"(\\d+)\"").matcher(html);
        while (fm.find()) {
            files.put(Util.jo("name", fm.group(1), "size", fm.group(2).isEmpty() ? 0 : Long.parseLong(fm.group(2)),
                    "url", base + "/dggb/board/boardFile/downFile.do?atchFileId=" + fm.group(3) + "&fileSn=" + fm.group(4)));
        }
        String title = "";
        Matcher tt = Pattern.compile("<th[^>]*>\\s*제목\\s*</th>\\s*<td[^>]*>(.*?)</td>", Pattern.DOTALL).matcher(html);
        if (tt.find()) title = text(tt.group(1));
        return Util.jo("title", title, "body", body, "images", images, "files", files, "url", base + "/" + menuId + "/subMenu.do");
    }
}
