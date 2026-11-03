package tv.gallery.nuviohome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.widget.EditText;
import android.widget.Toast;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * A PIN on this TV for profiles that have a PIN in Nuvio. Nuvio's own PIN cannot be checked by other apps (its PIN
 * flow is not part of the public API), so the launcher keeps its own: set on first use, asked on every switch.
 * Only a salted hash is stored.
 */
final class Pin {
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("pins", Context.MODE_PRIVATE); }

    /** Asks for the profile's Nuvio PIN and checks it with Nuvio. If Nuvio can't be asked, falls back to the PIN on this TV. */
    static void ask(Activity a, int profile, String name, Runnable onOk, Runnable onCancel) {
        prompt(a, name + "'s PIN", "Enter " + name + "'s Nuvio PIN.", pin -> new Thread(() -> {
            Integer r = new Nuvio(a).verifyPin(profile, pin);
            a.runOnUiThread(() -> {
                if (r == null) { Toast.makeText(a, "Couldn't check the PIN with Nuvio. Using this TV's PIN instead.", Toast.LENGTH_LONG).show(); askLocal(a, profile, name, onOk, onCancel); }
                else if (r == 1) onOk.run();
                else if (r < 0) { Toast.makeText(a, "Too many tries. Nuvio says wait " + (-r) + " seconds.", Toast.LENGTH_LONG).show(); if (onCancel != null) onCancel.run(); }
                else { Toast.makeText(a, "Wrong PIN.", Toast.LENGTH_SHORT).show(); ask(a, profile, name, onOk, onCancel); }
            });
        }).start(), onCancel);
    }

    /** The fallback: a PIN kept on this TV (salted hash), set on first use. */
    static void askLocal(Activity a, int profile, String name, Runnable onOk, Runnable onCancel) {
        String stored = prefs(a).getString("p" + profile, null);
        if (stored == null) set(a, profile, name, onOk, onCancel, null);
        else prompt(a, name + "'s PIN", "Enter the PIN for " + name + " on this TV.", pin -> {
            if (hash(prefs(a).getString("s" + profile, ""), pin).equals(stored)) onOk.run();
            else { Toast.makeText(a, "Wrong PIN.", Toast.LENGTH_SHORT).show(); askLocal(a, profile, name, onOk, onCancel); }
        }, onCancel);
    }

    private static void set(Activity a, int profile, String name, Runnable onOk, Runnable onCancel, String first) {
        if (first == null) {
            prompt(a, "Set a PIN for " + name, name + " has a PIN in Nuvio. Nuvio doesn't let other apps check that PIN, so choose a PIN for " + name + " on this TV (4 digits). You can use the same one.", pin -> {
                if (pin.length() < 4) { Toast.makeText(a, "Use at least 4 digits.", Toast.LENGTH_SHORT).show(); set(a, profile, name, onOk, onCancel, null); return; }
                set(a, profile, name, onOk, onCancel, pin);
            }, onCancel);
        } else {
            prompt(a, "Confirm the PIN", "Enter the same PIN again.", pin -> {
                if (!pin.equals(first)) { Toast.makeText(a, "The PINs didn't match. Try again.", Toast.LENGTH_SHORT).show(); set(a, profile, name, onOk, onCancel, null); return; }
                byte[] s = new byte[16]; new SecureRandom().nextBytes(s); String salt = hex(s);
                prefs(a).edit().putString("s" + profile, salt).putString("p" + profile, hash(salt, pin)).apply();
                onOk.run();
            }, onCancel);
        }
    }

    interface Got { void pin(String p); }
    /** An on-screen PIN pad: digits never appear, only dots. Remote number keys work too. */
    private static void prompt(Activity a, String title, String msg, Got got, Runnable onCancel) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(a); box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL); box.setPadding(Rows.dp(a, 18), Rows.dp(a, 16), Rows.dp(a, 18), Rows.dp(a, 14));
        box.setBackground(Rows.round(0xF21C1D22, 18, a));
        box.addView(Rows.text(a, title, 17, 0xFFFFFFFF, true));
        android.widget.TextView m = Rows.text(a, msg, 12, 0xFFB3B3B3, false); m.setGravity(android.view.Gravity.CENTER);  m.setPadding(0, Rows.dp(a, 6), 0, Rows.dp(a, 10));
        box.addView(m);
        android.widget.TextView dots = Rows.text(a, "", 20, 0xFFFFFFFF, true); dots.setLetterSpacing(0.35f); dots.setPadding(0, 0, 0, Rows.dp(a, 8));
        box.addView(dots);
        StringBuilder pin = new StringBuilder();
        Runnable show = () -> { StringBuilder d = new StringBuilder(); for (int i = 0; i < 4; i++) d.append(i < pin.length() ? "●" : "○"); dots.setText(d); };
        show.run();
        android.app.Dialog[] dlg = new android.app.Dialog[1];
        boolean[] done = {false};
        Runnable submit = () -> { done[0] = true; dlg[0].dismiss(); got.pin(pin.toString()); };
        String[][] keys = {{"1","2","3"},{"4","5","6"},{"7","8","9"},{"0","⌫"}};
        android.view.View first = null;
        for (int r = 0; r < 4; r++) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(a);
            for (int c = 0; c < keys[r].length; c++) {
                String k = keys[r][c];
                android.widget.TextView b = Rows.text(a, k, 16, 0xFFFFFFFF, true); b.setGravity(android.view.Gravity.CENTER); b.setFocusable(!k.isEmpty()); b.setClickable(!k.isEmpty()); if (k.isEmpty()) b.setVisibility(android.view.View.INVISIBLE);
                b.setBackground(Rows.round(0xFF2E3036, 20, a));
                b.setOnFocusChangeListener((v, f) -> { v.setBackground(Rows.round(f ? 0xFFF4F4F6 : 0xFF2E3036, 20, a)); ((android.widget.TextView) v).setTextColor(f ? 0xFF121316 : 0xFFFFFFFF); v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(100).start(); });
                b.setOnClickListener(v -> {
                    if (k.equals("⌫")) { if (pin.length() > 0) pin.setLength(pin.length() - 1); show.run(); }
                    else if (pin.length() < 4) { pin.append(k); show.run(); if (pin.length() == 4) submit.run(); }
                });
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(Rows.dp(a, k.equals("⌫") ? 100 : 46), Rows.dp(a, 34)); lp.setMargins(Rows.dp(a, 4), Rows.dp(a, 4), Rows.dp(a, 4), Rows.dp(a, 4));
                row.addView(b, lp);
                if (first == null) first = b;
            }
            box.addView(row);
        }
        dlg[0] = new android.app.Dialog(a); dlg[0].requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); dlg[0].setContentView(box);
        dlg[0].setOnKeyListener((d, code, ev) -> {
            if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (code >= android.view.KeyEvent.KEYCODE_0 && code <= android.view.KeyEvent.KEYCODE_9) { if (pin.length() < 4) { pin.append((char) ('0' + code - android.view.KeyEvent.KEYCODE_0)); show.run(); if (pin.length() == 4) submit.run(); } return true; }
            if (code == android.view.KeyEvent.KEYCODE_DEL) { if (pin.length() > 0) pin.setLength(pin.length() - 1); show.run(); return true; }
            return false;
        });
        dlg[0].setOnDismissListener(d -> { if (!done[0] && onCancel != null) onCancel.run(); });
        dlg[0].setCancelable(onCancel == null || true);
        dlg[0].show();
        if (dlg[0].getWindow() != null) { dlg[0].getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0)); dlg[0].getWindow().setLayout(Rows.dp(a, 198), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);   // a fixed width: TV dialog themes otherwise stretch it }
        if (first != null) first.requestFocus();
    }

    static void clearAll(Context c) { prefs(c).edit().clear().apply(); }

    private static String hash(String salt, String pin) {
        try { MessageDigest md = MessageDigest.getInstance("SHA-256"); return hex(md.digest((salt + ":" + pin).getBytes("UTF-8"))); }
        catch (Exception e) { return ""; }
    }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
}
