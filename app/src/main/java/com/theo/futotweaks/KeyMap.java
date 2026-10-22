package com.theo.futotweaks;

import android.graphics.RectF;
import android.view.View;

import com.theo.keyfx.FxKey;
import com.theo.keyfx.FxLayout;

import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * Builds keyfx's view of the current FUTO layout, in layer coordinates (the IME window root).
 *
 * Built from fields only (KeyboardView.mKeyboard, Keyboard.mSortedKeys and the Key
 * data class fields), never from FUTO methods. Release builds run R8, which can inline
 * small methods away, but fields that the keyboard itself reads always survive.
 *
 * Rebuilt only when the keyboard object or the view's position changes (layout switch,
 * shift, resize), so hit testing during a swipe is a plain loop over ~40 cached rectangles.
 */
final class KeyMap {
    private KeyMap() {}

    // FUTO key codes, inherited unchanged from AOSP LatinIME's Constants
    static final int CODE_SPACE = ' ';
    static final int CODE_ENTER = '\n';
    static final int CODE_DELETE = -5;
    static final int CODE_SHIFT = -1;

    private static Object sKeyboard;
    private static float sOffX = Float.NaN, sOffY = Float.NaN;
    private static int sWidth, sHeight;
    private static FxLayout sLayout = FxLayout.EMPTY;

    /**
     * The current layout, rebuilding the cache if anything changed. offX and offY are where the
     * keyboard view sits in the layer. Never null.
     */
    static FxLayout layout(final View view, final float offX, final float offY) {
        final Object keyboard;
        try {
            keyboard = XposedHelpers.getObjectField(view, "mKeyboard");
        } catch (Throwable t) {
            return FxLayout.EMPTY;
        }
        if (keyboard == null) return FxLayout.EMPTY;
        if (keyboard != sKeyboard || offX != sOffX || offY != sOffY
                || view.getWidth() != sWidth || view.getHeight() != sHeight) {
            sLayout = build(view, keyboard, offX, offY);
            sKeyboard = keyboard;
            sOffX = offX;
            sOffY = offY;
            sWidth = view.getWidth();
            sHeight = view.getHeight();
        }
        return sLayout;
    }

    private static FxLayout build(final View view, final Object keyboard, final float offX, final float offY) {
        final RectF kb = new RectF(offX, offY, offX + view.getWidth(), offY + view.getHeight());
        // FUTO's suggestion strip sits right above the keys, in the same window
        final float barH = 48 * view.getResources().getDisplayMetrics().density;
        final RectF bar = offY >= barH * 0.8f ? new RectF(offX, offY - barH, kb.right, offY) : new RectF();

        final List<?> list;
        try {
            list = (List<?>) XposedHelpers.getObjectField(keyboard, "mSortedKeys");
        } catch (Throwable t) {
            return new FxLayout(new FxKey[0], kb, bar);
        }
        if (list == null) return new FxLayout(new FxKey[0], kb, bar);

        final float padLeft = view.getPaddingLeft() + offX;
        final float padTop = view.getPaddingTop() + offY;
        final FxKey[] out = new FxKey[list.size()];
        int n = 0;
        for (final Object key : list) {
            try {
                if (boolField(key, "isSpacer")) continue;
                final FxKey k = new FxKey();
                k.code = XposedHelpers.getIntField(key, "code");
                k.label = (String) fieldOrNull(key, "label");
                final int x = XposedHelpers.getIntField(key, "x");
                final int y = XposedHelpers.getIntField(key, "y");
                final int w = XposedHelpers.getIntField(key, "width");
                final int h = XposedHelpers.getIntField(key, "height");
                final int hGap = intField(key, "horizontalGap");

                // Matches KeyboardView.getKeyDrawOffsetAndSize for the default gap config
                k.left = padLeft + x + hGap / 2;
                k.top = padTop + y;
                k.right = k.left + w;
                k.bottom = k.top + h;

                k.type = typeOf(k.code, k.label);
                final Object more = fieldOrNull(key, "moreKeys");
                k.longPress = (more instanceof List && !((List<?>) more).isEmpty()) || k.code == CODE_SPACE;
                out[n++] = k;
            } catch (Throwable ignored) {
                // A key we can't read is just skipped, the rest still work
            }
        }
        final FxKey[] keys = new FxKey[n];
        System.arraycopy(out, 0, keys, 0, n);
        return new FxLayout(keys, kb, bar);
    }

    private static int typeOf(final int code, final String label) {
        switch (code) {
            case CODE_SPACE: return FxKey.SPACE;
            case CODE_ENTER: return FxKey.ENTER;
            case CODE_DELETE: return FxKey.DELETE;
            case CODE_SHIFT: return FxKey.SHIFT;
            default:
                if (code >= '0' && code <= '9') return FxKey.NUM;
                return code > CODE_SPACE ? FxKey.CHAR : FxKey.OTHER;
        }
    }

    /** The key that types this code, preferring the one nearest (x, y). Capitals match their letter. */
    static FxKey findByCode(final FxLayout layout, final int code, final float x, final float y) {
        FxKey k = nearest(layout, code, x, y);
        if (k == null && Character.isValidCodePoint(code) && Character.isUpperCase(code)) {
            k = nearest(layout, Character.toLowerCase(code), x, y);
        }
        return k;
    }

    private static FxKey nearest(final FxLayout layout, final int code, final float x, final float y) {
        FxKey best = null;
        float bestDist = Float.MAX_VALUE;
        for (final FxKey k : layout.keys) {
            if (k.code != code) continue;
            final float dx = k.cx() - x, dy = k.cy() - y, d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = k;
            }
        }
        return best;
    }

    private static int intField(final Object obj, final String name) {
        try {
            return XposedHelpers.getIntField(obj, name);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean boolField(final Object obj, final String name) {
        try {
            return XposedHelpers.getBooleanField(obj, name);
        } catch (Throwable t) {
            return false;
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
