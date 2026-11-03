package tv.gallery.nuviohome;

import android.app.Activity;
import android.app.AlertDialog;
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
            prompt(a, "Set a PIN for " + name, name + " has a PIN in Nuvio. Nuvio doesn't let other apps check that PIN, so choose a PIN for " + name + " on this TV (4 to 8 digits). You can use the same one.", pin -> {
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
    private static void prompt(Activity a, String title, String msg, Got got, Runnable onCancel) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setTransformationMethod(PasswordTransformationMethod.getInstance());
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
        new AlertDialog.Builder(a).setTitle(title).setMessage(msg).setView(e)
                .setPositiveButton("OK", (d, w) -> got.pin(e.getText().toString().trim()))
                .setNegativeButton("Cancel", (d, w) -> { if (onCancel != null) onCancel.run(); })
                .setOnCancelListener(d -> { if (onCancel != null) onCancel.run(); })
                .setCancelable(onCancel == null).show();
        e.requestFocus();
    }

    static void clearAll(Context c) { prefs(c).edit().clear().apply(); }

    private static String hash(String salt, String pin) {
        try { MessageDigest md = MessageDigest.getInstance("SHA-256"); return hex(md.digest((salt + ":" + pin).getBytes("UTF-8"))); }
        catch (Exception e) { return ""; }
    }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
}
