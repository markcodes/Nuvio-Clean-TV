package tv.gallery.nuviohome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Artwork: a memory cache, a disk cache, RGB_565 decoding sized to the view, and a cheap blur for the backdrop. */
final class Images {
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(28 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static File dir;

    static void init(Context c) { dir = new File(c.getCacheDir(), "img"); dir.mkdirs(); }

    /** Loads url into v at about widthPx wide. A view recycled to another url in the meantime is left alone. */
    static void load(ImageView v, String url, int widthPx, Runnable onLoaded) {
        v.setTag(R_TAG, url);
        if (url == null || url.isEmpty()) { v.setImageDrawable(null); return; }
        String key = url + "@" + widthPx;
        Bitmap hit = MEM.get(key);
        if (hit != null) { v.setImageBitmap(hit); if (onLoaded != null) onLoaded.run(); return; }
        v.setImageDrawable(null);
        POOL.execute(() -> {
            Bitmap b = fetch(url, widthPx, false);
            if (b == null) return;
            MEM.put(key, b);
            UI.post(() -> { if (url.equals(v.getTag(R_TAG))) { v.setImageBitmap(b); if (onLoaded != null) onLoaded.run(); } });
        });
    }

    /** The backdrop: a small copy blurred on the GPU on Android 12+, or stretched (which blurs it) on older TVs. */
    static void loadBlurred(ImageView v, String url, Runnable onLoaded) {
        v.setTag(R_TAG, url);
        if (url == null || url.isEmpty()) return;
        String key = url + "@blur";
        Bitmap hit = MEM.get(key);
        if (hit != null) { show(v, hit); if (onLoaded != null) onLoaded.run(); return; }
        POOL.execute(() -> {
            Bitmap raw = fetch(url, 96, true);
            final Bitmap b = raw == null ? null : blur(raw, 4, 3);
            if (b == null) return;
            MEM.put(key, b);
            UI.post(() -> { if (url.equals(v.getTag(R_TAG))) { show(v, b); if (onLoaded != null) onLoaded.run(); } });
        });
    }
    private static void show(ImageView v, Bitmap b) {
        v.setImageBitmap(b);
        if (Build.VERSION.SDK_INT >= 31 && v.getTag(BLUR_TAG) == null) { v.setRenderEffect(RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP)); v.setTag(BLUR_TAG, Boolean.TRUE); }
    }

    private static Bitmap fetch(String url, int widthPx, boolean small) {
        try {
            byte[] data = disk(url);
            if (data == null) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(15000);
                if (c.getResponseCode() != 200) return null;
                try (InputStream in = c.getInputStream()) { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); data = out.toByteArray(); }
                c.disconnect();
                save(url, data);
            }
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int s = 1; while (o.outWidth / (s * 2) >= widthPx) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options(); d.inSampleSize = s; d.inPreferredConfig = small ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, d);
            if (b != null && small && b.getWidth() > widthPx) {
                Bitmap sc = Bitmap.createScaledBitmap(b, widthPx, Math.max(1, b.getHeight() * widthPx / b.getWidth()), true);
                if (sc != b) b.recycle(); b = sc;
            }
            return b;
        } catch (Throwable t) { return null; }
    }
    /** Box blur, several passes approximate a gaussian; works on a small copy so it is cheap. */
    static Bitmap blur(Bitmap src, int r, int passes) {
        Bitmap b = src.isMutable() && src.getConfig() == Bitmap.Config.ARGB_8888 ? src : src.copy(Bitmap.Config.ARGB_8888, true);
        int w = b.getWidth(), h = b.getHeight(); int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) { pass(px, tmp, w, h, r, true); pass(tmp, px, w, h, r, false); }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }
    private static void pass(int[] in, int[] out, int w, int h, int r, boolean horiz) {
        int len = horiz ? w : h, lines = horiz ? h : w, div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) { int c = in[idx(Math.max(0, Math.min(len - 1, i)), l, w, horiz)]; sa += c >>> 24; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; }
            for (int i = 0; i < len; i++) {
                out[idx(i, l, w, horiz)] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[idx(Math.min(len - 1, i + r + 1), l, w, horiz)], rem = in[idx(Math.max(0, i - r), l, w, horiz)];
                sa += (add >>> 24) - (rem >>> 24); sr += ((add >> 16) & 255) - ((rem >> 16) & 255); sg += ((add >> 8) & 255) - ((rem >> 8) & 255); sb += (add & 255) - (rem & 255);
            }
        }
    }
    private static int idx(int i, int l, int w, boolean horiz) { return horiz ? l * w + i : i * w + l; }

    private static File file(String url) { return new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length()); }
    private static byte[] disk(String url) {
        if (dir == null) return null; File f = file(url); if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[(int) f.length()]; int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; f.setLastModified(System.currentTimeMillis()); return b; }
        catch (Exception e) { return null; }
    }
    private static void save(String url, byte[] data) {
        if (dir == null) return;
        try (FileOutputStream o = new FileOutputStream(file(url))) { o.write(data); } catch (Exception ignored) { }
        File[] fs = dir.listFiles(); if (fs == null || fs.length < 1500) return;   // keep the disk cache bounded
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < fs.length - 1200; i++) fs[i].delete();
    }

    static final int R_TAG = 0x7f0b0001, BLUR_TAG = 0x7f0b0002;
}
