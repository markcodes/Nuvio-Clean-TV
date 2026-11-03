package tv.gallery.nuviohome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Optional TMDB extras for details pages: cast photos, trailers, reviews and production companies. Uses the
 * owner's own TMDB key (Settings → TMDB key), kept on this TV and sent only to api.themoviedb.org.
 */
final class Tmdb {
    static final String IMG = "https://image.tmdb.org/t/p/";

    static String key(Context c) { return c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("tmdb", ""); }
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
        String key = key(c); if (key.isEmpty()) return null;
        try { Nuvio.Resp r = get(key, "/person/" + id + "?append_to_response=combined_credits"); return r.code == 200 ? new JSONObject(r.body) : null; }
        catch (Exception e) { return null; }
    }
    /** The IMDb id (tt…) for a TMDB movie or tv id, which Nuvio and the addons use. null if TMDB has none. */
    static String imdbId(Context c, String kind, int id) {
        String key = key(c); if (key.isEmpty()) return null;
        try {
            Nuvio.Resp r = get(key, "/" + kind + "/" + id + "/external_ids");
            if (r.code != 200) return null;
            String imdb = new JSONObject(r.body).optString("imdb_id");
            return imdb.startsWith("tt") ? imdb : null;
        } catch (Exception e) { return null; }
    }

    /** The title's TMDB record with credits, videos and reviews, found by IMDb id (tt…) or tmdb:id. null without a key or a match. */
    static JSONObject details(Context c, String type, String id) {
        String key = key(c);
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
