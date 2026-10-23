package com.theo.futotweaks;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.InputConnection;

import com.theo.keyfx.FxLetter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Where the text cursor is on screen, from Android's CursorAnchorInfo, so a deleted letter's
 * effect can start where the letter was.
 *
 * The app's text is outside the keyboard's window, and the keyboard can't draw there, so a
 * letter above the window is placed on the window's top edge, lined up with the cursor.
 */
final class Caret {
    private Caret() {}

    private static final String TAG = "FX for FUTO Keyboard: ";
    private static volatile CursorAnchorInfo sInfo;
    private static boolean sHooked;
    private static final int[] sLoc = new int[2];

    /** Watches cursor updates on FUTO's IME class, and on the base class for overrides that call super. */
    static void hook(final Class<?> imeClass) {
        // The service can be created again in the same process; hook only once
        if (sHooked) return;
        sHooked = true;
        final XC_MethodHook keep = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof CursorAnchorInfo) {
                    sInfo = (CursorAnchorInfo) param.args[0];
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(InputMethodService.class, "onUpdateCursorAnchorInfo", keep);
            if (imeClass != InputMethodService.class) {
                XposedBridge.hookAllMethods(imeClass, "onUpdateCursorAnchorInfo", keep);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "cursor position hook failed: " + t);
        }
    }

    /** Asks the app to keep reporting the cursor position. Apps that don't support it just don't. */
    static void request(final InputConnection ic) {
        sInfo = null;
        if (ic == null) return;
        try {
            ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR | InputConnection.CURSOR_UPDATE_IMMEDIATE);
        } catch (Throwable ignored) {
            // Optional: letters then start from the top middle of the keyboard
        }
    }

    /** How much text before the cursor to look at; also the most letters one press can animate. */
    static final int WINDOW = 64;

    /** The text before the cursor, or null when the app won't say. */
    static String textBefore(final InputConnection ic) {
        if (ic == null) return null;
        try {
            final CharSequence cs = ic.getTextBeforeCursor(WINDOW, 0);
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * How many chars (UTF-16 units) were removed from the end of before to leave after. Exact when
     * the text is shorter than the window; otherwise found by matching the shifted window.
     */
    static int deletedLength(final String before, final String after) {
        if (before == null || after == null) return 0;
        if (before.length() < WINDOW) {
            return after.length() <= before.length() && before.startsWith(after) ? before.length() - after.length() : 0;
        }
        for (int d = 0; d <= before.length(); d++) {
            if (after.endsWith(before.substring(0, before.length() - d))) return d;
        }
        return 0;
    }

    /**
     * Where each of the last chars of before sits, in layer coordinates and text order, one
     * entry per code point; null for line breaks. Call it before the text is deleted, while the
     * app's cursor info still describes it.
     *
     * Apps usually report exact bounds only for the word being composed; other letters are
     * placed leftward from the cursor by an estimated width.
     */
    static FxLetter[] lettersBefore(final String before, final int chars, final View root) {
        if (before == null || root == null || chars <= 0) return new FxLetter[0];
        final String tail = before.substring(Math.max(0, before.length() - chars));
        final int count = tail.codePointCount(0, tail.length());
        final FxLetter[] out = new FxLetter[count];

        final float density = root.getResources().getDisplayMetrics().density;
        final float edge = 8 * density;
        float size = 17 * density, cursorX = root.getWidth() / 2f, y = edge;
        final CursorAnchorInfo info = sInfo;
        Matrix m = null;
        int selStart = -1;
        if (info != null) {
            try {
                m = info.getMatrix();
                selStart = info.getSelectionStart();
                final float mx = info.getInsertionMarkerHorizontal();
                if (!Float.isNaN(mx)) {
                    final float[] pts = { mx, info.getInsertionMarkerTop(), mx, info.getInsertionMarkerBottom() };
                    final float[] screen = toLayer(pts, m, root);
                    cursorX = screen[0];
                    y = (screen[1] + screen[3]) / 2;
                    size = Math.max(12 * density, Math.min(28 * density, (screen[3] - screen[1]) / 1.2f));
                }
            } catch (Throwable ignored) {
                // Stale or partial info: fall back to the default spot
            }
        }

        // Walk right to left from the cursor
        float right = cursorX;
        int offset = tail.length();
        for (int i = count - 1; i >= 0; i--) {
            final int cp = tail.codePointBefore(offset);
            offset -= Character.charCount(cp);
            final int textIndex = selStart - (tail.length() - offset);
            float x = right - size * 0.28f, w = size * 0.55f, cy = y;
            if (info != null && textIndex >= 0) {
                try {
                    final RectF r = info.getCharacterBounds(textIndex);
                    if (r != null && r.width() > 0) {
                        final float[] b = toLayer(new float[] { r.left, r.top, r.right, r.bottom }, m, root);
                        x = (b[0] + b[2]) / 2;
                        w = Math.max(4 * density, b[2] - b[0]);
                        cy = (b[1] + b[3]) / 2;
                    }
                } catch (Throwable ignored) {
                    // No bounds for this one: keep the estimate
                }
            }
            right = x - w / 2;
            if (cp == '\n') continue;
            final String ch = cp == ' ' ? "\u00B7" : new String(Character.toChars(cp));
            out[i] = new FxLetter(ch, Math.max(edge, Math.min(root.getWidth() - edge, x)), Math.max(edge, cy), w, size);
        }
        return out;
    }

    /** Maps points from the app's local text coordinates to our layer, through the screen. */
    private static float[] toLayer(final float[] pts, final Matrix m, final View root) {
        if (m != null) m.mapPoints(pts);
        root.getLocationOnScreen(sLoc);
        for (int i = 0; i < pts.length; i += 2) {
            pts[i] -= sLoc[0];
            pts[i + 1] -= sLoc[1];
        }
        return pts;
    }
}
