package com.theo.futotweaks;

import android.view.View;

import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * A snapshot of the current FUTO layout's keys, in keyboard view coordinates.
 *
 * Built from fields only (KeyboardView.mKeyboard, Keyboard.mSortedKeys and the Key
 * data class fields), never from FUTO methods. Release builds run R8, which can inline
 * small methods away, but fields that the keyboard itself reads always survive.
 *
 * Rebuilt only when the keyboard object changes (layout switch, shift, resize), so
 * hit testing during a swipe is a plain loop over ~40 cached rectangles.
 */
final class KeyMap {
    private KeyMap() {}

    /** One key: visual bounds (where it is drawn) and hit bounds (its full cell, gaps included). */
    static final class KeyInfo {
        int code;
        String label;
        boolean hasLongPress;
        float left, top, right, bottom;
        float hitLeft, hitTop, hitRight, hitBottom;

        float centerX() { return (left + right) * 0.5f; }
        float centerY() { return (top + bottom) * 0.5f; }
        float width() { return right - left; }
        float height() { return bottom - top; }

        /** The single character this key types, or null for keys like shift or "?123". */
        String glyph() {
            if (label != null && label.codePointCount(0, label.length()) == 1) return label;
            if (code > 32 && Character.isValidCodePoint(code)) return new String(Character.toChars(code));
            return null;
        }

        boolean isLetter() {
            final String g = glyph();
            return g != null && Character.isLetter(g.codePointAt(0));
        }
    }

    private static final KeyInfo[] EMPTY = new KeyInfo[0];
    private static Object sKeyboard;
    private static KeyInfo[] sKeys = EMPTY;

    /** Current keys, rebuilding the cache if the layout changed. Never null. */
    static KeyInfo[] keys(final View view) {
        final Object keyboard;
        try {
            keyboard = XposedHelpers.getObjectField(view, "mKeyboard");
        } catch (Throwable t) {
            return EMPTY;
        }
        if (keyboard == null) return EMPTY;
        if (keyboard != sKeyboard) {
            sKeys = build(view, keyboard);
            sKeyboard = keyboard;
        }
        return sKeys;
    }

    private static KeyInfo[] build(final View view, final Object keyboard) {
        final List<?> list;
        try {
            list = (List<?>) XposedHelpers.getObjectField(keyboard, "mSortedKeys");
        } catch (Throwable t) {
            return EMPTY;
        }
        if (list == null) return EMPTY;

        final int padLeft = view.getPaddingLeft();
        final int padTop = view.getPaddingTop();
        final KeyInfo[] out = new KeyInfo[list.size()];
        int n = 0;
        for (final Object key : list) {
            try {
                if (boolField(key, "isSpacer", false)) continue;
                final KeyInfo k = new KeyInfo();
                k.code = XposedHelpers.getIntField(key, "code");
                k.label = (String) XposedHelpers.getObjectField(key, "label");
                final int x = XposedHelpers.getIntField(key, "x");
                final int y = XposedHelpers.getIntField(key, "y");
                final int w = XposedHelpers.getIntField(key, "width");
                final int h = XposedHelpers.getIntField(key, "height");
                final int hGap = intField(key, "horizontalGap", 0);
                final int vGap = intField(key, "verticalGap", 0);

                // Matches KeyboardView.getKeyDrawOffsetAndSize for the default gap config
                k.left = padLeft + x + hGap / 2;
                k.top = padTop + y;
                k.right = k.left + w;
                k.bottom = k.top + h;

                k.hitLeft = padLeft + x;
                k.hitTop = padTop + y;
                k.hitRight = k.hitLeft + w + hGap;
                k.hitBottom = k.hitTop + h + vGap;

                final Object more = fieldOrNull(key, "moreKeys");
                k.hasLongPress = (more instanceof List && !((List<?>) more).isEmpty()) || k.code == ' ';
                out[n++] = k;
            } catch (Throwable ignored) {
                // A key we can't read is just skipped, the rest still work
            }
        }
        final KeyInfo[] trimmed = new KeyInfo[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** The key whose cell contains (x, y) in keyboard view coordinates, or null. */
    static KeyInfo hit(final View view, final float x, final float y) {
        for (final KeyInfo k : keys(view)) {
            if (x >= k.hitLeft && x < k.hitRight && y >= k.hitTop && y < k.hitBottom) return k;
        }
        return null;
    }

    /** The key that types this code, preferring the one nearest (nearX, nearY). Null if none. */
    static KeyInfo findByCode(final View view, final int code, final float nearX, final float nearY) {
        KeyInfo best = null;
        float bestDist = Float.MAX_VALUE;
        for (final KeyInfo k : keys(view)) {
            if (k.code != code) continue;
            final float dx = k.centerX() - nearX, dy = k.centerY() - nearY;
            final float d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = k;
            }
        }
        return best;
    }

    private static int intField(final Object obj, final String name, final int fallback) {
        try {
            return XposedHelpers.getIntField(obj, name);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean boolField(final Object obj, final String name, final boolean fallback) {
        try {
            return XposedHelpers.getBooleanField(obj, name);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static Object fieldOrNull(final Object obj, final String name) {
        try {
            return XposedHelpers.getObjectField(obj, name);
        } catch (Throwable t) {
            return null;
        }
    }
}
