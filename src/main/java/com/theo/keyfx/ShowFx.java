package com.theo.keyfx;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.IdentityHashMap;

/**
 * Keyboard show and hide animations. Both work from a snapshot of the keyboard: the host hides
 * the real view, keyfx animates pieces of the snapshot, then onDone lets the host show it again.
 * Hosts with CAP_KEY_TRANSFORM can instead animate each real key through look(), with no snapshot.
 */
final class ShowFx extends FxModule {
    private static final String G = "Keyboard show and hide";

    static Section[] sections() {
        return new Section[] {
                new Section("show", G, 700, "Show animation",
                        "Center bloom pops the keys in from the middle outward. Row cascade raises the rows from the bottom.",
                        false, true,
                        Field.choice("mode", "Style", "bloom", "bloom:Center bloom", "cascade:Row cascade", "none:None"),
                        Field.range("duration", "Duration", 120, 900, 10, "ms", 320).whenNot("mode", "none"),
                        Field.range("stagger", "Stagger", 0, 120, 1, "ms", 40).whenNot("mode", "none"),
                        Field.range("overshoot", "Overshoot", 0, 3, 0.05f, "×", 1.2f).whenNot("mode", "none"),
                        Field.toggle("perKey", "Animate each key", true).whenNot("mode", "none")
                                .requires(KeyFx.CAP_KEY_TRANSFORM)),
                new Section("hide", G, 710, "Hide animation",
                        "A quick moment before the keyboard slides away.", false, true,
                        Field.choice("mode", "Style", "dip", "dip:Dip", "none:None"),
                        Field.range("duration", "Duration", 80, 500, 10, "ms", 160).when("mode", "dip"))
                        .requires(KeyFx.CAP_HIDE_ANIMATION),
        };
    }

    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    /** One animation over the snapshot: pieces of it (keys or row bands) each with a start delay. */
    private final class Anim extends Effect {
        final Bitmap snap;
        final RectF kb;
        final Runnable onDone;
        final boolean hiding, bloom;
        final float[][] pieces; // left, top, right, bottom in layer coordinates
        final float[] delays;
        final float dur, overshoot;
        final int bg;
        final long t0;
        boolean done;

        Anim(Bitmap snap, Runnable onDone, boolean hiding, boolean bloom, float[][] pieces, float[] delays, float dur, float overshoot) {
            this.snap = snap;
            this.kb = new RectF(fx.layout().keyboard);
            this.onDone = onDone;
            this.hiding = hiding;
            this.bloom = bloom;
            this.pieces = pieces;
            this.delays = delays;
            this.dur = dur;
            this.overshoot = overshoot;
            this.t0 = fx.now();
            // The keyboard's own background, when the view draws one: it stays put under the pieces
            int px = 0;
            try {
                px = snap.getPixel(1, 1);
            } catch (Throwable ignored) {
                // Unreadable snapshot: no background
            }
            bg = (px >>> 24) > 200 ? px : 0;
        }

        @Override
        public boolean draw(Canvas c, long t) {
            if (done) return false;
            if (bg != 0) c.drawRect(kb, fx.fill(bg));
            boolean alive = false;
            for (int i = 0; i < pieces.length; i++) {
                final float[] r = pieces[i];
                final float u = (t - t0 - delays[i]) / dur;
                if (u < 1) alive = true;
                // Showing: invisible until it starts. Hiding: in place until it starts
                final float p = Fx.clamp01(u);
                final float e = hiding ? Fx.easeIn(p) : back(p, overshoot);
                final float alpha = hiding ? 1 - p : Fx.clamp01(p * 1.6f);
                if (alpha <= 0) continue;
                src.set((int) (r[0] - kb.left), (int) (r[1] - kb.top), (int) (r[2] - kb.left), (int) (r[3] - kb.top));
                if (bloom) {
                    final float sc = 0.2f + 0.8f * e, cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2;
                    final float hw = (r[2] - r[0]) / 2 * sc, hh = (r[3] - r[1]) / 2 * sc;
                    dst.set(cx - hw, cy - hh, cx + hw, cy + hh);
                } else {
                    // Rows rise from below when showing, and dip down when hiding
                    final float dy = hiding ? fx.dp(14) * e : (r[3] - r[1]) * 0.9f * (1 - e);
                    dst.set(r[0], r[1] + dy, r[2], r[3] + dy);
                }
                bmp.setAlpha((int) (255 * alpha));
                c.drawBitmap(snap, src, dst, bmp);
            }
            if (!alive) finish();
            return alive;
        }

