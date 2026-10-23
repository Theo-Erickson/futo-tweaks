package com.theo.futotweaks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.theo.keyfx.Field;
import com.theo.keyfx.FxSettings;
import com.theo.keyfx.KeyFx;
import com.theo.keyfx.Section;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings screen, generated from the keyfx schema plus the module's own keyboard section,
 * so every effect setting shows up here without screen code of its own.
 *
 * Built in code without AndroidX or layouts, in Java 8 without lambdas, so it also builds with
 * the no-SDK toolchain. Every change is written at once and announced with a broadcast, which
 * the hooked keyboard picks up, so effects change while you type in the test field.
 *
 * Preferences are written world-readable so the keyboard process can read them with
 * XSharedPreferences. LSPosed and Vector allow this for modules that declare
 * xposedsharedprefs in the manifest; if the module is not active, Android refuses it.
 */
public class SettingsActivity extends Activity {

    private SharedPreferences mPrefs;
    private boolean mPrefsReadable = true;
    private List<Section> mSections;
    private FxSettings mSettings;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    // Each section's field views, to show and hide them as their conditions change
    private final Map<String, List<Object[]>> mFieldViews = new HashMap<>();
    private static final String FXBOARD_URL = "https://github.com/Theo-Erickson/fxboard/releases";

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        mPrefs = openPrefs();
        mSections = new ArrayList<>();
        mSections.add(Prefs.KEYBOARD_SECTION);
        mSections.addAll(KeyFx.schema());
        mSettings = new FxSettings(mPrefs, mSections);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Fixed header: title, actions and the test field stay put while settings scroll
        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(8));
        final LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = text("FX for FUTO Keyboard", 22, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        titleRow.addView(button("Import", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                showImport();
            }
        }));
        titleRow.addView(button("Reset", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                confirmReset();
            }
        }));
        header.addView(titleRow);

        final EditText test = new EditText(this);
        test.setHint("Type or swipe here to try your settings");
        test.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        test.setMinLines(2);
        test.setMaxLines(4);
        header.addView(test, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(24));

        if (!mPrefsReadable) {
            list.addView(note("The module isn't active in LSPosed or Vector yet, so the keyboard can't see these "
                    + "settings. Enable the module, scope it to FUTO Keyboard, then reopen this screen."));
        }

        String group = null;
        for (final Section s : mSections) {
            if (!s.supportedBy(Prefs.CAPS)) continue; // listed in the Needs FXboard card instead
            if (!s.group.equals(group)) {
                group = s.group;
                list.addView(groupHeader(group));
            }
            list.addView(card(s));
            refreshVisibility(s.id);
        }

        list.addView(note("Changes apply right away, also while the keyboard is open. Effects turn off when "
                + "Android's \"Remove animations\" setting is on."));
        final View needs = needsFxboardCard();
        if (needs != null) list.addView(needs);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // targetSdk 35 forces edge to edge: pad by the system bars, cutout and keyboard ourselves
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(final View v, final WindowInsets insets) {
                final Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                final Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return insets;
            }
        });
        setContentView(root);
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

    // region Saving

    /** Writes now and tells the keyboard. commit(), not apply(): the file must exist before the broadcast. */
    private void save(final SharedPreferences.Editor editor, final String sectionId) {
        editor.commit();
        mSettings.reload();
        if (sectionId != null) refreshVisibility(sectionId);
        sendBroadcast(new Intent(Prefs.ACTION_RELOAD));
    }

    private final Runnable mPendingRange = new Runnable() {
        @Override
        public void run() {
            if (mPendingEditor != null) save(mPendingEditor, null);
            mPendingEditor = null;
        }
    };
    private SharedPreferences.Editor mPendingEditor;

    /** Slider changes are saved after a short pause, so dragging doesn't flood the keyboard with reloads. */
    private void saveSoon(final String key, final float value) {
        if (mPendingEditor == null) mPendingEditor = mPrefs.edit();
        mPendingEditor.putFloat(key, value);
        mHandler.removeCallbacks(mPendingRange);
        mHandler.postDelayed(mPendingRange, 150);
    }

    // endregion

    // region Generated cards

    /** An empty rounded card, as every section uses. */
    private LinearLayout cardShell() {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1A8890A0);
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        card.setLayoutParams(lp);
        return card;
    }

    private View card(final Section s) {
        final LinearLayout card = cardShell();

        final LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(s.title, 16, true));
        if (s.description != null) titles.addView(text(s.description, 13, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (s.toggleable) {
            final Switch sw = new Switch(this);
            sw.setChecked(mSettings.on(s.id));
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(final CompoundButton b, final boolean checked) {
                    save(mPrefs.edit().putBoolean(FxSettings.key(s.id, "on"), checked), s.id);
                }
            });
            head.addView(sw);
        }
        card.addView(head);

        final ArrayList<Object[]> views = new ArrayList<>();
        for (final Field f : s.fields) {
            if (!f.supportedBy(Prefs.CAPS)) continue;
            final View v = fieldView(s, f);
            card.addView(v);
            views.add(new Object[] { f, v });
        }
        mFieldViews.put(s.id, views);
        return card;
    }

    private View fieldView(final Section s, final Field f) {
        final String key = FxSettings.key(s.id, f.key);
        switch (f.type) {
            case Field.TOGGLE: {
                final Switch sw = new Switch(this);
                sw.setText(f.label);
                sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                sw.setPadding(0, dp(8), 0, dp(8));
                sw.setChecked(mSettings.b(s.id, f.key));
                sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(final CompoundButton b, final boolean checked) {
                        save(mPrefs.edit().putBoolean(key, checked), s.id);
                    }
                });
                return sw;
            }
            case Field.RANGE: {
                final LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(0, dp(6), 0, 0);
                final TextView label = text("", 14, false);
                box.addView(label);
                final SeekBar bar = new SeekBar(this);
                final int steps = Math.max(1, Math.round((f.max - f.min) / f.step));
                bar.setMax(steps);
                final float current = mSettings.f(s.id, f.key);
                bar.setProgress(Math.round((current - f.min) / f.step));
                label.setText(f.label + ": " + format(f, current));
                bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(final SeekBar sb, final int progress, final boolean fromUser) {
                        final float value = Math.min(f.max, f.min + progress * f.step);
                        label.setText(f.label + ": " + format(f, value));
                        if (fromUser) saveSoon(key, value);
                    }

                    @Override
                    public void onStartTrackingTouch(final SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(final SeekBar sb) {
                        mHandler.removeCallbacks(mPendingRange);
                        mPendingRange.run();
                    }
                });
                box.addView(bar);
                return box;
            }
            default: {
                final LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(0, dp(6), 0, 0);
                box.addView(text(f.label, 14, false));
                final RadioGroup group = new RadioGroup(this);
                group.setOrientation(LinearLayout.HORIZONTAL);
                final String current = mSettings.s(s.id, f.key);
                for (int i = 0; i < f.values.length; i++) {
                    final RadioButton rb = new RadioButton(this);
                    rb.setId(View.generateViewId());
                    rb.setText(f.names[i]);
                    rb.setPadding(0, 0, dp(12), 0);
                    group.addView(rb);
                    if (f.values[i].equals(current)) rb.setChecked(true);
                    final String value = f.values[i];
                    rb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(final CompoundButton b, final boolean checked) {
                            if (checked) save(mPrefs.edit().putString(key, value), s.id);
                        }
                    });
                }
                final HorizontalScrollView hs = new HorizontalScrollView(this);
                hs.setHorizontalScrollBarEnabled(false);
                hs.addView(group);
                box.addView(hs);
                return box;
            }
        }
    }

    /**
     * Everything the schema offers that this module can't do, grouped by the capability it
     * lacks, with why and a link to FXboard, which can. Null when there is nothing to list.
     */
    private View needsFxboardCard() {
        final LinkedHashMap<Integer, List<String>> byCap = new LinkedHashMap<>();
        for (final int cap : KeyFx.CAPS) byCap.put(cap, new ArrayList<String>());
        boolean any = false;
        for (final Section s : mSections) {
            final int missing = s.missing(Prefs.CAPS);
            if (missing != 0) {
                any |= addTo(byCap, missing, s.title);
                continue;
            }
            for (final Field f : s.fields) {
                final int fm = f.missing(Prefs.CAPS);
                if (fm == 0) continue;
                // A field that only shows under an unsupported parent is listed through the parent
                final Field parent = f.showKey() != null ? mSettings.field(s.id, f.showKey()) : null;
                if (parent != null && parent.missing(Prefs.CAPS) != 0) continue;
                any |= addTo(byCap, fm, s.title + ": " + f.label);
            }
        }
        if (!any) return null;

        final LinearLayout card = cardShell();
        card.addView(text("Needs FXboard", 16, true));
        card.addView(text("FXboard is FUTO Keyboard with keyfx built in. As a module, FX for FUTO Keyboard "
                + "can't offer these:", 13, false));
        for (final Map.Entry<Integer, List<String>> e : byCap.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            final TextView head = text(KeyFx.capTitle(e.getKey()), 14, true);
            head.setPadding(0, dp(12), 0, dp(2));
            card.addView(head);
            card.addView(text(KeyFx.capReason(e.getKey()), 13, false));
            for (final String item : e.getValue()) {
                final TextView line = text("\u00B7 " + item, 14, false);
                line.setPadding(dp(8), dp(2), 0, 0);
                card.addView(line);
            }
        }
        card.addView(button("Get FXboard", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(FXBOARD_URL)));
                } catch (ActivityNotFoundException noBrowser) {
                    Toast.makeText(SettingsActivity.this, FXBOARD_URL, Toast.LENGTH_LONG).show();
                }
            }
        }));
        return card;
    }

    /** Files item under the first capability it lacks, in KeyFx.CAPS order. */
    private static boolean addTo(final Map<Integer, List<String>> byCap, final int missing, final String item) {
        for (final Map.Entry<Integer, List<String>> e : byCap.entrySet()) {
            if ((missing & e.getKey()) != 0) {
                e.getValue().add(item);
                return true;
            }
        }
        return false;
    }

    private void refreshVisibility(final String sectionId) {
        final List<Object[]> views = mFieldViews.get(sectionId);
        if (views == null) return;
        for (final Object[] fv : views) {
            ((View) fv[1]).setVisibility(((Field) fv[0]).visible(mSettings, sectionId) ? View.VISIBLE : View.GONE);
        }
    }

    /** Like the lab: whole numbers for whole steps, otherwise one or two decimals. */
    private static String format(final Field f, final float v) {
        final String n = f.step >= 1 ? String.valueOf(Math.round(v))
                : f.step >= 0.1f ? String.format(Locale.US, "%.1f", v) : String.format(Locale.US, "%.2f", v);
        return f.unit.isEmpty() ? n : n + " " + f.unit;
    }

    // endregion

    // region Import and reset

    private void showImport() {
        final EditText input = new EditText(this);
        input.setHint("Paste the lab's export here");
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Import from FX for FUTO Keyboard Lab")
                .setMessage("In the lab, tap Export, copy everything, and paste it here. Settings the module "
                        + "doesn't have are skipped.")
                .setView(box)
                .setPositiveButton("Import", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface d, final int which) {
                        importLab(input.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Reads the lab export's "Full settings:" JSON. Section ids and keys match the lab's. */
    private void importLab(final String text) {
        int count = 0;
        try {
            final int marker = text.indexOf("Full settings:");
            final String tail = marker >= 0 ? text.substring(marker) : text;
            final int start = tail.indexOf('{'), end = tail.lastIndexOf('}');
            if (start < 0 || end <= start) throw new IllegalArgumentException("no settings found");
            final JSONObject all = new JSONObject(tail.substring(start, end + 1));
            final SharedPreferences.Editor ed = mPrefs.edit();
            for (final Section s : KeyFx.schema()) {
                final JSONObject o = all.optJSONObject(s.id);
                if (o == null) continue;
                if (s.toggleable && o.has("on")) {
                    ed.putBoolean(FxSettings.key(s.id, "on"), o.optBoolean("on"));
                    count++;
                }
                for (final Field f : s.fields) {
                    if (!o.has(f.key)) continue;
                    final String key = FxSettings.key(s.id, f.key);
                    if (f.type == Field.TOGGLE) {
                        ed.putBoolean(key, o.optBoolean(f.key));
                        count++;
                    } else if (f.type == Field.RANGE) {
                        final double v = o.optDouble(f.key, Double.NaN);
                        if (Double.isNaN(v)) continue;
                        ed.putFloat(key, Math.max(f.min, Math.min(f.max, (float) v)));
                        count++;
                    } else {
                        final String v = o.optString(f.key, null);
                        if (v == null) continue;
                        // Colors outside the presets still work, they just don't show as selected
                        boolean known = v.startsWith("#");
                        for (String allowed : f.values) known |= allowed.equals(v);
                        if (!known) continue;
                        ed.putString(key, v);
                        count++;
                    }
                }
            }
            save(ed, null);
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't read that: " + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Imported " + count + " settings", Toast.LENGTH_SHORT).show();
        recreate();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset everything?")
                .setMessage("All effects and keyboard options go back to their defaults.")
                .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface d, final int which) {
                        save(mPrefs.edit().clear(), null);
                        recreate();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // endregion

    // region Small views

    private TextView text(final String s, final int sp, final boolean bold) {
        final TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView groupHeader(final String s) {
        final TextView tv = text(s, 14, true);
        tv.setPadding(0, dp(18), 0, dp(2));
        return tv;
    }

    private TextView note(final String s) {
        final TextView tv = text(s, 13, false);
        tv.setPadding(0, dp(12), 0, dp(12));
        return tv;
    }

    private Button button(final String label, final View.OnClickListener click) {
        final Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setOnClickListener(click);
        return b;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // endregion
}
