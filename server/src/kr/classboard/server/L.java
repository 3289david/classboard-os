package kr.classboard.server;

/**
 * Logging that works both on Android (logcat) and on the data server (stderr),
 * so the shared data classes have no Android dependency.
 */
final class L {
    private L() {}

    private static final java.lang.reflect.Method ANDROID_W = find("w");
    private static final java.lang.reflect.Method ANDROID_E = find("e");

    private static java.lang.reflect.Method find(String name) {
        try {
            return Class.forName("android.util.Log").getMethod(name, String.class, String.class, Throwable.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(java.lang.reflect.Method m, String level, String tag, String msg, Throwable t) {
        if (m != null) {
            try {
                m.invoke(null, tag, msg, t);
                return;
            } catch (Throwable ignored) {
                // fall through to stderr
            }
        }
        System.err.println(java.time.LocalDateTime.now().withNano(0) + " " + level + " " + tag + ": " + msg + (t == null ? "" : " - " + t));
    }

    static void i(String tag, String msg) {
        System.err.println(java.time.LocalDateTime.now().withNano(0) + " INFO " + tag + ": " + msg);
    }

    static void w(String tag, String msg, Throwable t) {
        log(ANDROID_W, "WARN", tag, msg, t);
    }

    static void e(String tag, String msg, Throwable t) {
        log(ANDROID_E, "ERROR", tag, msg, t);
    }
}
