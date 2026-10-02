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
import android.graphics.RectF;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen memo drawn above any app: a full-screen transparent canvas plus a floating toolbar.
 * The toolbar is one view that draws its own buttons and is given an exact window size,
 * so it always shows in full (wrap-content overlay windows get measured at dialog width and clip).
 */
class MemoOverlay {
    private final Context ctx;
    private final WindowManager wm;
    private MemoView canvas;
    private Toolbar bar;
    private WindowManager.LayoutParams barLp;

    MemoOverlay(Context ctx, WindowManager wm) {
        this.ctx = ctx;
        this.wm = wm;
    }

    boolean shown() {
        return canvas != null;
    }

    private float dp(float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    private static WindowManager.LayoutParams base(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        // Android 11+ keeps overlay windows inside the status / navigation bar insets unless told otherwise.
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        return lp;
    }

    void show() {
        if (canvas != null) return;
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        canvas = new MemoView(ctx);
        WindowManager.LayoutParams lp = base(m.widthPixels, m.heightPixels, 0);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(canvas, lp);
        } catch (Exception e) {
            canvas = null;
            return;
        }
        bar = new Toolbar(ctx, m.widthPixels);
        barLp = base(bar.w, bar.h, 0);
        barLp.gravity = Gravity.TOP | Gravity.START;
        barLp.x = (m.widthPixels - bar.w) / 2;
        barLp.y = m.heightPixels - bar.h - (int) dp(285); // above the board's own dock and page dots
        try {
            wm.addView(bar, barLp);
        } catch (Exception e) {
            bar = null;
        }
    }

    void hide() {
        for (View v : new View[]{canvas, bar}) {
            if (v == null) continue;
            try {
                wm.removeView(v);
            } catch (Exception ignored) {
            }
        }
        canvas = null;
        bar = null;
    }

    private void setPassThrough(boolean on) {
        if (canvas == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) canvas.getLayoutParams();
        if (on) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.updateViewLayout(canvas, lp);
    }

    // ---------------------------------------------------------------- toolbar

    private static final int[] COLORS = {0xFF1F2328, 0xFFFFFFFF, 0xFFE5484D, 0xFF3B82F6, 0xFF22A06B, 0xFFF5B300};
    private static final String[] TOOLS = {"size", "marker", "eraser", "undo", "clear", "|", "hand", "close"};

    private class Toolbar extends View {
        final int w, h;
        final float cell, pad, grip;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        int color = 2;
        boolean marker, eraser, hand;
        float downX, downY;
        int startX, startY;
        boolean dragging;

        Toolbar(Context c, int screenW) {
            super(c);
            int slots = COLORS.length + TOOLS.length;
            float want = dp(35);
            pad = dp(4);
            grip = dp(14);
            // shrink buttons on narrow screens so the whole bar always fits
            cell = Math.min(want, (screenW - dp(32) - pad * 2 - grip - dp(8)) / slots);
            w = (int) (pad * 2 + grip + dp(8) + cell * slots);
            h = (int) (cell + pad * 2);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeWidth(dp(1.2f));
            line.setColor(Color.WHITE);
        }

