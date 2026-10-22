package com.theo.keyfx;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

import java.util.ArrayList;

/** What a normal key tap does: key pop, letter float, heat streak and neighbor wave. */
final class TypingFx extends FxModule {
    private static final String G = "Typing";

    static Section[] sections() {
        return new Section[] {
                new Section("pop", G, 100, "Key pop", "The key highlight swells past its edges, then pulls back.",
                        true, true,
                        Field.range("grow", "Swell", 0, 16, 0.5f, "dp", 6),
                        Field.range("pullback", "Pullback time", 80, 1000, 10, "ms", 340),
                        Field.range("bounce", "Bounce", 0, 1, 0.05f, "", 0.35f),
                        Field.strength()),
                new Section("float", G, 110, "Letter float",
                        "The typed letter lifts off its key. Ghost sends up a see-through copy of the key that sways as it rises.",
                        true, true,
                        Field.choice("mode", "Style", "ghost", "rise:Rise", "grow:Grow", "ghost:Ghost key"),
                        Field.range("rise", "Rise height", 0, 100, 1, "dp", 42),
                        Field.range("duration", "Duration", 200, 1500, 10, "ms", 640),
                        Field.range("grow", "Growth", 1, 3, 0.05f, "×", 1.6f),
                        Field.range("wave", "Sway width", 0, 24, 0.5f, "dp", 8).when("mode", "ghost"),
                        Field.range("waves", "Sways", 0.5f, 4, 0.25f, "", 1.5f).when("mode", "ghost"),
                        Field.strength()),
                new Section("heat", G, 120, "Heat streak",
                        "A glow on the keyboard's top edge that warms toward orange as you type faster.", true, true,
                        Field.toggle("swipe", "Swipes build heat too", true),
                        Field.range("build", "Build-up per key", 0.02f, 0.25f, 0.005f, "", 0.09f),
                        Field.range("cool", "Cool-down", 200, 4000, 50, "ms", 1100),
                        Field.range("threshold", "Shows from", 0, 0.6f, 0.01f, "", 0.12f),
                        Field.range("thickness", "Glow height", 4, 40, 1, "dp", 18),
                        Field.strength()),
                new Section("wave", G, 130, "Neighbor wave", "Each press sends a ripple of light across the keys around it.",
                        true, true,
                        Field.range("radius", "Reach", 1, 4, 0.1f, "keys", 1.8f),
                        Field.range("speed", "Speed", 0.1f, 1.2f, 0.05f, "dp/ms", 0.35f),
                        Field.range("strength", "Intensity", 0, 1.5f, 0.05f, "×", 0.8f)),
        };
    }

    // Heat level from 0 to 1, decaying over time
    private float heat;
    private long heatAt;

    TypingFx(KeyFx fx) {
        super(fx);
    }

    @Override
    public void onKeyDown(FxKey k, float x, float y) {
        FxParts.pop(fx, k);
        if (fx.on("wave") && k.type != FxKey.NUM) wave(k);
    }

    @Override
    public void onKeyTyped(FxKey k, String glyph, float x) {
        if (fx.on("float") && glyph != null && (k.type == FxKey.CHAR || k.type == FxKey.NUM)) letterFloat(k, glyph);
        addHeat(fx.settings.f("heat", "build"));
    }

    @Override
    public void onDeleteTap(FxKey k, FxLetter[] letters) {
        addHeat(fx.settings.f("heat", "build"));
    }

    @Override
    public void onSwipeCross(FxKey k, int index) {
        if (fx.settings.b("heat", "swipe")) addHeat(fx.settings.f("heat", "build") * 0.6f);
    }

    @Override
    public void reset() {
        heat = 0;
        heatAt = 0;
    }

    // region Letter float

