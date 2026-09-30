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
    private MemoView memoView;
    private View memoBar;
    private boolean memoPassThrough;

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

    public void showClassAlert(String title, String sub, int color, int seconds) {
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
            try {
                WindowManager.LayoutParams clp = params(false, false);
                int w = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.7f);
                box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                clp.width = w;
                clp.height = box.getMeasuredHeight();
                wm.addView(box, clp);
                classAlertView = box;
                ui.postDelayed(() -> {
                    if (classAlertView == box) hideClassAlertNow();
                }, Math.max(3, seconds) * 1000L);
            } catch (Exception ignored) {
            }
        });
    }

    private void hideClassAlertNow() {
        if (classAlertView != null) {
            try {
                wm.removeView(classAlertView);
            } catch (Exception ignored) {
            }
        }
        classAlertView = null;
    }

    // ---------------------------------------------------------------- memo

    public boolean memoShown() {
        return memoView != null;
    }

    public void toggleMemo() {
        ui.post(() -> {
            if (memoView != null) hideMemoNow();
            else showMemoNow();
        });
    }

    public void hideMemo() {
        ui.post(this::hideMemoNow);
    }

    private void showMemoNow() {
        if (!allowed()) return;
        memoView = new MemoView(ctx);
        memoPassThrough = false;
        WindowManager.LayoutParams lp = params(false, true);
        try {
            wm.addView(memoView, lp);
        } catch (Exception e) {
            memoView = null;
            return;
        }
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.setBackground(rounded(0xF01B1B1F, dp(28)));
        int[] colors = {0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835, 0xFF111111, 0xFFFFFFFF};
        for (int c : colors) {
            View sw = new View(ctx);
            GradientDrawable g = rounded(c, dp(20));
            g.setStroke(dp(2), 0x66FFFFFF);
            sw.setBackground(g);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(40), dp(40));
            l.setMargins(dp(6), 0, dp(6), 0);
            sw.setOnClickListener(v -> {
                memoView.setColor(c);
                memoView.setEraser(false);
            });
            bar.addView(sw, l);
        }
        addBarButton(bar, "굵게", v -> memoView.cycleWidth());
        addBarButton(bar, "지우개", v -> memoView.setEraser(true));
        addBarButton(bar, "전체 지우기", v -> memoView.clear());
        final TextView[] pass = new TextView[1];
        pass[0] = addBarButton(bar, "터치 통과", v -> {
            memoPassThrough = !memoPassThrough;
            WindowManager.LayoutParams mlp = (WindowManager.LayoutParams) memoView.getLayoutParams();
            if (memoPassThrough) mlp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else mlp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            wm.updateViewLayout(memoView, mlp);
            pass[0].setText(memoPassThrough ? "필기 재개" : "터치 통과");
        });
        addBarButton(bar, "닫기", v -> hideMemoNow());
        WindowManager.LayoutParams blp = params(false, false);
        blp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        blp.y = dp(84);
        fitWindow(bar, blp);
        try {
            wm.addView(bar, blp);
            memoBar = bar;
        } catch (Exception ignored) {
        }
    }

    private TextView addBarButton(LinearLayout bar, String label, View.OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(18);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        // Single line so the overlay window grows instead of wrapping labels at dialog width.
        b.setSingleLine(true);
        b.setOnClickListener(l);
        bar.addView(b);
        return b;
    }

    private void hideMemoNow() {
        if (memoView != null) {
            try {
                wm.removeView(memoView);
            } catch (Exception ignored) {
            }
        }
        if (memoBar != null) {
            try {
                wm.removeView(memoBar);
            } catch (Exception ignored) {
            }
        }
        memoView = null;
        memoBar = null;
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

    /** Transparent drawing surface. */
    static class MemoView extends FrameLayout {
        private Bitmap bmp;
        private Canvas cv;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float[] widths = {4, 8, 14, 24};
        private int wi = 1;
        private boolean eraser;
        private float lx, ly;

        MemoView(Context c) {
            super(c);
            setWillNotDraw(false);
            setBackgroundColor(0x01000000);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0xFFE53935);
            applyWidth();
        }

        private void applyWidth() {
            float d = getResources().getDisplayMetrics().density;
            paint.setStrokeWidth(widths[wi] * d * (eraser ? 4 : 1));
        }

        void setColor(int c) {
            paint.setColor(c);
        }

        void cycleWidth() {
            wi = (wi + 1) % widths.length;
            applyWidth();
        }

        void setEraser(boolean e) {
            eraser = e;
            paint.setXfermode(e ? new PorterDuffXfermode(PorterDuff.Mode.CLEAR) : null);
            applyWidth();
        }

        void clear() {
            if (bmp != null) bmp.eraseColor(Color.TRANSPARENT);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (w <= 0 || h <= 0) return;
            Bitmap nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas nc = new Canvas(nb);
            if (bmp != null) nc.drawBitmap(bmp, 0, 0, null);
            bmp = nb;
            cv = nc;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bmp != null) canvas.drawBitmap(bmp, 0, 0, null);
            if (!eraser) canvas.drawPath(path, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    path.reset();
                    path.moveTo(x, y);
                    lx = x;
                    ly = y;
                    break;
                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < e.getHistorySize(); i++) {
                        float hx = e.getHistoricalX(i), hy = e.getHistoricalY(i);
                        path.quadTo(lx, ly, (hx + lx) / 2, (hy + ly) / 2);
                        lx = hx;
                        ly = hy;
                    }
                    path.quadTo(lx, ly, (x + lx) / 2, (y + ly) / 2);
                    lx = x;
                    ly = y;
                    if (eraser && cv != null) {
                        cv.drawPath(path, paint);
                        path.reset();
                        path.moveTo(x, y);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    path.lineTo(x, y);
                    if (cv != null) cv.drawPath(path, paint);
                    path.reset();
                    break;
                default:
                    break;
            }
            invalidate();
            return true;
        }
    }
}