        private float slotX(int i) {
            int sepBefore = i >= COLORS.length ? 1 : 0;
            return pad + grip + i * cell + sepBefore * dp(8);
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = h / 2f;
            fill.setColor(0xF2202329);
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, fill);
            fill.setColor(0x1FFFFFFF);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(dp(1));
            c.drawRoundRect(new RectF(dp(.5f), dp(.5f), w - dp(.5f), h - dp(.5f)), r, r, fill);
            fill.setStyle(Paint.Style.FILL);
            // drag grip
            fill.setColor(0x80FFFFFF);
            float gx = pad + grip / 2f, cy = h / 2f;
            for (int i = -1; i <= 1; i++) {
                c.drawCircle(gx - dp(2.2f), cy + i * dp(3.6f), dp(1f), fill);
                c.drawCircle(gx + dp(2.2f), cy + i * dp(3.6f), dp(1f), fill);
            }
            // colors
            for (int i = 0; i < COLORS.length; i++) {
                float cx = slotX(i) + cell / 2f;
                float rr = cell * 0.27f;
                if (i == color && !eraser) {
                    line.setColor(Color.WHITE);
                    c.drawCircle(cx, cy, rr + dp(2.5f), line);
                }
                fill.setColor(COLORS[i]);
                c.drawCircle(cx, cy, rr, fill);
                if (COLORS[i] == 0xFF1F2328) {
                    line.setColor(0x66FFFFFF);
                    line.setStrokeWidth(dp(1));
                    c.drawCircle(cx, cy, rr, line);
                    line.setStrokeWidth(dp(1.2f));
                }
            }
            // separator
            fill.setColor(0x26FFFFFF);
            float sx = slotX(COLORS.length) - dp(4);
            c.drawRect(sx - dp(.5f), cy - cell * .3f, sx + dp(.5f), cy + cell * .3f, fill);
            line.setColor(Color.WHITE);
            for (int t = 0; t < TOOLS.length; t++) {
                String k = TOOLS[t];
                float x0 = slotX(COLORS.length + t), cx = x0 + cell / 2f;
                boolean active = ("marker".equals(k) && marker) || ("eraser".equals(k) && eraser) || ("hand".equals(k) && hand);
                if (active) {
                    fill.setColor(0x33FFFFFF);
                    c.drawRoundRect(new RectF(x0 + dp(2), cy - cell / 2f + dp(2), x0 + cell - dp(2), cy + cell / 2f - dp(2)), dp(6), dp(6), fill);
                }
                drawTool(c, k, cx, cy, cell * 0.2f);
            }
        }

