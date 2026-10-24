package com.theo.keyfx;

import java.util.Locale;

/** Easing, color and math helpers shared by every effect. */
public final class Fx {
    private Fx() {}

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    public static float clamp(float v, float a, float b) {
        return v < a ? a : (v > b ? b : v);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // Inputs are clamped: an effect created mid-frame can see a slightly negative progress
    public static float easeOut(float t) {
        final float u = 1f - clamp01(t);
        return 1f - u * u * u;
    }

    public static float easeIn(float t) {
        final float c = clamp01(t);
        return c * c * c;
    }

    /** Overshoots past 1 before settling, for springy landings. */
    public static float easeOutBack(float t) {
        final float c1 = 1.70158f, c3 = c1 + 1f, u = clamp01(t) - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    /** The color with its alpha multiplied by a (0 to 1). */
    public static int alpha(int color, float a) {
        final int base = (color >>> 24) & 0xFF;
        final int out = Math.max(0, Math.min(255, (int) (base * clamp01(a))));
        return (color & 0x00FFFFFF) | (out << 24);
    }

    public static int mix(int a, int b, float t) {
        final float k = clamp01(t);
        final int r = (int) lerp((a >> 16) & 0xFF, (b >> 16) & 0xFF, k);
        final int g = (int) lerp((a >> 8) & 0xFF, (b >> 8) & 0xFF, k);
        final int bl = (int) lerp(a & 0xFF, b & 0xFF, k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // Fire palette: yellow core, orange body, red tips, dark smoke
    private static final int[] FIRE = { 0xFFFFE68C, 0xFFFF8C32, 0xFFE8384F, 0xFF501428 };

    public static int fire(float t, float a) {
        final float x = clamp01(t) * (FIRE.length - 1);
        final int i = Math.min(FIRE.length - 2, (int) x);
        return alpha(mix(FIRE[i], FIRE[i + 1], x - i), a);
    }

    /** Hue of a color, 0 to 360. */
    public static float hue(int c) {
        final float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        final float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        if (d == 0) return 0;
        float h = mx == r ? ((g - b) / d) % 6 : mx == g ? (b - r) / d + 2 : (r - g) / d + 4;
        return (h * 60 + 360) % 360;
    }

    /** HSV to an opaque color; h in degrees, s and v from 0 to 1. */
    public static int hsv(float h, float s, float v) {
        h = ((h % 360) + 360) % 360;
        final float c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return 0xFF000000 | ((int) ((r + m) * 255) << 16) | ((int) ((g + m) * 255) << 8) | (int) ((b + m) * 255);
    }

    /** HSL to an opaque color, like CSS hsl(); h in degrees, s and l from 0 to 1. */
    public static int hsl(float h, float s, float l) {
        final float v = l + s * Math.min(l, 1 - l);
        final float sv = v == 0 ? 0 : 2 * (1 - l / v);
        return hsv(h, sv, v);
    }

    /**
     * The bloom accent popup: n points fanned out on an arc above (cx, cy), written to out as
     * x0, y0, x1, y1 and so on (out needs 2n floats), kept between minX and maxX and never
     * above minY (screen y grows down). Matches the lab.
     */
    public static void popupArc(float cx, float cy, int n, float radius, float minX, float maxX, float minY, float[] out) {
        final double span = Math.min(Math.PI * 0.95, 0.42 * n);
        for (int i = 0; i < n; i++) {
            final double a = -Math.PI / 2 - span / 2 + (n == 1 ? span / 2 : span * i / (n - 1));
            out[i * 2] = clamp(cx + (float) Math.cos(a) * radius, minX, maxX);
            out[i * 2 + 1] = Math.max(minY, cy + (float) Math.sin(a) * radius);
        }
    }

    /**
     * How many leading letters of the guessed word are lit by the swipe, counting raw letters and
     * ignoring case. Non-letters (an apostrophe in "don't") are skipped: not counted, and they
     * neither stop the count nor change which letter is "previous". A letter that repeats the
     * previous letter is lit exactly when that one was; any other letter must be crossed, in
     * order, after the previous crossing. Matches the lab's litLetters(word, seq).
     */
    public static int litLetters(String guess, String crossed) {
        if (guess == null || crossed == null) return 0;
        final String g = guess.toLowerCase(Locale.ROOT), seq = crossed.toLowerCase(Locale.ROOT);
        int from = 0, n = 0, prev = -1;
        for (int i = 0; i < g.length(); ) {
            final int cp = g.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            if (cp == prev) {
                n++; // the previous letter was lit, or we would have stopped
                continue;
            }
            prev = cp;
            final int at = seq.indexOf(cp, from);
            if (at < 0) break;
            from = at + Character.charCount(cp);
            n++;
        }
        return n;
    }

    /** Parses "#RRGGBB" or "#AARRGGBB"; returns fallback when it can't. */
    public static int parseColor(String s, int fallback) {
        if (s == null || !s.startsWith("#")) return fallback;
        try {
            final long v = Long.parseLong(s.substring(1), 16);
            return s.length() == 7 ? (int) (0xFF000000L | v) : (int) v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
