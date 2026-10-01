package kr.classboard.os;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native overlays drawn above any app: emergency banner, class alerts, and on-screen memo. */
public class Overlays {
    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private View emergencyView;
    private String emergencyId;
    private View classAlertView;

    public interface Ack {
        void ack(String id);
    }

    public Overlays(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean allowed() {
        return Settings.canDrawOverlays(ctx);
    }

    private WindowManager.LayoutParams params(boolean focusable, boolean fullscreen) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                fullscreen ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT,
                fullscreen ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                (focusable ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    /** Size a wrap-content overlay explicitly: the system otherwise measures it at dialog width and clips text. */
    private void fitWindow(View v, WindowManager.LayoutParams lp) {
        int max = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.94f);
        v.measure(View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        lp.width = v.getMeasuredWidth();
        lp.height = v.getMeasuredHeight();
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    // ---------------------------------------------------------------- emergency

    public void showEmergency(String id, String title, String text, String time, Ack ack) {
        ui.post(() -> {
            if (!allowed()) return;
            if (id.equals(emergencyId) && emergencyView != null) return;
            hideEmergencyNow();
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundColor(0xF2B3261E);
            box.setPadding(dp(48), dp(48), dp(48), dp(48));

            TextView badge = new TextView(ctx);
            badge.setText(title == null || title.isEmpty() ? "긴급 안내" : title);
            badge.setTextColor(0xFFB3261E);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setTextSize(28);
            badge.setPadding(dp(28), dp(10), dp(28), dp(10));
            badge.setBackground(rounded(Color.WHITE, dp(40)));
            box.addView(badge);

            TextView body = new TextView(ctx);
            body.setText(text);
            body.setTextColor(Color.WHITE);
            body.setTextSize(44);
            body.setTypeface(Typeface.DEFAULT_BOLD);
            body.setGravity(Gravity.CENTER);
            body.setLineSpacing(0, 1.2f);
            body.setPadding(0, dp(36), 0, dp(12));
            box.addView(body);

            TextView when = new TextView(ctx);
            when.setText(time);
            when.setTextColor(0xCCFFFFFF);
            when.setTextSize(20);
            box.addView(when);

            Button ok = new Button(ctx);
            ok.setText("확인");
            ok.setTextSize(26);
            ok.setTextColor(0xFFB3261E);
            ok.setTypeface(Typeface.DEFAULT_BOLD);
            ok.setBackground(rounded(Color.WHITE, dp(16)));
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(260), dp(80));
            olp.topMargin = dp(40);
            ok.setLayoutParams(olp);
            ok.setOnClickListener(v -> {
                ack.ack(id);
                hideEmergencyNow();
            });
            box.addView(ok);
            try {
                wm.addView(box, params(false, true));
                emergencyView = box;
                emergencyId = id;
            } catch (Exception ignored) {
            }
        });
    }

    public void hideEmergency(String id) {
        ui.post(() -> {
            if (id == null || id.equals(emergencyId)) hideEmergencyNow();
        });
    }

    private void hideEmergencyNow() {
        if (emergencyView != null) {
            try {
                wm.removeView(emergencyView);
            } catch (Exception ignored) {
            }
        }
        emergencyView = null;
        emergencyId = null;
    }

    // ---------------------------------------------------------------- class alert

    private Runnable flashTick;

    public void showClassAlert(String title, String sub, int color, int seconds, boolean flash) {
        ui.post(() -> {
            if (!allowed()) return;
            hideClassAlertNow();
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(72), dp(48), dp(72), dp(48));
            box.setBackground(rounded(0xF7FFFFFF, dp(36)));
            box.setElevation(dp(24));

            View bar = new View(ctx);
            bar.setBackground(rounded(color, dp(6)));
            box.addView(bar, new LinearLayout.LayoutParams(dp(120), dp(12)));

            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextColor(0xFF1B1B1F);
            t.setTextSize(64);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(28), 0, dp(8));
            box.addView(t);

            TextView s = new TextView(ctx);
            s.setText(sub);
            s.setTextColor(0xFF45464F);
            s.setTextSize(30);
            s.setGravity(Gravity.CENTER);
            box.addView(s);
            box.setOnClickListener(v -> hideClassAlertNow());
            if (flash) {
                // Hard blink between white and the accent colour every 400 ms (independent of system animation settings).
                GradientDrawable bg = rounded(0xF7FFFFFF, dp(36));
                box.setBackground(bg);
                final boolean[] on = {false};
                flashTick = new Runnable() {
                    @Override
                    public void run() {
                        if (classAlertView != box) return;
                        on[0] = !on[0];
                        bg.setColor(on[0] ? color : 0xF7FFFFFF);
                        t.setTextColor(on[0] ? Color.WHITE : 0xFF1B1B1F);
                        s.setTextColor(on[0] ? 0xE6FFFFFF : 0xFF45464F);
                        box.setScaleX(on[0] ? 1.04f : 1f);
                        box.setScaleY(on[0] ? 1.04f : 1f);
                        ui.postDelayed(this, 400);
                    }
                };
            }
            try {
                WindowManager.LayoutParams clp = params(false, false);
                int w = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.7f);
                box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                clp.width = w;
                clp.height = box.getMeasuredHeight();
                wm.addView(box, clp);
                classAlertView = box;
                if (flashTick != null) ui.post(flashTick);
                ui.postDelayed(() -> {
                    if (classAlertView == box) hideClassAlertNow();
                }, Math.max(3, seconds) * 1000L);
            } catch (Exception ignored) {
            }
        });
    }

    private void hideClassAlertNow() {
        if (flashTick != null) {
            ui.removeCallbacks(flashTick);
            flashTick = null;
        }
        if (classAlertView != null) {
            try {
                wm.removeView(classAlertView);
            } catch (Exception ignored) {
            }
        }
        classAlertView = null;
    }

    // ---------------------------------------------------------------- memo

    private MemoOverlay memo;

    public boolean memoShown() {
        return memo != null && memo.shown();
    }

    public void toggleMemo() {
        ui.post(() -> {
            if (memoShown()) hideMemoNow();
            else if (allowed()) {
                if (memo == null) memo = new MemoOverlay(ctx, wm);
                memo.show();
            }
        });
    }

    public void hideMemo() {
        ui.post(this::hideMemoNow);
    }

    private void hideMemoNow() {
        if (memo != null) memo.hide();
    }

    // ---------------------------------------------------------------- floating navigation bar

    private View navBar;

    public interface NavHandler {
        void nav(String action);
    }

    /** Android-style Back / Home / Recents bar drawn over other apps (the board hides the system bar). */
    public void showNav(NavHandler h) {
        ui.post(() -> {
            if (!allowed() || navBar != null) return;
            LinearLayout bar = new LinearLayout(ctx);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER);
            bar.setPadding(dp(14), dp(4), dp(14), dp(4));
            bar.setBackground(rounded(0xCC15181E, dp(26)));
            for (String a : new String[]{"back", "home", "recents", "memo"}) {
                NavKey k = new NavKey(ctx, a);
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(64), dp(48));
                l.setMargins(dp(6), 0, dp(6), 0);
                k.setOnClickListener(v -> h.nav(a));
                bar.addView(k, l);
            }
            WindowManager.LayoutParams lp = params(false, false);
            lp.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(10);
            fitWindow(bar, lp);
            try {
                wm.addView(bar, lp);
                navBar = bar;
            } catch (Exception ignored) {
            }
        });
    }

    public void hideNav() {
        ui.post(() -> {
            if (navBar != null) {
                try {
                    wm.removeView(navBar);
                } catch (Exception ignored) {
                }
            }
            navBar = null;
        });
    }

    /** Vector-drawn navigation key: triangle (back), circle (home), square (recents), pen (memo). */
    static class NavKey extends View {
        private final String kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        NavKey(Context c, String kind) {
            super(c);
            this.kind = kind;
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(getResources().getDisplayMetrics().density * 2.4f);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
            setContentDescription(kind);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) * 0.22f;
            Path path = new Path();
            switch (kind) {
                case "back":
                    path.moveTo(cx + r * 0.8f, cy - r);
                    path.lineTo(cx - r, cy);
                    path.lineTo(cx + r * 0.8f, cy + r);
                    path.close();
                    c.drawPath(path, p);
                    break;
                case "home":
                    c.drawCircle(cx, cy, r, p);
                    break;
                case "recents":
                    c.drawRoundRect(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f, r * 0.2f, r * 0.2f, p);
                    break;
                default:
                    path.moveTo(cx - r, cy + r);
                    path.lineTo(cx - r * 0.7f, cy + r * 0.2f);
                    path.lineTo(cx + r * 0.5f, cy - r);
                    path.lineTo(cx + r, cy - r * 0.5f);
                    path.lineTo(cx - r * 0.2f, cy + r * 0.7f);
                    path.close();
                    c.drawPath(path, p);
                    break;
            }
        }
    }

    public void hideAll() {
        ui.post(() -> {
            hideMemoNow();
            hideClassAlertNow();
        });
    }
}
