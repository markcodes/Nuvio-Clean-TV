package tv.gallery.nuviohome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static tv.gallery.nuviohome.Rows.dp;
import static tv.gallery.nuviohome.Rows.round;
import static tv.gallery.nuviohome.Rows.text;

/**
 * Home: a sidebar (Catalog, Applications, Inputs, Clock, Settings, Nuvio account, Nuvio Settings), the Catalog
 * page (Continue Watching, then the owner's Nuvio Home rows and collections in Nuvio's order, with the focused
 * title's details over a blurred backdrop) and the Applications page. OK on a title opens it in the official Nuvio.
 */
public class MainActivity extends Activity implements Rows.Listener {
    private static final String[] NUVIO_PACKAGES = {"com.nuvio.tv", "com.nuvio.app"};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Nuvio nuvio;

    private ImageView backdrop, heroArt;
    private View heroFade;
    private LinearLayout heroText, sidebar, catalogPage, appsPage;
    private TextView heroChip, heroTitle, heroMeta, heroDesc, clock, status;
    private RecyclerView rowsList, appsGrid;
    private final List<Nuvio.Row> rows = new ArrayList<>();
    private Rows.RowsAdapter rowsAdapter;
    private final List<LinearLayout> sideItems = new ArrayList<>();
    private boolean sideOpen, showingApps, loading;
    private View lastContentFocus;
    private Runnable pendingHero;
    private Frost frost;
    private final Runnable recapture = () -> { if (frost != null) frost.capture((ViewGroup) sidebar.getParent(), sidebar, dp(this, 260)); };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Images.init(this);
        Crash.install(this);
        nuvio = new Nuvio(this);
        buildUi();
        showHome(nuvio.cached());
        refresh(false);
        tick();
        Crash.showLast(this);
    }
    @Override protected void onResume() { super.onResume(); if (nuvio.signedIn() && rows.isEmpty() && !loading) refresh(false); }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); showPage(false); focusContent(); }   // Home button
    @Override protected void onDestroy() { super.onDestroy(); bg.shutdownNow(); }

    // ---------- layout ----------
    private void buildUi() {
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF0B0C10); root.setClipChildren(false);
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;

        backdrop = new ImageView(this); backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP); backdrop.setAlpha(0.55f);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        heroArt = new FadeImageView(this); heroArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(heroArt, new FrameLayout.LayoutParams((int) (W * 0.66f), (int) (H * 0.62f), Gravity.TOP | Gravity.END));
        heroFade = new View(this);
        GradientDrawable left = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF0B0C10, 0x990B0C10, 0x000B0C10});
        GradientDrawable down = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x000B0C10, 0x000B0C10, 0xFF0B0C10});
        heroFade.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{left, down}));
        heroFade.setVisibility(View.GONE);   // replaced by the transparent fade in FadeImageView
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xE60B0C10, 0x800B0C10, 0x000B0C10}));
        root.addView(scrim, new FrameLayout.LayoutParams((int) (W * 0.6f), -1));

        // Catalog page
        catalogPage = new LinearLayout(this); catalogPage.setOrientation(LinearLayout.VERTICAL);
        heroText = new LinearLayout(this); heroText.setOrientation(LinearLayout.VERTICAL); heroText.setPadding(dp(this, 104), 0, 0, 0);
        heroChip = text(this, "", 13, 0xD9FFFFFF, false);
        heroTitle = text(this, "", 34, Color.WHITE, true); heroTitle.setMaxLines(2); heroTitle.setPadding(0, dp(this, 6), 0, 0);
        heroMeta = text(this, "", 14, 0xE6FFFFFF, false); heroMeta.setPadding(0, dp(this, 8), 0, 0);
        heroDesc = text(this, "", 14, 0xC0FFFFFF, false); heroDesc.setMaxLines(3); heroDesc.setLineSpacing(0, 1.25f); heroDesc.setPadding(0, dp(this, 8), 0, 0);
        heroDesc.setMaxWidth((int) (W * 0.42f));
        heroText.addView(heroChip); heroText.addView(heroTitle); heroText.addView(heroMeta); heroText.addView(heroDesc);
        catalogPage.addView(heroText, new LinearLayout.LayoutParams(-1, (int) (H * 0.40f)));
        status = text(this, "", 16, 0xFFB3B3B3, false); status.setVisibility(View.GONE); status.setPadding(dp(this, 104), 0, 0, 0);
        catalogPage.addView(status);
        rowsList = new RecyclerView(this); rowsList.setLayoutManager(new RowsLayout(this)); rowsList.setItemAnimator(null);
        rowsList.setClipToPadding(false); rowsList.setPadding(0, 0, 0, (int) (H * 0.5f));
        rowsAdapter = new Rows.RowsAdapter(rows, this, dp(this, 104)); rowsList.setAdapter(rowsAdapter);
        catalogPage.addView(rowsList, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -1); cp.topMargin = dp(this, 36);
        root.addView(catalogPage, cp);

        // Applications page
        appsPage = new LinearLayout(this); appsPage.setOrientation(LinearLayout.VERTICAL); appsPage.setVisibility(View.GONE);
        TextView date = text(this, DateFormat.format("EEEE d MMMM", new Date()).toString().toUpperCase(), 13, 0xB3FFFFFF, false); date.setLetterSpacing(0.18f);
        appsPage.addView(date);
        appsPage.addView(text(this, greeting(), 26, Color.WHITE, false), lp(-2, -2, 6));
        appsGrid = new RecyclerView(this); appsGrid.setLayoutManager(new GridLayoutManager(this, 6)); appsGrid.setClipToPadding(false); appsGrid.setClipChildren(false);
        appsGrid.setPadding(dp(this, 4), dp(this, 16), dp(this, 40), dp(this, 40)); appsGrid.setItemAnimator(null);
        appsPage.addView(appsGrid, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-1, -1); ap.leftMargin = dp(this, 104); ap.topMargin = dp(this, 36);
        root.addView(appsPage, ap);

        clock = text(this, "", 15, Color.WHITE, false); clock.setBackground(round(0x33000000, 20, this)); clock.setPadding(dp(this, 14), dp(this, 6), dp(this, 14), dp(this, 6));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END); clp.topMargin = dp(this, 24); clp.rightMargin = dp(this, 32);
        root.addView(clock, clp);

        buildSidebar(root);
        setContentView(root);
    }
    private LinearLayout.LayoutParams lp(int w, int h, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.topMargin = dp(this, top); return p; }
    private static String greeting() { int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); return h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening"; }

    // ---------- sidebar ----------
    private static final int[] ICONS = {R.drawable.ic_catalog, R.drawable.ic_apps, R.drawable.ic_inputs, R.drawable.ic_clock, R.drawable.ic_settings, R.drawable.ic_account, R.drawable.ic_nuvio};
    private static final String[] LABELS = {"Catalog", "Applications", "Inputs", "Clock", "Settings", "Nuvio account", "Nuvio Settings"};
    private TextView[] sideLabels;
    private void buildSidebar(FrameLayout root) {
        sidebar = new LinearLayout(this); sidebar.setOrientation(LinearLayout.VERTICAL);
        frost = new Frost(dp(this, 22)); sidebar.setBackground(frost); sidebar.setPadding(dp(this, 8), dp(this, 18), dp(this, 8), dp(this, 18));
        sidebar.setElevation(dp(this, 8));
        sideLabels = new TextView[LABELS.length];
        for (int i = 0; i < LABELS.length; i++) {
            if (i == 5) { TextView sec = text(this, "Nuvio", 11, 0x80FFFFFF, false); sec.setPadding(dp(this, 14), dp(this, 18), 0, dp(this, 6)); sec.setTag("label"); sidebar.addView(sec); }
            LinearLayout it = new LinearLayout(this); it.setGravity(Gravity.CENTER_VERTICAL); it.setFocusable(true); it.setClickable(true);
            it.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10)); it.setBackground(null);
            ImageView ic = new ImageView(this); ic.setImageResource(ICONS[i]); ic.setColorFilter(0xD9FFFFFF);
            it.addView(ic, new LinearLayout.LayoutParams(dp(this, 22), dp(this, 22)));
            TextView t = text(this, LABELS[i], 15, Color.WHITE, false); t.setPadding(dp(this, 14), 0, 0, 0); t.setSingleLine(true); t.setVisibility(View.GONE);
            sideLabels[i] = t; it.addView(t);
            final int idx = i;
            it.setOnFocusChangeListener((v, f) -> {
                v.setBackground(f ? round(0xFFF4F4F6, 14, this) : null);
                ic.setColorFilter(f ? 0xFF121316 : 0xD9FFFFFF); t.setTextColor(f ? 0xFF121316 : Color.WHITE);
                ui.post(this::syncSidebar);
            });
            it.setOnClickListener(v -> onSide(idx));
            sideItems.add(it);
            sidebar.addView(it, lp(-1, -2, 2));
        }
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(this, 62), -1, Gravity.START);
        sp.leftMargin = dp(this, 16); sp.topMargin = dp(this, 20); sp.bottomMargin = dp(this, 20);
        root.addView(sidebar, sp);
    }
    private boolean inSidebar(View v) { while (v != null) { if (v == sidebar) return true; ViewParent p = v.getParent(); v = p instanceof View ? (View) p : null; } return false; }
    private void syncSidebar() {
        boolean open = inSidebar(getCurrentFocus());
        if (open == sideOpen) return; sideOpen = open;
        int from = sidebar.getWidth(), to = dp(this, open ? 250 : 62);
        ValueAnimator a = ValueAnimator.ofInt(from, to).setDuration(200);
        a.addUpdateListener(an -> { ViewGroup.LayoutParams p = sidebar.getLayoutParams(); p.width = (int) an.getAnimatedValue(); sidebar.setLayoutParams(p); });
        a.start();
        for (TextView t : sideLabels) t.setVisibility(open ? View.VISIBLE : View.GONE);
        for (int i = 0; i < sidebar.getChildCount(); i++) { View c = sidebar.getChildAt(i); if ("label".equals(c.getTag())) c.setVisibility(open ? View.VISIBLE : View.INVISIBLE); }
        recapture.run();
        frost.setGlass(open ? 0x8C14121A : 0x5514121A);
        (showingApps ? appsPage : catalogPage).animate().alpha(open ? 0.7f : 1f).setDuration(200).start();
    }
    private void onSide(int i) {
        switch (i) {
            case 0: showPage(false); focusContent(); break;
            case 1: showPage(true); focusContent(); break;
            case 2: case 3: case 4: Toast.makeText(this, LABELS[i] + " arrives in the next build", Toast.LENGTH_SHORT).show(); break;
            case 5: account(); break;
            case 6: openNuvio(); break;
        }
    }
    private void frostLater(long ms) { ui.removeCallbacks(recapture); ui.postDelayed(recapture, ms); }
    private void showPage(boolean apps) {
        frostLater(400);
        showingApps = apps;
        catalogPage.setVisibility(apps ? View.GONE : View.VISIBLE); appsPage.setVisibility(apps ? View.VISIBLE : View.GONE);
        heroArt.setVisibility(apps ? View.GONE : View.VISIBLE);
        if (apps && appsGrid.getAdapter() == null) appsGrid.setAdapter(new AppsAdapter(loadApps()));
    }
    private void focusContent() {
        ui.post(() -> {
            if (lastContentFocus != null && lastContentFocus.isAttachedToWindow() && lastContentFocus.getVisibility() == View.VISIBLE && !showingApps) { lastContentFocus.requestFocus(); return; }
            RecyclerView target = showingApps ? appsGrid : rowsList;
            if (!target.requestFocus()) sideItems.get(showingApps ? 1 : 0).requestFocus();
        });
    }

    // ---------- keys ----------
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            int k = e.getKeyCode();
            if (inSidebar(f)) {
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_BACK) { focusContent(); return true; }
            } else if (f != null) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && atRowStart(f)) { lastContentFocus = f; sideItems.get(showingApps ? 1 : 0).requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_BACK) {
                    lastContentFocus = f;
                    if (!showingApps && rowsList.computeVerticalScrollOffset() > 0) { ((RowsLayout) rowsList.getLayoutManager()).reset(); rowsList.scrollToPosition(0); lastContentFocus = null; focusContent(); }
                    else sideItems.get(showingApps ? 1 : 0).requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }
    /** True for the first card of a row, or a card in the first column of the app grid. */
    private boolean atRowStart(View f) {
        View card = f; ViewParent p = f.getParent();
        while (p instanceof View && !(p instanceof RecyclerView)) { card = (View) p; p = p.getParent(); }
        if (!(p instanceof RecyclerView)) return false;
        RecyclerView rv = (RecyclerView) p; int pos = rv.getChildAdapterPosition(card);
        if (rv == appsGrid) return pos % 6 == 0;
        return pos == 0;
    }

    // ---------- data ----------
    private void showHome(Nuvio.Home h) {
        rows.clear();
        if (h != null) rows.addAll(h.rows);
        rowsAdapter.notifyDataSetChanged();
        ((RowsLayout) rowsList.getLayoutManager()).reset();
        frostLater(1200);
        if (!nuvio.signedIn()) { status("Sign in to Nuvio to fill your Home: sidebar › Nuvio account."); hero("Nuvio Clean TV", "", "Your Nuvio Home, Continue Watching and collections appear here once you sign in.", ""); }
        else if (rows.isEmpty()) status(loading ? "Loading your Nuvio Home…" : "Nothing to show yet.");
        else status(null);
        if (!rows.isEmpty() && getCurrentFocus() == null) focusContent();
    }
    private void status(String s) { status.setText(s == null ? "" : s); status.setVisibility(s == null ? View.GONE : View.VISIBLE); }
    private void refresh(boolean force) {
        if (!nuvio.signedIn() || loading) return;
        loading = true;
        if (rows.isEmpty()) status("Loading your Nuvio Home…");
        bg.execute(() -> {
            try {
                Nuvio.Home h = nuvio.loadHome();
                ui.post(() -> {
                    loading = false;
                    // Swap in the fresh Home now unless the user is browsing rows; otherwise it is saved for the next start.
                    View f = getCurrentFocus();
                    if (force || rows.isEmpty() || f == null || inSidebar(f) || showingApps) { lastContentFocus = null; showHome(h); }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    loading = false;
                    if (!nuvio.signedIn()) { showHome(null); Toast.makeText(this, "Signed out of Nuvio. Sign in again from the sidebar.", Toast.LENGTH_LONG).show(); }
                    else if (rows.isEmpty()) status("Couldn't load your Nuvio Home: " + e.getMessage());
                });
            }
        });
    }

    // ---------- hero ----------
    @Override public void onItemFocused(Nuvio.Row row, Nuvio.Item it) {
        String desc = it.description == null ? "" : it.description;
        String meta = row.continueWatching ? Nuvio.nonEmpty(it.sub, "") + (it.year != null && !it.year.isEmpty() ? " · " + it.year : "") : Nuvio.nonEmpty(it.sub, it.year);
        heroLater(row.title, it.name, meta, desc, Nuvio.nonEmpty(it.background, it.poster));
    }
    @Override public void onFolderFocused(Nuvio.Row row, JSONObject f) {
        JSONArray s = f.optJSONArray("sources"); int n = s == null ? 0 : s.length();
        heroLater(row.title, f.optString("title"), n + (n == 1 ? " source" : " sources"), "OK opens this folder.", f.isNull("coverImageUrl") ? "" : f.optString("coverImageUrl"));
    }
    @Override public void onRowFocused(int rowIndex, View rowView) {
        ((RowsLayout) rowsList.getLayoutManager()).showRow(rowsList, rowIndex);
    }
    private void heroLater(String chip, String title, String meta, String desc, String img) {
        if (pendingHero != null) ui.removeCallbacks(pendingHero);
        hero(title, chip, desc, meta);      // text at once
        pendingHero = () -> {                // images once focus settles, so fast scrolling does not load every backdrop
            Images.loadBlurred(backdrop, img, null);
            Images.load(heroArt, img, (int) (getResources().getDisplayMetrics().widthPixels * 0.66f), null);
        };
        ui.postDelayed(pendingHero, 220);
        frostLater(700);
    }
    private void hero(String title, String chip, String desc, String meta) {
        heroChip.setText(chip == null ? "" : chip); heroTitle.setText(title); heroMeta.setText(meta == null ? "" : meta); heroDesc.setText(desc);
        heroMeta.setVisibility(meta == null || meta.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override public void onItem(Nuvio.Item it) {
        try { startActivity(new Intent(this, DetailActivity.class).putExtra("item", it.toJson().toString())); } catch (Exception ignored) { }
    }
    @Override public void onFolder(JSONObject f) {
        startActivity(new Intent(this, FolderActivity.class).putExtra("folder", f.toString()));
    }

    // ---------- Nuvio ----------
    private void openNuvio() {
        PackageManager pm = getPackageManager();
        for (String p : NUVIO_PACKAGES) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(p); if (i == null) i = pm.getLaunchIntentForPackage(p);
            if (i != null) { startActivity(i); Toast.makeText(this, "Nuvio has no direct link to its settings yet: open Settings in Nuvio.", Toast.LENGTH_LONG).show(); return; }
        }
        Toast.makeText(this, "Nuvio isn't installed.", Toast.LENGTH_LONG).show();
    }
    private void account() {
        if (!nuvio.signedIn()) { startActivityForResult(new Intent(this, SignInActivity.class), 1); return; }
        String prof = nuvio.profileName().isEmpty() ? "Profile " + nuvio.profile() : nuvio.profileName();
        new AlertDialog.Builder(this).setTitle("Nuvio account")
                .setMessage("Signed in as " + nuvio.email() + "\nProfile: " + prof)
                .setPositiveButton("Switch profile", (d, w) -> pickProfile())
                .setNeutralButton("Refresh Home", (d, w) -> { Toast.makeText(this, "Refreshing your Nuvio Home…", Toast.LENGTH_SHORT).show(); refresh(true); })
                .setNegativeButton("Sign out", (d, w) -> { bg.execute(() -> { nuvio.signOut(); ui.post(() -> { lastContentFocus = null; showHome(null); }); }); })
                .show();
    }
    private void pickProfile() {
        bg.execute(() -> {
            try {
                JSONArray ps = nuvio.profiles();
                List<String> names = new ArrayList<>(), labels = new ArrayList<>(); List<Integer> idx = new ArrayList<>(); List<Boolean> locked = new ArrayList<>();
                for (int i = 0; i < ps.length(); i++) { JSONObject p = ps.getJSONObject(i); names.add(p.optString("name")); locked.add(p.optBoolean("pin_enabled")); labels.add(p.optString("name") + (p.optBoolean("pin_enabled") ? "  🔒" : "")); idx.add(p.optInt("profile_index")); }
                ui.post(() -> new AlertDialog.Builder(this).setTitle("Who's watching?").setItems(labels.toArray(new String[0]), (d, w) -> {
                    Runnable go = () -> { nuvio.setProfile(idx.get(w), names.get(w)); lastContentFocus = null; showHome(nuvio.cached()); refresh(true); };
                    if (locked.get(w) && idx.get(w) != nuvio.profile()) Pin.ask(this, idx.get(w), names.get(w), go); else go.run();
                }).show());
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, "Couldn't load profiles: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        });
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && res == RESULT_OK) { showHome(null); refresh(true); pickProfile(); }
    }

    // ---------- clock ----------
    private void tick() {
        clock.setText(DateFormat.getTimeFormat(this).format(new Date()));
        ui.postDelayed(this::tick, 20000);
    }

    // ---------- applications ----------
    static final class App { String label, pkg; Intent launch; Drawable banner, icon; }
    private List<App> loadApps() {
        PackageManager pm = getPackageManager();
        List<App> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            App a = new App(); a.pkg = ri.activityInfo.packageName; a.label = String.valueOf(ri.loadLabel(pm));
            a.launch = pm.getLeanbackLaunchIntentForPackage(a.pkg);
            a.banner = ri.activityInfo.loadBanner(pm); if (a.banner == null) a.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (a.banner == null) a.icon = ri.loadIcon(pm);
            out.add(a);
        }
        Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return out;
    }
    final class AppsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<App> apps; AppsAdapter(List<App> a) { apps = a; }
        @Override public int getItemCount() { return apps.size(); }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
            LinearLayout card = new LinearLayout(MainActivity.this); card.setOrientation(LinearLayout.VERTICAL); card.setFocusable(true); card.setClickable(true); card.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout art = new FrameLayout(MainActivity.this); art.setBackground(round(0xFF2A2B33, 10, MainActivity.this)); art.setClipToOutline(true);
            ImageView iv = new ImageView(MainActivity.this); iv.setScaleType(ImageView.ScaleType.FIT_CENTER); art.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            card.addView(art, new LinearLayout.LayoutParams(-1, dp(MainActivity.this, 78)));
            TextView name = text(MainActivity.this, "", 13, Color.WHITE, false); name.setSingleLine(true); name.setPadding(0, dp(MainActivity.this, 8), 0, 0); name.setVisibility(View.INVISIBLE);
            card.addView(name);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2); lp.setMargins(dp(MainActivity.this, 8), dp(MainActivity.this, 8), dp(MainActivity.this, 8), dp(MainActivity.this, 8)); card.setLayoutParams(lp);
            card.setOnFocusChangeListener((v, f) -> { v.animate().scaleX(f ? 1.1f : 1f).scaleY(f ? 1.1f : 1f).setDuration(140).start(); art.setForeground(f ? Rows.ring(MainActivity.this, 10) : null); name.setVisibility(f ? View.VISIBLE : View.INVISIBLE); v.setZ(f ? 1 : 0); if (f) lastContentFocus = v; });
            return new RecyclerView.ViewHolder(card) { };
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            App a = apps.get(pos); LinearLayout card = (LinearLayout) h.itemView;
            FrameLayout art = (FrameLayout) card.getChildAt(0); ImageView iv = (ImageView) art.getChildAt(0); TextView name = (TextView) card.getChildAt(1);
            iv.setImageDrawable(a.banner != null ? a.banner : a.icon); iv.setScaleType(a.banner != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(a.banner != null ? 0 : dp(MainActivity.this, 14), a.banner != null ? 0 : dp(MainActivity.this, 14), a.banner != null ? 0 : dp(MainActivity.this, 14), a.banner != null ? 0 : dp(MainActivity.this, 14));
            name.setText(a.label);
            card.setOnClickListener(v -> { if (a.launch != null) startActivity(a.launch); });
        }
    }
}
