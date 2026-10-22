package com.theo.keyfx;

/**
 * A group of settings for one effect, shown as one card. The id is also the preference
 * prefix, so values are stored as "id.key" (and "id.on" for the on/off switch).
 */
public final class Section {
    public final String id, group, title, description;
    /** Sort position across all sections; groups are ordered by their first section. */
    public final int order;
    public final boolean toggleable, defaultOn;
    public final Field[] fields;
    // Host capabilities the whole section needs (KeyFx.CAP_*)
    int requires;

    public Section(String id, String group, int order, String title, String description,
                   boolean toggleable, boolean defaultOn, Field... fields) {
        this.id = id;
        this.group = group;
        this.order = order;
        this.title = title;
        this.description = description;
        this.toggleable = toggleable;
        this.defaultOn = defaultOn;
        this.fields = fields;
    }

    public Section requires(int caps) {
        requires = caps;
        return this;
    }

    public boolean supportedBy(int hostCaps) {
        return (requires & hostCaps) == requires;
    }
}