        void finish() {
            if (done) return;
            done = true;
            if (anim == this) anim = null;
            // Run after this frame: hosts restore their view, which must not happen mid-draw
            final Runnable cb = onDone;
            final Bitmap b = snap;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (cb != null) cb.run();
                    b.recycle();
                }
            });
            fx.kick();
        }
    }

    /** Back-out easing whose overshoot follows the setting; 0 is a plain ease-out. */
    private static float back(float t, float overshoot) {
        final float c1 = 1.2f * overshoot, u = Fx.clamp01(t) - 1;
        return 1 + (c1 + 1) * u * u * u + c1 * u * u;
    }

    private Anim anim;

    // Per-key show (hosts with CAP_KEY_TRANSFORM): each key's start delay, read by look()
    private final IdentityHashMap<FxKey, Float> keyDelays = new IdentityHashMap<>();
    private long keysT0;
    private float keysDur, keysOvershoot, keysEnd;
    private boolean keysBloom;
    private Runnable keysDone;

    ShowFx(KeyFx fx) {
        super(fx);
    }

    @Override
    public boolean onShow(Bitmap snapshot, Runnable onDone) {
        final String mode = fx.settings.s("show", "mode");
        if ("none".equals(mode) || fx.layout().keys.length == 0) return false;
        finishNow();
        final float dur = fx.D(fx.settings.f("show", "duration")), stagger = fx.D(fx.settings.f("show", "stagger"));
        final float overshoot = fx.settings.f("show", "overshoot");
        if (snapshot == null) return showKeys(onDone, "bloom".equals(mode), dur, stagger, overshoot);
        if ("bloom".equals(mode)) {
            final FxKey[] keys = fx.layout().keys;
            final RectF kb = fx.layout().keyboard;
            final float[][] pieces = new float[keys.length][];
            final float[] delays = new float[keys.length];
            for (int i = 0; i < keys.length; i++) {
                final FxKey k = keys[i];
                pieces[i] = new float[] { k.left, k.top, k.right, k.bottom };
                final float d = (float) Math.hypot(k.cx() - kb.centerX(), (k.cy() - kb.centerY()) * 1.4f);
                delays[i] = d / fx.dp(40) * stagger * 0.5f;
            }
            anim = new Anim(snapshot, onDone, false, true, pieces, delays, dur, overshoot);
        } else {
            final float[][] bands = rowBands();
            if (bands.length < 2) return false;
            final float[] delays = new float[bands.length];
            for (int i = 0; i < bands.length; i++) delays[i] = (bands.length - 1 - i) * stagger; // bottom row first
            anim = new Anim(snapshot, onDone, false, false, bands, delays, dur, overshoot);
        }
        fx.add(anim);
        return true;
    }

    @Override
    public boolean onHide(Bitmap snapshot, Runnable onDone) {
        if (!"dip".equals(fx.settings.s("hide", "mode")) || fx.layout().keys.length == 0) return false;
        finishNow();
        final float[][] bands = rowBands();
        if (bands.length == 0) return false;
        final float[] delays = new float[bands.length];
        for (int i = 0; i < bands.length; i++) delays[i] = i * fx.D(18);
        anim = new Anim(snapshot, onDone, true, false, bands, delays, fx.D(fx.settings.f("hide", "duration")), 0);
        fx.add(anim);
        return true;
    }

    /** Animates the real keys through look(): the same bloom or cascade timing, no snapshot. */
    private boolean showKeys(Runnable onDone, boolean bloom, float dur, float stagger, float overshoot) {
        final FxKey[] keys = fx.layout().keys;
        final RectF kb = fx.layout().keyboard;
        final float[][] bands = rowBands();
        keyDelays.clear();
        float last = 0;
        for (FxKey k : keys) {
            float d;
            if (bloom) {
                d = (float) Math.hypot(k.cx() - kb.centerX(), (k.cy() - kb.centerY()) * 1.4f) / fx.dp(40) * stagger * 0.5f;
            } else {
                int row = bands.length > 0 && k.cy() < bands[0][1] ? 0 : bands.length - 1;
                for (int i = 0; i < bands.length; i++) if (k.cy() >= bands[i][1] && k.cy() < bands[i][3]) row = i;
                d = (bands.length - 1 - row) * stagger; // bottom row first
            }
            keyDelays.put(k, d);
            last = Math.max(last, d);
        }
        keysBloom = bloom;
        keysDur = dur;
        keysOvershoot = overshoot;
        keysEnd = last + dur;
        keysDone = onDone;
        keysT0 = fx.now();
        fx.kick();
        return true;
    }

    @Override
    public void look(FxKey k, KeyLook out, long t) {
        if (keysT0 == 0) return;
        final Float d = keyDelays.get(k);
        if (d == null) return;
        final float p = Fx.clamp01((t - keysT0 - d) / keysDur), e = back(p, keysOvershoot);
        if (keysBloom) out.scaleBy(0.2f + 0.8f * e);
        else out.move(0, k.h() * 0.9f * (1 - e));
        out.fade(Fx.clamp01(p * 1.6f));
    }

    @Override
    public boolean keysMoving(long t) {
        if (keysT0 == 0) return false;
        if (t - keysT0 < keysEnd) return true;
        finishKeys();
        return false;
    }

    private void finishKeys() {
        if (keysT0 == 0) return;
        keysT0 = 0;
        keyDelays.clear();
        final Runnable cb = keysDone;
        keysDone = null;
        if (cb != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(cb);
        }
        fx.kick();
    }

    /** Horizontal bands of the keyboard, one per key row; padding above the first row travels with it. */
    private float[][] rowBands() {
        final RectF kb = fx.layout().keyboard;
        final float[] tops = fx.layout().rowTops(fx.dp(8));
        final java.util.ArrayList<Float> edges = new java.util.ArrayList<>();
        edges.add(kb.top);
        for (int i = 1; i < tops.length; i++) {
            if (tops[i] > kb.top && tops[i] < kb.bottom && tops[i] - edges.get(edges.size() - 1) >= fx.dp(8)) edges.add(tops[i]);
        }
        edges.add(kb.bottom);
        final float[][] out = new float[edges.size() - 1][];
        for (int i = 0; i < out.length; i++) out[i] = new float[] { kb.left, edges.get(i), kb.right, edges.get(i + 1) };
        return out;
    }

    /** A finger on the keys ends a running animation at once. */
    @Override
    public void onKeyDown(FxKey k, float x, float y) {
        finishNow();
    }

    private void finishNow() {
        if (anim != null) anim.finish();
        finishKeys();
    }

    @Override
    public void reset() {
        finishNow();
    }
}
