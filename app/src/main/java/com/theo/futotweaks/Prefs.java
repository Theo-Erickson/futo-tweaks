package com.theo.futotweaks;

/**
 * Preference keys and defaults, shared by the settings screen (module process)
 * and the hooks (keyboard process). Keep the two sides in sync through this class only.
 */
public final class Prefs {
    private Prefs() {}

    /** SharedPreferences file name, read in the keyboard through XSharedPreferences. */
    public static final String FILE = "futo_tweaks";

    public static final String VERTICAL_CURSOR = "vertical_cursor";
    public static final String CURSOR_HAPTICS = "cursor_haptics";
    public static final String HIDE_NAV_BAR = "hide_nav_bar";
    public static final String EFFECT_RIPPLE = "effect_ripple";
    public static final String EFFECT_GLOW = "effect_glow";
    public static final String EFFECT_PARTICLES = "effect_particles";
    public static final String EFFECT_TRAIL = "effect_trail";

    /** Default for a key: the two main features start on, every effect starts off. */
    public static boolean defaultFor(final String key) {
        switch (key) {
            case VERTICAL_CURSOR:
            case CURSOR_HAPTICS:
            case HIDE_NAV_BAR:
                return true;
            default:
                return false;
        }
    }
}
