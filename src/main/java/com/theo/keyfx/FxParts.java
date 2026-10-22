package com.theo.keyfx;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

/** Small effects that several modules reuse. */
final class FxParts {
    private FxParts() {}

    static final PorterDuffXfermode ADD = new PorterDuffXfermode(PorterDuff.Mode.ADD);

    /** The key pop, with the "pop" section's settings: the highlight swells past the key, then pulls back. */
    static void pop(final KeyFx fx, final FxKey k) {
        if (!fx.on("pop")) return;
        final long t0 = fx.now();
        final float grow = fx.D(70), pull = fx.D(fx.settings.f("pop", "pullback"));
        final float size = fx.dp(fx.settings.f("pop", "grow")), bounce = fx.settings.f("pop", "bounce");
        final float s = fx.S("pop");
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = t - t0;
                float sw;
                if (e < grow) {
                    sw = size * Fx.easeOut(e / grow);
                } else {
                    final float u = (e - grow) / pull;
                    if (u >= 1) return false;
                    // Pull back with an adjustable spring: no bounce eases straight in, full bounce wobbles
                    sw = size * (1 - u) * (float) Math.cos(u * Math.PI * (0.5 + 2.5 * bounce));
                }
                final float fade = 1 - e / (grow + pull);
                fx.roundRect(c, k.left - sw, k.top - sw, k.right + sw, k.bottom + sw, 10,
                        fx.fill(Fx.alpha(fx.accent(), 0.2f * s * fade)));
                fx.roundRect(c, k.left - sw, k.top - sw, k.right + sw, k.bottom + sw, 10,
                        fx.stroke(Fx.alpha(fx.accent(), 0.7f * s * fade), fx.dp(1.5f)));
                return true;
            }
        });
    }

    /** A ring that expands and fades: a small "swallowed" or "done" flash. */
    static void ringPop(final KeyFx fx, final float x, final float y, final float r, final float s) {
        final long t0 = fx.now();
        final float life = fx.D(380);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float p = (t - t0) / life;
                if (p >= 1) return false;
                c.drawCircle(x, y, Math.max(0.1f, r * Fx.easeOut(p)),
                        fx.stroke(Fx.alpha(fx.accent(), 0.7f * (1 - p) * s), fx.dp(3 - 2 * p)));
                return true;
            }
        });
    }

    /** A few embers that rise and fade, for things that burn up. */
    static void emberBurst(final KeyFx fx, final float x, final float y, final float s) {
        final long t0 = fx.now();
        final float life = fx.D(600);
        final float[] vx = new float[7], vy = new float[7], r = new float[7];
        for (int i = 0; i < 7; i++) {
            vx[i] = fx.dp(fx.rand(-25, 25));
            vy[i] = -fx.dp(fx.rand(30, 90));
            r[i] = fx.dp(fx.rand(1.2f, 2.6f));
        }
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = (t - t0) / 1000f, p = (t - t0) / life;
                if (p >= 1) return false;
                final Paint paint = fx.fill(Fx.fire(p, (1 - p) * s));
                for (int i = 0; i < 7; i++) {
                    c.drawCircle(x + vx[i] * e, y + vy[i] * e, r[i] * (1 - p * 0.5f), paint);
                }
                return true;
            }
        });
    }

    /** A horizontal band of accent light, brightest in the middle. */
    static void band(KeyFx fx, Canvas c, float x, float y, float w, float h, float alpha) {
        final Paint p = fx.fill(0xFFFFFFFF);
        final int a = fx.accent();
        p.setShader(new android.graphics.LinearGradient(x, 0, x + w, 0,
                new int[] { Fx.alpha(a, 0), Fx.alpha(a, alpha), Fx.alpha(a, 0) }, null,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(x, y, x + w, y + h, p);
    }
}
