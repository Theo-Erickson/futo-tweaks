package com.theo.keyfx;

/** How the suggestion bar should show the predicted word: its first lit letters glow and grow. */
public final class WordLook {
    public final String word;
    /**
     * How many letters, counted from the start and skipping non-letters, are lit. At most the
     * number of letters in word.
     */
    public final int lit;
    /** A scale multiplier for the lit letters; 1 means normal size. */
    public final float grow;
    /** The glow color, as ARGB. */
    public final int color;

    WordLook(String word, int lit, float grow, int color) {
        this.word = word;
        this.lit = lit;
        this.grow = grow;
        this.color = color;
    }

    boolean sameAs(WordLook o) {
        return o != null && (word == null ? o.word == null : word.equals(o.word)) && o.lit == lit && o.grow == grow && o.color == color;
    }
}
