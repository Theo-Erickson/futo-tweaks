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
                        Field.choice("mode", "Style", "bloom", "bloom:Center bloom", "cascade:Row cascade", "piano:Piano",
                                "decode:Decode", "flap:Split flap", "none:None")
                                .valueRequires("piano", KeyFx.CAP_KEY_TRANSFORM)
                                .valueRequires("decode", KeyFx.CAP_KEY_TRANSFORM)
                                .valueRequires("flap", KeyFx.CAP_KEY_TRANSFORM),
                        Field.range("duration", "Duration", 120, 900, 10, "ms", 320).whenNot("mode", "none"),
                        Field.range("stagger", "Stagger", 0, 120, 1, "ms", 40).whenNot("mode", "none"),
                        Field.range("overshoot", "Overshoot", 0, 3, 0.05f, "×", 1.2f).whenNot("mode", "none"),
                        Field.toggle("perKey", "Animate each key", true).whenNot("mode", "none", "piano", "decode", "flap")
                                .requires(KeyFx.CAP_KEY_TRANSFORM)),
                new Section("hide", G, 710, "Hide animation",
                        "A quick moment before the keyboard slides away.", false, true,
                        Field.choice("mode", "Style", "dip", "dip:Dip", "fold:Fold down", "sink:Sink", "scatter:Scatter",
                                "shrink:Shrink to center", "none:None"),
                        Field.range("duration", "Duration", 80, 600, 10, "ms", 160).whenNot("mode", "none"),
                        Field.range("stagger", "Stagger", 0, 60, 1, "ms", 18).whenNot("mode", "none", "sink"))
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
        // Hiding only: which hide mode, and per piece random or target values (scatter: dx, dy, degrees)
        String style = "dip";
        float[][] extra;

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
                final float alpha = hiding ? 1 - e : Fx.clamp01(p * 1.6f);
                if (alpha <= 0) continue;
                src.set((int) (r[0] - kb.left), (int) (r[1] - kb.top), (int) (r[2] - kb.left), (int) (r[3] - kb.top));
                float rot = 0;
                if (hiding && !"dip".equals(style)) {
                    final float cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2, hw = (r[2] - r[0]) / 2, hh = (r[3] - r[1]) / 2;
                    if ("fold".equals(style)) {
                        // Rows fold down away from you: flattened toward their middle
                        final float sy = (float) Math.cos(Math.toRadians(85 * e));
                        dst.set(cx - hw, cy - hh * sy, cx + hw, cy + hh * sy);
                    } else if ("sink".equals(style)) {
                        final float sc = 1 - 0.06f * e, dy = fx.dp(26) * e;
                        dst.set(cx - hw * sc, cy - hh * sc + dy, cx + hw * sc, cy + hh * sc + dy);
                    } else if ("scatter".equals(style)) {
                        final float[] x = extra[i];
                        final float mx = cx + x[0] * e, my = cy + x[1] * e;
                        dst.set(mx - hw, my - hh, mx + hw, my + hh);
                        rot = x[2] * e;
                    } else {
                        // Shrink: toward the middle, the target in extra
                        final float[] x = extra[i];
                        final float sc = 1 - 0.8f * e, mx = cx + x[0] * e, my = cy + x[1] * e;
                        dst.set(mx - hw * sc, my - hh * sc, mx + hw * sc, my + hh * sc);
                    }
                } else if (bloom) {
                    final float sc = 0.2f + 0.8f * e, cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2;
                    final float hw = (r[2] - r[0]) / 2 * sc, hh = (r[3] - r[1]) / 2 * sc;
                    dst.set(cx - hw, cy - hh, cx + hw, cy + hh);
                } else {
                    // Rows rise from below when showing, and dip down when hiding
                    final float dy = hiding ? fx.dp(14) * e : (r[3] - r[1]) * 0.9f * (1 - e);
                    dst.set(r[0], r[1] + dy, r[2], r[3] + dy);
                }
                bmp.setAlpha((int) (255 * alpha));
                if (rot != 0) {
                    c.save();
                    c.rotate(rot, dst.centerX(), dst.centerY());
                }
                c.drawBitmap(snap, src, dst, bmp);
                if (rot != 0) c.restore();
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
    private String keysMode = "bloom";
    private Runnable keysDone;
    // Decode and flap: each key's label sequence, drawn up front when the animation starts, and the
    // time each key's label begins (flap) or settles (decode)
    private final IdentityHashMap<FxKey, String[]> keyGlyphs = new IdentityHashMap<>();
    private final IdentityHashMap<FxKey, Float> keyTimes = new IdentityHashMap<>();
    private final java.util.Random rnd = new java.util.Random();
    private static final String FLAP_GLYPHS = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789";
    private static final String DECODE_GLYPHS = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789#$%&@*+=?";
    private static final float DECODE_STEP = 45, FLAP_EACH = 70;

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
        if (snapshot == null) return showKeys(onDone, mode, dur, stagger, overshoot);
        // Piano, decode and flap move or relabel real keys, which a snapshot can't do
        if ("piano".equals(mode) || "decode".equals(mode) || "flap".equals(mode)) return false;
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
        final String mode = fx.settings.s("hide", "mode");
        final boolean rows = "dip".equals(mode) || "fold".equals(mode), keyed = "scatter".equals(mode) || "shrink".equals(mode);
        if (!(rows || keyed || "sink".equals(mode)) || fx.layout().keys.length == 0 || snapshot == null) return false;
        finishNow();
        final float dur = fx.D(fx.settings.f("hide", "duration")), st = fx.D(fx.settings.f("hide", "stagger"));
        final float[][] pieces, extra;
        final float[] delays;
        if (rows) {
            pieces = rowBands();
            extra = null;
            delays = new float[pieces.length];
            for (int i = 0; i < pieces.length; i++) delays[i] = i * st; // top row first
        } else if ("sink".equals(mode)) {
            final RectF kb = fx.layout().keyboard;
            pieces = new float[][] { { kb.left, kb.top, kb.right, kb.bottom } };
            extra = null;
            delays = new float[1];
        } else {
            final FxKey[] keys = fx.layout().keys;
            final RectF kb = fx.layout().keyboard;
            final boolean scatter = "scatter".equals(mode);
            final float maxD = (float) Math.hypot(kb.width() / 2, kb.height() * 0.7f);
            pieces = new float[keys.length][];
            extra = new float[keys.length][];
            delays = new float[keys.length];
            // Random values are drawn once, here, so every frame agrees
            for (int i = 0; i < keys.length; i++) {
                final FxKey k = keys[i];
                pieces[i] = new float[] { k.left, k.top, k.right, k.bottom };
                if (scatter) {
                    // Keys tumble off in a random order
                    extra[i] = new float[] { fx.dp(rand(-40, 40)), fx.dp(rand(70, 140)), rand(-40, 40) };
                    delays[i] = rand(0, st * 6);
                } else {
                    // Bloom in reverse: the outer keys go first and everything shrinks toward the middle
                    final float d = (float) Math.hypot(k.cx() - kb.centerX(), (k.cy() - kb.centerY()) * 1.4f);
                    extra[i] = new float[] { (kb.centerX() - k.cx()) * 0.3f, (kb.centerY() - k.cy()) * 0.3f, 0 };
                    delays[i] = Math.max(0, 1 - d / maxD) * st * 6;
                }
            }
        }
        final Anim a = new Anim(snapshot, onDone, true, false, pieces, delays, dur, 0);
        a.style = mode;
        a.extra = extra;
        anim = a;
        fx.add(anim);
        return true;
    }

    private float rand(float a, float b) {
        return a + rnd.nextFloat() * (b - a);
    }

    /** Index of the row band holding y; rows above the first count as the first, below as the last. */
    private static int rowOf(float y, float[][] bands) {
        int row = bands.length > 0 && y < bands[0][1] ? 0 : bands.length - 1;
        for (int i = 0; i < bands.length; i++) if (y >= bands[i][1] && y < bands[i][3]) row = i;
        return Math.max(row, 0);
    }

    /** Animates the real keys through look(): bloom, cascade, piano, decode or flap timing, no snapshot. */
    private boolean showKeys(Runnable onDone, String mode, float dur, float stagger, float overshoot) {
        final FxKey[] keys = fx.layout().keys;
        final RectF kb = fx.layout().keyboard;
        final float[][] bands = rowBands();
        final float each = fx.D(FLAP_EACH);
        keyDelays.clear();
        keyGlyphs.clear();
        keyTimes.clear();
        float last = 0, end = dur;
        for (FxKey k : keys) {
            final float order = kb.width() > 0 ? (k.cx() - kb.left) / kb.width() : 0;
            final int row = rowOf(k.cy(), bands);
            float d = 0;
            if ("bloom".equals(mode)) {
                d = (float) Math.hypot(k.cx() - kb.centerX(), (k.cy() - kb.centerY()) * 1.4f) / fx.dp(40) * stagger * 0.5f;
            } else if ("cascade".equals(mode)) {
                d = (bands.length - 1 - row) * stagger; // bottom row first
            } else if ("piano".equals(mode)) {
                // Keys drop in left to right across the board
                d = order * stagger * 9 + row * stagger * 0.6f;
            } else if (k.type == FxKey.CHAR || k.type == FxKey.NUM) {
                // Decode and flap: the label sequence is chosen now, not per frame
                if ("flap".equals(mode)) {
                    final float start = order * stagger * 6 + row * stagger * 0.8f;
                    final String[] g = new String[3 + rnd.nextInt(4)];
                    for (int i = 0; i < g.length; i++) g[i] = String.valueOf(FLAP_GLYPHS.charAt(rnd.nextInt(FLAP_GLYPHS.length())));
                    keyGlyphs.put(k, g);
                    keyTimes.put(k, start);
                    end = Math.max(end, start + (g.length + 1) * each);
                } else {
                    final float settle = dur * 0.6f + order * stagger * 10 + row * stagger;
                    final String[] g = new String[(int) (settle / fx.D(DECODE_STEP)) + 1];
                    for (int i = 0; i < g.length; i++) g[i] = String.valueOf(DECODE_GLYPHS.charAt(rnd.nextInt(DECODE_GLYPHS.length())));
                    keyGlyphs.put(k, g);
                    keyTimes.put(k, settle);
                    end = Math.max(end, settle);
                }
            }
            keyDelays.put(k, d);
            last = Math.max(last, d);
        }
        keysMode = mode;
        keysDur = dur;
        keysOvershoot = overshoot;
        keysEnd = Math.max(last + dur, end);
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
        final float el = t - keysT0 - d, p = Fx.clamp01(el / keysDur);
        if ("piano".equals(keysMode)) {
            // Drops from above, lands a little low, then settles
            if (p < 0.7f) {
                final float q = Fx.easeIn(p / 0.7f);
                out.move(0, fx.dp(-46 + 50 * q));
                out.fade(q);
            } else {
                out.move(0, fx.dp(4) * (1 - Fx.easeIn((p - 0.7f) / 0.3f)));
            }
        } else if ("flap".equals(keysMode) || "decode".equals(keysMode)) {
            final boolean flap = "flap".equals(keysMode);
            out.fade((flap ? 0.3f : 0.25f) + (flap ? 0.7f : 0.75f) * p);
            final String[] g = keyGlyphs.get(k);
            final Float at = keyTimes.get(k);
            if (g == null || at == null) return;
            final float run = t - keysT0;
            if (flap) {
                // Each flip shows a new glyph and dims it a moment, standing in for the folding half
                final float each = fx.D(FLAP_EACH), u = (run - at) / each;
                if (u < 0 || u >= g.length + 1) return;
                final int f = (int) u;
                if (f < g.length) out.label = g[f];
                out.fadeLabel(0.35f + 0.65f * Fx.easeOut(Math.min(1, (u - f) / 0.9f)));
            } else if (run < at) {
                out.label = g[Math.min(g.length - 1, (int) (run / fx.D(DECODE_STEP)))];
            }
        } else {
            final float e = back(p, keysOvershoot);
            if ("bloom".equals(keysMode)) out.scaleBy(0.2f + 0.8f * e);
            else out.move(0, k.h() * 0.9f * (1 - e));
            out.fade(Fx.clamp01(p * 1.6f));
        }
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
        keyGlyphs.clear();
        keyTimes.clear();
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
