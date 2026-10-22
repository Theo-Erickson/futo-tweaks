package com.theo.keyfx;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayList;

/** Drawn along the finger while swiping a word: key trace, comet, ink brush and stardust. */
final class SwipeTrailFx extends FxModule {
    private static final String G = "Swipe trail";

    static Section[] sections() {
        return new Section[] {
                new Section("trace", G, 300, "Key trace", "Keys light up as the swipe crosses them.", true, true,
                        Field.choice("color", "Color", "accent", "accent:Accent", "rainbow:Rainbow", "custom:Custom"),
                        Field.choice("custom", "Custom color", "#FF8A3D", "#FF8A3D:Orange", "#FF3B5C:Red",
                                "#7FD8BE:Mint", "#C58AF9:Purple", "#FDD663:Yellow", "#FFFFFF:White").when("color", "custom"),
                        Field.range("duration", "Fade time", 200, 1500, 10, "ms", 620),
                        Field.strength()),
                new Section("comet", G, 310, "Comet", "A bright head with a tapering tail that shifts hue along its length.",
                        true, false,
                        Field.range("length", "Tail length", 80, 800, 10, "ms", 260),
                        Field.range("width", "Head width", 2, 18, 0.5f, "dp", 7),
                        Field.range("hue", "Hue shift", 0, 180, 1, "°", 60),
                        Field.strength()),
                new Section("ink", G, 320, "Ink brush", "Thick when you slow down, thin when you move fast, like a calligraphy pen.",
                        true, false,
                        Field.choice("color", "Ink", "accent", "accent:Accent", "ink:Ink"),
                        Field.range("min", "Thinnest", 0.5f, 6, 0.25f, "dp", 1.5f),
                        Field.range("max", "Thickest", 3, 20, 0.5f, "dp", 10),
                        Field.range("linger", "Linger", 100, 1500, 10, "ms", 500),
                        Field.strength()),
                new Section("dust", G, 330, "Stardust", "Sparkles shed from the trail and twinkle for a moment.",
                        true, false,
                        Field.range("density", "Density", 0.2f, 3, 0.05f, "×", 1),
                        Field.range("life", "Lifetime", 200, 1600, 10, "ms", 760),
                        Field.strength()),
        };
    }

    // Path points: x, y, time, speed in dp per ms
    private final ArrayList<float[]> pts = new ArrayList<>();
    private long base; // point times are stored relative to this, so floats keep their precision
    // Sparkles: x, y, time, radius, phase, rise speed
    private final ArrayList<float[]> dust = new ArrayList<>();
    private boolean active;
    private long endAt;

    SwipeTrailFx(KeyFx fx) {
        super(fx);
    }

    @Override
    public void onSwipeStart() {
        pts.clear();
        base = fx.now();
        active = true;
        endAt = 0;
    }

    @Override
    public void onSwipePoint(float x, float y, long t) {
        if (!active) return;
        final float rt = t - base;
        final float[] l = pts.isEmpty() ? null : pts.get(pts.size() - 1);
        final float v = l == null ? 0 : (float) Math.hypot(x - l[0], y - l[1]) / fx.density / Math.max(1, rt - l[2]);
        pts.add(new float[] { x, y, rt, v });
        if (l != null && fx.on("dust")) {
            final float n = (float) Math.hypot(x - l[0], y - l[1]) / fx.dp(7) * fx.settings.f("dust", "density");
            for (int i = 0; i < n && dust.size() < 400; i++) {
                final float f = fx.rnd.nextFloat();
                dust.add(new float[] { Fx.lerp(l[0], x, f) + fx.dp(fx.rand(-6, 6)), Fx.lerp(l[1], y, f) + fx.dp(fx.rand(-6, 6)),
                        rt, fx.dp(fx.rand(1, 2.6f)), fx.rand(0, 6), fx.dp(fx.rand(-12, 4)) });
            }
        }
        fx.kick();
    }

    @Override
    public void onSwipeEnd() {
        active = false;
        endAt = fx.now();
    }

    @Override
    public void onSwipeCancel() {
        onSwipeEnd();
    }

    @Override
    public void onSwipeCross(FxKey k, int index) {
        if (fx.on("trace")) trace(k, index);
    }

    @Override
    public void reset() {
        pts.clear();
        dust.clear();
        active = false;
    }

