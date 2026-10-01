package kr.classboard.os;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Plain file access to internal storage and USB drives (needs "모든 파일 접근" on Android 11+). */
final class LocalFiles {
    private LocalFiles() {}

    static boolean access(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static File primary() {
        return Environment.getExternalStorageDirectory();
    }

    /** Mounted removable volumes (USB drives, SD cards). */
    static JSONArray removable(Context c) {
        JSONArray out = new JSONArray();
        StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (v.isPrimary() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
            File dir = dirOf(v);
            if (dir == null) continue;
            JSONObject o = Util.jo("label", v.getDescription(c), "path", dir.getAbsolutePath(), "usb", true);
            try {
                StatFs fs = new StatFs(dir.getPath());
                Util.put(o, "total", fs.getTotalBytes());
                Util.put(o, "free", fs.getAvailableBytes());
            } catch (Exception ignored) {
            }
            out.put(o);
        }
        return out;
    }

    private static File dirOf(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30) return v.getDirectory();
        try {
            return (File) StorageVolume.class.getMethod("getPathFile").invoke(v);
        } catch (Exception e) {
            return null;
        }
    }

    static JSONObject roots(Context c) {
        File p = primary();
        JSONArray places = new JSONArray();
        StatFs fs = new StatFs(p.getPath());
        places.put(Util.jo("label", "내부 저장공간", "path", p.getAbsolutePath(), "kind", "internal", "total", fs.getTotalBytes(), "free", fs.getAvailableBytes()));
        Map<String, String[]> quick = new LinkedHashMap<>();
        quick.put("download", new String[]{"다운로드", Environment.DIRECTORY_DOWNLOADS});
        quick.put("board", new String[]{"칠판 · 캡처", Environment.DIRECTORY_PICTURES + "/ClassBoard"});
        quick.put("record", new String[]{"화면 녹화", Environment.DIRECTORY_MOVIES + "/ClassBoard"});
        quick.put("docs", new String[]{"문서", Environment.DIRECTORY_DOCUMENTS});
        quick.put("photos", new String[]{"사진", Environment.DIRECTORY_DCIM});
        for (Map.Entry<String, String[]> e : quick.entrySet()) {
            File d = new File(p, e.getValue()[1]);
            places.put(Util.jo("label", e.getValue()[0], "path", d.getAbsolutePath(), "kind", e.getKey(), "exists", d.isDirectory()));
        }
        // before Android 10 the board saves its own pictures in the app folder
        File own = c.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (Build.VERSION.SDK_INT < 29 && own != null) places.put(Util.jo("label", "칠판 · 캡처 (앱)", "path", new File(own, "ClassBoard").getAbsolutePath(), "kind", "board"));
        return Util.jo("access", access(c), "places", places, "usb", removable(c));
    }

    /** Only files on shared storage or USB drives, never the app's private data. */
    static File checked(String path) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("경로가 없습니다");
        File f = new File(path).getCanonicalFile();
        String s = f.getPath();
        if (!(s.startsWith("/storage/") || s.startsWith("/mnt/media_rw/") || s.startsWith("/sdcard"))) throw new IOException("열 수 없는 위치입니다");
        return f;
    }

    static JSONObject list(String path) throws IOException {
        File d = checked(path);
        File[] all = d.listFiles();
        if (all == null) throw new IOException("폴더를 읽을 수 없습니다 (권한 또는 USB 연결을 확인하세요)");
        Arrays.sort(all, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            if (a.isDirectory()) return a.getName().compareToIgnoreCase(b.getName());
            return Long.compare(b.lastModified(), a.lastModified()); // newest file first
        });
        JSONArray items = new JSONArray();
        for (File f : all) {
            if (f.getName().startsWith(".")) continue;
            if (items.length() >= 3000) break;
            boolean dir = f.isDirectory();
            JSONObject o = Util.jo("name", f.getName(), "path", f.getAbsolutePath(), "dir", dir, "mtime", f.lastModified());
            if (dir) {
                String[] kids = f.list();
                Util.put(o, "count", kids == null ? 0 : kids.length);
            } else {
                Util.put(o, "size", f.length());
            }
            items.put(o);
        }
        return Util.jo("path", d.getAbsolutePath(), "name", d.getName(), "items", items);
    }

    private static final Map<String, byte[]> thumbs = java.util.Collections.synchronizedMap(new LinkedHashMap<String, byte[]>(64, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > 120;
        }
    });

    /** Small JPEG preview of a picture (decoded at reduced size: boards have little memory). */
    static byte[] thumb(String path) throws IOException {
        File f = checked(path);
        String key = f.getPath() + "@" + f.lastModified();
        byte[] b = thumbs.get(key);
        if (b != null) return b;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) throw new IOException("그림이 아닙니다");
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 240 && o.outHeight / (sample * 2) >= 160) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
        if (bm == null) throw new IOException("그림을 읽을 수 없습니다");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 72, out);
        bm.recycle();
        b = out.toByteArray();
        thumbs.put(key, b);
        return b;
    }
}
