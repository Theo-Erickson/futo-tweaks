package com.theo.keyfx;

/**
 * A character the user just deleted, and where it was, in layer coordinates.
 * When the text lies outside the keyboard window, hosts place it at the window's top edge,
 * lined up with the cursor, so the letter seems to rise out of the text.
 */
public final class FxLetter {
    public final String ch;
    /** Center of the glyph. */
    public final float x, y;
    /** Glyph width and text size in px; hosts that can't measure pass an estimate. */
    public final float w, size;

    public FxLetter(String ch, float x, float y, float w, float size) {
        this.ch = ch;
        this.x = x;
        this.y = y;
        this.w = w;
        this.size = size;
    }
}
