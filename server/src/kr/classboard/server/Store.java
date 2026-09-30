package kr.classboard.server;


import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/** JSON document persisted to disk with a monotonically increasing revision. */
public class Store {
    private final File file;
    private JSONObject root;
    private long rev;
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pending;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public Store(File file) {
        this.file = file;
        String s = Util.readFile(file);
        JSONObject r = null;
        if (s != null) {
            try {
                r = new JSONObject(s);
            } catch (Exception e) {
                L.e("Store", "corrupt " + file, e);
                file.renameTo(new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis()));
            }
        }
        root = r == null ? new JSONObject() : r;
        rev = root.optLong("_rev", 0);
    }

    public synchronized <T> T read(Function<JSONObject, T> f) {
        return f.apply(root);
    }

    public void write(Consumer<JSONObject> f) {
        synchronized (this) {
            f.accept(root);
            rev++;
            Util.put(root, "_rev", rev);
            scheduleSave();
        }
        for (Runnable l : listeners) l.run();
    }

    /** Replace the whole document (used by client devices mirroring the hub). */
    public void replace(JSONObject doc, long newRev) {
        synchronized (this) {
            root = doc;
            rev = newRev;
            Util.put(root, "_rev", rev);
            scheduleSave();
        }
        for (Runnable l : listeners) l.run();
    }

    public synchronized long rev() {
        return rev;
    }

    public synchronized JSONObject snapshot() {
        return Util.copy(root);
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    private void scheduleSave() {
        if (pending != null && !pending.isDone()) return;
        pending = saver.schedule(this::saveNow, 400, TimeUnit.MILLISECONDS);
    }

    public void saveNow() {
        String s;
        synchronized (this) {
            s = root.toString();
        }
        try {
            Util.writeFileAtomic(file, s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            L.e("Store", "save", e);
        }
    }
}
