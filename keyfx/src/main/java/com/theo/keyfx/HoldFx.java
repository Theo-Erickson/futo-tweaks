package com.theo.keyfx;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/** Long press charge (ring or liquid fill), the accent popup layout, and the spacebar trackpad. */
final class HoldFx extends FxModule {
    private static final String G = "Long press and spacebar";

    static Section[] sections() {
        return new Section[] {
                new Section("ring", G, 600, "Hold charge", "Fills while you hold a key that has a long-press action.",
                        true, true,
                        Field.choice("style", "Style", "liquid", "liquid:Liquid fill", "ring:Ring"),
                        Field.range("appear", "Appears after", 0, 80, 1, "%", 35),
                        Field.range("thickness", "Ring thickness", 1, 8, 0.5f, "dp", 3).when("style", "ring"),
                        Field.range("wave", "Liquid wave", 0, 8, 0.25f, "dp", 3).when("style", "liquid"),
                        Field.strength()),
                // Read by hosts that lay out the popup themselves; keyfx draws nothing for it
                new Section("popup", G, 610, "Accent popup", "Bloom fans the accents out in an arc around the key instead of a row.",
                        false, true,
                        Field.choice("style", "Layout", "bloom", "bloom:Bloom arc", "row:Row"),
                        Field.range("radius", "Bloom radius", 40, 120, 1, "dp", 70).when("style", "bloom"))
                        .requires(KeyFx.CAP_POPUP_LAYOUT),
                new Section("trackpad", G, 620, "Spacebar trackpad",
                        "While the spacebar moves the cursor, the keys fade back and a grid follows your finger.", true, true,
                        Field.range("dim", "Dim keys", 0, 0.9f, 0.01f, "", 0.6f),
                        Field.range("labels", "Fade labels", 0, 1, 0.01f, "", 0.85f).requires(KeyFx.CAP_KEY_TRANSFORM),
                        Field.range("grid", "Grid", 0, 1, 0.01f, "", 0.35f),
                        Field.range("halo", "Finger halo", 0, 60, 1, "dp", 26),
                        Field.strength()),
        };
    }

    HoldFx(KeyFx fx) {
        super(fx);
    }

    // region Hold charge

    private final class Charge extends Effect {
        final FxKey k;
        final long t0 = fx.now(), timeout;
        final float cx, cy, rad;
        boolean cancelled;

        Charge(FxKey k, float x, long timeout) {
            this.k = k;
            this.timeout = timeout;
            final boolean space = k.type == FxKey.SPACE;
            cx = space && !Float.isNaN(x) ? x : k.cx();
            cy = k.cy();
            rad = space ? k.h() * 0.6f : Math.max(k.w(), k.h()) * 0.55f;
        }

        @Override
        public boolean draw(Canvas c, long t) {
            if (cancelled) return false;
            final float s = fx.S("ring"), e = t - t0;
            final boolean liquid = "liquid".equals(fx.settings.s("ring", "style"));
            if (e >= timeout) {
                if (liquid) splash(k, s);
                else FxParts.ringPop(fx, cx, cy, rad * 1.7f, s);
                charge = null;
                return false;
            }
            final float appear = timeout * fx.settings.f("ring", "appear") / 100;
            if (e < appear) return true;
            final float p = (e - appear) / (timeout - appear), fadeIn = Fx.clamp01((e - appear) / 80);
            if (!liquid) {
                final float th = fx.dp(fx.settings.f("ring", "thickness"));
                c.drawCircle(cx, cy, rad, fx.stroke(Fx.alpha(fx.accent(), 0.18f * fadeIn * s), th));
                final RectF oval = fx.rect(cx - rad, cy - rad, cx + rad, cy + rad);
                c.drawArc(oval, -90, 360 * p, false, fx.stroke(Fx.alpha(fx.accent(), 0.9f * fadeIn * s), th));
            } else {
                // Liquid: the key fills from the bottom with a moving wave on top
                c.save();
                c.clipPath(fx.roundRectPath(k.left, k.top, k.right, k.bottom, 9));
                final float level = k.top + k.h() * (1 - p), ph = t / 140f;
                final float wave = fx.dp(fx.settings.f("ring", "wave")) * (1 - p * 0.5f);
                final Path path = fx.path();
                path.moveTo(k.left, k.bottom);
                final float step = fx.dp(3);
                for (float xx = 0; xx <= k.w(); xx += step) {
                    path.lineTo(k.left + xx, level + (float) Math.sin(xx / fx.dp(7) + ph) * wave);
                }
                path.lineTo(k.right, k.bottom);
                path.close();
                c.drawPath(path, fx.fill(Fx.alpha(fx.accent(), 0.5f * fadeIn * s)));
                c.restore();
            }
            return true;
        }
    }

