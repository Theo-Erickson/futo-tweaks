package com.theo.keyfx;

/**
 * One setting in the schema: a toggle, a slider, or a set of choices.
 *
 * The schema is the single source of truth for every effect's settings. Hosts build their
 * settings screens from it, and the web lab mirrors it, so an export from the lab maps onto
 * the same section ids and keys.
 */
public final class Field {
    public static final int TOGGLE = 0, RANGE = 1, CHOICE = 2;

    public final int type;
    public final String key, label;

    // Range
    public final float min, max, step, defFloat;
    public final String unit;

    // Toggle
    public final boolean defBool;

    // Choice: parallel arrays of stored values and display names
    public final String[] values, names;
    public final String defString;

    // Visibility: shown only while another key in the same section has one of these values
    String showKey;
    String[] showValues;
    boolean showNegate;
    // Host capabilities this field needs (KeyFx.CAP_*); hidden in hosts that lack them
    int requires;
    // Host capabilities each choice value needs (KeyFx.CAP_*), parallel to values; null when none do
    int[] valueRequires;

    private Field(int type, String key, String label, float min, float max, float step, String unit,
                  float defFloat, boolean defBool, String[] values, String[] names, String defString) {
        this.type = type;
        this.key = key;
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.unit = unit;
        this.defFloat = defFloat;
        this.defBool = defBool;
        this.values = values;
        this.names = names;
        this.defString = defString;
    }

    public static Field range(String key, String label, float min, float max, float step, String unit, float def) {
        return new Field(RANGE, key, label, min, max, step, unit, def, false, null, null, null);
    }

    public static Field toggle(String key, String label, boolean def) {
        return new Field(TOGGLE, key, label, 0, 1, 1, "", 0, def, null, null, null);
    }

    /** options are "value:Display name" pairs. */
    public static Field choice(String key, String label, String def, String... options) {
        final String[] values = new String[options.length], names = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            final int at = options[i].indexOf(':');
            values[i] = options[i].substring(0, at);
            names[i] = options[i].substring(at + 1);
        }
        return new Field(CHOICE, key, label, 0, 0, 0, "", 0, false, values, names, def);
    }

    /** The standard per-effect intensity slider. */
    public static Field strength() {
        return range("strength", "Intensity", 0f, 1.5f, 0.05f, "×", 1f);
    }

    /** Show this field only while key has one of these values. */
    public Field when(String key, String... values) {
        showKey = key;
        showValues = values;
        showNegate = false;
        return this;
    }

    /** Show this field only while key has none of these values. */
    public Field whenNot(String key, String... values) {
        showKey = key;
        showValues = values;
        showNegate = true;
        return this;
    }

    /** Hide this field in hosts that lack these capabilities. */
    public Field requires(int caps) {
        requires = caps;
        return this;
    }

    public boolean supportedBy(int hostCaps) {
        return (requires & hostCaps) == requires;
    }

    /** The capabilities this field needs that the host lacks, or 0. */
    public int missing(int hostCaps) {
        return requires & ~hostCaps;
    }

    /** Offer this choice value only to hosts with these capabilities. */
    public Field valueRequires(String value, int caps) {
        if (values == null) return this;
        if (valueRequires == null) valueRequires = new int[values.length];
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) valueRequires[i] = caps;
        return this;
    }

    /** The capabilities choice value i needs that the host lacks, or 0. */
    public int valueMissing(int i, int hostCaps) {
        return valueRequires == null ? 0 : valueRequires[i] & ~hostCaps;
    }

    /** Whether value is one of this choice's values and the host can show it. */
    public boolean valueSupportedBy(String value, int hostCaps) {
        if (values == null) return true;
        // A color field also takes custom "#RRGGBB" values (lab imports store them)
        if (value.startsWith("#")) {
            for (String v : values) if (v.startsWith("#")) return true;
        }
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return valueMissing(i, hostCaps) == 0;
        return false;
    }

    /** The key of the field that decides whether this one shows, or null. */
    public String showKey() {
        return showKey;
    }

    /** Whether the field should show, given the section's current values. */
    public boolean visible(FxSettings settings, String sectionId) {
        if (showKey == null) return true;
        final String current = settings.raw(sectionId, showKey);
        boolean match = false;
        for (String v : showValues) if (v.equals(current)) match = true;
        return showNegate != match;
    }
}
