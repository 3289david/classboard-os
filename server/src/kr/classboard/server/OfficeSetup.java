package kr.classboard.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Makes sure the server can convert HWP / Office files: installs LibreOffice (Linux, apt or dnf, as root)
 * when it is missing and adds the H2Orestart extension (HWP import) to the server's own LibreOffice profile.
 * Runs once in the background at start. Set AUTO_INSTALL_OFFICE=0 to turn it off.
 */
final class OfficeSetup {
    private OfficeSetup() {}

    static final String H2O_VERSION = "v0.7.14";
    private static final String H2O_URL = "https://github.com/ebandal/H2Orestart/releases/download/" + H2O_VERSION + "/H2Orestart.oxt";

    static volatile String state = "확인 전";
    static volatile boolean ready;
    static volatile boolean hwp;
    private static File profile;

    /** LibreOffice profile shared by all conversions (the HWP extension lives here). */
    static File profile() {
        return profile;
    }

    static void start(File dataDir) {
        profile = new File(dataDir, "lo-profile");
        Thread t = new Thread(() -> {
            try {
                run(dataDir);
            } catch (Throwable e) {
                state = "설정 실패: " + e.getMessage();
                L.w("Office", "setup", e instanceof Exception ? (Exception) e : new Exception(e));
            }
        }, "office-setup");
        t.setDaemon(true);
        t.start();
    }

    private static void run(File dataDir) throws Exception {
        String soffice = find();
        if (soffice == null) {
            if ("0".equals(System.getenv("AUTO_INSTALL_OFFICE"))) {
                state = "LibreOffice 없음 (자동 설치 꺼짐)";
                return;
            }
            install();
            soffice = find();
            if (soffice == null) return;
        }
        ready = true;
        state = "LibreOffice 준비됨";
        // HWP import extension, installed into our own profile (works without root)
        File marker = new File(profile, ".h2orestart-" + H2O_VERSION);
        if (marker.isFile()) {
            hwp = true;
            state = "LibreOffice + HWP 변환 준비됨";
            return;
        }
        state = "HWP 변환 확장 설치 중";
        File oxt = new File(dataDir, "H2Orestart-" + H2O_VERSION + ".oxt");
        if (!oxt.isFile()) Files.write(oxt.toPath(), Util.httpGetBytes(H2O_URL, 120000));
        File unopkg = new File(new File(soffice).getParentFile(), isWindows() ? "unopkg.exe" : "unopkg");
        if (!unopkg.canExecute()) unopkg = new File("/usr/bin/unopkg");
        profile.mkdirs();
        int code = exec(Arrays.asList(unopkg.getPath(), "add", "-f", "--suppress-license", "-env:UserInstallation=" + profile.toURI(), oxt.getPath()), 300);
        if (code != 0) {
            state = "LibreOffice 준비됨 · HWP 확장 설치 실패 (자바 런타임 확인 필요)";
            return;
        }
        Files.write(marker.toPath(), new byte[0]);
        hwp = true;
        state = "LibreOffice + HWP 변환 준비됨";
    }

    private static void install() throws Exception {
        if (isWindows() || !new File("/proc").isDirectory()) {
            state = "LibreOffice 없음 (이 운영체제에서는 직접 설치해 주세요)";
            return;
        }
        boolean root = "root".equals(System.getProperty("user.name"));
        if (!root) {
            state = "LibreOffice 없음 (root 권한이 없어 자동 설치하지 못했습니다)";
            return;
        }
        state = "LibreOffice 설치 중 (몇 분 걸립니다)";
        L.i("Office", "installing LibreOffice");
        if (new File("/usr/bin/apt-get").canExecute()) {
            exec(Arrays.asList("apt-get", "update"), 600);
            int c = exec(Arrays.asList("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "-y", "--no-install-recommends",
                    "libreoffice-writer-nogui", "libreoffice-calc-nogui", "libreoffice-impress-nogui", "libreoffice-java-common",
                    "default-jre-headless", "fonts-nanum", "fonts-noto-cjk"), 1800);
            if (c != 0) {
                // older releases have no -nogui packages
                c = exec(Arrays.asList("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "-y", "--no-install-recommends",
                        "libreoffice-writer", "libreoffice-calc", "libreoffice-impress", "libreoffice-java-common", "default-jre-headless", "fonts-nanum"), 1800);
            }
            if (c != 0) state = "LibreOffice 설치 실패 (apt-get)";
        } else if (new File("/usr/bin/dnf").canExecute()) {
            int c = exec(Arrays.asList("dnf", "install", "-y", "libreoffice-writer", "libreoffice-calc", "libreoffice-impress",
                    "java-17-openjdk-headless", "google-noto-sans-cjk-ttc-fonts"), 1800);
            if (c != 0) state = "LibreOffice 설치 실패 (dnf)";
        } else {
            state = "LibreOffice 없음 (apt-get · dnf가 없어 직접 설치해 주세요)";
        }
    }

    static String find() {
        String env = System.getenv("SOFFICE");
        for (String c : new String[]{env, "/usr/bin/soffice", "/usr/lib/libreoffice/program/soffice", "/opt/libreoffice/program/soffice",
                "C:\\Program Files\\LibreOffice\\program\\soffice.exe"}) {
            if (c != null && new File(c).canExecute()) return c;
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static int exec(List<String> cmd, int timeoutSec) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        File log = File.createTempFile("office-setup", ".log");
        pb.redirectOutput(log);
        Process p = pb.start();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        if (p.exitValue() != 0) L.w("Office", String.join(" ", cmd) + " -> " + p.exitValue() + ": " + tail(log), null);
        log.delete();
        return p.exitValue();
    }

    private static String tail(File f) {
        String s = Util.readFile(f);
        if (s == null) return "";
        return s.length() > 600 ? s.substring(s.length() - 600) : s;
    }
}
