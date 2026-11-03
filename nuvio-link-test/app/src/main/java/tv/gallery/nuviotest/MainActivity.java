package tv.gallery.nuviotest;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test for the "official Nuvio" approach, before any launcher work:
 * sign in through Nuvio's documented public API (https://nuvio.tv/docs), read the profile's addons
 * and watch progress, build a Continue Watching row and one catalog row from the addons, and open
 * a title in the official Nuvio app with its nuvio://detail link. Every step is written to the
 * results panel. The password is never stored; only Nuvio's refresh token is kept, privately.
 */
public class MainActivity extends Activity {
    // Published for third-party clients at https://nuvio.tv/docs#publishable-key
    private static final String API = "https://api.nuvio.tv";
    private static final String KEY = "sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN";

    private final ExecutorService net = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> images = new LruCache<>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private SharedPreferences prefs;
    private String access;
    private int profile = 1;

    private LinearLayout page;
    private TextView log;
    private LinearLayout profileRow, cwRow, rowsBox;
    private TextView cwTitle;

    // ---------- small view helpers ----------
    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }
    private GradientDrawable round(int color, float r) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(r)); return g; }
    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(15); b.setOnClickListener(l);
        b.setTextColor(Color.WHITE); b.setBackground(round(0xFF2A2A30, 22)); b.setPadding(dp(20), dp(8), dp(20), dp(8));
        b.setOnFocusChangeListener((v, f) -> { v.setBackground(round(f ? 0xFFF4F4F6 : 0xFF2A2A30, 22)); ((Button) v).setTextColor(f ? 0xFF121316 : Color.WHITE); });
        return b;
    }
    private LinearLayout.LayoutParams wrap(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); p.topMargin = dp(top); return p; }

    private void say(String line) { ui.post(() -> log.append(line + "\n")); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("nuvio", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0B0C10);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(28), dp(48), dp(28));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.addView(page);
        folderPage = new LinearLayout(this); folderPage.setOrientation(LinearLayout.VERTICAL); folderPage.setPadding(dp(48), dp(28), dp(48), dp(28)); folderPage.setVisibility(View.GONE);
        root.addView(folderPage);
        scroll.addView(root);
        setContentView(scroll);
        String rt = prefs.getString("refresh", null);
        if (rt != null) { showResults(); net.execute(() -> { if (refresh(rt)) runChecks(); else ui.post(this::showSignIn); }); }
        else showSignIn();
    }

    // ---------- sign in ----------
    private void showSignIn() {
        page.removeAllViews();
        page.addView(text("Nuvio link test", 26, Color.WHITE, true));
        page.addView(text("Sign in with your Nuvio account email and password. This uses Nuvio's public API. The password is sent only to api.nuvio.tv and is not stored; the app keeps Nuvio's session token on this TV until you sign out.", 15, 0xFFB3B3B3, false), wrap(10));
        EditText email = new EditText(this); email.setHint("Email"); email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass = new EditText(this); pass.setHint("Password"); pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        for (EditText e : new EditText[]{email, pass}) { e.setTextColor(Color.WHITE); e.setHintTextColor(0xFF808080); e.setMinWidth(dp(420)); page.addView(e, wrap(14)); }
        TextView err = text("", 14, 0xFFF2A3A3, false);
        page.addView(button("Sign in", v -> {
            String em = email.getText().toString().trim(), pw = pass.getText().toString();
            if (em.isEmpty() || pw.isEmpty()) { err.setText("Enter your email and password."); return; }
            err.setText("Signing in…");
            net.execute(() -> {
                try {
                    JSONObject body = new JSONObject().put("email", em).put("password", pw);
                    Resp r = api("POST", "/auth/v1/token?grant_type=password", body.toString(), false);
                    if (r.code != 200) { ui.post(() -> err.setText("Sign-in failed (HTTP " + r.code + "): " + shortMsg(r.body))); return; }
                    keepTokens(new JSONObject(r.body));
                    ui.post(() -> { showResults(); net.execute(this::runChecks); });
                } catch (Exception e) { ui.post(() -> err.setText("Sign-in failed: " + e.getMessage())); }
            });
        }), wrap(18));
        page.addView(err, wrap(10));
        email.requestFocus();
    }
    private void keepTokens(JSONObject j) {
        access = j.optString("access_token", null);
        String rt = j.optString("refresh_token", null);
        if (rt != null) prefs.edit().putString("refresh", rt).apply();
    }
    private boolean refresh(String rt) {
        try {
            Resp r = api("POST", "/auth/v1/token?grant_type=refresh_token", new JSONObject().put("refresh_token", rt).toString(), false);
            if (r.code != 200) { prefs.edit().remove("refresh").apply(); return false; }
            keepTokens(new JSONObject(r.body)); return true;
        } catch (Exception e) { return false; }
    }

    // ---------- results screen ----------
    private void showResults() {
        page.removeAllViews();
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Nuvio link test", 26, Color.WHITE, true));
        View sp = new View(this); head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        head.addView(button("Sign out", v -> { prefs.edit().clear().apply(); access = null; showSignIn(); }));
        page.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout keyRow = new LinearLayout(this); keyRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText key = new EditText(this); key.setHint(tmdbKey().isEmpty() ? "TMDB API key or read access token (for TMDB folders)" : "TMDB key saved, type a new one to replace it");
        key.setTextColor(Color.WHITE); key.setHintTextColor(0xFF808080); key.setSingleLine(true); key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyRow.addView(key, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        keyRow.addView(button("Save key", v -> { String k = key.getText().toString().trim(); if (!k.isEmpty()) { prefs.edit().putString("tmdb", k).apply(); key.setText(""); key.setHint("TMDB key saved"); say("✓ TMDB key saved on this TV"); } }));
        page.addView(keyRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(text("Profile", 14, 0xFF808080, false), wrap(14));
        profileRow = new LinearLayout(this); page.addView(profileRow, wrap(6));
        cwTitle = text("Continue Watching", 20, Color.WHITE, true); page.addView(cwTitle, wrap(20));
        cwRow = row(); page.addView((View) cwRow.getParent(), wrap(10));
        rowsBox = new LinearLayout(this); rowsBox.setOrientation(LinearLayout.VERTICAL); page.addView(rowsBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(text("Results — tell Claude what these say", 14, 0xFF808080, false), wrap(24));
        log = text("", 14, 0xFFD8DAE0, false); log.setTypeface(Typeface.MONOSPACE); log.setLineSpacing(0, 1.2f);
        log.setBackground(round(0xFF16171C, 12)); log.setPadding(dp(16), dp(12), dp(16), dp(12));
        page.addView(log, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    private LinearLayout row() {
        HorizontalScrollView h = new HorizontalScrollView(this); h.setHorizontalScrollBarEnabled(false); h.setClipChildren(false); h.setClipToPadding(false);
        LinearLayout l = new LinearLayout(this); l.setPadding(dp(6), dp(10), dp(6), dp(10)); l.setClipChildren(false);
        h.addView(l); return l;
    }

    // ---------- the checks ----------
    private void runChecks() {
        long t0 = System.currentTimeMillis();
        try {
            Resp u = api("GET", "/auth/v1/user", null, true);
            say(u.code == 200 ? "✓ Signed in as " + new JSONObject(u.body).optString("email") : "✗ Current user: HTTP " + u.code + " " + shortMsg(u.body));
            Resp p = api("POST", "/rest/v1/rpc/sync_pull_profiles", null, true);
            if (p.code != 200) { say("✗ Profiles: HTTP " + p.code + " " + shortMsg(p.body)); return; }
            JSONArray profiles = new JSONArray(p.body);
            say("✓ Profiles: " + profiles.length());
            ui.post(() -> fillProfiles(profiles));
            loadProfile(profiles, profile);
        } catch (Exception e) { say("✗ " + e); }
        say("Done in " + (System.currentTimeMillis() - t0) + " ms");
    }
    private void fillProfiles(JSONArray profiles) {
        profileRow.removeAllViews();
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject pr = profiles.optJSONObject(i); int idx = pr.optInt("profile_index", i + 1);
            String label = pr.optString("name", "Profile " + idx) + (pr.optBoolean("pin_enabled") ? " (PIN)" : "") + (idx == profile ? " ✓" : "");
            Button b = button(label, v -> { profile = idx; log.setText(""); cwRow.removeAllViews(); rowsBox.removeAllViews(); net.execute(() -> { say("Profile " + idx); ui.post(() -> fillProfiles(profiles)); try { loadProfile(profiles, idx); } catch (Exception e) { say("✗ " + e); } }); });
            LinearLayout.LayoutParams lp = wrap(0); lp.rightMargin = dp(10); profileRow.addView(b, lp);
        }
    }

    static final class Addon { String name, base, id; JSONObject manifest; }

    private void loadProfile(JSONArray profiles, int idx) throws Exception {
        int addonProfile = idx;
        for (int i = 0; i < profiles.length(); i++) { JSONObject pr = profiles.optJSONObject(i); if (pr.optInt("profile_index") == idx && pr.optBoolean("uses_primary_addons")) addonProfile = 1; }
        if (addonProfile != idx) say("• This profile shares profile 1's addons");
        Resp a = api("GET", "/rest/v1/addons?select=*&profile_id=eq." + addonProfile + "&order=sort_order", null, true);
        if (a.code != 200) { say("✗ Addons: HTTP " + a.code + " " + shortMsg(a.body)); return; }
        JSONArray rows = new JSONArray(a.body);
        List<Addon> addons = new ArrayList<>();
        int disabled = 0, failed = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            if (!r.optBoolean("enabled", true)) { disabled++; continue; }
            Addon ad = new Addon(); ad.base = baseOf(r.optString("url"));
            Resp m = get(manifestOf(r.optString("url")));
            if (m.code != 200) { failed++; say("✗ Addon manifest " + host(r.optString("url")) + ": HTTP " + m.code); continue; }
            try { ad.manifest = new JSONObject(m.body); } catch (Exception e) { failed++; say("✗ Addon manifest " + host(r.optString("url")) + ": not JSON"); continue; }
            ad.id = ad.manifest.optString("id");
            ad.name = r.isNull("name") ? ad.manifest.optString("name", host(ad.base)) : r.optString("name");
            addons.add(ad);
        }
        curAddons = addons;
        say("✓ Addons: " + addons.size() + " working" + (disabled > 0 ? ", " + disabled + " disabled" : "") + (failed > 0 ? ", " + failed + " unreachable" : ""));
        StringBuilder names = new StringBuilder(); for (Addon ad : addons) names.append(names.length() > 0 ? ", " : "").append(ad.name);
        if (names.length() > 0) say("  " + names);

        // every catalog the addons offer, and the ones a home row can use (no required search/genre)
        int all = 0, usable = 0; Addon firstAd = null; JSONObject firstCat = null;
        for (Addon ad : addons) {
            JSONArray cats = ad.manifest.optJSONArray("catalogs"); if (cats == null) continue;
            for (int i = 0; i < cats.length(); i++) { JSONObject c = cats.getJSONObject(i); all++; if (!needsExtra(c)) { usable++; if (firstCat == null) { firstCat = c; firstAd = ad; } } }
        }
        say("✓ Catalogs: " + all + " in your addons, " + usable + " usable as Home rows");

        // Continue Watching from synced progress
        JSONObject body = new JSONObject().put("p_profile_id", idx).put("p_limit", 50);
        Resp w = api("POST", "/rest/v1/rpc/sync_pull_watch_progress", body.toString(), true);
        if (w.code != 200) say("✗ Watch progress: HTTP " + w.code + " " + shortMsg(w.body));
        else {
            JSONArray prog = new JSONArray(w.body);
            Set<String> seen = new LinkedHashSet<>(); List<JSONObject> items = new ArrayList<>();
            for (int i = 0; i < prog.length() && items.size() < 12; i++) {
                JSONObject pr = prog.getJSONObject(i); if (seen.add(pr.optString("content_id"))) items.add(pr);
            }
            say("✓ Watch progress: " + prog.length() + " entries, " + items.size() + " titles");
            StringBuilder ids = new StringBuilder(); for (int i = 0; i < Math.min(4, items.size()); i++) ids.append(i > 0 ? ", " : "").append(items.get(i).optString("content_type")).append(" ").append(items.get(i).optString("content_id"));
            say("  IDs: " + ids);
            int resolved = 0, viaCinemeta = 0;
            for (JSONObject pr : items) {
                String type = pr.optString("content_type"), id = pr.optString("content_id");
                JSONObject meta = findMeta(addons, type, id);
                if (meta == null) { meta = cinemeta(type, id); if (meta != null) viaCinemeta++; }
                if (meta != null) resolved++;
                long pos = pr.optLong("position"), dur = pr.optLong("duration");
                String sub = (pr.isNull("season") ? "" : "S" + pr.optInt("season") + " E" + pr.optInt("episode") + " · ") + (dur > 0 ? Math.max(0, (dur - pos) / 60000) + " min left" : "");
                addCard(cwRow, type, id, meta == null ? id : meta.optString("name", id), meta == null ? null : firstNonEmpty(meta.optString("background"), meta.optString("poster")), sub, dur > 0 ? (int) (pos * 100 / dur) : -1, true);
            }
            say((resolved == items.size() ? "✓" : "•") + " Titles and artwork found for " + resolved + " of " + items.size() + (viaCinemeta > 0 ? " (" + viaCinemeta + " from Cinemeta)" : ""));
            if (items.isEmpty()) ui.post(() -> cwTitle.setText("Continue Watching — nothing in progress for this profile"));
        }

        java.util.Map<String, JSONObject> colls = new java.util.HashMap<>();
        Resp cr = api("POST", "/rest/v1/rpc/sync_pull_collections", new JSONObject().put("p_profile_id", idx).toString(), true);
        if (cr.code == 200) {
            JSONArray arr = new JSONArray(cr.body);
            if (arr.length() > 0) { Object cj = arr.getJSONObject(0).opt("collections_json"); JSONArray list = cj instanceof JSONArray ? (JSONArray) cj : cj instanceof String ? new JSONArray((String) cj) : new JSONArray();
                int folders = 0, tm = 0, tr = 0, ad = 0;
                for (int i = 0; i < list.length(); i++) { JSONObject co = list.getJSONObject(i); colls.put(co.optString("id"), co); JSONArray fs = co.optJSONArray("folders"); if (fs == null) continue; folders += fs.length();
                    for (int j = 0; j < fs.length(); j++) { JSONArray ss = fs.getJSONObject(j).optJSONArray("sources"); if (ss == null) ss = fs.getJSONObject(j).optJSONArray("catalogSources"); if (ss == null) continue; for (int k = 0; k < ss.length(); k++) { String pv = ss.getJSONObject(k).optString("provider", "addon"); if (pv.equals("tmdb")) tm++; else if (pv.equals("trakt")) tr++; else ad++; } } }
                say("✓ Collections: " + list.length() + ", " + folders + " folders; sources: " + ad + " addon, " + tm + " TMDB, " + tr + " Trakt");
            }
        } else say("✗ Collections: HTTP " + cr.code + " " + shortMsg(cr.body));
        // Nuvio Home rows, as Nuvio syncs them (HomeCatalogSettingsSyncService: platform "home_catalog_shared")
        List<Object[]> rows2 = new ArrayList<>(); // {addon, catalog json, title}
        Resp hs = api("POST", "/rest/v1/rpc/sync_pull_home_catalog_settings", new JSONObject().put("p_profile_id", idx).put("p_platform", "home_catalog_shared").toString(), true);
        JSONArray hitems = null;
        if (hs.code == 200) { JSONArray arr = new JSONArray(hs.body); if (arr.length() > 0) { JSONObject sj = arr.getJSONObject(0).optJSONObject("settings_json"); if (sj != null) hitems = sj.optJSONArray("items"); } }
        else say("✗ Home row settings: HTTP " + hs.code + " " + shortMsg(hs.body));
        if (hitems != null && hitems.length() > 0) {
            List<JSONObject> its = new ArrayList<>(); for (int i = 0; i < hitems.length(); i++) its.add(hitems.getJSONObject(i));
            its.sort((x, y) -> Integer.compare(x.optInt("order"), y.optInt("order")));
            int on = 0, missing = 0, coll = 0;
            for (JSONObject it : its) {
                if (!it.optBoolean("enabled", true)) continue;
                on++;
                if (it.optBoolean("is_collection")) { coll++; JSONObject co = colls.get(it.optString("collection_id")); if (co == null) { missing++; continue; } rows2.add(new Object[]{"coll", co, firstNonEmpty(it.optString("custom_title"), co.optString("title"))}); continue; }
                Addon ad = null; for (Addon x : addons) if (x.id.equals(it.optString("addon_id"))) ad = x;
                JSONObject cat = null;
                if (ad != null) { JSONArray cs = ad.manifest.optJSONArray("catalogs"); if (cs != null) for (int i = 0; i < cs.length(); i++) { JSONObject c = cs.getJSONObject(i); if (c.optString("id").equals(it.optString("catalog_id")) && c.optString("type").equals(it.optString("type"))) cat = c; } }
                if (cat == null) { missing++; continue; }
                String title = firstNonEmpty(it.optString("custom_title"), firstNonEmpty(cat.optString("name"), cat.optString("id")));
                rows2.add(new Object[]{ad, cat, title});
            }
            say("✓ Nuvio Home rows: " + on + " switched on in Nuvio" + (coll > 0 ? ", " + coll + " are collections" : "") + (missing > 0 ? ", " + missing + " not matched to an addon" : ""));
        } else {
            say("• No Home row settings synced for this profile, showing your first catalogs instead");
            for (Addon ad : addons) { JSONArray cs = ad.manifest.optJSONArray("catalogs"); if (cs == null) continue; for (int i = 0; i < cs.length() && rows2.size() < 10; i++) { JSONObject c = cs.getJSONObject(i); if (!needsExtra(c)) rows2.add(new Object[]{ad, c, firstNonEmpty(c.optString("name"), c.optString("id"))}); } }
        }
        int ok = 0, empty = 0, failedRows = 0;
        for (Object[] r : rows2) {
            if ("coll".equals(r[0])) { JSONObject co = (JSONObject) r[1]; JSONArray fs = co.optJSONArray("folders"); if (fs == null || fs.length() == 0) { empty++; continue; } ok++; LinearLayout crow = addRow((String) r[2]); if (crow == null) return; for (int i = 0; i < fs.length(); i++) addFolderTile(crow, fs.getJSONObject(i)); continue; }
            Addon ad = (Addon) r[0]; JSONObject cat = (JSONObject) r[1]; String title = (String) r[2];
            String type = cat.optString("type"), cid = cat.optString("id");
            Resp c = get(addonUrl(ad.base, "/catalog/" + enc(type) + "/" + enc(cid) + ".json"));
            JSONArray metas = null;
            try { if (c.code == 200) metas = new JSONObject(c.body).optJSONArray("metas"); } catch (Exception ignored) { }
            if (metas == null) { failedRows++; say("✗ Row \"" + title + "\": HTTP " + c.code); continue; }
            if (metas.length() == 0) { empty++; continue; }
            ok++;
            LinearLayout row = addRow(title + " · " + ad.name);
            if (row == null) return;
            for (int i = 0; i < Math.min(20, metas.length()); i++) { JSONObject m = metas.getJSONObject(i); addCard(row, firstNonEmpty(m.optString("type"), type), m.optString("id"), m.optString("name"), m.optString("poster"), m.optString("releaseInfo"), -1, false); }
        }
        say("✓ Rows shown: " + ok + (empty > 0 ? ", " + empty + " empty" : "") + (failedRows > 0 ? ", " + failedRows + " failed" : ""));
        say("Select a title and press OK to open it in Nuvio. OK on a folder opens it.");
    }

    private LinearLayout addRow(String title) {
        final LinearLayout[] holder = new LinearLayout[1];
        final Object lock = new Object();
        ui.post(() -> {
            rowsBox.addView(text(title, 20, Color.WHITE, true), wrap(20));
            LinearLayout r = row(); rowsBox.addView((View) r.getParent(), wrap(10));
            synchronized (lock) { holder[0] = r; lock.notifyAll(); }
        });
        synchronized (lock) { while (holder[0] == null) { try { lock.wait(); } catch (InterruptedException e) { return null; } } }
        return holder[0];
    }

    /** Public Stremio metadata service for IMDb-style IDs no addon describes (Nuvio itself uses its own TMDB key). */
    private JSONObject cinemeta(String type, String id) {
        if (!id.startsWith("tt")) return null;
        try { Resp r = get("https://v3-cinemeta.strem.io/meta/" + enc(type) + "/" + enc(id) + ".json"); if (r.code == 200) { JSONObject m = new JSONObject(r.body).optJSONObject("meta"); if (m != null && m.has("name")) return m; } }
        catch (Exception ignored) { }
        return null;
    }

    private static boolean needsExtra(JSONObject c) {
        JSONArray ex = c.optJSONArray("extra");
        if (ex != null) for (int i = 0; i < ex.length(); i++) { JSONObject e = ex.optJSONObject(i); if (e != null && e.optBoolean("isRequired")) return true; }
        JSONArray req = c.optJSONArray("extraRequired"); return req != null && req.length() > 0;
    }
    private JSONObject findMeta(List<Addon> addons, String type, String id) {
        for (Addon ad : addons) {
            if (!supports(ad.manifest, "meta", type, id)) continue;
            try {
                Resp r = get(addonUrl(ad.base, "/meta/" + enc(type) + "/" + enc(id) + ".json"));
                if (r.code == 200) { JSONObject m = new JSONObject(r.body).optJSONObject("meta"); if (m != null) return m; }
            } catch (Exception ignored) { }
        }
        return null;
    }
    /** Stremio-style manifest: resources are names or {name, types, idPrefixes}. */
    private static boolean supports(JSONObject man, String res, String type, String id) {
        JSONArray rs = man.optJSONArray("resources"); if (rs == null) return false;
        for (int i = 0; i < rs.length(); i++) {
            Object o = rs.opt(i); JSONArray types = man.optJSONArray("types"), pre = man.optJSONArray("idPrefixes");
            if (o instanceof String) { if (!res.equals(o)) continue; }
            else if (o instanceof JSONObject) { JSONObject r = (JSONObject) o; if (!res.equals(r.optString("name"))) continue; if (r.has("types")) types = r.optJSONArray("types"); if (r.has("idPrefixes")) pre = r.optJSONArray("idPrefixes"); }
            else continue;
            if (types != null && !contains(types, type)) continue;
            if (pre != null && pre.length() > 0) { boolean ok = false; for (int j = 0; j < pre.length(); j++) if (id.startsWith(pre.optString(j))) ok = true; if (!ok) continue; }
            return true;
        }
        return false;
    }
    private static boolean contains(JSONArray a, String v) { for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true; return false; }

    // ---------- cards ----------
    private void addCard(LinearLayout row, String type, String id, String name, String img, String sub, int pct, boolean wide) {
        ui.post(() -> {
            int w = dp(wide ? 240 : 120), h = dp(wide ? 135 : 180);
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setFocusable(true); card.setClickable(true);
            FrameLayout art = new FrameLayout(this); art.setBackground(round(0xFF22232A, 10)); art.setClipToOutline(true);
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(iv, new FrameLayout.LayoutParams(w, h));
            TextView fallback = text(name, 13, 0xFFB3B3B3, false); fallback.setGravity(Gravity.CENTER); fallback.setPadding(dp(8), 0, dp(8), 0);
            art.addView(fallback, new FrameLayout.LayoutParams(w, h));
            if (pct >= 0) { View bar = new View(this); bar.setBackgroundColor(Color.WHITE); FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Math.max(dp(4), w * pct / 100), dp(4), Gravity.BOTTOM); art.addView(bar, bp); }
            card.addView(art, new LinearLayout.LayoutParams(w, h));
            TextView t = text(name, 13, Color.WHITE, false); t.setMaxWidth(w); t.setSingleLine(true); card.addView(t, wrap(6));
            if (sub != null && !sub.isEmpty()) { TextView s = text(sub, 12, 0xFF9AA0AA, false); s.setMaxWidth(w); s.setSingleLine(true); card.addView(s); }
            card.setOnFocusChangeListener((v, f) -> { v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(120).start(); art.setForeground(f ? ring() : null); });
            card.setOnClickListener(v -> openInNuvio(type, id, name));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.rightMargin = dp(18);
            row.addView(card, lp);
            if (img != null && !img.isEmpty()) loadImage(img, w, iv, fallback);
        });
    }
    private GradientDrawable ring() { GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(10)); g.setStroke(dp(3), Color.WHITE); g.setColor(Color.TRANSPARENT); return g; }
    private void loadImage(String url, int width, ImageView iv, View fallback) {
        Bitmap hit = images.get(url); if (hit != null) { iv.setImageBitmap(hit); fallback.setVisibility(View.GONE); return; }
        net.execute(() -> {
            try {
                Resp r = getBytes(url); if (r.code != 200 || r.bytes == null) return;
                BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(r.bytes, 0, r.bytes.length, o);
                int s = 1; while (o.outWidth / (s * 2) >= width) s *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options(); d.inSampleSize = s;
                Bitmap bm = BitmapFactory.decodeByteArray(r.bytes, 0, r.bytes.length, d); if (bm == null) return;
                images.put(url, bm); ui.post(() -> { iv.setImageBitmap(bm); fallback.setVisibility(View.GONE); });
            } catch (Exception ignored) { }
        });
    }
    private void openInNuvio(String type, String id, String name) {
        String t = "series".equals(type) || "tv".equals(type) ? "series" : "movie";
        Uri link = Uri.parse("nuvio://detail/" + t + "/" + Uri.encode(id, ":"));
        say("→ Opening " + name + ": " + link);
        try { startActivity(new Intent(Intent.ACTION_VIEW, link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException e) { say("✗ No app opened nuvio:// links. Is Nuvio installed?"); }
    }

    // ---------- HTTP ----------
    static final class Resp { int code; String body; byte[] bytes; }
    private Resp api(String method, String path, String body, boolean auth) throws Exception {
        Resp r = http(method, API + path, body, auth);
        if (auth && r.code == 401) { String rt = prefs.getString("refresh", null); if (rt != null && refresh(rt)) r = http(method, API + path, body, true); }
        return r;
    }
    private Resp http(String method, String url, String body, boolean auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setRequestProperty("apikey", KEY); c.setRequestProperty("Content-Type", "application/json");
        if (auth && access != null) c.setRequestProperty("Authorization", "Bearer " + access);
        if (body != null) { c.setDoOutput(true); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
        else if ("POST".equals(method)) { c.setDoOutput(true); c.getOutputStream().close(); }
        return read(c, false);
    }
    private Resp get(String url) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); c.setConnectTimeout(10000); c.setReadTimeout(15000); return read(c, false); }
    private Resp getBytes(String url) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); c.setConnectTimeout(10000); c.setReadTimeout(15000); return read(c, true); }
    private static Resp read(HttpURLConnection c, boolean raw) throws Exception {
        Resp r = new Resp(); r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); in.close(); }
        c.disconnect();
        if (raw) r.bytes = out.toByteArray(); else r.body = out.toString("UTF-8");
        return r;
    }

    // ---------- addon URL helpers (as in Nuvio's CatalogRepositoryImpl) ----------
    private static String manifestOf(String url) { String[] pq = splitQuery(url); String p = pq[0].replaceAll("/+$", ""); return (p.endsWith("/manifest.json") ? p : p + "/manifest.json") + pq[1]; }
    private static String baseOf(String url) { String[] pq = splitQuery(url); String p = pq[0].replaceAll("/+$", ""); if (p.endsWith("/manifest.json")) p = p.substring(0, p.length() - "/manifest.json".length()); return p + pq[1]; }
    private static String addonUrl(String base, String path) { String[] pq = splitQuery(base); return pq[0].replaceAll("/+$", "") + path + pq[1]; }
    private static String[] splitQuery(String u) { int q = u.indexOf('?'); return q < 0 ? new String[]{u, ""} : new String[]{u.substring(0, q), u.substring(q)}; }
    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%3A", ":"); } catch (Exception e) { return s; } }
    private static String host(String u) { try { return new URL(u).getHost(); } catch (Exception e) { return u; } }
    private static String firstNonEmpty(String a, String b) { return a != null && !a.isEmpty() ? a : b; }
    private static String shortMsg(String body) {
        if (body == null) return "";
        try { JSONObject j = new JSONObject(body); return firstNonEmpty(firstNonEmpty(j.optString("msg"), j.optString("message")), j.optString("error_description")); }
        catch (Exception e) { return body.length() > 120 ? body.substring(0, 120) : body; }
    }

    // ---------- collections (Nuvio's CollectionsDataStore format) ----------
    private List<Addon> curAddons = new ArrayList<>();
    private LinearLayout folderPage;
    private boolean inFolder;
    private String tmdbKey() { return prefs.getString("tmdb", ""); }

    private void addFolderTile(LinearLayout row, JSONObject f) {
        ui.post(() -> {
            String shape = f.optString("tileShape", "SQUARE").toUpperCase();
            int w = dp(shape.equals("POSTER") ? 120 : shape.equals("LANDSCAPE") || shape.equals("WIDE") ? 240 : 150);
            int h = dp(shape.equals("POSTER") ? 180 : shape.equals("LANDSCAPE") || shape.equals("WIDE") ? 135 : 150);
            String name = f.optString("title");
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setFocusable(true); card.setClickable(true);
            FrameLayout art = new FrameLayout(this); art.setBackground(round(0xFF2A2B33, 12)); art.setClipToOutline(true);
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); art.addView(iv, new FrameLayout.LayoutParams(w, h));
            String emoji = f.isNull("coverEmoji") ? "" : f.optString("coverEmoji");
            TextView fb = text(emoji.isEmpty() ? name : emoji, emoji.isEmpty() ? 15 : 40, Color.WHITE, !emoji.isEmpty()); fb.setGravity(Gravity.CENTER); fb.setPadding(dp(8), 0, dp(8), 0);
            art.addView(fb, new FrameLayout.LayoutParams(w, h));
            card.addView(art, new LinearLayout.LayoutParams(w, h));
            if (!f.optBoolean("hideTitle")) { TextView t = text(name, 13, Color.WHITE, false); t.setMaxWidth(w); t.setSingleLine(true); card.addView(t, wrap(6)); }
            card.setOnFocusChangeListener((v, fo) -> { v.animate().scaleX(fo ? 1.08f : 1f).scaleY(fo ? 1.08f : 1f).setDuration(120).start(); art.setForeground(fo ? ring() : null); });
            card.setOnClickListener(v -> openFolder(f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.rightMargin = dp(18);
            row.addView(card, lp);
            String img = f.isNull("coverImageUrl") ? "" : f.optString("coverImageUrl");
            if (!img.isEmpty()) loadImage(img, w, iv, fb);
        });
    }

    private void openFolder(JSONObject f) {
        inFolder = true; page.setVisibility(View.GONE); folderPage.setVisibility(View.VISIBLE); folderPage.removeAllViews();
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(f.optString("title"), 26, Color.WHITE, true));
        View sp = new View(this); head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        Button back = button("Back", v -> closeFolder()); head.addView(back);
        folderPage.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView flog = text("", 14, 0xFFD8DAE0, false); flog.setTypeface(Typeface.MONOSPACE);
        JSONArray srcs = f.optJSONArray("sources"); if (srcs == null || srcs.length() == 0) srcs = f.optJSONArray("catalogSources");
        final JSONArray sources = srcs == null ? new JSONArray() : srcs;
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        folderPage.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        folderPage.addView(flog, wrap(20));
        back.requestFocus();
        net.execute(() -> {
            for (int i = 0; i < sources.length(); i++) {
                JSONObject s = sources.optJSONObject(i); if (s == null) continue;
                String provider = s.optString("provider", "addon").toLowerCase();
                try {
                    if (provider.equals("tmdb")) tmdbSource(box, flog, s);
                    else if (provider.equals("trakt")) fsay(flog, "• Trakt list \"" + s.optString("title") + "\": not part of this test");
                    else addonSource(box, flog, s);
                } catch (Exception e) { fsay(flog, "✗ Source " + (i + 1) + ": " + e.getMessage()); }
            }
            fsay(flog, "Sources in this folder: " + sources.length());
        });
    }
    private void closeFolder() { inFolder = false; folderPage.setVisibility(View.GONE); page.setVisibility(View.VISIBLE); }
    @Override public void onBackPressed() { if (inFolder) closeFolder(); else super.onBackPressed(); }
    private void fsay(TextView t, String line) { ui.post(() -> t.append(line + "\n")); }

    private void addonSource(LinearLayout box, TextView flog, JSONObject s) throws Exception {
        Addon ad = null; for (Addon x : curAddons) if (x.id.equals(s.optString("addonId"))) ad = x;
        String type = s.optString("type"), cid = s.optString("catalogId"), genre = s.isNull("genre") ? "" : s.optString("genre");
        if (ad == null) { fsay(flog, "✗ Addon catalog " + cid + ": addon " + s.optString("addonId") + " not installed"); return; }
        String name = cid; JSONArray cs = ad.manifest.optJSONArray("catalogs");
        if (cs != null) for (int i = 0; i < cs.length(); i++) { JSONObject c = cs.getJSONObject(i); if (c.optString("id").equals(cid) && c.optString("type").equals(type)) name = firstNonEmpty(c.optString("name"), cid); }
        String path = "/catalog/" + enc(type) + "/" + enc(cid) + (genre.isEmpty() ? "" : "/genre=" + enc(genre)) + ".json";
        Resp c = get(addonUrl(ad.base, path));
        JSONArray metas = null; try { if (c.code == 200) metas = new JSONObject(c.body).optJSONArray("metas"); } catch (Exception ignored) { }
        if (metas == null) { fsay(flog, "✗ " + name + ": HTTP " + c.code); return; }
        fsay(flog, "✓ " + name + (genre.isEmpty() ? "" : " · " + genre) + " (" + ad.name + "): " + metas.length() + " titles");
        LinearLayout row = addRowTo(box, name + (genre.isEmpty() ? "" : " · " + genre)); if (row == null) return;
        for (int i = 0; i < Math.min(20, metas.length()); i++) { JSONObject m = metas.getJSONObject(i); addCard(row, firstNonEmpty(m.optString("type"), type), m.optString("id"), m.optString("name"), m.optString("poster"), m.optString("releaseInfo"), -1, false); }
    }

    /** Same TMDB requests as Nuvio's TmdbCollectionSourceResolver, with your own key. */
    private void tmdbSource(LinearLayout box, TextView flog, JSONObject s) throws Exception {
        String kind = s.optString("tmdbSourceType").toUpperCase(), title = s.optString("title", kind);
        String key = tmdbKey();
        if (key.isEmpty()) { fsay(flog, "• TMDB " + kind.toLowerCase() + " \"" + title + "\": add your TMDB key on the main screen"); return; }
        String media = "tv".equalsIgnoreCase(s.optString("mediaType")) ? "tv" : "movie";
        int id = s.optInt("tmdbId", 0);
        String sort = s.optString("sortBy", "popularity.desc"); if (sort.isEmpty() || sort.equals("original")) sort = "popularity.desc";
        JSONObject fl = s.optJSONObject("filters"); if (fl == null) fl = new JSONObject();
        String url; String arrayName = "results";
        switch (kind) {
            case "LIST": url = "list/" + id + "?page=1"; arrayName = "items"; break;
            case "COLLECTION": url = "collection/" + id + "?"; arrayName = "parts"; media = "movie"; break;
            case "PERSON": case "DIRECTOR": url = "person/" + id + "/combined_credits?"; arrayName = kind.equals("DIRECTOR") ? "crew" : "cast"; break;
            default: {
                if (kind.equals("NETWORK")) media = "tv";
                if (media.equals("movie") && sort.equals("first_air_date.desc")) sort = "primary_release_date.desc";
                if (media.equals("tv") && sort.equals("primary_release_date.desc")) sort = "first_air_date.desc";
                StringBuilder q = new StringBuilder("discover/" + media + "?page=1&sort_by=" + enc(sort));
                String companies = kind.equals("COMPANY") ? String.valueOf(id) : fl.optString("withCompanies", "");
                String networks = kind.equals("NETWORK") ? String.valueOf(id) : fl.optString("withNetworks", "");
                qp(q, "with_companies", companies);
                if (media.equals("tv")) { qp(q, "with_networks", networks); qp(q, "first_air_date.gte", fl.optString("releaseDateGte", "")); qp(q, "first_air_date.lte", firstNonEmpty(fl.optString("releaseDateLte", ""), kind.equals("NETWORK") ? java.time.LocalDate.now().toString() : "")); qp(q, "first_air_date_year", fl.has("year") ? fl.optString("year") : ""); if (kind.equals("NETWORK")) qp(q, "with_status", "0|3|4"); }
                else { qp(q, "primary_release_date.gte", fl.optString("releaseDateGte", "")); qp(q, "primary_release_date.lte", fl.optString("releaseDateLte", "")); qp(q, "year", fl.has("year") ? fl.optString("year") : ""); }
                qp(q, "with_genres", fl.optString("withGenres", "")); qp(q, "without_genres", fl.optString("withoutGenres", ""));
                qp(q, "vote_average.gte", fl.has("voteAverageGte") ? fl.optString("voteAverageGte") : ""); qp(q, "vote_average.lte", fl.has("voteAverageLte") ? fl.optString("voteAverageLte") : "");
                qp(q, "vote_count.gte", fl.has("voteCountGte") ? fl.optString("voteCountGte") : "");
                qp(q, "with_original_language", fl.optString("withOriginalLanguage", "")); qp(q, "with_origin_country", fl.optString("withOriginCountry", ""));
                qp(q, "with_keywords", fl.optString("withKeywords", "")); qp(q, "without_keywords", fl.optString("withoutKeywords", "")); qp(q, "without_companies", fl.optString("withoutCompanies", ""));
                String wp = fl.optString("withWatchProviders", ""), wop = fl.optString("withoutWatchProviders", "");
                if (!wp.isEmpty() || !wop.isEmpty()) qp(q, "watch_region", firstNonEmpty(fl.optString("watchRegion", ""), "US"));
                qp(q, "with_watch_providers", wp); qp(q, "without_watch_providers", wop);
                if (!wp.isEmpty()) qp(q, "with_watch_monetization_types", "flatrate|free|ads|rent|buy");
                url = q.toString();
            }
        }
        Resp r = tmdb(url + "&language=en-US", key);
        if (r.code != 200) { fsay(flog, "✗ TMDB " + kind.toLowerCase() + " \"" + title + "\": HTTP " + r.code + " " + shortMsg(r.body)); return; }
        JSONArray arr = new JSONObject(r.body).optJSONArray(arrayName); if (arr == null) arr = new JSONArray();
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.getJSONObject(i);
            if (kind.equals("DIRECTOR") && !"Director".equalsIgnoreCase(it.optString("job"))) continue;
            if ((kind.equals("PERSON") || kind.equals("DIRECTOR")) && !media.equals(it.optString("media_type", media))) continue;
            items.add(it);
        }
        fsay(flog, "✓ TMDB " + kind.toLowerCase() + " \"" + title + "\": " + items.size() + " titles");
        LinearLayout row = addRowTo(box, title); if (row == null) return;
        for (int i = 0; i < Math.min(20, items.size()); i++) {
            JSONObject it = items.get(i);
            String mt = it.optString("media_type", media); boolean tv = mt.equals("tv");
            String name = firstNonEmpty(it.optString("title"), it.optString("name"));
            String date = firstNonEmpty(it.optString("release_date"), it.optString("first_air_date"));
            String poster = it.isNull("poster_path") ? "" : "https://image.tmdb.org/t/p/w342" + it.optString("poster_path");
            addCard(row, tv ? "series" : "movie", "tmdb:" + it.optLong("id"), name, poster, date.length() >= 4 ? date.substring(0, 4) : "", -1, false);
        }
    }
    private static void qp(StringBuilder q, String k, String v) { if (v != null && !v.isEmpty() && !v.equals("null")) q.append('&').append(k).append('=').append(enc(v)); }
    private Resp tmdb(String pathAndQuery, String key) throws Exception {
        boolean bearer = key.length() > 40;
        String url = "https://api.themoviedb.org/3/" + pathAndQuery + (bearer ? "" : "&api_key=" + enc(key));
        HttpURLConnection c = (HttpURLConnection) new URL(url.replace("?&", "?")).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        return read(c, false);
    }

    private LinearLayout addRowTo(LinearLayout container, String title) {
        final LinearLayout[] holder = new LinearLayout[1];
        final Object lock = new Object();
        ui.post(() -> {
            container.addView(text(title, 20, Color.WHITE, true), wrap(20));
            LinearLayout r = row(); container.addView((View) r.getParent(), wrap(10));
            synchronized (lock) { holder[0] = r; lock.notifyAll(); }
        });
        synchronized (lock) { while (holder[0] == null) { try { lock.wait(); } catch (InterruptedException e) { return null; } } }
        return holder[0];
    }

    @Override protected void onDestroy() { super.onDestroy(); net.shutdownNow(); }
}
