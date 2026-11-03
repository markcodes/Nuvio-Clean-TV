package tv.gallery.nuviohome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.List;

/** Vertical list of Home rows; each row is a horizontal list of cards (titles) or tiles (collection folders). */
final class Rows {
    interface Listener {
        void onItemFocused(Nuvio.Row row, Nuvio.Item item);
        void onFolderFocused(Nuvio.Row row, JSONObject folder);
        void onItem(Nuvio.Item item);
        void onFolder(JSONObject folder);
        void onRowFocused(int rowIndex, View rowView);
    }

    static int dp(Context c, float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()); }
    static GradientDrawable round(int color, float r, Context c) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c, r)); return g; }
    static GradientDrawable ring(Context c, float r) { GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(c, r)); g.setStroke(dp(c, 3), Color.WHITE); g.setColor(Color.TRANSPARENT); return g; }
    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL)); return t;
    }

    static final class RowsAdapter extends RecyclerView.Adapter<RowsAdapter.VH> {
        final List<Nuvio.Row> rows; final Listener l; final RecyclerView.RecycledViewPool cardPool = new RecyclerView.RecycledViewPool();
        final int startPad;
        RowsAdapter(List<Nuvio.Row> rows, Listener l, int startPad) { this.rows = rows; this.l = l; this.startPad = startPad; cardPool.setMaxRecycledViews(0, 40); }
        static final class VH extends RecyclerView.ViewHolder { TextView title; RecyclerView list; VH(View v, TextView t, RecyclerView r) { super(v); title = t; list = r; } }
        @Override public int getItemCount() { return rows.size(); }
        @Override public VH onCreateViewHolder(ViewGroup p, int vt) {
            Context c = p.getContext();
            LinearLayout box = new LinearLayout(c); box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView t = text(c, "", 18, 0xF2FFFFFF, true); t.setPadding(startPad + dp(c, 4), dp(c, 14), 0, dp(c, 2)); box.addView(t);
            RecyclerView r = new RecyclerView(c); r.setLayoutManager(new LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false));
            r.setRecycledViewPool(cardPool); r.setClipToPadding(false); r.setClipChildren(true); r.setPadding(startPad, dp(c, 14), dp(c, 48), dp(c, 14));
            r.setItemAnimator(null); r.setHasFixedSize(true);
            box.setClipChildren(false); box.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(box, t, r);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            Nuvio.Row row = rows.get(pos);
            h.title.setText(row.title);
            h.list.setAdapter(new CardsAdapter(row, l, () -> l.onRowFocused(h.getBindingAdapterPosition(), h.itemView)));
        }
    }

    /** Cards for one row. Continue Watching is wide (16:9) with progress; catalogs are posters; folders use their tile shape. */
    static final class CardsAdapter extends RecyclerView.Adapter<CardsAdapter.VH> {
        final Nuvio.Row row; final Listener l; final Runnable rowFocused;
        CardsAdapter(Nuvio.Row row, Listener l, Runnable rowFocused) { this.row = row; this.l = l; this.rowFocused = rowFocused; }
        static final class VH extends RecyclerView.ViewHolder {
            FrameLayout frame, art; ImageView img; TextView fallback, title, sub, inner; View bar, shade;
            VH(View v) { super(v); }
        }
        @Override public int getItemCount() { return row.isCollection() ? row.folders.size() : row.items.size(); }
        @Override public VH onCreateViewHolder(ViewGroup p, int vt) {
            Context c = p.getContext();
            LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL); card.setFocusable(true); card.setClickable(true);
            card.setClipChildren(false);
            VH h = new VH(card);
            h.art = new FrameLayout(c); h.art.setBackground(round(0xFF22232A, 10, c)); h.art.setClipToOutline(true);
            h.img = new ImageView(c); h.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            h.art.addView(h.img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            h.fallback = text(c, "", 14, 0xFFB3B3B3, false); h.fallback.setGravity(Gravity.CENTER); h.fallback.setPadding(dp(c, 8), 0, dp(c, 8), 0);
            h.art.addView(h.fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            h.shade = new View(c); h.shade.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x00000000, 0xB3000000}));
            h.art.addView(h.shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            h.inner = text(c, "", 12, Color.WHITE, true); h.inner.setSingleLine(true);
            FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START); il.setMargins(dp(c, 10), 0, dp(c, 10), dp(c, 20));
            h.art.addView(h.inner, il);
            View track = new View(c); track.setBackground(round(0x66000000, 2, c)); track.setTag("track");
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 4), Gravity.BOTTOM); tl.setMargins(dp(c, 10), 0, dp(c, 10), dp(c, 10));
            h.art.addView(track, tl);
            h.bar = new View(c); h.bar.setBackground(round(0xFFFFFFFF, 2, c));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(0, dp(c, 4), Gravity.BOTTOM | Gravity.START); bl.setMargins(dp(c, 10), 0, 0, dp(c, 10));
            h.art.addView(h.bar, bl);
            // The focus ring sits around the picture (in this frame's padding), not on top of it.
            h.frame = new FrameLayout(c); h.frame.setPadding(dp(c, 5), dp(c, 5), dp(c, 5), dp(c, 5));
            h.frame.addView(h.art, new FrameLayout.LayoutParams(dp(c, 120), dp(c, 180)));
            card.addView(h.frame);
            h.title = text(c, "", 13, Color.WHITE, false); h.title.setSingleLine(true); h.title.setPadding(dp(c, 5), dp(c, 6), 0, 0); card.addView(h.title);
            h.sub = text(c, "", 12, 0xFF9AA0AA, false); h.sub.setSingleLine(true); h.sub.setPadding(dp(c, 5), dp(c, 3), 0, 0); card.addView(h.sub);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(c, 16); card.setLayoutParams(lp);
            card.setOnFocusChangeListener((v, f) -> {
                v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(140).start();
                h.frame.setBackground(f ? ring(c, 14) : null);
                if (h.title.getText().length() > 0) h.title.setVisibility(f ? View.VISIBLE : View.INVISIBLE);
                v.setZ(f ? 1 : 0);
                if (f) { int pos = h.getBindingAdapterPosition(); CardsAdapter a = (CardsAdapter) h.getBindingAdapter(); if (pos < 0 || a == null) return; a.rowFocused.run();
                    if (a.row.isCollection()) a.l.onFolderFocused(a.row, a.row.folders.get(pos)); else a.l.onItemFocused(a.row, a.row.items.get(pos)); }
            });
            card.setOnClickListener(v -> { int pos = h.getBindingAdapterPosition(); CardsAdapter a = (CardsAdapter) h.getBindingAdapter(); if (pos < 0 || a == null) return; if (a.row.isCollection()) a.l.onFolder(a.row.folders.get(pos)); else a.l.onItem(a.row.items.get(pos)); });
            return h;
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            Context c = h.itemView.getContext();
            int w, ht; String img, title, sub, fb;
            if (row.isCollection()) {
                JSONObject f = row.folders.get(pos);
                String shape = f.optString("tileShape", "SQUARE").toUpperCase();
                boolean wide = shape.equals("LANDSCAPE") || shape.equals("WIDE"), poster = shape.equals("POSTER");
                w = dp(c, poster ? 120 : wide ? 240 : 150); ht = dp(c, poster ? 180 : wide ? 135 : 150);
                img = f.isNull("coverImageUrl") ? "" : f.optString("coverImageUrl");
                title = f.optBoolean("hideTitle") ? "" : f.optString("title"); sub = "";
                String emoji = f.isNull("coverEmoji") ? "" : f.optString("coverEmoji");
                fb = emoji.isEmpty() ? f.optString("title") : emoji;
                h.fallback.setTextSize(emoji.isEmpty() ? 15 : 40);
                h.bar.setVisibility(View.GONE); h.art.findViewWithTag("track").setVisibility(View.GONE);
                h.inner.setVisibility(View.GONE); h.shade.setVisibility(View.GONE);
            } else {
                Nuvio.Item it = row.items.get(pos);
                boolean wide = row.continueWatching;
                w = dp(c, wide ? 224 : 120); ht = dp(c, wide ? 126 : 180);
                img = wide ? Nuvio.nonEmpty(it.background, it.poster) : Nuvio.nonEmpty(it.poster, it.background);
                title = wide ? it.name : ""; sub = "";
                h.inner.setText(wide && it.sub != null && !it.sub.isEmpty() ? "▶  " + it.sub : ""); h.inner.setVisibility(wide && it.sub != null && !it.sub.isEmpty() ? View.VISIBLE : View.GONE);
                h.shade.setVisibility(wide ? View.VISIBLE : View.GONE);
                fb = it.name; h.fallback.setTextSize(14);
                if (it.pct >= 0) { h.bar.setVisibility(View.VISIBLE); h.art.findViewWithTag("track").setVisibility(View.VISIBLE); FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) h.bar.getLayoutParams(); bp.width = Math.max(dp(c, 4), (w - dp(c, 20)) * it.pct / 100); h.bar.setLayoutParams(bp); }
                else { h.bar.setVisibility(View.GONE); h.art.findViewWithTag("track").setVisibility(View.GONE); }
            }
            ViewGroup.LayoutParams ap = h.art.getLayoutParams(); if (ap == null) ap = new LinearLayout.LayoutParams(w, ht); ap.width = w; ap.height = ht; h.art.setLayoutParams(ap);
            h.title.setMaxWidth(w); h.sub.setMaxWidth(w);
            h.title.setText(title); h.title.setVisibility(title.isEmpty() ? View.GONE : (h.itemView.hasFocus() ? View.VISIBLE : View.INVISIBLE));
            h.sub.setText(sub); h.sub.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
            h.fallback.setText(fb); h.fallback.setVisibility(View.VISIBLE);
            Images.load(h.img, img, w, () -> h.fallback.setVisibility(View.GONE));
        }
    }
}
