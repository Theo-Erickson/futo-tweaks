package com.theo.keyfx;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads effect settings from any SharedPreferences (the host's own, or XSharedPreferences in
 * a hooked process), falling back to the schema defaults.
 *
 * Values are snapshotted on reload() so effects can read them every frame without disk or IPC.
 * Keys are "sectionId.fieldKey"; ranges are stored as floats, choices as strings.
 */
public final class FxSettings {
    private final SharedPreferences prefs;
    private final int caps;
    private final Map<String, Field> fields = new HashMap<>();
    private final Map<String, Boolean> sectionDefaults = new HashMap<>();
    private Map<String, ?> values = Collections.emptyMap();

    public FxSettings(SharedPreferences prefs, List<Section> sections) {
        this(prefs, sections, ~0);
    }

    /** caps: the host's KeyFx.CAP_* flags; choice values it can't show read as the default. */
    public FxSettings(SharedPreferences prefs, List<Section> sections, int caps) {
        this.prefs = prefs;
        this.caps = caps;
        for (Section s : sections) {
            sectionDefaults.put(s.id, s.defaultOn);
            for (Field f : s.fields) fields.put(key(s.id, f.key), f);
        }
        reload();
    }

    public static String key(String id, String field) {
        return id + "." + field;
    }

    /** Re-reads every value. Call when the host learns settings changed. */
    public void reload() {
        try {
            values = prefs.getAll();
            if (values == null) values = Collections.emptyMap();
        } catch (Throwable t) {
            values = Collections.emptyMap();
        }
    }

    public SharedPreferences prefs() {
        return prefs;
    }

    /** Whether a toggleable section is switched on. */
    public boolean on(String id) {
        final Object v = values.get(key(id, "on"));
        if (v instanceof Boolean) return (Boolean) v;
        final Boolean def = sectionDefaults.get(id);
        return def != null && def;
    }

    public boolean b(String id, String k) {
        final Object v = values.get(key(id, k));
        if (v instanceof Boolean) return (Boolean) v;
        // The old standalone combo section's switch carries over until heat.combo is stored
        if (id.equals("heat") && k.equals("combo")) {
            final Object old = values.get(key("combo", "on"));
            if (old instanceof Boolean) return (Boolean) old;
        }
        final Field f = fields.get(key(id, k));
        return f != null && f.defBool;
    }

    public float f(String id, String k) {
        final Object v = values.get(key(id, k));
        if (v instanceof Float) return (Float) v;
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Long) return (Long) v;
        final Field f = fields.get(key(id, k));
        return f != null ? f.defFloat : 0f;
    }

    public String s(String id, String k) {
        if ("hop".equals(id) && ("word".equals(k) || "extra".equals(k)) && !values.containsKey(key(id, k))
                && values.get(key(id, "exit")) instanceof String) {
            // A saved v3 hop exit becomes the word and extra exits
            return Fx.hopFromExit((String) values.get(key(id, "exit")), "word".equals(k));
        }
        final Object v = values.get(key(id, k));
        final Field f = fields.get(key(id, k));
        if (v instanceof String) {
            final String m = Fx.migrate(key(id, k), (String) v);
            // A choice the host can't show (or that no longer exists) reads as the default
            if (f == null || f.type != Field.CHOICE || f.valueSupportedBy(m, caps)) return m;
        }
        return f != null && f.defString != null ? f.defString : "";
    }

    /** The value as a string whatever its type, for visibility rules. */
    String raw(String id, String k) {
        final Field f = fields.get(key(id, k));
        if (f == null) return "";
        if (f.type == Field.TOGGLE) return String.valueOf(b(id, k));
        if (f.type == Field.CHOICE) return s(id, k);
        return String.valueOf(f(id, k));
    }

    public Field field(String id, String k) {
        return fields.get(key(id, k));
    }
}
