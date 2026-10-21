package com.theo.futotweaks;

/**
 * Preference keys and defaults, shared by the settings screen (module process)
 * and the hooks (keyboard process). Keep the two sides in sync through this class only.
 */
public final class Prefs {
    private Prefs() {}

    /** This module's package, used by XSharedPreferences to find the settings file. */
    public static final String MODULE_PACKAGE = "com.theo.futotweaks";

    /** SharedPreferences file name, read in the keyboard through XSharedPreferences. */
    public static final String FILE = "futo_tweaks";

    // Cursor and navigation bar
    public static final String VERTICAL_CURSOR = "vertical_cursor";
    public static final String CURSOR_HAPTICS = "cursor_haptics";
    public static final String HIDE_NAV_BAR = "hide_nav_bar";

    // Typing effects
    public static final String EFFECT_POP = "effect_pop";
    public static final String EFFECT_LETTER_FLOAT = "effect_letter_float";
    public static final String EFFECT_HEAT = "effect_heat";
    public static final String EFFECT_SPECIAL_KEYS = "effect_special_keys";

    // Swipe typing effects
    public static final String EFFECT_KEY_TRACE = "effect_key_trace";
    public static final String EFFECT_LETTER_HOP = "effect_letter_hop";

    // Long press and spacebar effects
    public static final String EFFECT_CHARGE_RING = "effect_charge_ring";
    public static final String EFFECT_TRACKPAD = "effect_trackpad";

    // Keyboard show animation
    public static final String EFFECT_CASCADE = "effect_cascade";

    // Classic effects from 1.0
    public static final String EFFECT_RIPPLE = "effect_ripple";
    public static final String EFFECT_GLOW = "effect_glow";
    public static final String EFFECT_PARTICLES = "effect_particles";
    public static final String EFFECT_TRAIL = "effect_trail";

    /**
     * Defaults: the main features and the effect set picked for 1.1 start on,
     * the 1.0 classic effects start off.
     */
    public static boolean defaultFor(final String key) {
        switch (key) {
            case VERTICAL_CURSOR:
            case CURSOR_HAPTICS:
            case HIDE_NAV_BAR:
            case EFFECT_POP:
            case EFFECT_LETTER_FLOAT:
            case EFFECT_HEAT:
            case EFFECT_SPECIAL_KEYS:
            case EFFECT_KEY_TRACE:
            case EFFECT_LETTER_HOP:
            case EFFECT_CHARGE_RING:
            case EFFECT_TRACKPAD:
            case EFFECT_CASCADE:
                return true;
            default:
                return false;
        }
    }
}
