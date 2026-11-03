package tv.gallery.nuviohome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
 * A title's details, from the owner's addons (the same metadata Nuvio uses). Play hands the title, or the chosen
 * episode, to the official Nuvio's sources list with the same launch request Nuvio's own Continue Watching tiles
 * use; if that request ever stops working, it falls back to Nuvio's details page (nuvio://detail).
 */
public class DetailActivity extends Activity {
    private static final String[] NUVIO_PACKAGES = {"com.nuvio.tv", "com.nuvio.app"};
    private Nuvio.Item item;
    private JSONObject meta;
    private ImageView backdrop, logo;
    private TextView title, metaLine, credits, desc, status;
    private LinearLayout buttons, seasonsRow, episodesRow, seriesBox;
    private final TreeMap<Integer, List<JSONObject>> seasons = new TreeMap<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { item = Nuvio.Item.from(new JSONObject(getIntent().getStringExtra("item"))); } catch (Exception e) { finish(); return; }
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;

        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF0D0D0D);
        backdrop = new ImageView(this); backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF0D0D0D, 0xF00D0D0D, 0xB30D0D0D, 0x4D0D0D0D, 0x000D0D0D}));
        root.addView(left, new FrameLayout.LayoutParams((int) (W * 0.8f), -1));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x000D0D0D, 0x000D0D0D, 0xE60D0D0D}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); scroll.setClipChildren(false); scroll.setClipToPadding(false);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setClipChildren(false);
        page.setPadding(dp(this, 56), (int) (H * 0.22f), dp(this, 56), dp(this, 48)); page.setClipToPadding(false);
        logo = new ImageView(this); logo.setAdjustViewBounds(true); logo.setScaleType(ImageView.ScaleType.FIT_START); logo.setVisibility(View.GONE);
        page.addView(logo, new LinearLayout.LayoutParams((int) (W * 0.34f), dp(this, 100)));
        title = text(this, item.name, 36, Color.WHITE, true); title.setMaxWidth((int) (W * 0.6f));
        page.addView(title);
        metaLine = text(this, item.sub == null ? "" : item.sub, 15, 0xFFB3B3B3, false); metaLine.setPadding(0, dp(this, 12), 0, 0);
        page.addView(metaLine);
        buttons = new LinearLayout(this); buttons.setClipChildren(false); buttons.setClipToPadding(false); buttons.setPadding(dp(this, 4), dp(this, 22), dp(this, 4), dp(this, 6));
        page.addView(buttons);
        credits = text(this, "", 14, 0xFFB3B3B3, false); credits.setPadding(0, dp(this, 18), 0, 0); credits.setMaxWidth((int) (W * 0.6f));
        page.addView(credits);
        desc = text(this, item.description == null ? "" : item.description, 15, Color.WHITE, false);
        desc.setLineSpacing(0, 1.35f); desc.setMaxLines(6); desc.setMaxWidth((int) (W * 0.55f)); desc.setPadding(0, dp(this, 10), 0, 0);
        page.addView(desc);
        status = text(this, "Loading details…", 13, 0xFF808080, false); status.setPadding(0, dp(this, 10), 0, 0);
        page.addView(status);

        seriesBox = new LinearLayout(this); seriesBox.setOrientation(LinearLayout.VERTICAL); seriesBox.setVisibility(View.GONE); seriesBox.setClipChildren(false);
        seasonsRow = new LinearLayout(this); seasonsRow.setPadding(0, dp(this, 28), 0, dp(this, 8));
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.addView(seasonsRow);
        seriesBox.addView(hs);
        episodesRow = new LinearLayout(this); episodesRow.setClipChildren(false); episodesRow.setPadding(dp(this, 4), dp(this, 10), dp(this, 40), dp(this, 10));
        HorizontalScrollView he = new HorizontalScrollView(this); he.setHorizontalScrollBarEnabled(false); he.setClipChildren(false); he.setClipToPadding(false); he.addView(episodesRow);
        seriesBox.addView(he);
        page.addView(seriesBox);

        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        Images.load(backdrop, Nuvio.nonEmpty(item.background, item.poster), W, null);
        buildButtons();
        load();
    }

    // ---------- buttons ----------
    private View pill(String label, boolean primary, View.OnClickListener l) {
        TextView t = text(this, label, 16, primary ? 0xFF000000 : Color.WHITE, true);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(this, 26), dp(this, 12), dp(this, 26), dp(this, 12));
        t.setBackground(round(primary ? 0xFFFFFFFF : 0xFF2A2A2E, 30, this)); t.setFocusable(true); t.setClickable(true);
        t.setOnFocusChangeListener((v, f) -> {
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(120).start();
            if (!primary) { v.setBackground(round(f ? 0xFFF5F5F5 : 0xFF2A2A2E, 30, this)); t.setTextColor(f ? 0xFF111111 : Color.WHITE); }
            else { GradientDrawable g = round(0xFFFFFFFF, 30, this); if (f) g.setStroke(dp(this, 3), 0x99FFFFFF); v.setBackground(g); }
        });
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(this, 18);
        t.setPivotX(0);
        buttons.addView(t, lp);
        return t;
    }
    private void buildButtons() {
        buttons.removeAllViews();
        boolean resume = item.pct > 0;
        String playLabel = "▶  " + (resume ? "Resume" : "Play");
        if (item.season > 0) playLabel += "  S" + item.season + " E" + item.episode;
        View play = pill(playLabel, true, v -> playDefault());
        pill("Open in Nuvio", false, v -> openNuvioDetail());
        play.requestFocus();
    }

    // ---------- data ----------
    private void load() {
        new Thread(() -> {
            Nuvio nuvio = new Nuvio(this);
            Nuvio.Home h = nuvio.cached();
            List<Nuvio.Addon> addons = new ArrayList<>();
            if (h != null && h.addons != null) for (int i = 0; i < h.addons.length(); i++) addons.add(Nuvio.addonFrom(h.addons.optJSONObject(i)));
            JSONObject m = nuvio.findMeta(addons, Nuvio.norm(item.type), item.id);
            runOnUiThread(() -> { if (m == null) status.setText("No extra details from your addons."); else show(m); });
        }).start();
    }
    private void show(JSONObject m) {
        meta = m; status.setVisibility(View.GONE);
        int W = getResources().getDisplayMetrics().widthPixels;
        String bg = Nuvio.nonEmpty(m.optString("background"), item.background);
        if (bg != null && !bg.isEmpty()) Images.load(backdrop, bg, W, null);
        String lg = m.optString("logo");
        if (!lg.isEmpty()) Images.load(logo, lg, (int) (W * 0.34f), () -> { logo.setVisibility(View.VISIBLE); title.setVisibility(View.GONE); });
        title.setText(Nuvio.nonEmpty(m.optString("name"), item.name));

        List<String> bits = new ArrayList<>();
        JSONArray g = m.optJSONArray("genres");
        if (g != null) { StringBuilder gs = new StringBuilder(); for (int i = 0; i < Math.min(3, g.length()); i++) gs.append(i > 0 ? " · " : "").append(g.optString(i)); if (gs.length() > 0) bits.add(gs.toString()); }
        String year = Nuvio.nonEmpty(m.optString("releaseInfo"), m.optString("year")); if (!year.isEmpty()) bits.add(year);
        String rt = m.optString("runtime"); if (!rt.isEmpty()) bits.add(rt);
        String r = m.optString("imdbRating"); if (!r.isEmpty()) bits.add("IMDb " + r);
        metaLine.setText(String.join("   •   ", bits));

        StringBuilder cr = new StringBuilder();
        String dir = list(m.optJSONArray("director"), 2); if (!dir.isEmpty()) cr.append("Director: ").append(dir);
        String cast = list(m.optJSONArray("cast"), 5); if (!cast.isEmpty()) cr.append(cr.length() > 0 ? "\n" : "").append("Cast: ").append(cast);
        credits.setText(cr); credits.setVisibility(cr.length() == 0 ? View.GONE : View.VISIBLE);
        String d = m.optString("description"); if (!d.isEmpty()) desc.setText(d);

        JSONArray vids = m.optJSONArray("videos");
        if ("series".equals(Nuvio.norm(item.type)) && vids != null && vids.length() > 0) {
            for (int i = 0; i < vids.length(); i++) { JSONObject v = vids.optJSONObject(i); if (v == null) continue; int s = v.optInt("season", 0); seasons.computeIfAbsent(s, k -> new ArrayList<>()).add(v); }
            for (List<JSONObject> eps : seasons.values()) eps.sort((x, y) -> Integer.compare(x.optInt("episode", x.optInt("number")), y.optInt("episode", y.optInt("number"))));
            seriesBox.setVisibility(View.VISIBLE);
            int start = item.season > 0 && seasons.containsKey(item.season) ? item.season : seasons.firstKey() == 0 && seasons.size() > 1 ? seasons.higherKey(0) : seasons.firstKey();
            buildSeasons(start);
        }
    }
    private static String list(JSONArray a, int max) { if (a == null) return ""; StringBuilder s = new StringBuilder(); for (int i = 0; i < Math.min(max, a.length()); i++) s.append(i > 0 ? ", " : "").append(a.optString(i)); return s.toString(); }

    private void buildSeasons(int selected) {
        seasonsRow.removeAllViews();
        for (int s : seasons.keySet()) {
            TextView t = text(this, s == 0 ? "Specials" : "Season " + s, 15, Color.WHITE, s == selected);
            t.setPadding(dp(this, 18), dp(this, 8), dp(this, 18), dp(this, 8)); t.setFocusable(true); t.setClickable(true);
            t.setBackground(round(s == selected ? 0x33FFFFFF : 0x00000000, 20, this));
            t.setOnFocusChangeListener((v, f) -> { v.setBackground(round(f ? 0xFFF5F5F5 : (s == selected ? 0x33FFFFFF : 0x00000000), 20, this)); t.setTextColor(f ? 0xFF111111 : Color.WHITE); if (f && s != selected) { buildEpisodes(s); } });
            t.setOnClickListener(v -> buildSeasons(s));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(this, 8);
            seasonsRow.addView(t, lp);
        }
        buildEpisodes(selected);
    }
    private void buildEpisodes(int season) {
        episodesRow.removeAllViews();
        List<JSONObject> eps = seasons.get(season); if (eps == null) return;
        int w = dp(this, 240), h = dp(this, 135);
        for (JSONObject v : eps) {
            int ep = v.optInt("episode", v.optInt("number"));
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setFocusable(true); card.setClickable(true);
            FrameLayout art = new FrameLayout(this); art.setBackground(round(0xFF22232A, Rows.CORNER, this)); art.setClipToOutline(true);
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); art.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            TextView tag = text(this, "E" + ep, 12, Color.WHITE, true); tag.setBackground(round(0xBF000000, 6, this)); tag.setPadding(dp(this, 8), dp(this, 3), dp(this, 8), dp(this, 3));
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); tp.setMargins(dp(this, 8), 0, 0, dp(this, 8)); art.addView(tag, tp);
            card.addView(art, new LinearLayout.LayoutParams(w, h));
            String raw = Nuvio.nonEmpty(v.optString("name"), v.optString("title"));
            final String name = raw.isEmpty() ? "Episode " + ep : raw;
            TextView t = text(this, name, 13, Color.WHITE, false); t.setSingleLine(true); t.setMaxWidth(w); t.setPadding(0, dp(this, 8), 0, 0); card.addView(t);
            card.setOnFocusChangeListener((vw, f) -> Rows.lift(vw, art, f));
            card.setOnClickListener(vw -> play(v.optString("id"), season, ep, name));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(this, 16);
            episodesRow.addView(card, lp);
            String thumb = v.optString("thumbnail"); if (!thumb.isEmpty()) Images.load(iv, thumb, w, null);
        }
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