        private void drawTool(Canvas c, String k, float cx, float cy, float s) {
            Path p = new Path();
            switch (k) {
                case "size": {
                    fill.setColor(Color.WHITE);
                    float rr = new float[]{dp(1f), dp(1.8f), dp(2.8f), dp(4f)}[canvas == null ? 1 : canvas.wi];
                    c.drawCircle(cx, cy, rr, fill);
                    break;
                }
                case "marker":
                    p.moveTo(cx - s, cy + s);
                    p.lineTo(cx - s * .55f, cy + s * .1f);
                    p.lineTo(cx + s * .45f, cy - s * .9f);
                    p.lineTo(cx + s, cy - s * .35f);
                    p.lineTo(cx, cy + s * .65f);
                    p.close();
                    c.drawPath(p, line);
                    fill.setColor(0x99F5D90A);
                    c.drawRect(cx - s * 1.1f, cy + s * 1.15f, cx + s * 1.1f, cy + s * 1.45f, fill);
                    break;
                case "eraser":
                    p.moveTo(cx - s * 1.05f, cy + s * .35f);
                    p.lineTo(cx + s * .2f, cy - s * .9f);
                    p.lineTo(cx + s * 1.05f, cy - s * .05f);
                    p.lineTo(cx - s * .2f, cy + s * 1.1f);
                    p.close();
                    c.drawPath(p, line);
                    c.drawLine(cx - s * .45f, cy - s * .3f, cx + s * .4f, cy + s * .55f, line);
                    break;
                case "undo":
                    p.addArc(new RectF(cx - s, cy - s * .8f, cx + s, cy + s * 1.2f), 200, 250);
                    c.drawPath(p, line);
                    c.drawLine(cx - s * .95f, cy - s * .1f, cx - s * 1.0f, cy - s * .9f, line);
                    c.drawLine(cx - s * .95f, cy - s * .1f, cx - s * .2f, cy - s * .25f, line);
                    break;
                case "clear":
                    c.drawLine(cx - s, cy - s * .7f, cx + s, cy - s * .7f, line);
                    c.drawLine(cx - s * .3f, cy - s * 1.0f, cx + s * .3f, cy - s * 1.0f, line);
                    p.moveTo(cx - s * .75f, cy - s * .7f);
                    p.lineTo(cx - s * .6f, cy + s * 1.05f);
                    p.lineTo(cx + s * .6f, cy + s * 1.05f);
                    p.lineTo(cx + s * .75f, cy - s * .7f);
                    c.drawPath(p, line);
                    break;
                case "hand":
                    // pointer arrow: touches pass through to the app below
                    p.moveTo(cx - s * .7f, cy - s);
                    p.lineTo(cx - s * .7f, cy + s * .8f);
                    p.lineTo(cx - s * .2f, cy + s * .35f);
                    p.lineTo(cx + s * .25f, cy + s * 1.1f);
                    p.lineTo(cx + s * .55f, cy + s * .95f);
                    p.lineTo(cx + s * .1f, cy + s * .2f);
                    p.lineTo(cx + s * .75f, cy + s * .15f);
                    p.close();
                    c.drawPath(p, line);
                    break;
                case "close":
                    c.drawLine(cx - s * .8f, cy - s * .8f, cx + s * .8f, cy + s * .8f, line);
                    c.drawLine(cx + s * .8f, cy - s * .8f, cx - s * .8f, cy + s * .8f, line);
                    break;
                default:
                    break;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = barLp.x;
                    startY = barLp.y;
                    dragging = e.getX() < pad + grip;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        barLp.x = startX + (int) (e.getRawX() - downX);
                        barLp.y = startY + (int) (e.getRawY() - downY);
                        wm.updateViewLayout(this, barLp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) tap(e.getX());
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }

        private void tap(float x) {
            for (int i = 0; i < COLORS.length + TOOLS.length; i++) {
                float x0 = slotX(i);
                if (x < x0 || x >= x0 + cell) continue;
                if (i < COLORS.length) {
                    color = i;
                    eraser = false;
                    if (hand) {
                        hand = false;
                        setPassThrough(false);
                    }
                } else {
                    switch (TOOLS[i - COLORS.length]) {
                        case "size":
                            canvas.wi = (canvas.wi + 1) % 4;
                            break;
                        case "marker":
                            marker = !marker;
                            eraser = false;
                            break;
                        case "eraser":
                            eraser = !eraser;
                            break;
                        case "undo":
                            canvas.undo();
                            break;
                        case "clear":
                            canvas.clear();
                            break;
                        case "hand":
                            hand = !hand;
                            setPassThrough(hand);
                            break;
                        case "close":
                            hide();
                            return;
                        default:
                            break;
                    }
                }
                if (canvas != null) canvas.setTool(COLORS[color], marker, eraser);
                invalidate();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- drawing surface

    static class MemoView extends View {
        private static class Stroke {
            final Path path;
            final Paint paint;

            Stroke(Path p, Paint pt) {
                path = p;
                paint = pt;
            }
        }

        private final List<Stroke> strokes = new ArrayList<>();
        private Bitmap bmp;
        private Canvas cv;
        private Paint paint;
        private Path path;
        private float lx, ly;
        int wi = 1;
        private int color = 0xFFE5484D;
        private boolean marker, eraser;
        private static final float[] WIDTHS = {3, 6, 11, 18};

        MemoView(Context c) {
            super(c);
            setBackgroundColor(0x01000000); // must not be fully transparent or touches fall through
        }

        void setTool(int c, boolean m, boolean e) {
            color = c;
            marker = m;
            eraser = e;
        }

        private Paint newPaint() {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            float d = getResources().getDisplayMetrics().density;
            float w = WIDTHS[wi] * d;
            if (eraser) {
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                p.setStrokeWidth(w * 4);
            } else if (marker) {
                p.setColor((color & 0x00FFFFFF) | 0x66000000);
                p.setStrokeWidth(w * 3.5f);
                p.setStrokeCap(Paint.Cap.SQUARE);
            } else {
                p.setColor(color);
                p.setStrokeWidth(w);
            }
            return p;
        }

        void undo() {
            if (strokes.isEmpty()) return;
            strokes.remove(strokes.size() - 1);
            redraw();
        }

        void clear() {
            strokes.clear();
            redraw();
        }

        private void redraw() {
            if (bmp == null) return;
            bmp.eraseColor(Color.TRANSPARENT);
            for (Stroke s : strokes) cv.drawPath(s.path, s.paint);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (w <= 0 || h <= 0) return;
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            cv = new Canvas(bmp);
            redraw();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bmp != null) c.drawBitmap(bmp, 0, 0, null);
            if (path != null && !eraser) c.drawPath(path, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    paint = newPaint();
                    path = new Path();
                    path.moveTo(x, y);
                    lx = x;
                    ly = y;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (path == null) break;
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
                        cv.drawPath(path, paint); // erase as the finger moves
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (path == null) break;
                    path.lineTo(x + 0.1f, y + 0.1f);
                    strokes.add(new Stroke(path, paint));
                    if (cv != null) cv.drawPath(path, paint);
                    if (eraser) redraw();
                    path = null;
                    break;
                default:
                    break;
            }
            invalidate();
            return true;
        }
    }
}
