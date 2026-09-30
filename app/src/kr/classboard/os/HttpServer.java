package kr.classboard.os;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small dependency-free HTTP/1.1 server (one request per connection). */
public class HttpServer {
    private static final String TAG = "HttpServer";

    public interface Handler {
        Response handle(Request req) throws Exception;
    }

    public static class ApiException extends Exception {
        public final int status;

        public ApiException(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }

    public static class Request {
        public String method;
        public String path;
        public String rawQuery = "";
        public final Map<String, String> headers = new HashMap<>();
        public final Map<String, String> params = new HashMap<>();
        public String remoteIp;
        public long contentLength;
        public InputStream in;
        private byte[] body;

        public byte[] body() throws IOException {
            if (body == null) {
                if (contentLength > 32L * 1024 * 1024) throw new IOException("body too large");
                body = new byte[(int) Math.max(0, contentLength)];
                int off = 0;
                while (off < body.length) {
                    int n = in.read(body, off, body.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            return body;
        }

        public String bodyString() throws IOException {
            return new String(body(), StandardCharsets.UTF_8);
        }

        public JSONObject json() throws IOException, ApiException {
            String s = bodyString().trim();
            if (s.isEmpty()) return new JSONObject();
            try {
                return new JSONObject(s);
            } catch (JSONException e) {
                throw new ApiException(400, "잘못된 JSON 요청");
            }
        }

        public String header(String k) {
            return headers.get(k.toLowerCase(Locale.ROOT));
        }

        public String param(String k) {
            return params.get(k);
        }

        public boolean isLocal() {
            return "127.0.0.1".equals(remoteIp) || "::1".equals(remoteIp) || "0:0:0:0:0:0:0:1".equals(remoteIp);
        }
    }

    public static class Response {
        public int status = 200;
        public String type = "application/json; charset=utf-8";
        public byte[] body;
        public InputStream stream;
        public long length = -1;
        public final Map<String, String> headers = new LinkedHashMap<>();

        public static Response json(Object o) {
            Response r = new Response();
            r.body = String.valueOf(o).getBytes(StandardCharsets.UTF_8);
            return r;
        }

        public static Response ok() {
            return json("{\"ok\":true}");
        }

        public static Response error(int status, String msg) {
            JSONObject o = new JSONObject();
            Util.put(o, "error", msg);
            Response r = json(o);
            r.status = status;
            return r;
        }

        public static Response bytes(byte[] b, String type) {
            Response r = new Response();
            r.body = b;
            r.type = type;
            return r;
        }

        public static Response file(File f, String type) throws IOException {
            Response r = new Response();
            r.stream = new FileInputStream(f);
            r.length = f.length();
            r.type = type;
            return r;
        }
    }

    private final Handler handler;
    private ServerSocket server;
    private final ExecutorService pool = new ThreadPoolExecutor(4, 320, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
    private volatile boolean running;

    public HttpServer(Handler handler) {
        this.handler = handler;
    }

    public synchronized void start(int port) throws IOException {
        if (running) return;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    pool.execute(() -> serve(s));
                } catch (IOException e) {
                    if (running) Log.w(TAG, "accept", e);
                }
            }
        }, "http-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
            if (bo.size() > 16384) throw new IOException("header line too long");
        }
        if (c < 0 && bo.size() == 0) return null;
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void parseQuery(String q, Map<String, String> out) {
        if (q == null || q.isEmpty()) return;
        for (String part : q.split("&")) {
            if (part.isEmpty()) continue;
            int i = part.indexOf('=');
            try {
                String k = URLDecoder.decode(i < 0 ? part : part.substring(0, i), "UTF-8");
                String v = i < 0 ? "" : URLDecoder.decode(part.substring(i + 1), "UTF-8");
                out.put(k, v);
            } catch (Exception ignored) {
            }
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            String line = readLine(in);
            if (line == null) return;
            String[] rl = line.split(" ");
            if (rl.length < 2) return;
            Request req = new Request();
            req.method = rl[0].toUpperCase(Locale.ROOT);
            String target = rl[1];
            int qi = target.indexOf('?');
            try {
                req.path = URLDecoder.decode(qi < 0 ? target : target.substring(0, qi), "UTF-8");
            } catch (IllegalArgumentException e) {
                req.path = qi < 0 ? target : target.substring(0, qi);
            }
            if (qi >= 0) {
                req.rawQuery = target.substring(qi + 1);
                parseQuery(req.rawQuery, req.params);
            }
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int ci = h.indexOf(':');
                if (ci > 0) req.headers.put(h.substring(0, ci).trim().toLowerCase(Locale.ROOT), h.substring(ci + 1).trim());
            }
            String cl = req.headers.get("content-length");
            req.contentLength = cl == null ? 0 : Long.parseLong(cl.trim());
            req.in = in;
            req.remoteIp = s.getInetAddress().getHostAddress();

            Response res;
            if ("OPTIONS".equals(req.method)) {
                res = new Response();
                res.status = 204;
                res.body = new byte[0];
            } else {
                try {
                    res = handler.handle(req);
                    if (res == null) res = Response.error(404, "없는 경로입니다");
                } catch (ApiException e) {
                    res = Response.error(e.status, e.getMessage());
                } catch (Exception e) {
                    Log.w(TAG, "handler " + req.path, e);
                    res = Response.error(500, String.valueOf(e.getMessage()));
                }
            }
            write(out, res, "HEAD".equals(req.method));
        } catch (Exception e) {
            Log.d(TAG, "serve", e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 429: return "Too Many Requests";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return code >= 500 ? "Server Error" : "OK";
        }
    }

    private static void write(OutputStream out, Response r, boolean head) throws IOException {
        long len = r.body != null ? r.body.length : r.length;
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n");
        sb.append("Content-Type: ").append(r.type).append("\r\n");
        if (len >= 0) sb.append("Content-Length: ").append(len).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Headers: Content-Type, X-Token, X-Device-Key, X-Filename\r\n");
        sb.append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n");
        if (!r.headers.containsKey("Cache-Control")) sb.append("Cache-Control: no-store\r\n");
        for (Map.Entry<String, String> e : r.headers.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (!head) {
            if (r.body != null) out.write(r.body);
            else if (r.stream != null) Util.copy(r.stream, out, -1);
        }
        if (r.stream != null) r.stream.close();
        out.flush();
    }
}
