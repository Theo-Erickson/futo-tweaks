package com.theo.keyfx;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * The keyboard as keyfx sees it, in layer coordinates: its keys, the keyboard area, and the
 * suggestion bar area above it. Immutable; hosts build a new one when the layout moves or changes.
 */
public final class FxLayout {
    public static final FxLayout EMPTY = new FxLayout(new FxKey[0], new RectF(), new RectF());

    public final FxKey[] keys;
    /** The keyboard view's bounds; show animations snapshot exactly this area. */
    public final RectF keyboard;
    /** Where suggestions are shown, possibly empty if the host does not know. */
    public final RectF suggestions;
    /** The number row's keys left to right, empty when the number row is off. */
    public final FxKey[] numberRow;

    public FxLayout(FxKey[] keys, RectF keyboard, RectF suggestions) {
        this.keys = keys;
        this.keyboard = keyboard;
        this.suggestions = suggestions;
        final ArrayList<FxKey> nums = new ArrayList<>();
        for (FxKey k : keys) if (k.type == FxKey.NUM) nums.add(k);
        final FxKey[] row = nums.toArray(new FxKey[0]);
        Arrays.sort(row, new Comparator<FxKey>() {
            @Override
            public int compare(FxKey a, FxKey b) {
                return Float.compare(a.left, b.left);
            }
        });
        numberRow = row;
    }

    /** The key under (x, y), or the nearest one within slop, or null. */
    public FxKey hit(float x, float y, float slop) {
        FxKey best = null;
        float bestD = slop * slop;
        for (FxKey k : keys) {
            final float dx = Math.max(Math.max(k.left - x, 0), x - k.right);
            final float dy = Math.max(Math.max(k.top - y, 0), y - k.bottom);
            final float d = dx * dx + dy * dy;
            if (d <= bestD) {
                bestD = d;
                best = k;
                if (d == 0) break;
            }
        }
        return best;
    }

    public FxKey find(int type) {
        for (FxKey k : keys) if (k.type == type) return k;
        return null;
    }

    /** Distinct row tops, top to bottom, merging rows within mergePx of each other. */
    public float[] rowTops(float mergePx) {
        final float[] tops = new float[keys.length];
        for (int i = 0; i < keys.length; i++) tops[i] = keys[i].top;
        Arrays.sort(tops);
        final float[] out = new float[tops.length];
        int n = 0;
        for (float t : tops) if (n == 0 || t - out[n - 1] >= mergePx) out[n++] = t;
        return Arrays.copyOf(out, n);
    }
}
