package com.theo.keyfx;

/**
 * One key as keyfx sees it: what it is, and where it is drawn, in layer coordinates.
 * Hosts build these from their own key objects.
 */
public final class FxKey {
    public static final int CHAR = 0, NUM = 1, SPACE = 2, ENTER = 3, DELETE = 4, SHIFT = 5, OTHER = 6;

    public int type = OTHER;
    public int code;
    public String label;
    /** The key has a long-press action (accents, language switch, cursor mode). */
    public boolean longPress;
    public float left, top, right, bottom;

    public float cx() { return (left + right) * 0.5f; }
    public float cy() { return (top + bottom) * 0.5f; }
    public float w() { return right - left; }
    public float h() { return bottom - top; }

    /** The single character this key types, or null for keys like shift or "?123". */
    public String glyph() {
        if (label != null && label.codePointCount(0, label.length()) == 1) return label;
        if (code > 32 && Character.isValidCodePoint(code)) return new String(Character.toChars(code));
        return null;
    }

    public boolean isLetter() {
        final String g = glyph();
        return type == CHAR && g != null && Character.isLetter(g.codePointAt(0));
    }
}
