package tv.gallery.nuviohome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

/** Hands a title to the official Nuvio: its sources list (the request Nuvio's own Continue Watching uses), or its details page. */
final class Launch {
    private static final String[] PKGS = {"com.nuvio.tv", "com.nuvio.app"};
    static String pkg(Activity a) {
        PackageManager pm = a.getPackageManager();
        for (String p : PKGS) { try { pm.getPackageInfo(p, 0); return p; } catch (PackageManager.NameNotFoundException ignored) { } }
        return null;
    }
    /** Movies, and episodes we know (Continue Watching), go straight to sources; a series without an episode opens our details. */
    static void play(Activity a, Nuvio.Item it) {
        boolean series = "series".equals(Nuvio.norm(it.type));
        if (series && it.season < 0) { a.startActivity(new Intent(a, DetailActivity.class).putExtra("item", json(it))); return; }
        String p = pkg(a); if (p == null) { Toast.makeText(a, "Nuvio isn't installed.", Toast.LENGTH_LONG).show(); return; }
        String vid = series ? Nuvio.nonEmpty(it.videoId, it.id + ":" + it.season + ":" + it.episode) : it.id;
        Intent i = new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(p, "com.nuvio.tv.MainActivity"))
                .putExtra("contentId", it.id).putExtra("contentType", Nuvio.norm(it.type)).putExtra("videoId", vid)
                .putExtra("name", it.name).putExtra("poster", it.poster).putExtra("backdrop", it.background).putExtra("logo", it.logo)
                .putExtra("launchMode", "stream").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (series) { i.putExtra("season", it.season); i.putExtra("episode", it.episode); }
        try { a.startActivity(i); } catch (RuntimeException e) { detail(a, it); }
    }
    static void detail(Activity a, Nuvio.Item it) {
        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("nuvio://detail/" + Nuvio.norm(it.type) + "/" + Uri.encode(it.id, ":"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException e) { Toast.makeText(a, "Nuvio isn't installed.", Toast.LENGTH_LONG).show(); }
    }
    static String json(Nuvio.Item it) { try { return it.toJson().toString(); } catch (Exception e) { return "{}"; } }
}
