package tv.gallery.nuviohome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static tv.gallery.nuviohome.Rows.dp;
import static tv.gallery.nuviohome.Rows.round;
import static tv.gallery.nuviohome.Rows.text;

/**
 * A cast or crew member's page, from TMDB: photo, name, a short bio, then their Movies, TV Shows and work behind the
 * camera. Selecting a title looks up its IMDb id (what Nuvio and the addons use) and opens its details page.
 */
public class PersonActivity extends Activity {
    private int PAD;
    private DetailActivity.Page scroll;
    private LinearLayout page;
    private TextView bio, sub, status;
    private final List<View> sections = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int id = getIntent().getIntExtra("id", 0);
        String name = getIntent().getStringExtra("name");
        String photo = getIntent().getStringExtra("photo");
        PAD = dp(this, 56);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF2A2C33, 0xFF1A1B20, 0xFF141519}));
        scroll = new DetailActivity.Page(this); scroll.setVerticalScrollBarEnabled(false); scroll.setClipChildren(false); scroll.setClipToPadding(false);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setClipChildren(false); page.setClipToPadding(false);
        page.setPadding(0, dp(this, 48), 0, getResources().getDisplayMetrics().heightPixels / 2);

        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(PAD, 0, PAD, dp(this, 8));
        FrameLayout ph = new FrameLayout(this); ph.setBackground(round(0xFF4A505C, 64, this)); ph.setClipToOutline(true); Rows.shadow(ph);
        TextView ini = text(this, initials(name), 34, Color.WHITE, true); ini.setGravity(Gravity.CENTER); ph.addView(ini, new FrameLayout.LayoutParams(-1, -1));
        ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); ph.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        head.addView(ph, new LinearLayout.LayoutParams(dp(this, 128), dp(this, 128)));
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(this, 28), 0, 0, 0);
        col.addView(text(this, name == null ? "" : name, 30, Color.WHITE, true));
        sub = text(this, "", 13, 0xB3FFFFFF, true); sub.setPadding(0, dp(this, 8), 0, 0); col.addView(sub);
        bio = text(this, "", 12, 0xD9FFFFFF, false); bio.setMaxLines(4); bio.setLineSpacing(0, 1.3f); bio.setMaxWidth(dp(this, 560)); bio.setPadding(0, dp(this, 10), 0, 0); col.addView(bio);
        head.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(head);
        status = text(this, "Loading…", 14, 0xB3FFFFFF, false); status.setPadding(PAD, dp(this, 30), PAD, 0); page.addView(status);
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> follow(n));
        if (photo != null && !photo.isEmpty()) Images.load(iv, photo.replace("/w185/", "/h632/"), dp(this, 128), null);

        new Thread(() -> {
            JSONObject p = Tmdb.person(this, id);
            runOnUiThread(() -> { if (p == null) status.setText("Couldn't load this person from TMDB. Check the TMDB key in Settings."); else show(p); });
        }).start();
    }

    private void show(JSONObject p) {
        status.setVisibility(View.GONE);
        List<String> s = new ArrayList<>();
        String dept = p.optString("known_for_department"); if (!dept.isEmpty() && !"null".equals(dept)) s.add(dept);
        String born = p.optString("birthday"); if (born.length() >= 4 && !"null".equals(born)) s.add("Born " + born.substring(0, 4) + place(p.optString("place_of_birth")));
        String died = p.optString("deathday"); if (died.length() >= 4 && !"null".equals(died)) s.add("Died " + died.substring(0, 4));
        sub.setText(String.join(" · ", s));
        String b = p.optString("biography"); bio.setText("null".equals(b) ? "" : b);

        JSONObject cc = p.optJSONObject("combined_credits");
        JSONArray cast = cc == null ? null : cc.optJSONArray("cast"), crew = cc == null ? null : cc.optJSONArray("crew");
        List<JSONObject> movies = new ArrayList<>(), shows = new ArrayList<>(), behind = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (cast != null) for (int i = 0; i < cast.length(); i++) {
            JSONObject c = cast.optJSONObject(i); if (c == null) continue;
            String mt = c.optString("media_type");
            if ("tv".equals(mt) && c.optInt("episode_count") <= 1 && c.optString("character").toLowerCase().contains("self")) continue;   // talk-show guest spots
            if (!seen.add(mt + c.optInt("id"))) continue;
            ("tv".equals(mt) ? shows : movies).add(c);
        }
        Set<String> seenCrew = new HashSet<>();
        if (crew != null) for (int i = 0; i < crew.length(); i++) {
            JSONObject c = crew.optJSONObject(i); if (c == null) continue;
            String key = c.optString("media_type") + c.optInt("id");
            if (seen.contains(key) || !seenCrew.add(key)) continue;
            behind.add(c);
        }
        for (List<JSONObject> l : java.util.Arrays.asList(movies, shows, behind)) l.sort((x, y) -> Double.compare(y.optDouble("popularity", 0) * Math.log(2 + y.optInt("vote_count")), x.optDouble("popularity", 0) * Math.log(2 + x.optInt("vote_count"))));
        boolean director = "Directing".equals(dept) || "Writing".equals(dept);
        if (director) row("Behind the Camera", behind, true);
        row("Movies", movies, false);
        row("TV Shows", shows, false);
        if (!director) row("Behind the Camera", behind, true);
        if (sections.isEmpty()) { status.setText("TMDB lists no titles for this person."); status.setVisibility(View.VISIBLE); }
        else sections.get(0).post(() -> { View first = ((LinearLayout) sections.get(0).getTag()).getChildAt(0); if (first != null) first.requestFocus(); });
    }
    private static String place(String p) {
        if (p == null || p.isEmpty() || "null".equals(p)) return "";
        String[] parts = p.split(",");
        return " in " + parts[0].trim() + (parts.length > 1 ? ", " + parts[parts.length - 1].trim() : "");
    }

    private void row(String name, List<JSONObject> items, boolean showJob) {
        if (items.isEmpty()) return;
        LinearLayout s = new LinearLayout(this); s.setOrientation(LinearLayout.VERTICAL); s.setClipChildren(false); s.setClipToPadding(false);
        TextView t = text(this, name, 18, Color.WHITE, true); t.setPadding(PAD, dp(this, 26), PAD, 0); s.addView(t);
        LinearLayout row = new LinearLayout(this); row.setClipChildren(false); row.setClipToPadding(false); row.setPadding(PAD, dp(this, 18), PAD, dp(this, 24));
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.setClipChildren(false); hs.setClipToPadding(false); hs.addView(row);
        s.addView(hs); s.setTag(row);
        for (int i = 0; i < Math.min(30, items.size()); i++) {
            JSONObject c = items.get(i);
            boolean tv = "tv".equals(c.optString("media_type"));
            String title = Nuvio.nonEmpty(c.optString("title"), c.optString("name"));
            String date = Nuvio.nonEmpty(c.optString("release_date"), c.optString("first_air_date"));
            String year = date != null && date.length() >= 4 ? date.substring(0, 4) : "";
            String role = showJob ? c.optString("job") : c.optString("character");
            String subLine = year + (role.isEmpty() || "null".equals(role) ? "" : (year.isEmpty() ? "" : " · ") + role);
            String bd = c.optString("backdrop_path"), po = c.optString("poster_path");
            String img = !bd.isEmpty() && !"null".equals(bd) ? Tmdb.IMG + "w500" + bd : !po.isEmpty() && !"null".equals(po) ? Tmdb.IMG + "w342" + po : null;
            String poster = !po.isEmpty() && !"null".equals(po) ? Tmdb.IMG + "w342" + po : null;
            row.addView(card(img, title, subLine, v -> open(tv, c.optInt("id"), title, poster, img, year)), lp());
        }
        page.addView(s, new LinearLayout.LayoutParams(-1, -2));
        sections.add(s);
    }
    private LinearLayout.LayoutParams lp() { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.rightMargin = dp(this, 18); return l; }

    private View card(String image, String caption, String subLine, View.OnClickListener l) {
        int w = dp(this, 224), h = dp(this, 126);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setFocusable(true); card.setClickable(true); card.setClipChildren(false);
        FrameLayout art = new FrameLayout(this); art.setBackground(round(0xFF2A2C33, Rows.CORNER, this)); art.setClipToOutline(true); Rows.shadow(art);
        TextView fb = text(this, caption, 14, 0xB3FFFFFF, true); fb.setGravity(Gravity.CENTER); fb.setPadding(dp(this, 10), 0, dp(this, 10), 0); art.addView(fb, new FrameLayout.LayoutParams(-1, -1));
        ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); art.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        card.addView(art, new LinearLayout.LayoutParams(w, h));
        TextView c = text(this, caption, 13, Color.WHITE, true); c.setSingleLine(true); c.setMaxWidth(w); c.setPadding(0, dp(this, 12), 0, 0);
        TextView s = text(this, subLine, 11, 0xB3FFFFFF, false); s.setSingleLine(true); s.setMaxWidth(w); s.setPadding(0, dp(this, 3), 0, 0);
        card.addView(c); card.addView(s);
        card.setOnFocusChangeListener((v, f) -> { Rows.lift(v, art, f); c.setTranslationY(f ? dp(this, 12) : 0); s.setTranslationY(f ? dp(this, 12) : 0); });
        card.setOnClickListener(l);
        if (image != null) Images.load(iv, image, w, null);
        return card;
    }

    /** Nuvio and the addons know titles by IMDb id, so look that up before opening the details page. */
    private void open(boolean tv, int tmdbId, String title, String poster, String background, String year) {
        new Thread(() -> {
            String imdb = Tmdb.imdbId(this, tv ? "tv" : "movie", tmdbId);
            runOnUiThread(() -> {
                if (imdb == null) { Toast.makeText(this, "Nuvio can't open " + title + ": TMDB has no IMDb id for it.", Toast.LENGTH_LONG).show(); return; }
                Nuvio.Item it = new Nuvio.Item();
                it.type = tv ? "series" : "movie"; it.id = imdb; it.name = title; it.poster = poster; it.background = background; it.year = year; it.sub = year;
                startActivity(DetailActivity.intent(this, it, null));
            });
        }).start();
    }

    private void follow(View n) {
        if (n == null) return;
        for (View s : sections) if (inside(n, s)) { scroll.smoothScrollTo(0, Math.max(0, s.getTop() - dp(this, 20))); return; }
    }
    private static boolean inside(View v, View group) { while (v != null) { if (v == group) return true; ViewParent p = v.getParent(); v = p instanceof View ? (View) p : null; } return false; }
    private static String initials(String n) {
        StringBuilder s = new StringBuilder();
        if (n != null) for (String p : n.trim().split("\\s+")) if (!p.isEmpty() && s.length() < 2) s.append(Character.toUpperCase(p.charAt(0)));
        return s.toString();
    }
}
