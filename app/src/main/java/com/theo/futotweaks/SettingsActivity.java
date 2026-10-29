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
import java.util.Iterator;
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
    // Each toggleable card's on/off switch, refreshed when a preview restores them
    private final Map<String, Switch> mCardSwitches = new HashMap<>();
    // True while switches are set from code, so their listeners don't save again
    private boolean mSyncingSwitches;
    private AlertDialog mPreviewDialog;
    // Every section's on/off value from before a preview, as JSON, until it is restored
    private static final String PREVIEW_BACKUP = "preview.backup";
    private static final String FXBOARD_URL = "https://github.com/Theo-Erickson/fxboard/releases";

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        mPrefs = openPrefs();
        mSections = new ArrayList<>();
        mSections.add(Prefs.KEYBOARD_SECTION);
        mSections.addAll(KeyFx.schema());
        // With the module's caps, so FXboard-only choice values read as the default, as in the keyboard
        mSettings = new FxSettings(mPrefs, mSections, Prefs.CAPS);
        migrateHoldWords();
        // A preview left running when the app was killed: put the effects back first
        restorePreview();

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

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving the screen ends a preview: close it and put every effect back
        if (mPreviewDialog != null && mPreviewDialog.isShowing()) {
            mPreviewDialog.dismiss();
            restorePreview();
        }
    }

    // region Saving

    /**
     * Whole-word deletion moved from the keyboard section to keyfx's Backspace hold card in 1.3.0.
     * Each value saved under an old key is copied over once, unless its new key is already stored,
     * and then the old keys are removed.
     */
    private void migrateHoldWords() {
        final String oldOn = Prefs.key(Prefs.OLD_HOLD_WORDS);
        final String oldAfter = Prefs.key(Prefs.OLD_WORDS_AFTER);
        final String oldPace = Prefs.key(Prefs.OLD_WORD_PACE);
        if (!mPrefs.contains(oldOn) && !mPrefs.contains(oldAfter) && !mPrefs.contains(oldPace)) return;
        final SharedPreferences.Editor ed = mPrefs.edit();
        final String newOn = FxSettings.key(Prefs.DEL_HOLD, Prefs.DEL_WORDS);
        if (mPrefs.contains(oldOn) && !mPrefs.contains(newOn)) {
            try {
                ed.putBoolean(newOn, mPrefs.getBoolean(oldOn, true));
            } catch (ClassCastException wrongType) {
                // An unexpected stored type: keep keyfx's default
            }
        }
        copyFloat(ed, oldAfter, FxSettings.key(Prefs.DEL_HOLD, Prefs.DEL_WORDS_AFTER));
        copyFloat(ed, oldPace, FxSettings.key(Prefs.DEL_HOLD, Prefs.DEL_WORD_PACE));
        ed.remove(oldOn).remove(oldAfter).remove(oldPace);
        save(ed, null);
    }

    /** Copies a stored float to a new key that isn't stored yet. */
    private void copyFloat(final SharedPreferences.Editor ed, final String from, final String to) {
        if (!mPrefs.contains(from) || mPrefs.contains(to)) return;
        try {
            ed.putFloat(to, mPrefs.getFloat(from, 0f));
        } catch (ClassCastException wrongType) {
            // An unexpected stored type: keep keyfx's default
        }
    }

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
            // Preview: this effect alone, with a text field to try it in
            head.addView(button("Preview", new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    startPreview(s);
                }
            }));
            final Switch sw = new Switch(this);
            sw.setChecked(mSettings.on(s.id));
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(final CompoundButton b, final boolean checked) {
                    if (mSyncingSwitches) return;
                    save(mPrefs.edit().putBoolean(FxSettings.key(s.id, "on"), checked), s.id);
                }
            });
            head.addView(sw);
            mCardSwitches.put(s.id, sw);
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
                    // Values this module can't show are listed in the Needs FXboard card instead
                    if (f.valueMissing(i, Prefs.CAPS) != 0) continue;
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
                // A field that only shows under an unsupported parent is listed through the parent
                final Field parent = f.showKey() != null ? mSettings.field(s.id, f.showKey()) : null;
                if (parent != null && parent.missing(Prefs.CAPS) != 0) continue;
                if (fm != 0) {
                    any |= addTo(byCap, fm, s.title + ": " + f.label);
                    continue;
                }
                // A supported choice can still have values that need FXboard
                if (f.type != Field.CHOICE || f.values == null) continue;
                for (int i = 0; i < f.values.length; i++) {
                    final int vm = f.valueMissing(i, Prefs.CAPS);
                    if (vm != 0) any |= addTo(byCap, vm, s.title + ": " + f.names[i]);
                }
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

    // region Preview

    /**
     * Turns every toggleable effect off except s, after backing up their on/off values, and opens
     * a dialog with a focused text field so the keyboard comes up showing only that effect.
     */
    private void startPreview(final Section s) {
        restorePreview(); // never stack a second backup on a first
        final JSONObject backup = new JSONObject();
        final SharedPreferences.Editor ed = mPrefs.edit();
        try {
            for (final Section other : mSections) {
                if (!other.toggleable) continue;
                final String key = FxSettings.key(other.id, "on");
                // Unset values are backed up as null, so the restore leaves them unset again
                backup.put(key, mPrefs.contains(key) ? (Object) mPrefs.getBoolean(key, other.defaultOn) : JSONObject.NULL);
                ed.putBoolean(key, other.id.equals(s.id));
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't start the preview: " + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        ed.putString(PREVIEW_BACKUP, backup.toString());
        save(ed, null);
        syncCardSwitches();

        final EditText input = new EditText(this);
        input.setHint("Type or swipe here");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setMaxLines(6);
        final LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Previewing: " + s.title)
                .setMessage("Only this effect is on. Type or swipe below.")
                .setView(box)
                .setPositiveButton("Done", null)
                .create();
        // Done, back and a tap outside all end the preview the same way
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(final DialogInterface d) {
                if (mPreviewDialog != dialog) return;
                mPreviewDialog = null;
                restorePreview();
            }
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        mPreviewDialog = dialog;
        dialog.show();
        input.requestFocus();
    }

    /** Puts back the on/off values saved by a preview, if one is stored, and deletes the backup. */
    private void restorePreview() {
        final String json = mPrefs.getString(PREVIEW_BACKUP, null);
        if (json == null) return;
        final SharedPreferences.Editor ed = mPrefs.edit();
        try {
            final JSONObject backup = new JSONObject(json);
            final Iterator<String> keys = backup.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                if (backup.isNull(key)) ed.remove(key);
                else ed.putBoolean(key, backup.optBoolean(key));
            }
        } catch (Throwable t) {
            // A damaged backup can't be restored; drop it so it isn't tried again
        }
        ed.remove(PREVIEW_BACKUP);
        save(ed, null);
        syncCardSwitches();
    }

    /** Shows each card's stored on/off value without saving it again. */
    private void syncCardSwitches() {
        mSyncingSwitches = true;
        try {
            for (final Map.Entry<String, Switch> e : mCardSwitches.entrySet()) {
                e.getValue().setChecked(mSettings.on(e.getKey()));
            }
        } finally {
            mSyncingSwitches = false;
        }
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
                        // Values that need FXboard are skipped, so the count only covers what applies
                        for (String allowed : f.values) known |= allowed.equals(v) && f.valueSupportedBy(v, Prefs.CAPS);
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
