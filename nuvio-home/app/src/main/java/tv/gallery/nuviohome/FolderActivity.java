package tv.gallery.nuviohome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static tv.gallery.nuviohome.Rows.dp;
import static tv.gallery.nuviohome.Rows.text;

/** A collection folder: one row per addon catalog source (with its genre filter). Back returns to Home. */
public class FolderActivity extends Activity implements Rows.Listener {
    private final List<Nuvio.Row> rows = new ArrayList<>();
    private Rows.RowsAdapter adapter;
    private RecyclerView list;
    private ImageView backdrop;
    private TextView heroTitle, heroMeta, heroDesc, status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        JSONObject folder;
        try { folder = new JSONObject(getIntent().getStringExtra("folder")); }
        catch (Exception e) { finish(); return; }
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF0B0C10);
        backdrop = new ImageView(this); backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP); backdrop.setAlpha(0.5f);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(this, 56), dp(this, 36), 0, 0); 
        page.addView(text(this, folder.optString("title"), 30, Color.WHITE, true));
        heroTitle = text(this, "", 20, Color.WHITE, true); heroTitle.setPadding(0, dp(this, 14), 0, 0);
        heroMeta = text(this, "", 13, 0xD9FFFFFF, false); heroMeta.setPadding(0, dp(this, 6), 0, 0);
        heroDesc = text(this, "", 13, 0xB3FFFFFF, false); heroDesc.setMaxLines(2); heroDesc.setMaxWidth(dp(this, 520)); heroDesc.setPadding(0, dp(this, 6), 0, 0);
        page.addView(heroTitle); page.addView(heroMeta); page.addView(heroDesc);
        status = text(this, "Loading…", 15, 0xFFB3B3B3, false); status.setPadding(0, dp(this, 16), 0, 0); page.addView(status);
        list = new RecyclerView(this); list.setLayoutManager(new LinearLayoutManager(this)); list.setItemAnimator(null); list.setClipToPadding(false);
        list.setPadding(0, 0, 0, getResources().getDisplayMetrics().heightPixels / 2);
        adapter = new Rows.RowsAdapter(rows, this); list.setAdapter(adapter);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (!folder.isNull("coverImageUrl")) Images.loadBlurred(backdrop, folder.optString("coverImageUrl"), null);

        JSONArray srcs = folder.optJSONArray("sources"); if (srcs == null || srcs.length() == 0) srcs = folder.optJSONArray("catalogSources");
        final JSONArray sources = srcs == null ? new JSONArray() : srcs;
        Nuvio nuvio = new Nuvio(this);
        new Thread(() -> {
            Nuvio.Home saved = nuvio.cached();
            JSONArray addonsJson = saved == null || saved.addons == null ? new JSONArray() : saved.addons;
            List<Nuvio.Addon> addons = new ArrayList<>();
            for (int i = 0; i < addonsJson.length(); i++) addons.add(Nuvio.addonFrom(addonsJson.optJSONObject(i)));
            int skipped = 0;
            for (int i = 0; i < sources.length(); i++) {
                JSONObject s = sources.optJSONObject(i); if (s == null) continue;
                String provider = s.optString("provider", "addon").toLowerCase();
                if (!provider.equals("addon")) { skipped++; continue; }
                Nuvio.Addon ad = null; for (Nuvio.Addon x : addons) if (x.id.equals(s.optString("addonId"))) ad = x;
                try {
                    Nuvio.Row r = nuvio.catalogRow(ad, s.optString("type"), s.optString("catalogId"), s.isNull("genre") ? null : s.optString("genre"), null);
                    if (r != null && !r.items.isEmpty()) runOnUiThread(() -> { rows.add(r); adapter.notifyItemInserted(rows.size() - 1); if (rows.size() == 1) { status.setVisibility(View.GONE); list.requestFocus(); } });
                } catch (Exception ignored) { }
            }
            final int sk = skipped;
            runOnUiThread(() -> { if (rows.isEmpty()) status.setText(sk > 0 ? "This folder uses TMDB or Trakt sources, which arrive in a later build." : "Nothing in this folder right now."); });
        }).start();
    }

    @Override public void onItemFocused(Nuvio.Row row, Nuvio.Item it) {
        heroTitle.setText(it.name); heroMeta.setText(it.sub); heroDesc.setText(it.description);
        Images.loadBlurred(backdrop, Nuvio.nonEmpty(it.background, it.poster), null);
    }
    @Override public void onFolderFocused(Nuvio.Row row, JSONObject folder) { }
    @Override public void onRowFocused(int rowIndex, View rowView) { if (rowView.getTop() != 0) list.smoothScrollBy(0, rowView.getTop()); }
    @Override public void onItem(Nuvio.Item it) {
        try { startActivity(new Intent(this, DetailActivity.class).putExtra("item", it.toJson().toString())); } catch (Exception ignored) { }
    }
    @Override public void onFolder(JSONObject folder) { }
}
