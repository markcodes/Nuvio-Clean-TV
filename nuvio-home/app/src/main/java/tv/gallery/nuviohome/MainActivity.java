package tv.gallery.nuviohome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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

import androidx.recyclerview.widget.ConcatAdapter;
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
 * Home, laid out like the Apple TV app: a full-screen featured carousel (backdrop, logo, details, Play / + / info /
 * next, page dots) with the rows on a grey ground below it, and a floating frosted sidebar that folds away to a
 * small "‹ Catalog" pill. Pages: Catalog (the owner's Nuvio Home) and Applications.
 */
public class MainActivity extends Activity implements Rows.Listener {
    private static final int BG = 0xFF1E1F23;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Nuvio nuvio;
    private int W, H, PAD;

    private FrameLayout root, heroLayer;
    private ImageView bdA, bdB, bdFront;
    private RecyclerView rowsList;
    private android.widget.ScrollView appsScroll;
    private LinearLayout appsRows;
    private boolean appsBuilt;
    private LinearLayout appsPage, sidebar, pill, heroView, dots;
    private ImageView pillIcon, heroLogo;
    private TextView pillLabel, status, heroTitle, heroMeta, heroDesc, sideClock, sideName, avatar;
    private View playBtn, heroFirst;
    private final List<Nuvio.Row> rows = new ArrayList<>();
    private final List<Nuvio.Item> featured = new ArrayList<>();
    private int heroIndex;
    private Rows.RowsAdapter rowsAdapter;
    private final List<View> sideItems = new ArrayList<>();
    private boolean sideOpen, showingApps, loading;
    private View lastContentFocus;
    private Frost frost;
    private final Runnable recapture = () -> { if (frost != null && sidebar.getWidth() > 0) frost.capture(root, sidebar, sidebar.getWidth() + dp(this, 4)); };
    private final Runnable rotate = this::autoAdvance;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Images.init(this);
        Crash.install(this);
        nuvio = new Nuvio(this);
        W = getResources().getDisplayMetrics().widthPixels; H = getResources().getDisplayMetrics().heightPixels; PAD = dp(this, 56);
        buildUi();
        showHome(nuvio.cached());
        if (nuvio.signedIn() && nuvio.profileLocked()) {      // a locked profile asks for its PIN when the launcher starts
            rowsList.setVisibility(View.INVISIBLE);
            ui.post(() -> Pin.ask(this, nuvio.profile(), nuvio.profileName(), this::unlock, this::pickProfile));
        }
        refresh(false);
        tick();
        Crash.showLast(this);
    }
    @Override protected void onResume() { super.onResume(); if (nuvio.signedIn() && rows.isEmpty() && !loading) refresh(false); scheduleRotate(); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(rotate); }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); closeSidebar(false); showPage(false); lastContentFocus = null; ((RowsLayout) rowsList.getLayoutManager()).reset(); rowsList.scrollToPosition(0); focusContent(); }
    @Override protected void onDestroy() { super.onDestroy(); bg.shutdownNow(); }

    // ---------- layout ----------
    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF2A2B30, BG}));

        // Featured backdrop: full screen, sharp, fading out as the rows scroll up.
        heroLayer = new FrameLayout(this);
        bdA = new ImageView(this); bdA.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bdB = new ImageView(this); bdB.setScaleType(ImageView.ScaleType.CENTER_CROP); bdB.setAlpha(0f);
        heroLayer.addView(bdA, new FrameLayout.LayoutParams(-1, -1)); heroLayer.addView(bdB, new FrameLayout.LayoutParams(-1, -1)); bdFront = bdA;
        View left = new View(this); left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xB3000000, 0x59000000, 0x00000000, 0x00000000}));
        heroLayer.addView(left, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(this); bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x00000000, 0x80000000, BG}));
        heroLayer.addView(bottom, new FrameLayout.LayoutParams(-1, -1));
        root.addView(heroLayer, new FrameLayout.LayoutParams(-1, -1));

        buildHero();

        // Catalog: the featured carousel is the list's first item, the rows follow.
        rowsList = new RecyclerView(this); rowsList.setLayoutManager(new RowsLayout(this)); rowsList.setItemAnimator(null);
        rowsList.setClipToPadding(false); rowsList.setPadding(0, 0, 0, (int) (H * 0.55f));
        rowsAdapter = new Rows.RowsAdapter(rows, this, PAD);
        rowsList.setAdapter(new ConcatAdapter(new HeroAdapter(), rowsAdapter));
        rowsList.addOnScrollListener(new RecyclerView.OnScrollListener() { @Override public void onScrolled(RecyclerView rv, int dx, int dy) { syncHeroFade(); } });
        root.addView(rowsList, new FrameLayout.LayoutParams(-1, -1));

        // Applications
        appsPage = new LinearLayout(this); appsPage.setOrientation(LinearLayout.VERTICAL); appsPage.setVisibility(View.GONE);
        appsPage.setPadding(0, dp(this, 92), 0, 0);   // rows run to the screen edge; text keeps the 56dp margin appsPage.setClipChildren(false); appsPage.setClipToPadding(false);
        TextView date = text(this, DateFormat.format("EEEE d MMMM", new Date()).toString().toUpperCase(), 13, 0xB3FFFFFF, false); date.setLetterSpacing(0.18f); date.setPadding(PAD, 0, PAD, 0);
        appsPage.addView(date);
        TextView greet = text(this, greeting(), 28, Color.WHITE, true); greet.setPadding(PAD, dp(this, 6), PAD, dp(this, 6)); appsPage.addView(greet);
        appsScroll = new android.widget.ScrollView(this); appsScroll.setVerticalScrollBarEnabled(false); appsScroll.setClipChildren(false); appsScroll.setClipToPadding(false);
        appsScroll.setFillViewport(true);
        appsRows = new LinearLayout(this); appsRows.setOrientation(LinearLayout.VERTICAL); appsRows.setClipChildren(false); appsRows.setClipToPadding(false);
        appsRows.setPadding(0, dp(this, 4), 0, dp(this, 60));
        appsScroll.addView(appsRows);
        appsPage.addView(appsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(appsPage, new FrameLayout.LayoutParams(-1, -1));

        status = text(this, "", 16, 0xFFD0D0D0, false); status.setVisibility(View.GONE); status.setMaxWidth((int) (W * 0.5f));
        FrameLayout.LayoutParams stp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); stp.leftMargin = PAD; stp.bottomMargin = (int) (H * 0.18f);
        root.addView(status, stp);

        buildPill();
        buildSidebar();
        setContentView(root);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> { if (sideOpen && n != null && !inSidebar(n)) closeSidebar(false); });
    }
    private static String greeting() { int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); return h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening"; }

    // ---------- featured carousel ----------
    private final class HeroAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return 1; }
        @Override public int getItemViewType(int p) { return 7; }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
            FrameLayout f = new FrameLayout(MainActivity.this); f.setClipChildren(false);
            f.setLayoutParams(new RecyclerView.LayoutParams(-1, (int) (H * 0.72f)));
            if (heroView.getParent() != null) ((ViewGroup) heroView.getParent()).removeView(heroView);
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START); hp.bottomMargin = dp(MainActivity.this, 22);
            f.addView(heroView, hp);
            if (dots.getParent() != null) ((ViewGroup) dots.getParent()).removeView(dots);
            FrameLayout.LayoutParams dp2 = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); dp2.bottomMargin = dp(MainActivity.this, 30);
            f.addView(dots, dp2);
            return new RecyclerView.ViewHolder(f) { };
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int p) { }
    }
    private void buildHero() {
        heroView = new LinearLayout(this); heroView.setOrientation(LinearLayout.VERTICAL); heroView.setPadding(PAD, 0, PAD, 0); heroView.setClipChildren(false); heroView.setClipToPadding(false);
        heroLogo = new ImageView(this); heroLogo.setAdjustViewBounds(true); heroLogo.setScaleType(ImageView.ScaleType.FIT_START); heroLogo.setVisibility(View.GONE);
        heroView.addView(heroLogo, new LinearLayout.LayoutParams((int) (W * 0.30f), dp(this, 96)));
        heroTitle = text(this, "", 40, Color.WHITE, true); heroTitle.setMaxLines(2); heroTitle.setMaxWidth((int) (W * 0.5f));
        heroView.addView(heroTitle);
        heroMeta = text(this, "", 15, 0xF2FFFFFF, true); heroMeta.setPadding(0, dp(this, 12), 0, 0);
        heroView.addView(heroMeta);
        heroDesc = text(this, "", 15, 0xE6FFFFFF, false); heroDesc.setMaxLines(2); heroDesc.setLineSpacing(0, 1.25f); heroDesc.setPadding(0, dp(this, 8), 0, 0);
        heroView.addView(heroDesc);
        LinearLayout btns = new LinearLayout(this); btns.setGravity(Gravity.CENTER_VERTICAL); btns.setPadding(0, dp(this, 18), 0, dp(this, 4)); btns.setClipChildren(false); btns.setClipToPadding(false);
        playBtn = heroButton(btns, R.drawable.ic_play, "Play", v -> heroPlay());
        heroButton(btns, R.drawable.ic_add, null, v -> heroAdd());
        heroButton(btns, R.drawable.ic_info, null, v -> heroInfo());
        heroButton(btns, R.drawable.ic_next, null, v -> showFeatured(heroIndex + 1));
        heroFirst = playBtn;
        heroView.addView(btns);
        dots = new LinearLayout(this); dots.setGravity(Gravity.CENTER_VERTICAL);
    }
    private View heroButton(LinearLayout parent, int icon, String label, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this); b.setGravity(Gravity.CENTER); b.setFocusable(true); b.setClickable(true);
        ImageView ic = new ImageView(this); ic.setImageResource(icon); ic.setColorFilter(Color.WHITE);
        b.addView(ic, new LinearLayout.LayoutParams(dp(this, 13), dp(this, 13)));
        TextView t = null;
        if (label != null) { t = text(this, label, 12, Color.WHITE, true); t.setPadding(dp(this, 5), 0, 0, 0); b.addView(t); b.setPadding(dp(this, 15), 0, dp(this, 17), 0); }
        final TextView tl = t;
        b.setBackground(round(0x59000000, 14, this));
        b.setOnFocusChangeListener((v, f) -> {
            v.setBackground(round(f ? 0xFFF4F4F6 : 0x59000000, 14, this)); ic.setColorFilter(f ? 0xFF121316 : Color.WHITE); if (tl != null) tl.setTextColor(f ? 0xFF121316 : Color.WHITE);
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(120).start();
            if (f) { lastContentFocus = v; ((RowsLayout) rowsList.getLayoutManager()).showRow(rowsList, 0); }
        });
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(label != null ? -2 : dp(this, 28), dp(this, 28)); lp.rightMargin = dp(this, 9);
        b.setPivotX(0);
        parent.addView(b, lp);
        return b;
    }
    private void pickFeatured() {
        featured.clear();
        for (Nuvio.Row r : rows) {
            if (r.continueWatching || r.isCollection()) continue;
            for (Nuvio.Item it : r.items) if (it.background != null && !it.background.isEmpty() && featured.size() < 8) featured.add(it);
            if (!featured.isEmpty()) break;
        }
        if (featured.isEmpty()) for (Nuvio.Row r : rows) if (r.continueWatching) for (Nuvio.Item it : r.items) if (featured.size() < 8) featured.add(it);
        dots.removeAllViews();
        for (int i = 0; i < featured.size() && featured.size() > 1; i++) { View d = new View(this); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(this, 7), dp(this, 7)); lp.setMargins(dp(this, 4), 0, dp(this, 4), 0); dots.addView(d, lp); }
        showFeatured(0);
    }
    private void showFeatured(int i) {
        TextView playLabel = (TextView) ((ViewGroup) playBtn).getChildAt(1);
        if (featured.isEmpty()) {
            heroLogo.setVisibility(View.GONE); heroTitle.setVisibility(View.VISIBLE);
            heroTitle.setText(nuvio.signedIn() ? (loading ? "Loading your Nuvio Home…" : "Your Nuvio Home") : "Nuvio Clean TV");
            heroMeta.setText(""); heroDesc.setText(nuvio.signedIn() ? "" : "Sign in to Nuvio to fill your Home with Continue Watching, your catalogs and collections.");
            playLabel.setText(nuvio.signedIn() ? "Refresh" : "Sign in");
            return;
        }
        playLabel.setText("Play");
        heroIndex = ((i % featured.size()) + featured.size()) % featured.size();
        Nuvio.Item it = featured.get(heroIndex);
        heroTitle.setText(it.name); heroTitle.setVisibility(View.VISIBLE); heroLogo.setVisibility(View.GONE); heroLogo.setImageDrawable(null);
        heroMeta.setText(("series".equals(Nuvio.norm(it.type)) ? "TV Show" : "Movie") + (it.sub == null || it.sub.isEmpty() ? "" : "  ·  " + it.sub.replace(" · ", "  ·  ")));
        heroDesc.setText(it.description == null ? "" : it.description);
        if (it.logo != null && !it.logo.isEmpty()) Images.load(heroLogo, it.logo, (int) (W * 0.30f), () -> { heroLogo.setVisibility(View.VISIBLE); heroTitle.setVisibility(View.GONE); });
        // cross-fade the backdrop
        ImageView back = bdFront == bdA ? bdB : bdA;
        back.setAlpha(0f);
        Images.load(back, Nuvio.nonEmpty(it.background, it.poster), W, () -> { back.animate().alpha(1f).setDuration(450).start(); if (bdFront != back) bdFront.animate().alpha(0f).setDuration(450).start(); bdFront = back; });
        for (int d = 0; d < dots.getChildCount(); d++) {
            View v = dots.getChildAt(d); boolean on = d == heroIndex;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams(); lp.width = dp(this, on ? 22 : 7); v.setLayoutParams(lp);
            v.setBackground(round(on ? 0xFFFFFFFF : 0x99FFFFFF, 4, this));
        }
        scheduleRotate();
    }
    private Nuvio.Item heroItem() { return featured.isEmpty() ? null : featured.get(heroIndex); }
    private void heroPlay() {
        Nuvio.Item it = heroItem();
        if (it == null) { if (nuvio.signedIn()) refresh(true); else account(); return; }
        Launch.play(this, it);
    }
    private void heroInfo() { Nuvio.Item it = heroItem(); if (it != null) onItem(it); }
    private void heroAdd() {
        Nuvio.Item it = heroItem(); if (it == null) return;
        bg.execute(() -> { boolean ok = nuvio.addToLibrary(it); ui.post(() -> Toast.makeText(this, ok ? "Added " + it.name + " to your Nuvio library" : "Couldn't add to your Nuvio library", Toast.LENGTH_SHORT).show()); });
    }
    private void scheduleRotate() { ui.removeCallbacks(rotate); ui.postDelayed(rotate, 9000); }
    private void autoAdvance() {
        View hero = rowsList.getLayoutManager().findViewByPosition(0);
        if (!showingApps && !sideOpen && hero != null && hero.getTop() > -dp(this, 10) && featured.size() > 1) showFeatured(heroIndex + 1);
        else scheduleRotate();
    }
    private void syncHeroFade() {
        View hero = rowsList.getLayoutManager().findViewByPosition(0);
        float a = hero == null ? 0f : Math.max(0f, Math.min(1f, 1f + hero.getTop() / (H * 0.55f)));
        heroLayer.setAlpha(showingApps ? 0f : a);
        if (!sideOpen) pill.setVisibility(showingApps || a > 0.9f ? View.VISIBLE : View.INVISIBLE);
    }

    // ---------- pill + sidebar ----------
    private void buildPill() {
        pill = new LinearLayout(this); pill.setGravity(Gravity.CENTER_VERTICAL); pill.setPadding(dp(this, 8), dp(this, 6), dp(this, 16), dp(this, 6));
        pill.setBackground(round(0x66303238, 22, this));
        ImageView chev = new ImageView(this); chev.setImageResource(R.drawable.ic_back); chev.setColorFilter(0xCCFFFFFF);
        pill.addView(chev, new LinearLayout.LayoutParams(dp(this, 16), dp(this, 16)));
        FrameLayout ic = new FrameLayout(this); ic.setBackground(round(0x33FFFFFF, 14, this));
        pillIcon = new ImageView(this); pillIcon.setColorFilter(Color.WHITE); pillIcon.setImageResource(R.drawable.ic_catalog);
        ic.addView(pillIcon, new FrameLayout.LayoutParams(dp(this, 16), dp(this, 16), Gravity.CENTER));
        LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(dp(this, 28), dp(this, 28)); icp.leftMargin = dp(this, 4); pill.addView(ic, icp);
        pillLabel = text(this, "", 15, Color.WHITE, true); pillLabel.setPadding(dp(this, 8), 0, 0, 0); pill.addView(pillLabel);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START); pp.leftMargin = PAD - dp(this, 12); pp.topMargin = dp(this, 32);
        root.addView(pill, pp);
    }
    private static final int[] ICONS = {R.drawable.ic_catalog, R.drawable.ic_apps, R.drawable.ic_inputs, R.drawable.ic_clock, R.drawable.ic_settings, R.drawable.ic_nuvio};
    private static final String[] LABELS = {"Catalog", "Applications", "Inputs", "Clock", "Settings", "Nuvio Settings"};
    private void buildSidebar() {
        sidebar = new LinearLayout(this); sidebar.setOrientation(LinearLayout.VERTICAL); sidebar.setVisibility(View.GONE);
        frost = new Frost(dp(this, 28)); sidebar.setBackground(frost); sidebar.setPadding(dp(this, 10), dp(this, 14), dp(this, 10), dp(this, 16));
        sidebar.setElevation(dp(this, 12));
        // header: profile (opens the Nuvio account) and the clock
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setFocusable(true); head.setClickable(true);
        head.setPadding(dp(this, 8), dp(this, 8), dp(this, 12), dp(this, 8));
        avatar = text(this, "", 15, Color.WHITE, true); avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(round(0xFF6B5CFF, 18, this));
        head.addView(avatar, new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36)));
        sideName = text(this, "", 15, Color.WHITE, false); sideName.setSingleLine(true); sideName.setPadding(dp(this, 10), 0, dp(this, 8), 0);
        head.addView(sideName, new LinearLayout.LayoutParams(0, -2, 1));
        sideClock = text(this, "", 14, 0xCCFFFFFF, false); head.addView(sideClock);
        sideItems.add(head);
        styleSide(head, null, new TextView[]{sideName, sideClock});
        head.setOnClickListener(v -> { if (nuvio.signedIn()) pickProfile(); else account(); });   // the profile opens "Who's watching?"
        sidebar.addView(head);
        View gap = new View(this); sidebar.addView(gap, new LinearLayout.LayoutParams(1, dp(this, 12)));
        for (int i = 0; i < LABELS.length; i++) {
            LinearLayout it = new LinearLayout(this); it.setGravity(Gravity.CENTER_VERTICAL); it.setFocusable(true); it.setClickable(true);
            it.setPadding(dp(this, 8), dp(this, 7), dp(this, 16), dp(this, 7));
            FrameLayout circle = new FrameLayout(this); circle.setBackground(round(0x40000000, 17, this));
            ImageView ic = new ImageView(this); ic.setImageResource(ICONS[i]); ic.setColorFilter(Color.WHITE);
            circle.addView(ic, new FrameLayout.LayoutParams(dp(this, 18), dp(this, 18), Gravity.CENTER));
            it.addView(circle, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));
            TextView t = text(this, LABELS[i], 16, Color.WHITE, false); t.setPadding(dp(this, 12), 0, 0, 0); t.setSingleLine(true);
            it.addView(t);
            sideItems.add(it);
            styleSide(it, ic, new TextView[]{t});
            final int idx = i;
            it.setOnClickListener(v -> onSide(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(this, 3);
            sidebar.addView(it, lp);
        }
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(this, 250), -2, Gravity.TOP | Gravity.START);
        sp.leftMargin = dp(this, 22); sp.topMargin = dp(this, 22);
        root.addView(sidebar, sp);
    }
    private void styleSide(View v, ImageView ic, TextView[] texts) {
        v.setOnFocusChangeListener((x, f) -> {
            x.setBackground(f ? round(0xFFF4F4F6, 24, this) : currentBg(x));
            if (ic != null) ic.setColorFilter(f ? 0xFF121316 : Color.WHITE);
            for (TextView t : texts) t.setTextColor(f ? 0xFF121316 : Color.WHITE);
        });
    }
    private Drawable currentBg(View item) {
        int idx = sideItems.indexOf(item) - 1;   // 0 Catalog, 1 Applications
        return idx == (showingApps ? 1 : 0) ? round(0x26FFFFFF, 24, this) : null;
    }
    private boolean inSidebar(View v) { while (v != null) { if (v == sidebar) return true; ViewParent p = v.getParent(); v = p instanceof View ? (View) p : null; } return false; }
    private void openSidebar() {
        if (sideOpen) return; sideOpen = true;
        View f = getCurrentFocus(); if (f != null && !inSidebar(f)) lastContentFocus = f;
        String name = nuvio.profileName().isEmpty() ? (nuvio.signedIn() ? "Profile " + nuvio.profile() : "Sign in") : nuvio.profileName();
        sideName.setText(name); avatar.setText(name.substring(0, 1).toUpperCase());
        for (View it : sideItems) if (!it.hasFocus()) it.setBackground(currentBg(it));
        pill.setVisibility(View.INVISIBLE);
        sidebar.setVisibility(View.VISIBLE); sidebar.setAlpha(0f); sidebar.setTranslationX(-dp(this, 24));
        sidebar.animate().alpha(1f).translationX(0).setDuration(180).start();
        sidebar.post(recapture);
        sideItems.get(showingApps ? 2 : 1).requestFocus();
    }
    private void closeSidebar(boolean refocus) {
        if (!sideOpen) return; sideOpen = false;
        sidebar.animate().alpha(0f).translationX(-dp(this, 24)).setDuration(150).withEndAction(() -> { if (!sideOpen) sidebar.setVisibility(View.GONE); }).start();
        syncHeroFade();
        if (refocus) focusContent();
    }
    private void onSide(int i) {
        switch (i) {
            case 0: showPage(false); closeSidebar(true); break;
            case 1: showPage(true); closeSidebar(true); break;
            case 2: case 3: case 4: Toast.makeText(this, LABELS[i] + " arrives in the next build", Toast.LENGTH_SHORT).show(); break;
            case 5: openNuvio(); break;
        }
    }
    private void showPage(boolean apps) {
        if (apps != showingApps) lastContentFocus = null;
        showingApps = apps;
        rowsList.setVisibility(apps ? View.GONE : View.VISIBLE); appsPage.setVisibility(apps ? View.VISIBLE : View.GONE);
        pillIcon.setImageResource(apps ? R.drawable.ic_apps : R.drawable.ic_catalog);
        if (apps && !appsBuilt) { appsBuilt = true; buildAppRows(loadApps()); }
        syncHeroFade();
    }
    private void focusContent() {
        ui.post(() -> {
            if (lastContentFocus != null && lastContentFocus.isAttachedToWindow() && lastContentFocus.isShown()) { lastContentFocus.requestFocus(); return; }
            if (showingApps) { if (!appsRows.requestFocus()) appsRows.post(appsRows::requestFocus); }
            else heroFirst.requestFocus();
        });
    }

    // ---------- keys ----------
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            scheduleRotate();
            View f = getCurrentFocus();
            int k = e.getKeyCode();
            if (sideOpen) {
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_BACK) { closeSidebar(true); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            } else if (f != null) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && atRowStart(f)) { openSidebar(); return true; }
                if (k == KeyEvent.KEYCODE_BACK) {
                    View hero = rowsList.getLayoutManager().findViewByPosition(0);
                    if (!showingApps && (hero == null || hero.getTop() < 0)) { lastContentFocus = null; ((RowsLayout) rowsList.getLayoutManager()).reset(); rowsList.smoothScrollToPosition(0); heroFirst.requestFocus(); }
                    else openSidebar();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }
    /** True for the hero's Play button or the first card of a row (catalog rows and app rows). */
    private boolean atRowStart(View f) {
        if (f == heroFirst) return true;
        View card = f; ViewParent p = f.getParent();
        while (p instanceof View && !(p instanceof RecyclerView)) { card = (View) p; p = p.getParent(); }
        if (!(p instanceof RecyclerView)) return false;
        RecyclerView rv = (RecyclerView) p; int pos = rv.getChildAdapterPosition(card);
        return rv != rowsList && pos == 0;
    }

    // ---------- data ----------
    private void showHome(Nuvio.Home h) {
        rows.clear();
        if (h != null) rows.addAll(h.rows);
        rowsAdapter.notifyDataSetChanged();
        ((RowsLayout) rowsList.getLayoutManager()).reset();
        pickFeatured();
        if (nuvio.signedIn() && rows.isEmpty() && !loading) status("Nothing to show yet."); else status(null);
        if (getCurrentFocus() == null) focusContent();
    }
    private void status(String s) { status.setText(s == null ? "" : s); status.setVisibility(s == null ? View.GONE : View.VISIBLE); }
    private void refresh(boolean force) {
        if (!nuvio.signedIn() || loading) return;
        loading = true;
        if (rows.isEmpty()) showFeatured(0);
        bg.execute(() -> {
            try {
                Nuvio.Home h = nuvio.loadHome();
                ui.post(() -> {
                    loading = false;
                    // Swap in the fresh Home unless the user is browsing rows; otherwise it is saved for the next start.
                    View f = getCurrentFocus();
                    boolean browsing = f != null && !showingApps && !sideOpen && f.getParent() instanceof RecyclerView;
                    if (force || rows.isEmpty() || !browsing) { lastContentFocus = null; showHome(h); }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    loading = false;
                    if (!nuvio.signedIn()) { showHome(null); Toast.makeText(this, "Signed out of Nuvio. Sign in again from the sidebar.", Toast.LENGTH_LONG).show(); }
                    else if (rows.isEmpty()) { showFeatured(0); status("Couldn't load your Nuvio Home: " + e.getMessage()); }
                });
            }
        });
    }

    // ---------- rows ----------
    @Override public void onItemFocused(Nuvio.Row row, Nuvio.Item it) { }
    @Override public void onFolderFocused(Nuvio.Row row, JSONObject f) { }
    @Override public void onRowFocused(int rowIndex, View rowView) {
        View f = getCurrentFocus(); if (f != null) lastContentFocus = f;
        ((RowsLayout) rowsList.getLayoutManager()).showRow(rowsList, rowIndex + 1);
    }
    @Override public void onItem(Nuvio.Item it) { startActivity(new Intent(this, DetailActivity.class).putExtra("item", Launch.json(it))); }
    @Override public void onFolder(JSONObject f) { startActivity(new Intent(this, FolderActivity.class).putExtra("folder", f.toString())); }

    // ---------- Nuvio ----------
    private void openNuvio() {
        String p = Launch.pkg(this);
        Intent i = p == null ? null : getPackageManager().getLeanbackLaunchIntentForPackage(p);
        if (i == null && p != null) i = getPackageManager().getLaunchIntentForPackage(p);
        if (i != null) { startActivity(i); Toast.makeText(this, "Nuvio has no direct link to its settings yet: open Settings in Nuvio.", Toast.LENGTH_LONG).show(); }
        else Toast.makeText(this, "Nuvio isn't installed.", Toast.LENGTH_LONG).show();
    }
    private void account() {
        if (!nuvio.signedIn()) { startActivityForResult(new Intent(this, SignInActivity.class), 1); return; }
        String prof = nuvio.profileName().isEmpty() ? "Profile " + nuvio.profile() : nuvio.profileName();
        new AlertDialog.Builder(this).setTitle("Nuvio account")
                .setMessage("Signed in as " + nuvio.email() + "\nProfile: " + prof)
                .setPositiveButton("Switch profile", (d, w) -> pickProfile())
                .setNeutralButton("Refresh Home", (d, w) -> { Toast.makeText(this, "Refreshing your Nuvio Home…", Toast.LENGTH_SHORT).show(); refresh(true); })
                .setNegativeButton("Sign out", (d, w) -> bg.execute(() -> { nuvio.signOut(); ui.post(() -> { lastContentFocus = null; showHome(null); }); }))
                .show();
    }
    private void unlock() { rowsList.setVisibility(showingApps ? View.GONE : View.VISIBLE); focusContent(); }
    private void pickProfile() {
        bg.execute(() -> {
            try {
                JSONArray ps = nuvio.profiles();
                List<String> names = new ArrayList<>(), labels = new ArrayList<>(); List<Integer> idx = new ArrayList<>(); List<Boolean> locked = new ArrayList<>();
                for (int i = 0; i < ps.length(); i++) { JSONObject p = ps.getJSONObject(i); names.add(p.optString("name")); locked.add(p.optBoolean("pin_enabled")); labels.add(p.optString("name") + (p.optBoolean("pin_enabled") ? "  🔒" : "")); idx.add(p.optInt("profile_index")); }
                boolean optional = !nuvio.profileName().isEmpty() && rowsList.getVisibility() != View.INVISIBLE;
                ui.post(() -> {
                    AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Who's watching?").setItems(labels.toArray(new String[0]), (d, w) -> {
                        Runnable go = () -> { nuvio.setProfile(idx.get(w), names.get(w), locked.get(w)); lastContentFocus = null; unlock(); showHome(nuvio.cached()); refresh(true); };
                        if (locked.get(w)) Pin.ask(this, idx.get(w), names.get(w), go, this::pickProfile); else go.run();
                    }).setCancelable(optional);
                    if (optional) b.setNeutralButton("Refresh Home", (d, w) -> { Toast.makeText(this, "Refreshing your Nuvio Home…", Toast.LENGTH_SHORT).show(); refresh(true); })
                            .setNegativeButton("Sign out", (d, w) -> bg.execute(() -> { nuvio.signOut(); ui.post(() -> { lastContentFocus = null; showHome(null); }); }));
                    b.show();
                });
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, "Couldn't load profiles: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        });
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && res == RESULT_OK) { showHome(null); refresh(true); pickProfile(); }
    }

    // ---------- clock ----------
    private void tick() {
        String now = DateFormat.getTimeFormat(this).format(new Date());
        sideClock.setText(now); pillLabel.setText(now);
        ui.postDelayed(this::tick, 20000);
    }

    // ---------- applications ----------
    static final class App { String label, pkg; Intent launch; Drawable banner, icon; int cat = -1; boolean game; }
    private List<App> loadApps() {
        PackageManager pm = getPackageManager();
        List<App> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            App a = new App(); a.pkg = ri.activityInfo.packageName; a.label = String.valueOf(ri.loadLabel(pm));
            a.launch = pm.getLeanbackLaunchIntentForPackage(a.pkg);
            a.banner = ri.activityInfo.loadBanner(pm); if (a.banner == null) a.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (a.banner == null) a.icon = ri.loadIcon(pm);
            a.cat = ri.activityInfo.applicationInfo.category;
            a.game = (ri.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0;
            out.add(a);
        }
        Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return out;
    }
    private static final String[] GROUPS = {"Streaming", "Music & Media", "Games", "Tools", "TV & System", "Other"};
    private static boolean has(String s, String... words) { for (String w : words) if (s.contains(w)) return true; return false; }
    /** The app's row: names we know first, then the category the app declares to Android, then TCL/system packages. */
    static int group(App a) {
        String n = a.label.toLowerCase(java.util.Locale.ROOT), p = a.pkg.toLowerCase(java.util.Locale.ROOT);
        if (p.equals("com.android.vending")) return 3;                                   // Play Store
        if (has(p, "com.google.android.play.games") || has(n, "games")) return 2;
        if (has(p, "com.google.android.videos") || has(n, "nuvio", "youtube", "netflix", "prime video", "disney", "hbo", "apple tv", "dazn", "plex", "stremio", "kodi", "twitch", "sport", "movies", "crunchyroll", "paramount", "peacock", "hulu", "jellyfin", "emby")) return 0;
        if (has(n, "music", "spotify", "player", "gallery", "photo", "media", "magiconnect", "exhibition", "t-solo", "tidal", "deezer", "podcast", "radio")) return 1;
        if (has(n, "downloader", "vpn", "speed", "browser", "file", "cleaner", "send files", "analy")) return 3;
        if (has(n, "setting", "guide", "quick", "safety", "panel", "tcl", "home") || n.equals("tv") || has(p, "com.tcl.", "com.android.", "com.google.android.tv")) return 4;
        if (a.game) return 2;
        switch (a.cat) {
            case android.content.pm.ApplicationInfo.CATEGORY_VIDEO: return 0;
            case android.content.pm.ApplicationInfo.CATEGORY_AUDIO: case android.content.pm.ApplicationInfo.CATEGORY_IMAGE: return 1;
            case android.content.pm.ApplicationInfo.CATEGORY_GAME: return 2;
            case android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY: case android.content.pm.ApplicationInfo.CATEGORY_ACCESSIBILITY: return 3;
        }
        return 5;
    }
    /** One sideways row per category, empty categories left out; Nuvio leads Streaming. */
    private void buildAppRows(List<App> all) {
        appsRows.removeAllViews();
        for (int g = 0; g < GROUPS.length; g++) {
            List<App> in = new ArrayList<>();
            for (App a : all) if (group(a) == g) in.add(a);
            if (in.isEmpty()) continue;
            Collections.sort(in, (x, y) -> { boolean nx = x.label.toLowerCase().contains("nuvio"), ny = y.label.toLowerCase().contains("nuvio"); return nx != ny ? (nx ? -1 : 1) : x.label.compareToIgnoreCase(y.label); });
            TextView t = text(this, GROUPS[g], 18, Color.WHITE, true); t.setPadding(PAD, dp(this, appsRows.getChildCount() == 0 ? 10 : 18), PAD, 0);
            appsRows.addView(t);
            RecyclerView rv = new RecyclerView(this);
            rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            rv.setClipToPadding(false); rv.setClipChildren(false); rv.setItemAnimator(null);
            rv.setPadding(PAD, dp(this, 8), PAD, dp(this, 8));   // first tile at the margin; scrolled tiles show to the screen edge
            rv.setAdapter(new AppsAdapter(in));
            appsRows.addView(rv, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    final class AppsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<App> apps; final int tileW, tileH;
        AppsAdapter(List<App> a) {
            apps = a;
            tileW = dp(MainActivity.this, 224); tileH = dp(MainActivity.this, 126);   // same size as the Continue Watching cards
        }
        @Override public int getItemCount() { return apps.size(); }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
            LinearLayout card = new LinearLayout(MainActivity.this); card.setOrientation(LinearLayout.VERTICAL); card.setFocusable(true); card.setClickable(true); card.setGravity(Gravity.START);
            FrameLayout art = new FrameLayout(MainActivity.this); art.setBackground(round(0xFF2E3036, Rows.CORNER, MainActivity.this)); art.setClipToOutline(true);
            ImageView iv = new ImageView(MainActivity.this); iv.setScaleType(ImageView.ScaleType.FIT_CENTER); art.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            card.addView(art, new LinearLayout.LayoutParams(tileW, tileH));
            TextView name = text(MainActivity.this, "", 14, Color.WHITE, false); name.setSingleLine(true); name.setPadding(0, dp(MainActivity.this, 10), 0, 0); name.setVisibility(View.INVISIBLE);
            card.addView(name);
            int m = dp(MainActivity.this, 9);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-2, -2); lp.setMargins(0, m, 2 * m, 0); card.setLayoutParams(lp);
            card.setOnFocusChangeListener((v, f) -> { Rows.lift(v, art, f); name.setVisibility(f ? View.VISIBLE : View.INVISIBLE); if (f) lastContentFocus = v; });
            return new RecyclerView.ViewHolder(card) { };
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
            App a = apps.get(pos); LinearLayout card = (LinearLayout) h.itemView;
            FrameLayout art = (FrameLayout) card.getChildAt(0); ImageView iv = (ImageView) art.getChildAt(0); TextView name = (TextView) card.getChildAt(1);
            iv.setImageDrawable(a.banner != null ? a.banner : a.icon); iv.setScaleType(a.banner != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            int ip = a.banner != null ? 0 : dp(MainActivity.this, 16); iv.setPadding(ip, ip, ip, ip);
            name.setText(a.label);
            card.setOnClickListener(v -> { if (a.launch != null) startActivity(a.launch); });
        }
    }
}
