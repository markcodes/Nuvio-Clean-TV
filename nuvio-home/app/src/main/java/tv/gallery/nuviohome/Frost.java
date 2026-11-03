package tv.gallery.nuviohome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * Frosted glass for the sidebar: the screen behind the panel is drawn small, blurred, and painted back under a
 * tint, clipped to the panel's rounded shape. Recaptured when the panel opens and when what is behind it changes.
 */
final class Frost extends Drawable {
    private static final int SCALE = 4;
    private final Paint bmPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private Bitmap bm; private int top;

    Frost(float radiusPx) { radius = radiusPx; edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(1.5f); edge.setColor(0x26FFFFFF); setGlass(0x5514121A); }
    void setGlass(int argb) { tint.setColor(argb); invalidateSelf(); }

    /** Draws every child of root except panel into a small bitmap covering the panel's column, then blurs it. */
    void capture(ViewGroup root, View panel, int widthPx) {
        int h = root.getHeight(); if (h == 0 || widthPx == 0) return;
        Bitmap b = Bitmap.createBitmap(Math.max(1, widthPx / SCALE), Math.max(1, h / SCALE), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF0B0C10);
        c.scale(1f / SCALE, 1f / SCALE);
        c.translate(-panel.getLeft(), 0);
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v == panel || v.getVisibility() != View.VISIBLE) continue;
            int save = c.save(); c.translate(v.getLeft(), v.getTop());
            if (v.getAlpha() < 1f) c.saveLayerAlpha(0, 0, v.getWidth(), v.getHeight(), (int) (v.getAlpha() * 255));
            try { v.draw(c); } catch (RuntimeException ignored) { }
            c.restoreToCount(save);
        }
        bm = Images.blur(b, 3, 2); top = panel.getTop();
        invalidateSelf();
    }

    @Override public void draw(Canvas c) {
        RectF r = new RectF(getBounds());
        Path clip = new Path(); clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        int save = c.save(); c.clipPath(clip);
        if (bm != null) { c.save(); c.translate(0, -top); c.scale(SCALE, SCALE); c.drawBitmap(bm, 0, 0, bmPaint); c.restore(); }
        c.drawRect(r, tint);
        c.restoreToCount(save);
        r.inset(0.75f, 0.75f); c.drawRoundRect(r, radius, radius, edge);
    }
    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
