package tv.gallery.nuviohome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static tv.gallery.nuviohome.Rows.dp;
import static tv.gallery.nuviohome.Rows.round;
import static tv.gallery.nuviohome.Rows.text;

/**
 * A title's details, laid out like Apple TV: the backdrop fills the screen with the title, a short description and
 * Play / + / i at the bottom left and Starring / Director at the bottom right. Down scrolls to the rows: episodes
 * (series), Cast & Crew, Trailers, More like this, Reviews and Production Companies. Details come from the owner's
 * addons (the same metadata Nuvio uses); cast photos, reviews and companies need a TMDB key in Settings.
 * Play hands the title to the official Nuvio's sources list, falling back to Nuvio's details page.
 */
public class DetailActivity extends Activity {
    private static final String[] NUVIO_PACKAGES = {"com.nuvio.tv", "com.nuvio.app"};

    /** Opens a title's details; similar is the row it came from, for "More like this". */
    static Intent intent(Context c, Nuvio.Item it, List<Nuvio.Item> similar) {
        Intent i = new Intent(c, DetailActivity.class).putExtra("item", Launch.json(it));
        if (similar != null) {
            JSONArray a = new JSONArray();
            for (Nuvio.Item s : similar) {
                if (a.length() >= 20) break;
                if (s == it || s.id == null || s.id.equals(it.id)) continue;
                try { a.put(new JSONObject().put("type", s.type).put("id", s.id).put("name", s.name).put("poster", s.poster).put("background", s.background).put("year", s.year).put("sub", s.sub)); } catch (Exception ignored) { }
            }
            i.putExtra("similar", a.toString());
        }
        return i;
    }

    private int W, H, PAD;
    private Nuvio.Item item;
    private JSONObject meta;
    private final List<Nuvio.Item> similar = new ArrayList<>();
    private ImageView backdrop, logo;
    private View dim, playBtn, watchedBtn;
    private Boolean isWatched;
    private TextView title, metaText, chip, desc, facts, credits;
    private Page scroll;
    private LinearLayout page, seasonsRow;
    private LinearLayout episodesSec, castSec, trailersSec, moreSec, reviewsSec, companiesSec;
    private final List<View> sections = new ArrayList<>();
    private final TreeMap<Integer, List<JSONObject>> seasons = new TreeMap<>();