    @Override
    public boolean drawOver(Canvas c, long now) {
        if (pts.isEmpty() && dust.isEmpty()) return false;
        final float t = now - base;
        boolean alive = active;
        if (fx.on("comet")) {
            final float len = fx.D(fx.settings.f("comet", "length")), w = fx.dp(fx.settings.f("comet", "width"));
            final float hue0 = Fx.hue(fx.accent()), shift = fx.settings.f("comet", "hue"), s = fx.S("comet");
            for (int i = 1; i < pts.size(); i++) {
                final float age = t - pts.get(i)[2];
                if (age > len) continue;
                final float f = 1 - age / len;
                alive = true;
                final int col = Fx.alpha(Fx.hsl((hue0 + shift * (1 - f)) % 360, 0.9f, 0.68f), f * s);
                final Paint p = fx.stroke(col, Math.max(fx.dp(0.5f), w * f));
                p.setShadowLayer(fx.dp(5), 0, 0, col);
                segment(c, i, p);
            }
        }
        if (fx.on("ink") && pts.size() > 1) {
            final float fade = active ? 1 : 1 - Fx.clamp01((now - endAt) / fx.D(fx.settings.f("ink", "linger")));
            if (fade > 0) {
                alive = true;
                final float min = fx.settings.f("ink", "min"), max = fx.settings.f("ink", "max"), s = fx.S("ink");
                final int col = "ink".equals(fx.settings.s("ink", "color")) ? (fx.dark() ? 0xFFF2F4F8 : 0xFF14161B) : fx.accent();
                for (int i = 1; i < pts.size(); i++) {
                    final float w = Fx.clamp(max - pts.get(i)[3] * (max - min) / 1.6f, min, max);
                    segment(c, i, fx.stroke(Fx.alpha(col, 0.85f * fade * s), fx.dp(w)));
                }
            }
        }
        if (!dust.isEmpty()) {
            final float life = fx.D(fx.settings.f("dust", "life")), s = fx.S("dust");
            for (int i = 0; i < dust.size(); i++) {
                final float[] q = dust.get(i);
                final float p = (t - q[2]) / life;
                if (p >= 1) {
                    dust.remove(i);
                    i--;
                    continue;
                }
                final float tw = 0.5f + 0.5f * (float) Math.sin(q[4] + (t - q[2]) / 60);
                final float a = (1 - p) * tw * s;
                final Paint paint = fx.fill(fx.rnd.nextFloat() < 0.2f ? Fx.alpha(0xFFFFFFFF, a) : Fx.alpha(fx.accent(), a));
                fx.star(c, q[0], q[1] + q[5] * (t - q[2]) / 1000f, q[3] * 1.6f, 4, 0.35f, q[4], paint);
            }
            alive = true;
        }
        if (!alive && !active) pts.clear();
        return alive;
    }

    private void segment(Canvas c, int i, Paint p) {
        final float[] a = pts.get(i - 1), b = pts.get(i);
        c.drawLine(a[0], a[1], b[0], b[1], p);
    }

    private void trace(final FxKey k, int index) {
        final long t0 = fx.now();
        final float dur = fx.D(fx.settings.f("trace", "duration")), s = fx.S("trace");
        final String mode = fx.settings.s("trace", "color");
        final int col = "custom".equals(mode) ? Fx.parseColor(fx.settings.s("trace", "custom"), 0xFFFF8A3D)
                : "rainbow".equals(mode) ? Fx.hsl((Fx.hue(fx.accent()) + index * 38) % 360, 0.9f, 0.65f) : fx.accent();
        final float shrink = fx.dp(3);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float p = (t - t0) / dur;
                if (p >= 1) return false;
                final float inset = shrink * (1 - Fx.easeOut(Fx.clamp01(p / 0.15f))), f = (float) Math.pow(1 - p, 1.5) * s;
                fx.roundRect(c, k.left + inset, k.top + inset, k.right - inset, k.bottom - inset, 9,
                        fx.fill(Fx.alpha(col, 0.32f * f)));
                fx.roundRect(c, k.left + inset, k.top + inset, k.right - inset, k.bottom - inset, 9,
                        fx.stroke(Fx.alpha(col, 0.85f * f), fx.dp(2)));
                return true;
            }
        });
    }
}
