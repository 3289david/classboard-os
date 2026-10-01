package kr.classboard.server;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.json.JSONObject;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Turns attachments (PDF, HWP, HWPX, Office files) into page images so low-end boards only show pictures.
 * PDF is rendered with PDFBox; other formats are converted to PDF by LibreOffice when the server has it
 * (HWP needs the H2Orestart extension, which the Docker image installs).
 */
final class DocService {
    private static final String TAG = "Docs";
    private static final int MAX_PAGES = 80;
    private static final long MAX_BYTES = 40L * 1024 * 1024;
    private static final long CACHE_BYTES = 600L * 1024 * 1024;

    private final File dir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "doc-convert");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, String> working = new ConcurrentHashMap<>(); // id -> status text
    private final Map<String, String> failed = new ConcurrentHashMap<>();

    DocService(File dataDir) {
        this.dir = new File(dataDir, "docs");
        if (!dir.isDirectory()) dir.mkdirs();
        System.setProperty("java.awt.headless", "true");
    }

    static String id(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return Util.hex(md.digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    static boolean allowedUrl(String url) {
        try {
            URL u = new URL(url);
            String h = u.getHost().toLowerCase(Locale.ROOT);
            return ("https".equals(u.getProtocol()) || "http".equals(u.getProtocol()))
                    && (h.endsWith(".sen.ms.kr") || h.endsWith(".sen.hs.kr") || h.endsWith(".sen.es.kr") || h.endsWith(".sen.go.kr"));
        } catch (Exception e) {
            return false;
        }
    }

    static String ext(String name) {
        int i = name == null ? -1 : name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    static boolean supported(String name) {
        return Arrays.asList("pdf", "hwp", "hwpx", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "odt", "odp", "ods", "rtf", "txt").contains(ext(name));
    }

    private File folder(String id) {
        return new File(dir, id);
    }

    /** Status of a document: ready (with page count), working, or error. Starts the conversion if needed. */
    JSONObject status(String id, String name, Source src) {
        File meta = new File(folder(id), "meta.json");
        String m = Util.readFile(meta);
        if (m != null) {
            folder(id).setLastModified(System.currentTimeMillis());
            JSONObject o = new JSONObject(m);
            Util.put(o, "status", "ready");
            Util.put(o, "id", id);
            return o;
        }
        if (failed.containsKey(id)) return Util.jo("status", "error", "id", id, "error", failed.get(id));
        if (src != null && working.putIfAbsent(id, "대기 중") == null) {
            worker.submit(() -> convert(id, name, src));
        }
        String st = working.get(id);
        if (st == null) return Util.jo("status", "error", "id", id, "error", "변환할 문서가 없습니다");
        return Util.jo("status", "working", "id", id, "step", st);
    }

    File page(String id, int p) {
        File f = new File(folder(id), "page-" + p + ".jpg");
        return f.isFile() ? f : null;
    }

    interface Source {
        byte[] bytes() throws IOException;
    }

    private void convert(String id, String name, Source src) {
        File tmp = null;
        try {
            working.put(id, "내려받는 중");
            byte[] data = src.bytes();
            if (data.length > MAX_BYTES) throw new IOException("파일이 너무 큽니다 (40MB 초과)");
            tmp = Files.createTempDirectory("doc").toFile();
            File in = new File(tmp, "in." + (ext(name).isEmpty() ? "bin" : ext(name)));
            Files.write(in.toPath(), data);
            File pdf = in;
            boolean isPdf = data.length > 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
            if (!isPdf) {
                working.put(id, "PDF로 바꾸는 중");
                pdf = toPdf(in, tmp);
            }
            working.put(id, "쪽 그리는 중");
            File out = folder(id);
            out.mkdirs();
            int pages;
            try (PDDocument doc = PDDocument.load(pdf)) {
                PDFRenderer r = new PDFRenderer(doc);
                pages = Math.min(MAX_PAGES, doc.getNumberOfPages());
                for (int i = 0; i < pages; i++) {
                    PDRectangle box = doc.getPage(i).getCropBox();
                    float scale = 1500f / Math.max(1f, Math.max(box.getWidth(), box.getHeight()));
                    BufferedImage img = r.renderImage(i, scale, ImageType.RGB);
                    writeJpeg(img, new File(out, "page-" + (i + 1) + ".jpg"));
                    working.put(id, "쪽 그리는 중 (" + (i + 1) + "/" + pages + ")");
                }
                Util.writeFileAtomic(new File(out, "meta.json"), Util.jo("name", name, "pages", pages, "total", doc.getNumberOfPages(),
                        "at", System.currentTimeMillis()).toString().getBytes(StandardCharsets.UTF_8));
            }
            trimCache();
        } catch (Throwable e) {
            L.w(TAG, "convert " + name, e instanceof Exception ? e : new Exception(e));
            failed.put(id, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            working.remove(id);
            if (tmp != null) deleteTree(tmp);
        }
    }

    /** LibreOffice (soffice) headless conversion; HWP/HWPX use the H2Orestart extension in the server's profile. */
    private static File toPdf(File in, File tmp) throws Exception {
        String soffice = OfficeSetup.find();
        String kind = ext(in.getName()).toUpperCase(Locale.ROOT);
        if (soffice == null) {
            throw new IOException(OfficeSetup.state.contains("설치 중") ? "서버가 문서 변환 프로그램을 설치하는 중입니다. 잠시 후 다시 열어 주세요."
                    : "서버에서 " + kind + " 파일을 변환할 수 없습니다 (" + OfficeSetup.state + ")");
        }
        boolean hwpFile = "HWP".equals(kind) || "HWPX".equals(kind);
        if (hwpFile && !OfficeSetup.hwp) throw new IOException("서버의 HWP 변환 기능이 아직 준비되지 않았습니다 (" + OfficeSetup.state + ")");
        File profile = OfficeSetup.profile();
        profile.mkdirs();
        ProcessBuilder pb = new ProcessBuilder(soffice, "-env:UserInstallation=" + profile.toURI(), "--headless", "--norestore",
                "--convert-to", "pdf", "--outdir", tmp.getAbsolutePath(), in.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(tmp, "lo.log"));
        Process p = pb.start();
        if (!p.waitFor(150, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("문서 변환이 너무 오래 걸립니다");
        }
        File out = new File(tmp, in.getName().replaceAll("\\.[^.]+$", "") + ".pdf");
        if (!out.isFile()) throw new IOException("이 " + kind + " 문서를 변환하지 못했습니다");
        return out;
    }

    static boolean canConvertOffice() {
        return OfficeSetup.ready;
    }

    private static void writeJpeg(BufferedImage img, File f) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.82f);
        File t = new File(f.getPath() + ".tmp");
        try (ImageOutputStream os = ImageIO.createImageOutputStream(t)) {
            w.setOutput(os);
            w.write(null, new IIOImage(img, null, null), p);
        } finally {
            w.dispose();
        }
        if (!t.renameTo(f)) throw new IOException("save " + f);
    }

    private void trimCache() {
        File[] all = dir.listFiles(File::isDirectory);
        if (all == null) return;
        long total = 0;
        for (File d : all) total += size(d);
        if (total <= CACHE_BYTES) return;
        Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File d : all) {
            if (total <= CACHE_BYTES * 0.8) break;
            total -= size(d);
            deleteTree(d);
        }
    }

    private static long size(File d) {
        long s = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) s += f.isDirectory() ? size(f) : f.length();
        return s;
    }

    private static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }
}
