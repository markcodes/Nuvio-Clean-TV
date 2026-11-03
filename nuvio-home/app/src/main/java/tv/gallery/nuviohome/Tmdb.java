package tv.gallery.nuviohome;

import android.content.Context;

import org.json.JSONArray;
import java.util.List;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Optional TMDB extras for details pages: cast photos, trailers, reviews and production companies. Uses the
 * owner's own TMDB key (Settings → TMDB key), kept on this TV and sent only to api.themoviedb.org.
 */
final class Tmdb {
    static final String IMG = "https://image.tmdb.org/t/p/";

    /** The key typed in Settings, or else the one found in the owner's addons (see found()). */
    static String key(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String own = p.getString("tmdb", "");
        return own.isEmpty() ? p.getString("tmdb_found", "") : own;
    }
    static String typed(Context c) { return c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("tmdb", ""); }
    static String foundIn(Context c) { return c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("tmdb_found_in", ""); }

    /**
     * Looks for a TMDB key in the owner's addon links (Bingecat first), which carry their configuration: plain,
     * URL-encoded, base64 or JSON. A candidate is used only if TMDB accepts it; the result is remembered.
     * Runs on a background thread. Returns the key, or "" if none works.
     */
    static String found(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String have = p.getString("tmdb_found", "");
        if (!have.isEmpty()) return have;
        Nuvio.Home h = new Nuvio(c).cached();
        if (h == null || h.addons == null) return "";
        List<JSONObject> order = new java.util.ArrayList<>();
        for (int i = 0; i < h.addons.length(); i++) { JSONObject a = h.addons.optJSONObject(i); if (a != null && (a.optString("name") + a.optString("id") + a.optString("base")).toLowerCase().contains("bingecat")) order.add(a); }
        for (int i = 0; i < h.addons.length(); i++) { JSONObject a = h.addons.optJSONObject(i); if (a != null && !order.contains(a)) order.add(a); }
        java.util.Set<String> tried = new java.util.HashSet<>();
        for (JSONObject a : order) {
            boolean binge = (a.optString("name") + a.optString("id") + a.optString("base")).toLowerCase().contains("bingecat");
            for (String k : candidates(a.optString("base"), binge)) {
                if (!tried.add(k)) continue;
                if (check(k) == null) { p.edit().putString("tmdb_found", k).putString("tmdb_found_in", a.optString("name", "an addon")).apply(); return k; }
            }
        }
        return "";
    }
    private static final java.util.regex.Pattern NAMED = java.util.regex.Pattern.compile("(?i)tmdb[a-z_]*\\W{0,4}([a-f0-9]{32}|eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+)");
    private static final java.util.regex.Pattern BARE = java.util.regex.Pattern.compile("(?<![a-f0-9])([a-f0-9]{32})(?![a-f0-9])|(eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+)");
    /**
     * Possible keys in an addon link: values labelled tmdb… first; then, only for Bingecat (where the owner put the
     * key), any 32-hex string or JWT. Other addons' unlabelled values may be other services' secrets, so they are
     * never sent to TMDB.
     */
    static List<String> candidates(String url, boolean unlabelled) {
        List<String> texts = new java.util.ArrayList<>(), named = new java.util.ArrayList<>(), bare = new java.util.ArrayList<>();
        if (url == null || url.isEmpty()) return named;
        String whole = url;
        try { whole = java.net.URLDecoder.decode(url, "UTF-8"); } catch (Exception ignored) { }
        texts.add(url); texts.add(whole);
        for (String part : whole.split("[/?&=]")) {
            if (part.length() < 16) continue;
            for (int flags : new int[]{android.util.Base64.URL_SAFE, android.util.Base64.DEFAULT}) {
                try { String d = new String(android.util.Base64.decode(part, flags), "UTF-8"); if (d.contains("{") || d.toLowerCase().contains("tmdb")) texts.add(d); } catch (Exception ignored) { }
            }
        }
        for (String t : texts) {
            java.util.regex.Matcher m = NAMED.matcher(t); while (m.find()) if (!named.contains(m.group(1))) named.add(m.group(1));
            java.util.regex.Matcher b = BARE.matcher(t); while (b.find()) { String k = b.group(1) != null ? b.group(1) : b.group(2); if (!bare.contains(k)) bare.add(k); }
        }
        if (unlabelled) for (String k : bare) if (!named.contains(k)) named.add(k);
        return named;
    }
    static void save(Context c, String k) { c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("tmdb", k == null ? "" : k.trim()).apply(); }
    static String masked(String k) { return k.length() <= 10 ? "••••" : k.substring(0, 4) + "••••" + k.substring(k.length() - 4); }

    /** A v3 API key goes in the query; a v4 Read Access Token (starts "eyJ") goes in the Authorization header. */
    static Nuvio.Resp get(String key, String path) throws Exception {
        boolean bearer = key.startsWith("eyJ");
        String url = "https://api.themoviedb.org/3" + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + Nuvio.enc(key));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        return Nuvio.read(c);
    }

    /** null when the key works, otherwise the reason. */
    static String check(String key) {
        try {
            Nuvio.Resp r = get(key.trim(), "/configuration");
            if (r.code == 200) return null;
            if (r.code == 401) return "TMDB says this key isn't valid.";
            return "TMDB answered " + r.code + ".";
        } catch (Exception e) { return "Couldn't reach TMDB: " + e.getMessage(); }
    }

    /** A person with their combined movie and TV credits. null without a key. */
    static JSONObject person(Context c, int id) {
        String key = key(c); if (key.isEmpty()) key = found(c); if (key.isEmpty()) return null;
        try { Nuvio.Resp r = get(key, "/person/" + id + "?append_to_response=combined_credits"); return r.code == 200 ? new JSONObject(r.body) : null; }
        catch (Exception e) { return null; }
    }
    /** The IMDb id (tt…) for a TMDB movie or tv id, which Nuvio and the addons use. null if TMDB has none. */
    static String imdbId(Context c, String kind, int id) {
        String key = key(c); if (key.isEmpty()) key = found(c); if (key.isEmpty()) return null;
        try {
            Nuvio.Resp r = get(key, "/" + kind + "/" + id + "/external_ids");
            if (r.code != 200) return null;
            String imdb = new JSONObject(r.body).optString("imdb_id");
            return imdb.startsWith("tt") ? imdb : null;
        } catch (Exception e) { return null; }
    }

    /** The title's TMDB record with credits, videos and reviews, found by IMDb id (tt…) or tmdb:id. null without a key or a match. */
    static JSONObject details(Context c, String type, String id) {
        String key = key(c); if (key.isEmpty()) key = found(c);
        if (key.isEmpty() || id == null) return null;
        try {
            boolean tv = "series".equals(type);
            String kind = tv ? "tv" : "movie", tid = null;
            if (id.startsWith("tmdb:")) tid = id.substring(5).split(":")[0];
            else if (id.startsWith("tt")) {
                JSONObject f = new JSONObject(get(key, "/find/" + id.split(":")[0] + "?external_source=imdb_id").body);
                JSONArray a = f.optJSONArray(tv ? "tv_results" : "movie_results");
                if (a == null || a.length() == 0) { a = f.optJSONArray(tv ? "movie_results" : "tv_results"); kind = tv ? "movie" : "tv"; }
                if (a != null && a.length() > 0) tid = a.getJSONObject(0).optString("id");
            }
            if (tid == null || tid.isEmpty()) return null;
            Nuvio.Resp r = get(key, "/" + kind + "/" + tid + "?append_to_response=credits,videos,reviews");
            return r.code == 200 ? new JSONObject(r.body) : null;
        } catch (Exception e) { return null; }
    }
}
