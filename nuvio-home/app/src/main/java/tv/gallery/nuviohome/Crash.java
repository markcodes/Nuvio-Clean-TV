package tv.gallery.nuviohome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Keeps the last crash in a file and shows it at the next start, so a problem can be reported without adb. */
final class Crash {
    static void install(Context c) {
        File f = new File(c.getFilesDir(), "last_crash.txt");
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (FileOutputStream o = new FileOutputStream(f)) {
                StringWriter sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw));
                o.write(("Nuvio Clean TV " + BuildConfigLite.VERSION + " crashed on thread " + t.getName() + "\n" + sw).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
    static void showLast(Activity a) {
        File f = new File(a.getFilesDir(), "last_crash.txt");
        if (!f.exists()) return;
        try {
            String s = Nuvio.readAll(new java.io.FileInputStream(f));
            String[] lines = s.split("\n"); StringBuilder top = new StringBuilder();
            for (int i = 0; i < Math.min(10, lines.length); i++) top.append(lines[i].trim()).append('\n');
            new AlertDialog.Builder(a).setTitle("Nuvio Clean TV stopped last time").setMessage("Send a photo of this to Claude:\n\n" + top).setPositiveButton("OK", null).show();
        } catch (Exception ignored) { }
        f.delete();
    }
    private static final class BuildConfigLite { static final String VERSION = "0.20"; }
}
