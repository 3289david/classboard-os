package kr.classboard.os;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** Screenshot and screen recording through MediaProjection, saved to Pictures/Movies "ClassBoard". */
public class CaptureService extends Service {
    public static final String ACTION_SHOT = "shot";
    public static final String ACTION_RECORD = "record";
    public static final String ACTION_STOP = "stop";
    private static volatile boolean recording;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private MediaRecorder recorder;
    private Uri recordUri;
    private File recordFile;
    private ParcelFileDescriptor recordPfd;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static boolean isRecording() {
        return recording;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void foreground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("capture", "화면 캡처", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = new Notification.Builder(this, "capture").setContentTitle(text).setSmallIcon(R.drawable.ic_stat).setOngoing(true);
        if (recording || ACTION_RECORD.equals(text)) {
            PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, CaptureService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "녹화 중지", stop).build());
        }
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(2, b.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
            return START_NOT_STICKY;
        }
        if (!ACTION_SHOT.equals(action) && !ACTION_RECORD.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean rec = ACTION_RECORD.equals(action);
        foreground(rec ? "화면 녹화 중" : "화면 캡처 중");
        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            fail("캡처를 시작할 수 없습니다: " + e.getMessage());
            return START_NOT_STICKY;
        }
        if (projection == null) {
            fail("캡처 권한을 받지 못했습니다");
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (recording) stopRecording();
            }
        }, handler);
        if (rec) startRecording();
        else handler.postDelayed(this::takeShot, 600);
        return START_NOT_STICKY;
    }

    private DisplayMetrics metrics() {
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        return m;
    }

    private void fail(String msg) {
        MainActivity.notifyWeb("capture", Util.jo("ok", false, "error", msg).toString());
        cleanup();
    }

    private void takeShot() {
        DisplayMetrics m = metrics();
        int w = m.widthPixels, h = m.heightPixels;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("classboard-shot", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);
        reader.setOnImageAvailableListener(r -> {
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img == null) return;
                Image.Plane p = img.getPlanes()[0];
                ByteBuffer buf = p.getBuffer();
                int rowPad = p.getRowStride() - p.getPixelStride() * w;
                Bitmap bmp = Bitmap.createBitmap(w + rowPad / p.getPixelStride(), h, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(buf);
                Bitmap crop = Bitmap.createBitmap(bmp, 0, 0, w, h);
                String name = "ClassBoard_" + Util.fmt("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".png";
                String where = savePng(crop, name);
                MainActivity.notifyWeb("capture", Util.jo("ok", true, "kind", "shot", "name", name, "where", where).toString());
            } catch (Exception e) {
                Log.w("Capture", "shot", e);
                MainActivity.notifyWeb("capture", Util.jo("ok", false, "error", String.valueOf(e.getMessage())).toString());
            } finally {
                if (img != null) img.close();
                r.setOnImageAvailableListener(null, null);
                handler.post(this::cleanup);
            }
        }, handler);
    }

    private String savePng(Bitmap bmp, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ClassBoard");
            ContentResolver cr = getContentResolver();
            Uri u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("저장 위치를 만들 수 없습니다");
            try (OutputStream o = cr.openOutputStream(u)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            return "사진/ClassBoard";
        }
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ClassBoard");
        dir.mkdirs();
        File f = new File(dir, name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        return f.getAbsolutePath();
    }

    private void startRecording() {
        try {
            DisplayMetrics m = metrics();
            // Keep the encoder within common hardware limits while preserving aspect ratio.
            int w = m.widthPixels, h = m.heightPixels;
            float scale = Math.min(1f, 1920f / Math.max(w, h));
            w = ((int) (w * scale)) & ~15;
            h = ((int) (h * scale)) & ~15;
            boolean mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            if (mic) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            String name = "ClassBoard_" + Util.fmt("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".mp4";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ClassBoard");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                recordUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                recordPfd = getContentResolver().openFileDescriptor(recordUri, "w");
                recorder.setOutputFile(recordPfd.getFileDescriptor());
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ClassBoard");
                dir.mkdirs();
                recordFile = new File(dir, name);
                recorder.setOutputFile(recordFile.getAbsolutePath());
            }
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (mic) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioEncodingBitRate(128000);
            }
            recorder.setVideoSize(w, h);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(8_000_000);
            recorder.prepare();
            display = projection.createVirtualDisplay("classboard-rec", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.getSurface(), null, handler);
            recorder.start();
            recording = true;
            foreground("화면 녹화 중");
            MainActivity.notifyWeb("capture", Util.jo("ok", true, "kind", "recording", "mic", mic).toString());
        } catch (Exception e) {
            Log.w("Capture", "record", e);
            recording = false;
            fail("녹화를 시작할 수 없습니다: " + e.getMessage());
        }
    }

    private void stopRecording() {
        boolean was = recording;
        recording = false;
        try {
            if (recorder != null) recorder.stop();
        } catch (Exception e) {
            Log.w("Capture", "stop", e);
        }
        if (recorder != null) recorder.release();
        recorder = null;
        try {
            if (recordPfd != null) recordPfd.close();
        } catch (Exception ignored) {
        }
        if (recordUri != null && Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(recordUri, v, null, null);
        }
        if (was) MainActivity.notifyWeb("capture", Util.jo("ok", true, "kind", "recorded", "where", recordFile != null ? recordFile.getAbsolutePath() : "동영상/ClassBoard").toString());
        cleanup();
    }

    private void cleanup() {
        if (display != null) display.release();
        display = null;
        if (reader != null) reader.close();
        reader = null;
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            p.stop();
        }
        stopForeground(true);
        stopSelf();
    }
}