    private void letterFloat(final FxKey k, final String glyph) {
        final long t0 = fx.now();
        final float dur = fx.D(fx.settings.f("float", "duration")), s = fx.S("float");
        final String mode = fx.settings.s("float", "mode");
        final float rise = fx.dp(fx.settings.f("float", "rise")), grow = fx.settings.f("float", "grow");
        final float wave = fx.dp(fx.settings.f("float", "wave")), waves = fx.settings.f("float", "waves");
        final float size = k.h() * 0.48f / fx.density; // in dp, for glowText
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float p = (t - t0) / dur;
                if (p >= 1) return false;
                final float ep = Fx.easeOut(p);
                if ("rise".equals(mode)) {
                    fx.glowText(c, glyph, k.cx(), k.cy() - rise * ep, size, 1 + (grow - 1) * 0.4f * ep, (1 - p) * s,
                            fx.accent(), 0);
                } else if ("grow".equals(mode)) {
                    fx.glowText(c, glyph, k.cx(), k.cy() - rise * 0.35f * ep, size, 1 + (grow - 1) * ep,
                            (float) Math.pow(1 - p, 1.2) * s, fx.accent(), 0);
                } else {
                    // Ghost: a see-through copy of the key that sways as it drifts up
                    final float sway = (float) Math.sin(p * Math.PI * 2 * waves) * wave * (1 - p * 0.4f);
                    final float x = k.cx() + sway, y = k.cy() - rise * ep, sc = 1 + (grow - 1) * 0.35f * ep;
                    final float a = (1 - p) * 0.85f * s, rot = sway / fx.density * 0.012f;
                    c.save();
                    c.translate(x, y);
                    c.rotate((float) Math.toDegrees(rot));
                    c.scale(sc, sc);
                    final float hw = k.w() / 2, hh = k.h() / 2;
                    fx.roundRect(c, -hw, -hh, hw, hh, 9, fx.fill(Fx.alpha(fx.accent(), 0.14f * a)));
                    fx.roundRect(c, -hw, -hh, hw, hh, 9, fx.stroke(Fx.alpha(fx.accent(), 0.55f * a), fx.dp(1.5f)));
                    c.restore();
                    fx.glowText(c, glyph, x, y, size, sc, a, fx.accent(), rot);
                }
                return true;
            }
        });
    }

    // endregion

    // region Heat streak

    private void addHeat(float amount) {
        if (!fx.on("heat")) return;
        decay(fx.now());
        heat = Math.min(1, heat + amount);
        fx.kick();
    }

    private void decay(long t) {
        if (heatAt != 0) heat *= (float) Math.exp(-(t - heatAt) / fx.D(fx.settings.f("heat", "cool")));
        heatAt = t;
    }

    @Override
    public boolean drawUnder(Canvas c, long t) {
        if (heat <= 0) return false;
        decay(t);
        if (heat < 0.012f || !fx.on("heat")) {
            heat = 0;
            heatAt = 0;
            return false;
        }
        final float vis = Fx.clamp01((heat - fx.settings.f("heat", "threshold")) / 0.5f) * fx.S("heat");
        if (vis > 0) {
            final int col = heat < 0.5f ? Fx.mix(fx.accent(), 0xFFFF8A3D, heat / 0.5f)
                    : Fx.mix(0xFFFF8A3D, 0xFFFF3B5C, (heat - 0.5f) / 0.5f);
            final float top = fx.layout().keyboard.top, left = fx.layout().keyboard.left;
            final float right = fx.layout().keyboard.right, th = fx.dp(fx.settings.f("heat", "thickness"));
            final Paint p = fx.fill(0xFFFFFFFF);
            p.setShader(new LinearGradient(0, top, 0, top + th, Fx.alpha(col, 0.6f * vis), Fx.alpha(col, 0),
                    Shader.TileMode.CLAMP));
            c.drawRect(left, top, right, top + th, p);
            c.drawRect(left, top, right, top + fx.dp(2), fx.fill(Fx.alpha(col, 0.95f * vis)));
        }
        return true;
    }

    // endregion

    // region Neighbor wave

    private void wave(final FxKey k) {
        final long t0 = fx.now();
        final float reach = fx.settings.f("wave", "radius") * k.w();
        final float speed = fx.dp(fx.settings.f("wave", "speed")), s = fx.S("wave");
        final ArrayList<FxKey> keys = new ArrayList<>();
        final ArrayList<Float> dists = new ArrayList<>();
        for (FxKey n : fx.layout().keys) {
            if (n == k || n.type == FxKey.NUM) continue;
            final float d = (float) Math.hypot(n.cx() - k.cx(), (n.cy() - k.cy()) * 0.9f);
            if (d <= reach) {
                keys.add(n);
                dists.add(d);
            }
        }
        if (keys.isEmpty()) return;
        final float ring = fx.D(320);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                boolean alive = false;
                for (int i = 0; i < keys.size(); i++) {
                    final FxKey n = keys.get(i);
                    final float d = dists.get(i);
                    final float u = (t - t0 - fx.D(d / speed)) / ring;
                    if (u < 0) {
                        alive = true;
                        continue;
                    }
                    if (u >= 1) continue;
                    alive = true;
                    final float a = (float) (Math.pow(1 - d / reach, 1.2) * Math.pow(1 - u, 2)) * s;
                    fx.roundRect(c, n.left, n.top, n.right, n.bottom, 9, fx.fill(Fx.alpha(fx.accent(), 0.32f * a)));
                }
                return alive;
            }
        });
    }

    // endregion
}