    private Charge charge;

    @Override
    public void onLongPressStart(FxKey k, float x, long timeoutMs) {
        cancelCharge();
        if (!fx.on("ring") || timeoutMs <= 0) return;
        charge = new Charge(k, x, timeoutMs);
        fx.add(charge);
    }

    @Override
    public void onLongPressEnd() {
        cancelCharge();
    }

    private void cancelCharge() {
        if (charge != null) {
            charge.cancelled = true;
            charge = null;
        }
    }

    private void splash(final FxKey k, final float s) {
        final long t0 = fx.now();
        final float life = fx.D(480), gravity = fx.dp(520);
        final float[] x = new float[8], vx = new float[8], vy = new float[8];
        for (int i = 0; i < 8; i++) {
            x[i] = fx.rand(k.left + fx.dp(4), k.right - fx.dp(4));
            vx[i] = fx.dp(fx.rand(-40, 40));
            vy[i] = -fx.dp(fx.rand(80, 170));
        }
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = (t - t0) / 1000f, p = (t - t0) / life;
                if (p >= 1) return false;
                final Paint drop = fx.fill(Fx.alpha(fx.accent(), (1 - p) * 0.9f * s));
                for (int i = 0; i < 8; i++) c.drawCircle(x[i] + vx[i] * e, k.top + vy[i] * e + gravity * e * e, fx.dp(2.2f), drop);
                fx.roundRect(c, k.left, k.top, k.right, k.bottom, 9, fx.fill(Fx.alpha(fx.accent(), 0.45f * (1 - p) * s)));
                return true;
            }
        });
    }

    // endregion

    // region Spacebar trackpad

    private float level, target;
    private long last;
    private float tpX, tpY;

    @Override
    public void onCursorMode(boolean on, float x, float y) {
        cancelCharge();
        target = on && fx.on("trackpad") ? 1 : 0;
        if (on) {
            tpX = x;
            tpY = y;
        }
        fx.kick();
    }

    @Override
    public void onCursorMove(float x, float y) {
        tpX = x;
        tpY = y;
        if (level > 0) fx.kick();
    }

    @Override
    public boolean drawUnder(Canvas c, long t) {
        final float dt = last != 0 ? t - last : 0;
        last = t;
        level = level < target ? Math.min(1, level + dt / 150) : Math.max(target, level - dt / 200);
        if (level <= 0 && target <= 0) {
            last = 0;
            return false;
        }
        final float s = fx.S("trackpad"), L = level;
        final RectF kb = fx.layout().keyboard;
        final float dim = fx.settings.f("trackpad", "dim");
        c.drawRect(kb, fx.fill(fx.dark() ? Fx.alpha(0xFF0A0B0E, dim * L * s) : Fx.alpha(0xFFE4E7EC, dim * L * s)));

        final float step = fx.dp(22);
        final Paint grid = fx.stroke(Fx.alpha(fx.accent(), 0.4f * fx.settings.f("trackpad", "grid") * L * s), fx.dp(1));
        for (float x = kb.left + mod(tpX - kb.left, step); x < kb.right; x += step) c.drawLine(x, kb.top, x, kb.bottom, grid);
        for (float y = kb.top + mod(tpY - kb.top, step); y < kb.bottom; y += step) c.drawLine(kb.left, y, kb.right, y, grid);

        final float halo = fx.dp(fx.settings.f("trackpad", "halo"));
        if (halo > 0) {
            final Paint g = fx.fill(0xFFFFFFFF);
            g.setShader(new RadialGradient(tpX, tpY, halo * 1.6f, Fx.alpha(fx.accent(), 0.55f * L * s),
                    Fx.alpha(fx.accent(), 0), Shader.TileMode.CLAMP));
            c.drawCircle(tpX, tpY, halo * 1.6f, g);
            c.drawCircle(tpX, tpY, halo, fx.stroke(Fx.alpha(fx.accent(), 0.5f * L * s), fx.dp(1.5f)));
        }
        return level != target || target > 0;
    }

    @Override
    public void look(FxKey k, KeyLook out, long t) {
        if (level > 0) out.fadeLabel(1 - fx.settings.f("trackpad", "labels") * level);
    }

    @Override
    public boolean keysMoving(long t) {
        return level != target;
    }

    private static float mod(float a, float m) {
        final float r = a % m;
        return r < 0 ? r + m : r;
    }

    // endregion

    @Override
    public void reset() {
        cancelCharge();
        level = target = 0;
        last = 0;
    }
}
