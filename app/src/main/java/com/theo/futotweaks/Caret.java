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

    private static final String TAG = "FutoTweaks: ";
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

    /** The character just before the cursor and where it is, in layer coordinates, or null. */
    static FxLetter letterBeforeCursor(final InputConnection ic, final View root) {
        if (ic == null || root == null) return null;
        final CharSequence before;
        try {
            before = ic.getTextBeforeCursor(2, 0);
        } catch (Throwable t) {
            return null;
        }
        if (before == null || before.length() == 0) return null;
        // Take the whole last code point, so emoji and other surrogate pairs stay intact
        final String s = before.toString();
        final int cp = s.codePointBefore(s.length());
        if (cp == '\n') return null;
        final String ch = cp == ' ' ? "·" : new String(Character.toChars(cp));

        final float density = root.getResources().getDisplayMetrics().density;
        final float edge = 8 * density;
        float x = root.getWidth() / 2f, y = edge, w = 9 * density, size = 17 * density;

        final CursorAnchorInfo info = sInfo;
        if (info != null) {
            try {
                final Matrix m = info.getMatrix();
                RectF r = null;
                final int index = info.getSelectionStart() - 1;
                if (index >= 0) r = info.getCharacterBounds(index);
                final float[] pts;
                if (r != null && r.width() > 0) {
                    pts = new float[] { r.left, r.top, r.right, r.bottom };
                } else if (!Float.isNaN(info.getInsertionMarkerHorizontal())) {
                    final float mx = info.getInsertionMarkerHorizontal();
                    pts = new float[] { mx - w, info.getInsertionMarkerTop(), mx, info.getInsertionMarkerBottom() };
                } else {
                    pts = null;
                }
                if (pts != null) {
                    if (m != null) m.mapPoints(pts);
                    root.getLocationOnScreen(sLoc);
                    final float left = pts[0] - sLoc[0], right = pts[2] - sLoc[0];
                    final float top = pts[1] - sLoc[1], bottom = pts[3] - sLoc[1];
                    x = (left + right) / 2;
                    y = (top + bottom) / 2;
                    w = Math.max(4 * density, right - left);
                    size = Math.max(12 * density, Math.min(28 * density, (bottom - top) / 1.2f));
                }
            } catch (Throwable ignored) {
                // Stale or partial info: fall back to the default spot
            }
        }
        x = Math.max(edge, Math.min(root.getWidth() - edge, x));
        y = Math.max(edge, y);
        return new FxLetter(ch, x, y, w, size);
    }
}
