package com.theo.keyfx;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

import java.util.ArrayList;

/** What a normal key tap does: key pop, letter float, heat streak, neighbor wave, ripple and sparks. */
final class TypingFx extends FxModule {
    private static final String G = "Typing";

    static Section[] sections() {
        return new Section[] {
                new Section("pop", G, 100, "Key pop", "The key highlight swells past its edges, then pulls back.",
                        true, true,
                        Field.range("grow", "Swell", 0, 16, 0.5f, "dp", 6),
                        Field.range("pullback", "Pullback time", 80, 1000, 10, "ms", 340),
                        Field.range("bounce", "Bounce", 0, 1, 0.05f, "", 0.35f),
                        Field.toggle("scaleKey", "Scale the real key", true).requires(KeyFx.CAP_KEY_TRANSFORM),
                        Field.range("keyScale", "Key growth", 1, 1.4f, 0.01f, "×", 1.12f)
                                .when("scaleKey", "true").requires(KeyFx.CAP_KEY_TRANSFORM),
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
                new Section("ripple", G, 140, "Ripple", "A ring expands from where you touch.", true, false,
                        Field.range("radius", "Size", 12, 80, 1, "dp", 36),
                        Field.range("duration", "Duration", 150, 1000, 10, "ms", 380),
                        Field.strength()),
                new Section("sparks", G, 150, "Sparks", "A small burst of sparks flies off each press and falls.",
                        true, false,
                        Field.range("count", "Amount", 3, 30, 1, "", 10),
                        Field.range("life", "Lifetime", 200, 1200, 10, "ms", 520),
                        Field.range("gravity", "Gravity", 0, 800, 10, "dp/s²", 320),
                        Field.strength()),
        };
    }

    // Heat level from 0 to 1, decaying over time
    private float heat;
    private long heatAt;
    /** A key's pop on the real key, with the settings it started with. */
    private static final class Press {
        final FxKey k;
        final long t0;
        final float grow, pull, bounce, scale;

        Press(FxKey k, long t0, float grow, float pull, float bounce, float scale) {
            this.k = k;
            this.t0 = t0;
            this.grow = grow;
            this.pull = pull;
            this.bounce = bounce;
            this.scale = scale;
        }
    }

    private final ArrayList<Press> presses = new ArrayList<>();

    TypingFx(KeyFx fx) {
        super(fx);
    }

    @Override
    public void onKeyDown(FxKey k, float x, float y) {
        FxParts.pop(fx, k);
        if ((fx.caps & KeyFx.CAP_KEY_TRANSFORM) != 0 && fx.on("pop") && fx.settings.b("pop", "scaleKey")) {
            for (int i = 0; i < presses.size(); i++) {
                if (presses.get(i).k == k) {
                    presses.remove(i);
                    i--;
                }
            }
            presses.add(new Press(k, fx.now(), fx.D(70), fx.D(fx.settings.f("pop", "pullback")),
                    fx.settings.f("pop", "bounce"), fx.settings.f("pop", "keyScale")));
            fx.kick();
        }
        if (fx.on("wave") && k.type != FxKey.NUM) wave(k);
        if (fx.on("ripple")) ripple(x, y);
        if (fx.on("sparks")) sparks(x, y);
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
        presses.clear();
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

    // region Key pop on the real key

    /**
     * The pop's swell, as FxParts.pop draws it: up to 1, then springing back through 0 (slightly
     * below 0 while it bounces). NaN once it is over.
     */
    private static float popAmount(Press p, long elapsed) {
        if (elapsed < p.grow) return Fx.easeOut(elapsed / p.grow);
        final float u = (elapsed - p.grow) / p.pull;
        if (u >= 1) return Float.NaN;
        return (1 - u) * (float) Math.cos(u * Math.PI * (0.5 + 2.5 * p.bounce));
    }

    @Override
    public void look(FxKey k, KeyLook out, long t) {
        for (Press p : presses) {
            if (p.k != k) continue;
            final float a = popAmount(p, t - p.t0);
            if (!Float.isNaN(a)) out.scaleBy(1 + (p.scale - 1) * a);
        }
    }

    @Override
    public boolean keysMoving(long t) {
        for (int i = 0; i < presses.size(); i++) {
            if (Float.isNaN(popAmount(presses.get(i), t - presses.get(i).t0))) {
                presses.remove(i);
                i--;
            }
        }
        return !presses.isEmpty();
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

    // region Ripple and sparks

    private void ripple(final float x, final float y) {
        final long t0 = fx.now();
        final float life = fx.D(fx.settings.f("ripple", "duration")), r = fx.dp(fx.settings.f("ripple", "radius"));
        final float s = fx.S("ripple");
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

    private void sparks(final float x, final float y) {
        final long t0 = fx.now();
        final int n = Math.round(fx.settings.f("sparks", "count"));
        final float lifeMs = fx.settings.f("sparks", "life"), life = fx.D(lifeMs);
        final float g = fx.dp(fx.settings.f("sparks", "gravity")), s = fx.S("sparks");
        final float[] vx = new float[n], vy = new float[n], r = new float[n];
        for (int i = 0; i < n; i++) {
            // Spread around the full circle, biased upward so sparks rise off the key
            final double a = fx.rnd.nextDouble() * Math.PI * 2;
            final float speed = fx.dp(fx.rand(60, 150));
            vx[i] = (float) Math.cos(a) * speed;
            vy[i] = (float) Math.sin(a) * speed - fx.dp(60);
            r[i] = fx.dp(fx.rand(1.5f, 3));
        }
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float p = (t - t0) / life;
                if (p >= 1) return false;
                // Seconds at normal speed, so the speed setting speeds up the flight too
                final float e = p * lifeMs / 1000f;
                final Paint paint = fx.fill(Fx.alpha(fx.accent(), (1 - p) * s));
                for (int i = 0; i < n; i++) {
                    c.drawCircle(x + vx[i] * e, y + vy[i] * e + 0.5f * g * e * e, r[i] * (1 - 0.5f * p), paint);
                }
                return true;
            }
        });
    }

    // endregion
}
