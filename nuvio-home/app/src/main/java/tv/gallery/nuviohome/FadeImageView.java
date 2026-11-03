package tv.gallery.nuviohome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.widget.ImageView;

/** The sharp hero art, faded to transparent on its left and bottom so it melts into the blurred backdrop. */
final class FadeImageView extends ImageView {
    private final Paint mask = new Paint();
    FadeImageView(Context c) { super(c); mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN)); }
    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight(); if (w == 0 || h == 0) { super.onDraw(c); return; }
        int save = c.saveLayer(0, 0, w, h, null);
        super.onDraw(c);
        mask.setShader(new LinearGradient(0, 0, w * 0.55f, 0, new int[]{0x00000000, 0x59000000, 0xFF000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, mask);
        mask.setShader(new LinearGradient(0, h * 0.45f, 0, h, new int[]{0xFF000000, 0x66000000, 0x00000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, mask);
        c.restoreToCount(save);
    }
}
