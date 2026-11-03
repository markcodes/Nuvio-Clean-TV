package tv.gallery.nuviohome;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.inputmethod.EditorInfo;
import android.widget.ScrollView;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import static tv.gallery.nuviohome.Rows.dp;
import static tv.gallery.nuviohome.Rows.round;
import static tv.gallery.nuviohome.Rows.text;

/** Nuvio sign-in through the public API. The password goes only to api.nuvio.tv and is not stored. */
public class SignInActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Nuvio nuvio = new Nuvio(this);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(dp(this, 120), dp(this, 40), dp(this, 120), dp(this, 40));
        page.addView(text(this, "Sign in to Nuvio", 30, Color.WHITE, true));
        TextView note = text(this, "Use the email and password of your Nuvio account. Your password goes only to Nuvio and isn't stored on this TV; Nuvio Clean TV keeps Nuvio's sign-in token until you sign out.", 15, 0xFFB3B3B3, false);
        note.setLineSpacing(0, 1.3f); note.setMaxWidth(dp(this, 560)); note.setPadding(0, dp(this, 12), 0, dp(this, 18));
        page.addView(note);
        EditText email = new EditText(this); email.setHint("Email"); email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass = new EditText(this); pass.setHint("Password"); pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        email.setImeOptions(EditorInfo.IME_ACTION_NEXT); pass.setImeOptions(EditorInfo.IME_ACTION_DONE);
        for (EditText e : new EditText[]{email, pass}) { e.setTextColor(Color.WHITE); e.setHintTextColor(0xFF808080); e.setSingleLine(true); page.addView(e, new LinearLayout.LayoutParams(dp(this, 460), -2)); }
        pass.setTransformationMethod(PasswordTransformationMethod.getInstance());   // after setSingleLine, which would replace it
        TextView err = text(this, "", 14, 0xFFF2A3A3, false); err.setPadding(0, dp(this, 12), 0, 0);
        Button go = new Button(this); go.setText("Sign in"); go.setAllCaps(false); go.setTextColor(0xFF121316); go.setBackground(round(0xFFF4F4F6, 24, this));
        go.setPadding(dp(this, 28), dp(this, 10), dp(this, 28), dp(this, 10));
        go.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(120).start());
        go.setOnClickListener(v -> {
            String em = email.getText().toString().trim(), pw = pass.getText().toString();
            if (em.isEmpty() || pw.isEmpty()) { err.setText("Enter your email and password."); return; }
            err.setText("Signing in…"); go.setEnabled(false);
            new Thread(() -> {
                String problem = nuvio.signIn(em, pw);
                runOnUiThread(() -> { go.setEnabled(true); if (problem == null) { setResult(RESULT_OK); finish(); } else err.setText(problem); });
            }).start();
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2); gp.topMargin = dp(this, 20);
        page.addView(go, gp); page.addView(err);
        // The keyboard's ✓ signs in, so the button can't be hidden behind the keyboard.
        pass.setOnEditorActionListener((v, id, ev) -> { if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_GO) { go.performClick(); return true; } return false; });
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(0xFF0B0C10); sv.setFillViewport(true); sv.addView(page);
        setContentView(sv);
        email.requestFocus();
    }
}
