package com.theo.futotweaks;

import com.theo.keyfx.Field;
import com.theo.keyfx.Section;

/**
 * Settings shared by the settings screen (module process) and the hooks (keyboard process).
 *
 * Effect settings come from the keyfx schema. This class adds the module's own keyboard
 * features as one more section, so the settings screen builds everything the same way.
 */
public final class Prefs {
    private Prefs() {}

    /** This module's package, used by XSharedPreferences to find the settings file. */
    public static final String MODULE_PACKAGE = "com.theo.futotweaks";

    /** SharedPreferences file name, read in the keyboard through XSharedPreferences. */
    public static final String FILE = "futo_tweaks";

    /** Sent by the settings screen after every change; the hooked keyboard reloads on it. */
    public static final String ACTION_RELOAD = "com.theo.futotweaks.RELOAD";

    /**
     * What this host can do beyond drawing over the keyboard (keyfx's CAP_* flags). The module
     * only draws on top, so popup layout, hide animations and the like are left to the fork.
     */
    public static final int CAPS = 0;

    // The module's own keyboard features, stored like any other section: "keyboard.<key>"
    public static final String KEYBOARD = "keyboard";
    public static final String VERTICAL_CURSOR = "verticalCursor";
    public static final String CURSOR_HAPTICS = "cursorHaptics";
    public static final String HIDE_NAV_BAR = "hideNavBar";
    public static final String HOLD_WORDS = "holdWords";
    public static final String WORDS_AFTER = "wordsAfter";
    public static final String WORD_PACE = "wordPace";

    public static final Section KEYBOARD_SECTION = new Section(KEYBOARD, "Keyboard", -10, "Cursor, backspace and navigation bar",
            "Changes to how FUTO Keyboard itself behaves.", false, true,
            Field.toggle(VERTICAL_CURSOR, "Drag spacebar up and down to change lines", true),
            Field.toggle(CURSOR_HAPTICS, "Vibrate on each cursor step", true).when(VERTICAL_CURSOR, "true"),
            Field.toggle(HIDE_NAV_BAR, "Hide keyboard navigation bar (Android 15+)", true),
            Field.toggle(HOLD_WORDS, "Holding backspace deletes whole words", true),
            Field.range(WORDS_AFTER, "Switch to words after", 1, 30, 1, "letters", 8).when(HOLD_WORDS, "true"),
            Field.range(WORD_PACE, "Time per word", 80, 600, 10, "ms", 220).when(HOLD_WORDS, "true"));

    public static String key(String field) {
        return KEYBOARD + "." + field;
    }
}
