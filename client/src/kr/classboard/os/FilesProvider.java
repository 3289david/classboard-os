package kr.classboard.os;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only provider that lets viewer apps open downloaded lesson materials from the cache. */
public class FilesProvider extends ContentProvider {
    public static final String AUTHORITY = "kr.classboard.os.files";

    public static Uri uriFor(Context c, File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.getName()).build();
    }

    /** content:// link for a file on shared storage or a USB drive (opened read-only by viewer apps). */
    public static Uri uriForPath(File f) {
        String enc = android.util.Base64.encodeToString(f.getAbsolutePath().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("p").appendPath(enc).appendPath(f.getName()).build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() == 3 && "p".equals(seg.get(0))) {
            try {
                String path = new String(android.util.Base64.decode(seg.get(1), android.util.Base64.URL_SAFE), java.nio.charset.StandardCharsets.UTF_8);
                File f = LocalFiles.checked(path);
                if (!f.isFile()) throw new FileNotFoundException(path);
                return f;
            } catch (java.io.IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
        File f = new File(new File(getContext().getCacheDir(), "open"), name);
        if (!f.exists()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = fileFor(uri);
            boolean shared = uri.getPathSegments().size() == 3;
            String display = shared || !f.getName().contains("_") ? f.getName() : f.getName().substring(f.getName().indexOf('_') + 1);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{display, f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        return n == null ? "application/octet-stream" : Util.mimeFor(n).split(";")[0];
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
