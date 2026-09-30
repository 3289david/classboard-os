package kr.classboard.os;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.ref.WeakReference;

/** The home screen: a full-screen board UI rendered from the local server. */
public class MainActivity extends Activity {
    static final int REQ_TREE = 10;
    static final int REQ_PERMS = 11;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    private static volatile boolean foreground;
    private static volatile boolean keepOn = true;

    private WebView web;
    private NativeBridge bridge;
    private boolean loaded;

    public static boolean isForeground() {
        return foreground;
    }

    public static void notifyWeb(String event, String json) {
        UI.post(() -> {
            MainActivity a = current.get();
            if (a != null && a.web != null && a.loaded) {
                a.web.evaluateJavascript("window.onNative&&window.onNative(" + org.json.JSONObject.quote(event) + "," + json + ")", null);
            }
        });
    }

    public static void setKeepOn(boolean on) {
        keepOn = on;
        UI.post(() -> {
            MainActivity a = current.get();
            if (a != null) a.applyKeepOn();
        });
    }

    private void applyKeepOn() {
        if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = new WeakReference<>(this);
        CoreService.start(this);
        getWindow().setStatusBarColor(Color.BLACK);
        applyKeepOn();
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0F1115"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        bridge = new NativeBridge(this);
        web.addJavascriptInterface(bridge, "Native");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                android.util.Log.d("BoardWeb", m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    loaded = false;
                    UI.postDelayed(() -> view.loadUrl(boardUrl()), 1000);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost();
                if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        setContentView(web);
        // Give the embedded server a moment on first boot.
        UI.postDelayed(() -> web.loadUrl(boardUrl()), 300);
    }

    private static String boardUrl() {
        return "http://127.0.0.1:" + DeviceConfig.PORT + "/board/";
    }

    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        current = new WeakReference<>(this);
        immersive();
        CoreService cs = CoreService.get();
        if (cs != null) {
            cs.overlays().hideEmergency(null);
            cs.onBoardResumed();
        }
        notifyWeb("resume", "{}");
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
        // Alerts arriving while another app is open are drawn by native overlays.
        UI.postDelayed(() -> {
            CoreService s = CoreService.get();
            if (!foreground && s != null) {
                s.onBoardPaused();
                s.recheckAlerts();
            }
        }, 300);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        notifyWeb("home", "{}");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        notifyWeb("back", "{}");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            notifyWeb("folder", Util.jo("uri", tree.toString()).toString());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        notifyWeb("perms", "{}");
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