    /** Scrolls only where we tell it to: each row snaps under the top, like Home. */
    static final class Page extends ScrollView {
        Page(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { item = Nuvio.Item.from(new JSONObject(getIntent().getStringExtra("item"))); } catch (Exception e) { finish(); return; }
        W = getResources().getDisplayMetrics().widthPixels; H = getResources().getDisplayMetrics().heightPixels; PAD = dp(this, 56);
        try { JSONArray a = new JSONArray(getIntent().getStringExtra("similar")); for (int i = 0; i < a.length(); i++) similar.add(Nuvio.Item.from(a.getJSONObject(i))); } catch (Exception ignored) { }

        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF15161A);
        backdrop = new ImageView(this); backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        // Apple keeps the picture bright: only a light shade at the left and bottom so the text reads.
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x99000000, 0x5C000000, 0x24000000, 0x00000000}));
        root.addView(left, new FrameLayout.LayoutParams((int) (W * 0.62f), -1));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x00000000, 0x14000000, 0x80000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -1));
        dim = new View(this); dim.setBackgroundColor(0xD00B0C10); dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        scroll = new Page(this); scroll.setVerticalScrollBarEnabled(false); scroll.setClipChildren(false); scroll.setClipToPadding(false); scroll.setSmoothScrollingEnabled(true);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setClipChildren(false); page.setClipToPadding(false);
        page.setPadding(0, 0, 0, H / 2);
        page.addView(buildTop(), new LinearLayout.LayoutParams(-1, H - dp(this, 30)));
        episodesSec = section("Episodes");
        castSec = section("Cast & Crew");
        trailersSec = section("Trailers");
        moreSec = section("More like this");
        reviewsSec = section("Reviews");
        companiesSec = section("Production Companies");
        seasonsRow = new LinearLayout(this); seasonsRow.setPadding(PAD, dp(this, 12), PAD, 0);
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.addView(seasonsRow);
        episodesSec.addView(hs, 1);
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> dim.setAlpha(Math.min(1f, y / (H * 0.55f))));
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> follow(n));

        Images.load(backdrop, Nuvio.nonEmpty(item.background, item.poster), W, null);
        showSimilar();
        playBtn.requestFocus();
        load();
    }

    // ---------- top of the page ----------
    private View buildTop() {
        FrameLayout top = new FrameLayout(this); top.setClipChildren(false);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setClipChildren(false); col.setClipToPadding(false);
        logo = new ImageView(this); logo.setAdjustViewBounds(true); logo.setScaleType(ImageView.ScaleType.FIT_START); logo.setVisibility(View.GONE);
        col.addView(logo, new LinearLayout.LayoutParams(dp(this, 300), dp(this, 84)));
        title = text(this, item.name, 30, Color.WHITE, true); title.setMaxLines(2); shadow(title);
        col.addView(title);
        LinearLayout mr = new LinearLayout(this); mr.setGravity(Gravity.CENTER_VERTICAL); mr.setPadding(0, dp(this, 10), 0, 0);
        metaText = text(this, label(), 13, Color.WHITE, true); shadow(metaText); mr.addView(metaText);
        chip = text(this, "", 10, 0xE6FFFFFF, true); chip.setPadding(dp(this, 5), dp(this, 1), dp(this, 5), dp(this, 1));
        GradientDrawable cb = new GradientDrawable(); cb.setCornerRadius(dp(this, 3)); cb.setStroke(Math.max(1, dp(this, 1)), 0x99FFFFFF); chip.setBackground(cb); chip.setVisibility(View.GONE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2); clp.leftMargin = dp(this, 8); mr.addView(chip, clp);
        col.addView(mr);
        desc = text(this, item.description == null ? "" : item.description, 12, 0xF2FFFFFF, false); desc.setMaxLines(4); desc.setLineSpacing(0, 1.3f); desc.setPadding(0, dp(this, 8), 0, 0); shadow(desc);
        col.addView(desc);
        facts = text(this, item.year == null ? "" : item.year, 12, 0xD9FFFFFF, false); facts.setPadding(0, dp(this, 8), 0, 0); shadow(facts);
        col.addView(facts);
        LinearLayout btns = new LinearLayout(this); btns.setGravity(Gravity.CENTER_VERTICAL); btns.setPadding(0, dp(this, 16), 0, 0); btns.setClipChildren(false); btns.setClipToPadding(false);
        boolean resume = item.pct > 0;
        String pl = resume ? "Resume" : "Play";
        if (item.season > 0) pl += "  S" + item.season + " E" + item.episode;
        playBtn = button(btns, R.drawable.ic_play, pl, v -> playDefault());
        button(btns, R.drawable.ic_add, null, v -> addToLibrary());
        watchedBtn = button(btns, R.drawable.ic_check, "Mark as Watched", v -> toggleWatched());
        col.addView(btns);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(this, 380), -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = PAD; lp.bottomMargin = dp(this, 70);
        top.addView(col, lp);

        credits = text(this, "", 11, Color.WHITE, false); credits.setLineSpacing(0, 1.3f); shadow(credits);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(this, 220), -2, Gravity.BOTTOM | Gravity.START);
        rp.leftMargin = (int) (W * 0.71f); rp.bottomMargin = dp(this, 72);
        top.addView(credits, rp);
        return top;
    }
    private void shadow(TextView t) { t.setShadowLayer(dp(this, 3), 0, dp(this, 1), 0x66000000); }
    private String label() { return "series".equals(Nuvio.norm(item.type)) ? "TV Show" : "Movie"; }

    private View button(LinearLayout parent, int icon, String label, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this); b.setGravity(Gravity.CENTER); b.setFocusable(true); b.setClickable(true);
        ImageView ic = new ImageView(this); ic.setImageResource(icon); ic.setColorFilter(Color.WHITE);
        b.addView(ic, new LinearLayout.LayoutParams(dp(this, 13), dp(this, 13)));
        TextView t = null;
        if (label != null) { t = text(this, label, 13, Color.WHITE, true); t.setPadding(dp(this, 6), 0, 0, 0); b.addView(t); b.setPadding(dp(this, 16), 0, dp(this, 18), 0); }
        b.setBackground(round(0x99202024, 16, this));
        final TextView tt = t;
        b.setOnFocusChangeListener((v, f) -> {
            v.setBackground(round(f ? 0xFFF2F2F4 : 0x99202024, 16, this));
            ic.setColorFilter(f ? 0xFF16171B : Color.WHITE); if (tt != null) tt.setTextColor(f ? 0xFF16171B : Color.WHITE);
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(120).start();
        });
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(label != null ? Math.max(dp(this, 100), -2) : dp(this, 31), dp(this, 31));
        if (label != null) lp.width = -2;
        lp.rightMargin = dp(this, 9);
        if (label != null) b.setMinimumWidth(dp(this, 100));
        parent.addView(b, lp);
        return b;
    }

    // ---------- rows ----------
    /** A titled row, hidden until it has something in it. The row's cards go in the section's last child. */
    private LinearLayout section(String name) {
        LinearLayout s = new LinearLayout(this); s.setOrientation(LinearLayout.VERTICAL); s.setClipChildren(false); s.setClipToPadding(false); s.setVisibility(View.GONE);
        TextView t = text(this, name, 18, Color.WHITE, true); t.setPadding(PAD, dp(this, 22), PAD, 0); s.addView(t);
        LinearLayout row = new LinearLayout(this); row.setClipChildren(false); row.setClipToPadding(false); row.setPadding(PAD, dp(this, 18), PAD, dp(this, 24));
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.setClipChildren(false); hs.setClipToPadding(false); hs.addView(row);
        s.addView(hs); s.setTag(row);
        page.addView(s, new LinearLayout.LayoutParams(-1, -2));
        sections.add(s);
        return s;
    }
    private LinearLayout rowOf(LinearLayout sec) { return (LinearLayout) sec.getTag(); }
    private void add(LinearLayout sec, View card, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = gap;
        rowOf(sec).addView(card, lp); sec.setVisibility(View.VISIBLE);
    }
    private void clear(LinearLayout sec) { rowOf(sec).removeAllViews(); sec.setVisibility(View.GONE); }

    /** Focus in a row snaps that row under the top; focus back on the buttons returns to the top. */
    private void follow(View n) {
        if (n == null) return;
        for (View s : sections) if (inside(n, s)) { scroll.smoothScrollTo(0, Math.max(0, s.getTop() - dp(this, 20))); return; }
        if (inside(n, page)) scroll.smoothScrollTo(0, 0);
    }
    private static boolean inside(View v, View group) { while (v != null) { if (v == group) return true; ViewParent p = v.getParent(); v = p instanceof View ? (View) p : null; } return false; }

    private View landscape(String image, String caption, String sub, boolean alwaysShowCaption, View.OnClickListener l) {
        int w = dp(this, 224), h = dp(this, 126);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setFocusable(true); card.setClickable(true); card.setClipChildren(false);
        FrameLayout art = new FrameLayout(this); art.setBackground(round(0xFF2A2C33, Rows.CORNER, this)); art.setClipToOutline(true); Rows.shadow(art);
        ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); art.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        card.addView(art, new LinearLayout.LayoutParams(w, h));
        TextView c = text(this, caption == null ? "" : caption, 13, Color.WHITE, true); c.setSingleLine(true); c.setMaxWidth(w); c.setPadding(0, dp(this, 12), 0, 0);
        TextView s = text(this, sub == null ? "" : sub, 11, 0xB3FFFFFF, false); s.setMaxLines(2); s.setMaxWidth(w); s.setPadding(0, dp(this, 3), 0, 0);
        card.addView(c); card.addView(s);
        if (!alwaysShowCaption) { c.setVisibility(View.INVISIBLE); s.setVisibility(View.INVISIBLE); }
        card.setOnFocusChangeListener((v, f) -> {
            Rows.lift(v, art, f);
            if (!alwaysShowCaption) { c.setVisibility(f ? View.VISIBLE : View.INVISIBLE); s.setVisibility(f ? View.VISIBLE : View.INVISIBLE); }
            c.setTranslationY(f ? dp(this, 12) : 0); s.setTranslationY(f ? dp(this, 12) : 0);
        });
        card.setOnClickListener(l);
        if (image != null && !image.isEmpty()) Images.load(iv, image, w, null);
        card.setTag(art);
        return card;
    }

    private View person(String name, String role, String photo, int tmdbId) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL); card.setFocusable(true); card.setClickable(true);
        FrameLayout ph = new FrameLayout(this); ph.setBackground(round(0xFF4A505C, 42, this)); ph.setClipToOutline(true); Rows.shadow(ph);
        TextView ini = text(this, initials(name), 20, Color.WHITE, true); ini.setGravity(Gravity.CENTER); ph.addView(ini, new FrameLayout.LayoutParams(-1, -1));
        ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); ph.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        card.addView(ph, new LinearLayout.LayoutParams(dp(this, 84), dp(this, 84)));
        TextView n = text(this, name, 12, Color.WHITE, false); n.setGravity(Gravity.CENTER); n.setMaxLines(2); n.setPadding(0, dp(this, 10), 0, 0);
        TextView r = text(this, role == null ? "" : role, 11, 0x99FFFFFF, false); r.setGravity(Gravity.CENTER); r.setMaxLines(2); r.setPadding(0, dp(this, 2), 0, 0);
        card.addView(n, new LinearLayout.LayoutParams(-1, -2)); card.addView(r, new LinearLayout.LayoutParams(-1, -2));
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 100), -2));
        card.setOnFocusChangeListener((v, f) -> {
            ph.animate().scaleX(f ? 1.15f : 1f).scaleY(f ? 1.15f : 1f).translationZ(f ? dp(this, 10) : 0).setDuration(160).start();
            n.setTranslationY(f ? dp(this, 7) : 0); r.setTranslationY(f ? dp(this, 7) : 0);
        });
        if (photo != null && !photo.isEmpty()) Images.load(iv, photo, dp(this, 84), null);
        card.setOnClickListener(v -> {
            if (tmdbId > 0) startActivity(new Intent(this, PersonActivity.class).putExtra("id", tmdbId).putExtra("name", name).putExtra("photo", photo));
            else Toast.makeText(this, "Add a TMDB key in Settings to see " + name + "'s movies.", Toast.LENGTH_LONG).show();
        });
        return card;
    }
    private static String initials(String n) {
        StringBuilder s = new StringBuilder();
        for (String p : n.trim().split("\\s+")) if (!p.isEmpty() && s.length() < 2) s.append(Character.toUpperCase(p.charAt(0)));
        return s.toString();
    }

    // ---------- data ----------
    private void load() {
        new Thread(() -> {
            Nuvio nuvio = new Nuvio(this);
            Nuvio.Home h = nuvio.cached();
            List<Nuvio.Addon> addons = new ArrayList<>();
            if (h != null && h.addons != null) for (int i = 0; i < h.addons.length(); i++) addons.add(Nuvio.addonFrom(h.addons.optJSONObject(i)));
            JSONObject m = nuvio.findMeta(addons, Nuvio.norm(item.type), item.id);
            if (similar.isEmpty() && h != null) fallbackSimilar(h);
            runOnUiThread(() -> { if (m != null) show(m); if (similar.size() > 0 && rowOf(moreSec).getChildCount() == 0) showSimilar(); loadWatched(); });
            JSONObject t = Tmdb.details(this, Nuvio.norm(item.type), item.id);
            if (t != null) runOnUiThread(() -> showTmdb(t));
        }).start();
    }
    /** Not opened from a catalog row (Continue Watching, a folder): use the first catalog of the same kind. */
    private void fallbackSimilar(Nuvio.Home h) {
        String type = Nuvio.norm(item.type);
        for (Nuvio.Row r : h.rows) {
            if (r.continueWatching || r.isCollection() || r.items.size() < 4) continue;
            if (!type.equals(Nuvio.norm(r.items.get(0).type))) continue;
            for (Nuvio.Item s : r.items) { if (similar.size() >= 20) break; if (!s.id.equals(item.id)) similar.add(s); }
            return;
        }
    }

    private void show(JSONObject m) {
        meta = m;
        String bg = Nuvio.nonEmpty(m.optString("background"), item.background);
        if (bg != null && !bg.isEmpty()) Images.load(backdrop, bg, W, null);
        String lg = m.optString("logo");
        if (!lg.isEmpty()) Images.load(logo, lg, dp(this, 300), () -> { logo.setVisibility(View.VISIBLE); title.setVisibility(View.GONE); });
        title.setText(Nuvio.nonEmpty(m.optString("name"), item.name));

        StringBuilder ml = new StringBuilder(label());
        JSONArray g = m.optJSONArray("genres");
        if (g != null) for (int i = 0; i < Math.min(2, g.length()); i++) ml.append(" · ").append(g.optString(i));
        metaText.setText(ml);
        String r = m.optString("imdbRating");
        if (!r.isEmpty()) { chip.setText("★ " + r); chip.setVisibility(View.VISIBLE); }
        String d = m.optString("description"); if (!d.isEmpty()) desc.setText(d);

        List<String> f = new ArrayList<>();
        String year = Nuvio.nonEmpty(m.optString("releaseInfo"), m.optString("year")); if (!year.isEmpty()) f.add(year);
        String rt = runtime(m.optString("runtime")); if (!rt.isEmpty()) f.add(rt);
        facts.setText(String.join(" · ", f));

        setCredits(names(m.optJSONArray("cast"), 3), names(m.optJSONArray("director"), 2));
        if (rowOf(castSec).getChildCount() == 0) {
            JSONArray cast = m.optJSONArray("cast"), dir = m.optJSONArray("director"), wr = m.optJSONArray("writer");
            if (cast != null) for (int i = 0; i < Math.min(12, cast.length()); i++) add(castSec, person(cast.optString(i), "", null, 0), dp(this, 14));
            if (dir != null) for (int i = 0; i < Math.min(2, dir.length()); i++) add(castSec, person(dir.optString(i), "Director", null, 0), dp(this, 14));
            if (wr != null) for (int i = 0; i < Math.min(2, wr.length()); i++) add(castSec, person(wr.optString(i), "Writer", null, 0), dp(this, 14));
        }
        if (rowOf(trailersSec).getChildCount() == 0) {
            JSONArray tr = m.optJSONArray("trailers");   // Cinemeta: [{source: YouTube id, type: "Trailer"}]
            if (tr != null) for (int i = 0; i < tr.length(); i++) { JSONObject t = tr.optJSONObject(i); if (t != null && !t.optString("source").isEmpty()) trailer(t.optString("source"), Nuvio.nonEmpty(t.optString("type"), "Trailer") + (tr.length() > 1 ? " " + (i + 1) : "")); }
            JSONArray ts = m.optJSONArray("trailerStreams");
            if (rowOf(trailersSec).getChildCount() == 0 && ts != null) for (int i = 0; i < ts.length(); i++) { JSONObject t = ts.optJSONObject(i); if (t != null && !t.optString("ytId").isEmpty()) trailer(t.optString("ytId"), "Trailer" + (ts.length() > 1 ? " " + (i + 1) : "")); }
        }

        JSONArray vids = m.optJSONArray("videos");
        if ("series".equals(Nuvio.norm(item.type)) && vids != null && vids.length() > 0) {
            for (int i = 0; i < vids.length(); i++) { JSONObject v = vids.optJSONObject(i); if (v == null) continue; int s = v.optInt("season", 0); seasons.computeIfAbsent(s, k -> new ArrayList<>()).add(v); }
            for (List<JSONObject> eps : seasons.values()) eps.sort((x, y) -> Integer.compare(x.optInt("episode", x.optInt("number")), y.optInt("episode", y.optInt("number"))));
            int start = item.season > 0 && seasons.containsKey(item.season) ? item.season : seasons.firstKey() == 0 && seasons.size() > 1 ? seasons.higherKey(0) : seasons.firstKey();
            buildSeasons(start);
        }
    }

    private void showTmdb(JSONObject t) {
        if (meta == null) {
            String o = t.optString("overview"); if (!o.isEmpty() && desc.getText().length() == 0) desc.setText(o);
        }
        JSONObject cr = t.optJSONObject("credits");
        if (cr != null) {
            JSONArray cast = cr.optJSONArray("cast"), crew = cr.optJSONArray("crew");
            if ((cast != null && cast.length() > 0) || (crew != null && crew.length() > 0)) {
                clear(castSec);
                if (cast != null) for (int i = 0; i < Math.min(15, cast.length()); i++) { JSONObject p = cast.optJSONObject(i); if (p != null) add(castSec, person(p.optString("name"), p.optString("character"), img("w185", p.optString("profile_path")), p.optInt("id")), dp(this, 14)); }
                List<String> dirs = new ArrayList<>();
                if (crew != null) {
                    int writers = 0;
                    for (int i = 0; i < crew.length(); i++) {
                        JSONObject p = crew.optJSONObject(i); if (p == null) continue;
                        String job = p.optString("job");
                        if ("Director".equals(job)) { dirs.add(p.optString("name")); add(castSec, person(p.optString("name"), "Director", img("w185", p.optString("profile_path")), p.optInt("id")), dp(this, 14)); }
                        else if (("Screenplay".equals(job) || "Writer".equals(job)) && writers < 3) { writers++; add(castSec, person(p.optString("name"), job, img("w185", p.optString("profile_path")), p.optInt("id")), dp(this, 14)); }
                    }
                }
                if (credits.getText().length() == 0) {
                    List<String> st = new ArrayList<>(); if (cast != null) for (int i = 0; i < Math.min(3, cast.length()); i++) st.add(cast.optJSONObject(i).optString("name"));
                    setCredits(String.join(", ", st), String.join(", ", dirs.subList(0, Math.min(2, dirs.size()))));
                }
            }
        }
        JSONObject vs = t.optJSONObject("videos");
        JSONArray vr = vs == null ? null : vs.optJSONArray("results");
        if (vr != null && vr.length() > 0) {
            List<JSONObject> yt = new ArrayList<>();
            for (int i = 0; i < vr.length(); i++) { JSONObject v = vr.optJSONObject(i); if (v != null && "YouTube".equals(v.optString("site"))) yt.add(v); }
            yt.sort((a, b) -> Integer.compare(rank(a.optString("type")), rank(b.optString("type"))));
            if (!yt.isEmpty()) { clear(trailersSec); for (int i = 0; i < Math.min(10, yt.size()); i++) trailer(yt.get(i).optString("key"), yt.get(i).optString("name")); }
        }
        JSONObject rv = t.optJSONObject("reviews");
        JSONArray rr = rv == null ? null : rv.optJSONArray("results");
        if (rr != null) for (int i = 0; i < Math.min(10, rr.length()); i++) { JSONObject r = rr.optJSONObject(i); if (r != null) review(r); }
        JSONArray pc = t.optJSONArray("production_companies");
        if (pc != null) for (int i = 0; i < Math.min(8, pc.length()); i++) { JSONObject c = pc.optJSONObject(i); if (c != null) company(c.optString("name"), img("w300", c.optString("logo_path"))); }
    }
    private static int rank(String type) { switch (type) { case "Trailer": return 0; case "Teaser": return 1; case "Clip": return 2; case "Featurette": return 3; case "Behind the Scenes": return 4; default: return 5; } }
    private static String img(String size, String path) { return path == null || path.isEmpty() || "null".equals(path) ? null : Tmdb.IMG + size + path; }

    private void setCredits(String starring, String director) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        if (!starring.isEmpty()) part(s, "Starring ", starring);
        if (!director.isEmpty()) { if (s.length() > 0) s.append("\n"); part(s, "Director ", director); }
        credits.setText(s);
    }
    private static void part(SpannableStringBuilder s, String label, String value) {
        int a = s.length(); s.append(label); s.setSpan(new ForegroundColorSpan(0xA6FFFFFF), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); s.append(value);
    }
    private static String names(JSONArray a, int max) { if (a == null) return ""; StringBuilder s = new StringBuilder(); for (int i = 0; i < Math.min(max, a.length()); i++) s.append(i > 0 ? ", " : "").append(a.optString(i)); return s.toString(); }
    /** "125 min" → "2 hrs 5 mins", as Apple writes it. */
    private static String runtime(String r) {
        if (r == null || r.isEmpty()) return "";
        try {
            int m = Integer.parseInt(r.replaceAll("[^0-9].*$", "").trim());
            if (m <= 0) return r;
            int h = m / 60, mm = m % 60;
            String out = h > 0 ? h + (h == 1 ? " hr" : " hrs") : "";
            if (mm > 0) out += (out.isEmpty() ? "" : " ") + mm + (mm == 1 ? " min" : " mins");
            return out;
        } catch (Exception e) { return r; }
    }

    private void trailer(String yt, String name) {
        add(trailersSec, landscape("https://img.youtube.com/vi/" + yt + "/hqdefault.jpg", name, null, true, v -> youtube(yt)), dp(this, 18));
        View card = rowOf(trailersSec).getChildAt(rowOf(trailersSec).getChildCount() - 1);
        FrameLayout art = (FrameLayout) card.getTag();
        TextView badge = text(this, "▶", 11, Color.WHITE, true); badge.setBackground(round(0x8C000000, 10, this)); badge.setPadding(dp(this, 9), dp(this, 3), dp(this, 9), dp(this, 3));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); bp.setMargins(dp(this, 10), 0, 0, dp(this, 8));
        art.addView(badge, bp);
    }
    private void youtube(String id) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id))); }
            catch (ActivityNotFoundException x) { Toast.makeText(this, "YouTube isn't installed.", Toast.LENGTH_LONG).show(); }
        }
    }

    private void showSimilar() {
        clear(moreSec);
        for (Nuvio.Item s : similar) {
            String sub = s.sub == null || s.sub.isEmpty() ? s.year : s.sub;
            add(moreSec, landscape(Nuvio.nonEmpty(s.background, s.poster), s.name, sub, false, v -> startActivity(intent(this, s, similar))), dp(this, 18));
        }
    }

    private void review(JSONObject r) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setFocusable(true); card.setClickable(true);
        card.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        card.setBackground(round(0x1FFFFFFF, 12, this)); card.setElevation(dp(this, 4));
        String author = Nuvio.nonEmpty(r.optString("author"), "Anonymous");
        JSONObject ad = r.optJSONObject("author_details");
        double rating = ad == null ? -1 : ad.optDouble("rating", -1);
        String body = r.optString("content").replaceAll("[*_#>]", "").replaceAll("\\s+", " ").trim();
        TextView a = text(this, author, 13, Color.WHITE, true); a.setSingleLine(true);
        TextView s = text(this, rating >= 0 ? "★ " + (rating == Math.floor(rating) ? String.valueOf((int) rating) : String.valueOf(rating)) + "/10" : "", 11, 0x99FFFFFF, false); s.setPadding(0, dp(this, 2), 0, 0);
        TextView b = text(this, body, 11, 0xD9FFFFFF, false); b.setMaxLines(4); b.setLineSpacing(0, 1.3f); b.setPadding(0, dp(this, 8), 0, 0);
        card.addView(a); card.addView(s); card.addView(b);
        card.setOnFocusChangeListener((v, f) -> {
            v.setBackground(round(f ? 0x40FFFFFF : 0x1FFFFFFF, 12, this));
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).translationZ(f ? dp(this, 10) : 0).setDuration(160).start();
        });
        card.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(author).setMessage(r.optString("content")).setPositiveButton("Close", null).show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(this, 300), dp(this, 150)); lp.rightMargin = dp(this, 18);
        rowOf(reviewsSec).addView(card, lp); reviewsSec.setVisibility(View.VISIBLE);
    }

    private void company(String name, String logoUrl) {
        FrameLayout card = new FrameLayout(this); card.setFocusable(true); card.setClickable(true);
        card.setBackground(round(0xEEF0F0F4, 10, this)); card.setElevation(dp(this, 4));
        if (logoUrl != null) {
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.FIT_CENTER); iv.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1)); Images.load(iv, logoUrl, dp(this, 170), null);
        } else {
            TextView t = text(this, name, 13, 0xFF1B1C20, true); t.setGravity(Gravity.CENTER); t.setPadding(dp(this, 10), 0, dp(this, 10), 0); t.setMaxLines(2);
            card.addView(t, new FrameLayout.LayoutParams(-1, -1));
        }
        card.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.1f : 1f).scaleY(f ? 1.1f : 1f).translationZ(f ? dp(this, 10) : 0).setDuration(160).start());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(this, 170), dp(this, 80)); lp.rightMargin = dp(this, 18);
        rowOf(companiesSec).addView(card, lp); companiesSec.setVisibility(View.VISIBLE);
    }

    // ---------- series ----------
    private void buildSeasons(int selected) {
        seasonsRow.removeAllViews();
        if (seasons.size() > 1 || !seasons.containsKey(1)) for (int s : seasons.keySet()) {
            TextView t = text(this, s == 0 ? "Specials" : "Season " + s, 14, Color.WHITE, true);
            t.setPadding(dp(this, 14), dp(this, 6), dp(this, 14), dp(this, 6)); t.setFocusable(true); t.setClickable(true);
            t.setBackground(round(s == selected ? 0x4DFFFFFF : 0x1FFFFFFF, 16, this));
            t.setOnFocusChangeListener((v, f) -> { v.setBackground(round(f ? 0xFFF2F2F4 : (s == selected ? 0x4DFFFFFF : 0x1FFFFFFF), 16, this)); t.setTextColor(f ? 0xFF16171B : Color.WHITE); });
            t.setOnClickListener(v -> buildSeasons(s));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(this, 8);
            seasonsRow.addView(t, lp);
        }
        buildEpisodes(selected);
    }
    private void buildEpisodes(int season) {
        clear(episodesSec);
        List<JSONObject> eps = seasons.get(season); if (eps == null) return;
        for (JSONObject v : eps) {
            int ep = v.optInt("episode", v.optInt("number"));
            String raw = Nuvio.nonEmpty(v.optString("name"), v.optString("title"));
            final String name = raw.isEmpty() ? "Episode " + ep : raw;
            String sum = Nuvio.nonEmpty(v.optString("overview"), v.optString("description"));
            View card = landscape(v.optString("thumbnail"), name, sum, true, x -> play(v.optString("id"), season, ep, name));
            TextView num = text(this, "EPISODE " + ep, 10, 0x99FFFFFF, true); num.setLetterSpacing(0.08f); num.setPadding(0, dp(this, 12), 0, 0);
            LinearLayout c = (LinearLayout) card; c.addView(num, 1);
            ((TextView) c.getChildAt(2)).setPadding(0, dp(this, 2), 0, 0);
            card.setOnFocusChangeListener(chain(card.getOnFocusChangeListener(), (x, f) -> num.setTranslationY(f ? dp(this, 12) : 0)));
            add(episodesSec, card, dp(this, 18));
        }
    }
    private static View.OnFocusChangeListener chain(View.OnFocusChangeListener a, View.OnFocusChangeListener b) { return (v, f) -> { if (a != null) a.onFocusChange(v, f); b.onFocusChange(v, f); }; }

    // ---------- buttons ----------
    private void addToLibrary() {
        new Thread(() -> { boolean ok = new Nuvio(this).addToLibrary(item); runOnUiThread(() -> Toast.makeText(this, ok ? "Added " + item.name + " to your Nuvio library" : "Couldn't add to your Nuvio library", Toast.LENGTH_SHORT).show()); }).start();
    }
    /** What the watched button covers: the movie, or every episode of the series (specials aside). */
    private List<Nuvio.Mark> marks() {
        List<Nuvio.Mark> m = new ArrayList<>();
        if (!"series".equals(Nuvio.norm(item.type))) { m.add(new Nuvio.Mark(-1, -1)); return m; }
        for (java.util.Map.Entry<Integer, List<JSONObject>> e : seasons.entrySet()) {
            if (e.getKey() == 0 && seasons.size() > 1) continue;
            for (JSONObject v : e.getValue()) m.add(new Nuvio.Mark(e.getKey(), v.optInt("episode", v.optInt("number"))));
        }
        return m;
    }
    private void loadWatched() {
        new Thread(() -> {
            java.util.Set<String> w = new Nuvio(this).watched(item.id);
            if (w == null) return;
            List<Nuvio.Mark> m = marks();
            boolean all = !m.isEmpty(); for (Nuvio.Mark k : m) if (!w.contains(k.season + ":" + k.episode)) { all = false; break; }
            final boolean done = all;
            runOnUiThread(() -> showWatched(done));
        }).start();
    }
    private void showWatched(boolean w) {
        isWatched = w;
        LinearLayout b = (LinearLayout) watchedBtn;
        ((TextView) b.getChildAt(1)).setText(w ? "Watched" : "Mark as Watched");
    }
    private void toggleWatched() {
        boolean to = isWatched == null || !isWatched;
        List<Nuvio.Mark> m = marks();
        if (m.isEmpty()) { Toast.makeText(this, "Still loading the episodes…", Toast.LENGTH_SHORT).show(); return; }
        String name = title.getText().toString();
        new Thread(() -> {
            boolean ok = new Nuvio(this).setWatched(item.id, item.type, name, m, to);
            runOnUiThread(() -> {
                if (ok) { showWatched(to); Toast.makeText(this, to ? "Marked as watched in Nuvio" : "Marked as unwatched in Nuvio", Toast.LENGTH_SHORT).show(); }
                else Toast.makeText(this, "Couldn't update Nuvio. Try again.", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_DOWN && scroll != null && scroll.getScrollY() > 0) {
            playBtn.requestFocus(); scroll.smoothScrollTo(0, 0); return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP && scroll != null && scroll.getScrollY() > 0) return true;
        return super.dispatchKeyEvent(e);
    }

    // ---------- hand-off to Nuvio ----------
    private void playDefault() {
        boolean series = "series".equals(Nuvio.norm(item.type));
        if (!series) { play(item.id, -1, -1, null); return; }
        if (item.season > 0) { play(Nuvio.nonEmpty(item.videoId, item.id + ":" + item.season + ":" + item.episode), item.season, item.episode, null); return; }
        if (!seasons.isEmpty()) {   // first episode of the first real season
            int s = seasons.firstKey() == 0 && seasons.size() > 1 ? seasons.higherKey(0) : seasons.firstKey();
            JSONObject v = seasons.get(s).get(0); int ep = v.optInt("episode", v.optInt("number"));
            play(v.optString("id"), s, ep, v.optString("name")); return;
        }
        openNuvioDetail();
    }
    /** The launch request Nuvio's own Continue Watching tiles send (MainActivity, launchMode=stream). */
    private void play(String videoId, int season, int episode, String episodeTitle) {
        String pkg = nuvioPackage();
        if (pkg == null) { Toast.makeText(this, "Nuvio isn't installed.", Toast.LENGTH_LONG).show(); return; }
        String type = Nuvio.norm(item.type);
        String name = meta != null ? Nuvio.nonEmpty(meta.optString("name"), item.name) : item.name;
        Intent i = new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(pkg, "com.nuvio.tv.MainActivity"))
                .putExtra("contentId", item.id).putExtra("contentType", type).putExtra("videoId", videoId)
                .putExtra("name", name).putExtra("poster", item.poster)
                .putExtra("backdrop", meta != null ? Nuvio.nonEmpty(meta.optString("background"), item.background) : item.background)
                .putExtra("logo", meta != null ? meta.optString("logo") : null)
                .putExtra("launchMode", "stream")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (season >= 0) { i.putExtra("season", season); i.putExtra("episode", episode); }
        if (episodeTitle != null && !episodeTitle.isEmpty()) i.putExtra("episodeTitle", episodeTitle);
        try { startActivity(i); }
        catch (RuntimeException e) { openNuvioDetail(); }   // Nuvio changed its launch request: fall back to its details page
    }
    private void openNuvioDetail() {
        Uri link = Uri.parse("nuvio://detail/" + Nuvio.norm(item.type) + "/" + Uri.encode(item.id, ":"));
        try { startActivity(new Intent(Intent.ACTION_VIEW, link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "Nuvio isn't installed.", Toast.LENGTH_LONG).show(); }
    }
    private String nuvioPackage() {
        PackageManager pm = getPackageManager();
        for (String p : NUVIO_PACKAGES) { try { pm.getPackageInfo(p, 0); return p; } catch (PackageManager.NameNotFoundException ignored) { } }
        return null;
    }
}
