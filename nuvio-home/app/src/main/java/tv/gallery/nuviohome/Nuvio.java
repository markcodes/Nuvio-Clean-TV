package tv.gallery.nuviohome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Reads the owner's Nuvio account through Nuvio's documented public API (https://nuvio.tv/docs) and their
 * addons (Stremio addon protocol), and builds Home the way Nuvio lays it out. The password is never stored:
 * only Nuvio's refresh token, in this app's private preferences. Home is saved to a file so the next start
 * shows it at once while a fresh copy loads.
 */
final class Nuvio {
    static final String API = "https://api.nuvio.tv";
    // Published for third-party clients at https://nuvio.tv/docs#publishable-key
    static final String KEY = "sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN";
    private static final String CINEMETA = "https://v3-cinemeta.strem.io";

    private final Context ctx;
    private final SharedPreferences prefs;
    private volatile String access;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    Nuvio(Context c) { ctx = c.getApplicationContext(); prefs = ctx.getSharedPreferences("nuvio", Context.MODE_PRIVATE); }

    boolean signedIn() { return prefs.getString("refresh", null) != null; }
    String email() { return prefs.getString("email", ""); }
    int profile() { return prefs.getInt("profile", 1); }
    String profileName() { return prefs.getString("profileName", ""); }
    void setProfile(int idx, String name, boolean locked) { prefs.edit().putInt("profile", idx).putString("profileName", name).putBoolean("profileLocked", locked).apply(); }
    boolean profileLocked() { return prefs.getBoolean("profileLocked", false); }

    // ---------- model ----------
    static final class Item {
        String type, id, name, poster, background, logo, description, year, sub, videoId; int pct = -1, season = -1, episode = -1;
        JSONObject toJson() throws Exception {
            return new JSONObject().put("type", type).put("id", id).put("name", name).put("poster", poster).put("background", background).put("logo", logo)
                    .put("description", description).put("year", year).put("sub", sub).put("pct", pct).put("videoId", videoId).put("season", season).put("episode", episode);
        }
        static Item from(JSONObject j) {
            Item i = new Item(); i.type = j.optString("type"); i.id = j.optString("id"); i.name = j.optString("name");
            i.poster = j.optString("poster"); i.background = j.optString("background"); i.logo = j.optString("logo"); i.description = j.optString("description");
            i.year = j.optString("year"); i.sub = j.optString("sub"); i.pct = j.optInt("pct", -1); i.videoId = j.optString("videoId", null); i.season = j.optInt("season", -1); i.episode = j.optInt("episode", -1); return i;
        }
        static Item fromMeta(JSONObject m, String fallbackType) {
            Item i = new Item(); i.type = norm(m.optString("type", fallbackType)); i.id = m.optString("id"); i.name = m.optString("name");
            i.poster = m.optString("poster"); i.background = m.optString("background"); i.logo = m.optString("logo");
            i.description = m.optString("description");
            String ri = m.optString("releaseInfo"); if (ri.isEmpty()) ri = m.optString("year"); i.year = ri;
            JSONArray g = m.optJSONArray("genres"); String rating = m.optString("imdbRating");
            StringBuilder sb = new StringBuilder();
            if (g != null && g.length() > 0) sb.append(g.optString(0));
            if (!ri.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(ri);
            if (!rating.isEmpty()) sb.append(sb.length() > 0 ? " · ★ " : "★ ").append(rating);
            i.sub = sb.toString(); return i;
        }
    }
    /** A Home row: catalog items, or a collection's folders. */
    static final class Row {
        String title; boolean continueWatching; List<Item> items = new ArrayList<>(); List<JSONObject> folders = new ArrayList<>();
        boolean isCollection() { return !folders.isEmpty(); }
    }
    static final class Home { List<Row> rows = new ArrayList<>(); JSONArray addons = new JSONArray(); long loadedAt; String log = ""; }
    static final class Addon { String id, name, base; JSONObject manifest; }

    // ---------- auth ----------
    String signIn(String email, String password) {
        try {
            Resp r = http("POST", API + "/auth/v1/token?grant_type=password", new JSONObject().put("email", email).put("password", password).toString(), false);
            if (r.code != 200) return "Sign-in failed: " + shortMsg(r.body);
            keep(new JSONObject(r.body)); prefs.edit().putString("email", email).apply(); return null;
        } catch (Exception e) { return "Sign-in failed: " + e.getMessage(); }
    }
    void signOut() {
        try { if (access != null) http("POST", API + "/auth/v1/logout", null, true); } catch (Exception ignored) { }
        access = null; prefs.edit().clear().apply(); Pin.clearAll(ctx);
        for (File f : ctx.getFilesDir().listFiles()) if (f.getName().startsWith("home_")) f.delete();
    }
    private void keep(JSONObject j) {
        access = j.optString("access_token", null);
        String rt = j.optString("refresh_token", null); if (rt != null) prefs.edit().putString("refresh", rt).apply();
    }
    private synchronized boolean refresh() {
        String rt = prefs.getString("refresh", null); if (rt == null) return false;
        try {
            Resp r = http("POST", API + "/auth/v1/token?grant_type=refresh_token", new JSONObject().put("refresh_token", rt).toString(), false);
            if (r.code == 400 || r.code == 401) { prefs.edit().remove("refresh").apply(); return false; }
            if (r.code != 200) return false;
            keep(new JSONObject(r.body)); return true;
        } catch (Exception e) { return false; }
    }
    private Resp api(String method, String path, String body) throws Exception {
        if (access == null && !refresh()) throw new IllegalStateException("Signed out of Nuvio");
        Resp r = http(method, API + path, body, true);
        if (r.code == 401 && refresh()) r = http(method, API + path, body, true);
        return r;
    }

    JSONArray profiles() throws Exception {
        Resp p = api("POST", "/rest/v1/rpc/sync_pull_profiles", null);
        if (p.code != 200) throw new IllegalStateException("Profiles: HTTP " + p.code);
        return new JSONArray(p.body);
    }

    // ---------- Home ----------
    Home cached() {
        File f = new File(ctx.getFilesDir(), "home_" + profile() + ".json");
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            JSONObject j = new JSONObject(readAll(in)); Home h = new Home(); h.loadedAt = j.optLong("loadedAt"); h.addons = j.optJSONArray("addons");
            JSONArray rows = j.getJSONArray("rows");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i); Row row = new Row(); row.title = r.optString("title"); row.continueWatching = r.optBoolean("cw");
                JSONArray it = r.optJSONArray("items"); if (it != null) for (int k = 0; k < it.length(); k++) row.items.add(Item.from(it.getJSONObject(k)));
                JSONArray fs = r.optJSONArray("folders"); if (fs != null) for (int k = 0; k < fs.length(); k++) row.folders.add(fs.getJSONObject(k));
                h.rows.add(row);
            }
            return h;
        } catch (Exception e) { return null; }
    }
    private void save(Home h, int profile) {
        try {
            JSONArray rows = new JSONArray();
            for (Row r : h.rows) {
                JSONArray it = new JSONArray(); for (Item i : r.items) it.put(i.toJson());
                JSONArray fs = new JSONArray(); for (JSONObject f : r.folders) fs.put(f);
                rows.put(new JSONObject().put("title", r.title).put("cw", r.continueWatching).put("items", it).put("folders", fs));
            }
            JSONObject j = new JSONObject().put("loadedAt", h.loadedAt).put("addons", h.addons).put("rows", rows);
            File tmp = new File(ctx.getFilesDir(), "home_" + profile + ".tmp");
            try (OutputStream o = new FileOutputStream(tmp)) { o.write(j.toString().getBytes(StandardCharsets.UTF_8)); }
            tmp.renameTo(new File(ctx.getFilesDir(), "home_" + profile + ".json"));
        } catch (Exception ignored) { }
    }

    /** Full load: addons, Continue Watching, then Home rows and collections in Nuvio's order. */
    Home loadHome() throws Exception {
        int idx = profile();
        StringBuilder log = new StringBuilder();
        JSONArray profiles = profiles();
        int addonProfile = idx;
        for (int i = 0; i < profiles.length(); i++) { JSONObject p = profiles.getJSONObject(i); if (p.optInt("profile_index") == idx && p.optBoolean("uses_primary_addons")) addonProfile = 1; }

        Resp a = api("GET", "/rest/v1/addons?select=*&profile_id=eq." + addonProfile + "&order=sort_order", null);
        if (a.code != 200) throw new IllegalStateException("Addons: HTTP " + a.code);
        JSONArray rows = new JSONArray(a.body);
        List<Future<Addon>> fa = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i); if (!r.optBoolean("enabled", true)) continue;
            fa.add(pool.submit(() -> {
                Resp m = get(manifestOf(r.optString("url"))); if (m.code != 200) return null;
                Addon ad = new Addon(); ad.base = baseOf(r.optString("url")); ad.manifest = new JSONObject(m.body);
                ad.id = ad.manifest.optString("id"); ad.name = r.isNull("name") ? ad.manifest.optString("name") : r.optString("name"); return ad;
            }));
        }
        List<Addon> addons = new ArrayList<>(); for (Future<Addon> f : fa) { try { Addon ad = f.get(); if (ad != null) addons.add(ad); } catch (Exception ignored) { } }
        log.append("addons ").append(addons.size()).append('\n');

        Home h = new Home(); h.loadedAt = System.currentTimeMillis();
        for (Addon ad : addons) {
            JSONArray slim = new JSONArray(), cs = ad.manifest.optJSONArray("catalogs");
            if (cs != null) for (int i = 0; i < cs.length(); i++) { JSONObject c = cs.getJSONObject(i); slim.put(new JSONObject().put("id", c.optString("id")).put("type", c.optString("type")).put("name", c.optString("name"))); }
            h.addons.put(new JSONObject().put("id", ad.id).put("name", ad.name).put("base", ad.base).put("catalogs", slim)
                    .put("resources", ad.manifest.optJSONArray("resources")).put("types", ad.manifest.optJSONArray("types")).put("idPrefixes", ad.manifest.optJSONArray("idPrefixes")));
        }

        // Continue Watching
        Future<Row> cw = pool.submit(() -> continueWatching(idx, addons));

        // Home layout and collections
        Map<String, JSONObject> colls = new HashMap<>();
        Resp cr = api("POST", "/rest/v1/rpc/sync_pull_collections", new JSONObject().put("p_profile_id", idx).toString());
        if (cr.code == 200) {
            JSONArray arr = new JSONArray(cr.body);
            if (arr.length() > 0) { Object cj = arr.getJSONObject(0).opt("collections_json"); JSONArray list = cj instanceof JSONArray ? (JSONArray) cj : cj instanceof String ? new JSONArray((String) cj) : new JSONArray();
                for (int i = 0; i < list.length(); i++) { JSONObject co = list.getJSONObject(i); colls.put(co.optString("id"), co); } }
        }
        Resp hs = api("POST", "/rest/v1/rpc/sync_pull_home_catalog_settings", new JSONObject().put("p_profile_id", idx).put("p_platform", "home_catalog_shared").toString());
        JSONArray items = null;
        if (hs.code == 200) { JSONArray arr = new JSONArray(hs.body); if (arr.length() > 0) { JSONObject sj = arr.getJSONObject(0).optJSONObject("settings_json"); if (sj != null) items = sj.optJSONArray("items"); } }
        List<JSONObject> order = new ArrayList<>();
        if (items != null) for (int i = 0; i < items.length(); i++) { JSONObject it = items.getJSONObject(i); if (it.optBoolean("enabled", true)) order.add(it); }
        order.sort((x, y) -> Integer.compare(x.optInt("order"), y.optInt("order")));
        if (order.isEmpty()) { // nothing synced: the addons' own catalogs, as Nuvio shows them by default
            for (Addon ad : addons) { JSONArray cs = ad.manifest.optJSONArray("catalogs"); if (cs == null) continue;
                for (int i = 0; i < cs.length() && order.size() < 12; i++) { JSONObject c = cs.getJSONObject(i); if (!needsExtra(c)) order.add(new JSONObject().put("addon_id", ad.id).put("type", c.optString("type")).put("catalog_id", c.optString("id"))); } }
        }
        List<Future<Row>> fr = new ArrayList<>();
        for (JSONObject it : order) {
            if (it.optBoolean("is_collection")) {
                JSONObject co = colls.get(it.optString("collection_id"));
                Row row = new Row(); if (co != null) { row.title = nonEmpty(it.optString("custom_title"), co.optString("title")); JSONArray fs = co.optJSONArray("folders"); if (fs != null) for (int i = 0; i < fs.length(); i++) row.folders.add(fs.getJSONObject(i)); }
                fr.add(java.util.concurrent.CompletableFuture.completedFuture(row));
            } else {
                Addon ad = null; for (Addon x : addons) if (x.id.equals(it.optString("addon_id"))) ad = x;
                final Addon fad = ad;
                fr.add(pool.submit(() -> catalogRow(fad, it.optString("type"), it.optString("catalog_id"), null, it.optString("custom_title"))));
            }
        }
        try { Row r = cw.get(); if (r != null && !r.items.isEmpty()) h.rows.add(r); } catch (Exception ignored) { }
        for (Future<Row> f : fr) { try { Row r = f.get(); if (r != null && (!r.items.isEmpty() || r.isCollection())) h.rows.add(r); } catch (Exception ignored) { } }
        h.log = log.toString();
        save(h, idx);
        return h;
    }

    private Row continueWatching(int idx, List<Addon> addons) throws Exception {
        Resp w = api("POST", "/rest/v1/rpc/sync_pull_watch_progress", new JSONObject().put("p_profile_id", idx).put("p_limit", 60).toString());
        if (w.code != 200) return null;
        JSONArray prog = new JSONArray(w.body);
        Set<String> seen = new LinkedHashSet<>(); List<JSONObject> latest = new ArrayList<>();
        for (int i = 0; i < prog.length() && latest.size() < 15; i++) {
            JSONObject p = prog.getJSONObject(i);
            long pos = p.optLong("position"), dur = p.optLong("duration");
            if (dur > 0 && pos * 100 / dur >= 95) continue;           // finished: leave out, as Nuvio does
            if (seen.add(p.optString("content_id"))) latest.add(p);
        }
        List<Future<Item>> fi = new ArrayList<>();
        for (JSONObject p : latest) fi.add(pool.submit(() -> {
            String type = norm(p.optString("content_type")), id = p.optString("content_id");
            JSONObject meta = findMeta(addons, type, id);
            Item it = meta != null ? Item.fromMeta(meta, type) : new Item();
            it.type = type; it.id = id; if (it.name == null || it.name.isEmpty()) it.name = id;
            long pos = p.optLong("position"), dur = p.optLong("duration");
            it.pct = dur > 0 ? (int) (pos * 100 / dur) : -1;
            String ep = p.isNull("season") ? "" : "S" + p.optInt("season") + " E" + p.optInt("episode");
            it.videoId = p.optString("video_id", null); if (!p.isNull("season")) { it.season = p.optInt("season"); it.episode = p.optInt("episode"); }
            String left = dur > 0 ? Math.max(1, (dur - pos) / 60000) + " min left" : "";
            it.sub = ep.isEmpty() ? left : (left.isEmpty() ? ep : ep + " · " + left);
            return it;
        }));
        Row row = new Row(); row.title = "Continue Watching"; row.continueWatching = true;
        for (Future<Item> f : fi) { try { row.items.add(f.get()); } catch (Exception ignored) { } }
        return row;
    }

    /** One catalog from an addon; genre is a collection folder's filter. */
    Row catalogRow(Addon ad, String type, String catalogId, String genre, String customTitle) throws Exception {
        if (ad == null) return null;
        String name = catalogId; JSONArray cs = ad.manifest.optJSONArray("catalogs");
        if (cs != null) for (int i = 0; i < cs.length(); i++) { JSONObject c = cs.getJSONObject(i); if (c.optString("id").equals(catalogId) && c.optString("type").equals(type)) name = nonEmpty(c.optString("name"), catalogId); }
        String path = "/catalog/" + enc(type) + "/" + enc(catalogId) + (genre == null || genre.isEmpty() ? "" : "/genre=" + enc(genre)) + ".json";
        Resp c = get(addonUrl(ad.base, path));
        if (c.code != 200) return null;
        JSONArray metas = new JSONObject(c.body).optJSONArray("metas");
        Row row = new Row(); row.title = nonEmpty(customTitle, name) + (genre == null || genre.isEmpty() ? "" : " · " + genre);
        if (metas != null) for (int i = 0; i < Math.min(30, metas.length()); i++) row.items.add(Item.fromMeta(metas.getJSONObject(i), type));
        return row;
    }
    static Addon addonFrom(JSONObject j) {
        Addon a = new Addon(); a.id = j.optString("id"); a.name = j.optString("name"); a.base = j.optString("base");
        try { a.manifest = new JSONObject().put("catalogs", j.optJSONArray("catalogs")).put("resources", j.optJSONArray("resources")).put("types", j.optJSONArray("types")).put("idPrefixes", j.optJSONArray("idPrefixes")); } catch (Exception e) { a.manifest = new JSONObject(); }
        return a;
    }

    JSONObject findMeta(List<Addon> addons, String type, String id) {
        for (Addon ad : addons) {
            if (!supports(ad.manifest, "meta", type, id)) continue;
            try { Resp r = get(addonUrl(ad.base, "/meta/" + enc(type) + "/" + enc(id) + ".json")); if (r.code == 200) { JSONObject m = new JSONObject(r.body).optJSONObject("meta"); if (m != null && m.has("name")) return m; } }
            catch (Exception ignored) { }
        }
        if (id.startsWith("tt")) {
            try { Resp r = get(CINEMETA + "/meta/" + enc(type) + "/" + enc(id) + ".json"); if (r.code == 200) { JSONObject m = new JSONObject(r.body).optJSONObject("meta"); if (m != null && m.has("name")) return m; } }
            catch (Exception ignored) { }
        }
        return null;
    }
    private static boolean supports(JSONObject man, String res, String type, String id) {
        JSONArray rs = man.optJSONArray("resources"); if (rs == null) return false;
        for (int i = 0; i < rs.length(); i++) {
            Object o = rs.opt(i); JSONArray types = man.optJSONArray("types"), pre = man.optJSONArray("idPrefixes");
            if (o instanceof String) { if (!res.equals(o)) continue; }
            else if (o instanceof JSONObject) { JSONObject r = (JSONObject) o; if (!res.equals(r.optString("name"))) continue; if (r.has("types")) types = r.optJSONArray("types"); if (r.has("idPrefixes")) pre = r.optJSONArray("idPrefixes"); }
            else continue;
            if (types != null) { boolean ok = false; for (int j = 0; j < types.length(); j++) if (type.equals(types.optString(j))) ok = true; if (!ok) continue; }
            if (pre != null && pre.length() > 0) { boolean ok = false; for (int j = 0; j < pre.length(); j++) if (id.startsWith(pre.optString(j))) ok = true; if (!ok) continue; }
            return true;
        }
        return false;
    }
    static boolean needsExtra(JSONObject c) {
        JSONArray ex = c.optJSONArray("extra");
        if (ex != null) for (int i = 0; i < ex.length(); i++) { JSONObject e = ex.optJSONObject(i); if (e != null && e.optBoolean("isRequired")) return true; }
        JSONArray req = c.optJSONArray("extraRequired"); return req != null && req.length() > 0;
    }

    // ---------- HTTP ----------
    static final class Resp { int code; String body; }
    private Resp http(String method, String url, String body, boolean auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setRequestProperty("apikey", KEY); c.setRequestProperty("Content-Type", "application/json");
        if (auth && access != null) c.setRequestProperty("Authorization", "Bearer " + access);
        if (body != null) { c.setDoOutput(true); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
        else if ("POST".equals(method)) { c.setDoOutput(true); c.getOutputStream().close(); }
        return read(c);
    }
    static Resp get(String url) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); c.setConnectTimeout(10000); c.setReadTimeout(15000); return read(c); }
    private static Resp read(HttpURLConnection c) throws Exception {
        Resp r = new Resp(); r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = in == null ? "" : readAll(in); c.disconnect(); return r;
    }
    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n); in.close(); return out.toString("UTF-8");
    }

    // ---------- addon URLs (as Nuvio's CatalogRepositoryImpl builds them) ----------
    static String manifestOf(String url) { String[] pq = splitQuery(url); String p = pq[0].replaceAll("/+$", ""); return (p.endsWith("/manifest.json") ? p : p + "/manifest.json") + pq[1]; }
    static String baseOf(String url) { String[] pq = splitQuery(url); String p = pq[0].replaceAll("/+$", ""); if (p.endsWith("/manifest.json")) p = p.substring(0, p.length() - "/manifest.json".length()); return p + pq[1]; }
    static String addonUrl(String base, String path) { String[] pq = splitQuery(base); return pq[0].replaceAll("/+$", "") + path + pq[1]; }
    private static String[] splitQuery(String u) { int q = u.indexOf('?'); return q < 0 ? new String[]{u, ""} : new String[]{u.substring(0, q), u.substring(q)}; }
    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%3A", ":"); } catch (Exception e) { return s; } }
    static String norm(String t) { return "series".equals(t) || "tv".equals(t) || "show".equals(t) ? "series" : "movie"; }
    static String nonEmpty(String a, String b) { return a != null && !a.isEmpty() ? a : b; }
    private static String shortMsg(String body) {
        try { JSONObject j = new JSONObject(body); return nonEmpty(nonEmpty(j.optString("msg"), j.optString("message")), j.optString("error_description")); }
        catch (Exception e) { return body == null ? "" : body.length() > 120 ? body.substring(0, 120) : body; }
    }
}
