package com.theo.futotweaks;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Plain settings screen built in code, so the module needs no AndroidX or layout files.
 * Written in Java 8 without lambdas, so it also builds with the no-SDK toolchain.
 *
 * Preferences are written world-readable so the keyboard process can read them with
 * XSharedPreferences. LSPosed and Vector allow this for modules that declare
 * xposedsharedprefs in the manifest; if the module is not active, Android refuses it.
 */
public class SettingsActivity extends Activity {

    private SharedPreferences mPrefs;
    private boolean mPrefsReadable = true;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = openPrefs();

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        list.addView(title("FUTO Tweaks"));

        if (!mPrefsReadable) {
            list.addView(note("The module isn't active in LSPosed or Vector yet, so the keyboard can't see these "
                    + "settings. Enable the module, scope it to FUTO Keyboard, then reopen this screen."));
        }

        list.addView(header("Cursor and navigation bar"));
        list.addView(toggle(Prefs.VERTICAL_CURSOR, "Drag spacebar up and down to change lines",
                "Once the drag goes mostly vertical, the spacebar moves the cursor by line, and sideways by character. Only multi-line fields respond to up and down."));
        list.addView(toggle(Prefs.CURSOR_HAPTICS, "Vibrate on each cursor step", null));
        list.addView(toggle(Prefs.HIDE_NAV_BAR, "Hide keyboard navigation bar",
                "Removes the down arrow and keyboard switcher below the keys. Android 15 or newer."));

        list.addView(header("Keypress effects"));
        list.addView(toggle(Prefs.EFFECT_RIPPLE, "Ripple", "A ring expands from where you touch"));
        list.addView(toggle(Prefs.EFFECT_GLOW, "Key glow", "The pressed key flashes and fades"));
        list.addView(toggle(Prefs.EFFECT_PARTICLES, "Particles", "A small burst of sparks on each press"));
        list.addView(toggle(Prefs.EFFECT_TRAIL, "Spacebar trail", "A glowing trail follows your finger while dragging the spacebar"));

        list.addView(note("Changes apply the next time the keyboard opens. If something doesn't update, "
                + "force stop FUTO Keyboard in Android settings."));

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // targetSdk 35 forces edge to edge: pad the content by the system bars and cutout ourselves
        final int pad = dp(16);
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(final View v, final WindowInsets insets) {
                final Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                list.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
                return insets;
            }
        });
        setContentView(scroll);
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences openPrefs() {
        try {
            return getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE);
        } catch (SecurityException notActive) {
            // Framework not hooking us: keep the screen usable, but warn that nothing reaches the keyboard
            mPrefsReadable = false;
            return getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
        }
    }

    private Switch toggle(final String key, final String title, final String subtitle) {
        final Switch sw = new Switch(this);
        sw.setText(subtitle == null ? title : title + "\n" + subtitle);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sw.setPadding(0, dp(12), 0, dp(12));
        sw.setChecked(mPrefs.getBoolean(key, Prefs.defaultFor(key)));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(final CompoundButton button, final boolean checked) {
                mPrefs.edit().putBoolean(key, checked).apply();
            }
        });
        return sw;
    }

    private TextView title(final String text) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        tv.setPadding(0, dp(8), 0, dp(8));
        return tv;
    }

    private TextView header(final String text) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(0, dp(20), 0, dp(4));
        return tv;
    }

    private TextView note(final String text) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(0, dp(12), 0, dp(12));
        return tv;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
