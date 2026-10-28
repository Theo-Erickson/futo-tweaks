package com.theo.keyfx;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.Collections;

/**
 * What a normal key tap does: key pop, letter float, heat streak (glow, wave, bar, circuit or
 * embers, with an optional combo counter), neighbor wave (wave, orbit, firework or firecracker),
 * ripple, sparks and water drop.
 */
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
                        Field.choice("mode", "Style", "ghost", "rise:Rise", "ghost:Ghost key"),
                        Field.range("rise", "Rise height", 0, 100, 1, "dp", 42),
                        Field.range("duration", "Duration", 200, 1500, 10, "ms", 640),
                        Field.range("grow", "Growth", 1, 3, 0.05f, "×", 1.6f),
                        Field.range("wave", "Sway width", 0, 24, 0.5f, "dp", 8).when("mode", "ghost"),
                        Field.range("waves", "Sways", 0.5f, 4, 0.25f, "", 1.5f).when("mode", "ghost"),
                        Field.strength()),
                new Section("heat", G, 120, "Heat streak",
                        "The keyboard's top edge warms toward orange as you type faster. Pick how the build-up looks, and add a combo counter.",
                        true, true,
                        Field.choice("style", "Build-up looks like", "glow", "glow:Glow", "wave:Wave", "bar:Progress bar",
                                "circuit:Energizing circuit", "embers:Rising embers"),
                        Field.toggle("swipe", "Swipes build heat too", true),
                        Field.range("build", "Build-up per key", 0.02f, 0.25f, 0.005f, "", 0.09f),
                        Field.range("cool", "Cool-down", 200, 4000, 50, "ms", 1100),
                        Field.range("threshold", "Shows from", 0, 0.6f, 0.01f, "", 0.12f),
                        Field.range("thickness", "Glow height", 4, 40, 1, "dp", 18).when("style", "glow"),
                        Field.toggle("combo", "Combo counter (swipes count every letter of the word)", false),
                        Field.range("window", "Combo breaks after", 250, 1500, 10, "ms", 700).when("combo", "true"),
                        Field.range("showAt", "Combo shows from", 2, 20, 1, "", 5).when("combo", "true"),
                        Field.strength()),
                new Section("wave", G, 130, "Neighbor wave", "Each press reaches out to the keys around it.",
                        true, true,
                        Field.choice("style", "Style", "wave", "wave:Wave", "orbit:Orbit around the key",
                                "firework:Firework", "firecracker:Firecracker"),
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
                new Section("drop", G, 160, "Water drop", "Rings ripple outward across the key from where your finger lands.",
                        true, false,
                        Field.range("rings", "Rings", 1, 5, 1, "", 3),
                        Field.range("duration", "Duration", 250, 1400, 10, "ms", 650),
                        Field.strength()),
        };
    }

    // Heat level from 0 to 1, decaying over time
    private float heat;
    private long heatAt;
    // Heat wave phase, accumulated so a changing speed doesn't make it jump
    private double heatPh;
    private long heatPhAt, emberAt;
    // Letters crossed by the current swipe (doubles collapsed), the combo's fallback when no word arrives
    private int crossedN;
    private String crossedLast;
    private String comboText = "";
    private int shownN;
    /** An ember rising off the heat streak's edge (x, rise speed and radius in dp). */
    private static final class Ember {
        final long t0;
        final float x, vy, r, ph;

        Ember(long t0, float x, float vy, float r, float ph) {
            this.t0 = t0;
            this.x = x;
            this.vy = vy;
            this.r = r;
            this.ph = ph;
        }
    }

    private final ArrayList<Ember> embers = new ArrayList<>();
    // Combo counter: count, time of the last hit, time of the last bump
    private int comboN;
    private long comboLast, comboBumpAt;
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
        if (fx.on("drop")) drop(k, x, y);
    }

    @Override
    public void onKeyTyped(FxKey k, String glyph, float x) {
        if (fx.on("float") && glyph != null && (k.type == FxKey.CHAR || k.type == FxKey.NUM)) letterFloat(k, glyph);
        addHeat(fx.settings.f("heat", "build"));
        comboHit(1);
    }

    @Override
    public void onDeleteTap(FxKey k, FxLetter[] letters) {
        addHeat(fx.settings.f("heat", "build"));
        comboHit(1);
    }

    @Override
    public void onSwipeWord(String word) {
        // A swiped word counts each of its letters, with doubled letters collapsed as the lab does
        // No word arrived: fall back to the letters the swipe crossed, like the lab
        if (word == null) {
            comboHit(crossedN);
            return;
        }
        int n = 0;
        for (int i = 0; i < word.length(); i++) if (i == 0 || word.charAt(i) != word.charAt(i - 1)) n++;
        comboHit(n);
    }

    @Override
    public void onSwipeStart() {
        crossedN = 0;
        crossedLast = null;
    }

    @Override
    public void onSwipeCross(FxKey k, int index) {
        final String g = k.isLetter() ? k.glyph() : null;
        if (g != null && !g.equals(crossedLast)) crossedN++;
        if (g != null) crossedLast = g;
        if (fx.settings.b("heat", "swipe")) addHeat(fx.settings.f("heat", "build") * 0.6f);
    }

    @Override
    public void reset() {
        heat = 0;
        heatAt = 0;
        heatPh = 0;
        heatPhAt = 0;
        emberAt = 0;
        embers.clear();
        crossedN = 0;
        crossedLast = null;
        comboN = 0;
        comboText = "";
        shownN = 0;
        comboLast = 0;
        comboBumpAt = 0;
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
            heatPh = 0;
            heatPhAt = 0;
            emberAt = 0;
            embers.clear();
            return false;
        }
        final float vis = Fx.clamp01((heat - fx.settings.f("heat", "threshold")) / 0.5f) * fx.S("heat");
        if (vis > 0) {
            final int col = heat < 0.5f ? Fx.mix(fx.accent(), 0xFFFF8A3D, heat / 0.5f)
                    : Fx.mix(0xFFFF8A3D, 0xFFFF3B5C, (heat - 0.5f) / 0.5f);
            // Drawn in dp units with the origin at the keyboard's top left, like the lab
            final RectF kb = fx.layout().keyboard;
            c.save();
            c.translate(kb.left, kb.top);
            c.scale(fx.density, fx.density);
            drawHeat(c, fx.settings.s("heat", "style"), heat, col, vis, t, kb.width() / fx.density);
            c.restore();
        }
        return true;
    }

    /** The heat streak's look along the keyboard's top edge. L is the heat level, vis its visibility, w the width in dp. */
    private void drawHeat(Canvas c, String style, float L, int col, float vis, long t, float w) {
        if ("wave".equals(style)) {
            // A wave that grows taller and faster as the heat builds
            final float amp = 2 + 9 * L, k = 0.045f;
            // Accumulated, so the speed changing with the heat doesn't make the wave jump
            if (heatPhAt != 0) heatPh = (heatPh + (t - heatPhAt) / (double) (260 - 150 * L)) % (2 * Math.PI);
            heatPhAt = t;
            final float ph = (float) heatPh;
            Path p = fx.path();
            for (float x = 0; x <= w; x += 4) {
                final float y = 2 + amp * (0.5f + 0.5f * (float) Math.sin(x * k - ph));
                if (x == 0) p.moveTo(x, y);
                else p.lineTo(x, y);
            }
            p.lineTo(w, 0);
            p.lineTo(0, 0);
            p.close();
            c.drawPath(p, fx.fill(Fx.alpha(col, 0.28f * vis)));
            p = fx.path();
            for (float x = 0; x <= w; x += 4) {
                final float y = 2 + amp * (0.5f + 0.5f * (float) Math.sin(x * k - ph));
                if (x == 0) p.moveTo(x, y);
                else p.lineTo(x, y);
            }
            c.drawPath(p, fx.stroke(Fx.alpha(col, 0.95f * vis), 2));
        } else if ("bar".equals(style)) {
            // A progress bar filling left to right, with a bright head
            final float bw = w * L;
            c.drawRect(0, 0, w, 4, fx.fill(Fx.alpha(col, 0.15f * vis)));
            Paint p = fx.fill(0xFFFFFFFF);
            p.setShader(new LinearGradient(0, 0, Math.max(1, bw), 0, Fx.alpha(fx.accent(), 0.9f * vis),
                    Fx.alpha(col, vis), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, 4, p);
            p = fx.fill(0xFFFFFFFF);
            p.setShader(new RadialGradient(bw, 2, 12, Fx.alpha(0xFFFFFFFF, 0.9f * vis), Fx.alpha(col, 0),
                    Shader.TileMode.CLAMP));
            c.drawCircle(bw, 2, 12, p);
        } else if ("circuit".equals(style)) {
            // A trace along the top edge that powers up from both ends toward the middle
            final float seg = 26, half = w / 2, lit = half * L;
            for (int side = -1; side <= 1; side += 2) {
                c.drawPath(tracePath(side, half, half, w, seg), fx.stroke(Fx.alpha(col, 0.18f * vis), 1.5f));
                // A wider, softer stroke under the lit trace stands in for the lab's blur
                final Path lp = tracePath(side, lit, half, w, seg);
                c.drawPath(lp, fx.stroke(Fx.alpha(col, 0.25f * vis), 5));
                c.drawPath(lp, fx.stroke(Fx.alpha(col, 0.95f * vis), 1.5f));
                // Vias that light as the power reaches them, and a spark at the front
                for (float d = seg; d < half; d += seg) {
                    final float x = side < 0 ? d : w - d;
                    c.drawCircle(x, circuitY(x - 1, seg), 2.2f,
                            fx.fill(d <= lit ? Fx.alpha(col, vis) : Fx.alpha(col, 0.2f * vis)));
                }
                final float sx = side < 0 ? lit : w - lit, sy = circuitY(sx, seg);
                final Paint sp = fx.fill(0xFFFFFFFF);
                sp.setShader(new RadialGradient(sx, sy, 8, Fx.alpha(0xFFFFFFFF, vis), Fx.alpha(col, 0),
                        Shader.TileMode.CLAMP));
                c.drawCircle(sx, sy, 8, sp);
            }
        } else if ("embers".equals(style)) {
            // Embers rise off the top edge, more and hotter as the heat builds
            // The lab spawns per 60 Hz frame, so scale the chance by the frame time
            final float dt = emberAt == 0 ? 16.7f : Math.min(50, t - emberAt);
            emberAt = t;
            if (embers.size() < 200 && fx.rnd.nextFloat() < (0.15f + 0.85f * L) * dt / 16.7f) {
                for (int i = 0; i < 1 + (int) (L * 3); i++) {
                    embers.add(new Ember(t, fx.rand(0, w), fx.rand(20, 50 + 60 * L), fx.rand(1, 2.4f), fx.rand(0, 6)));
                }
            }
            for (int i = 0; i < embers.size(); i++) {
                if (t - embers.get(i).t0 >= 900) {
                    embers.remove(i);
                    i--;
                }
            }
            for (int i = 0; i < embers.size(); i++) {
                final Ember q = embers.get(i);
                final float u = (t - q.t0) / 900f, sec = (t - q.t0) / 1000f;
                c.drawCircle(q.x + (float) Math.sin(q.ph + sec * 6) * 4, -q.vy * sec, q.r * (1 - u * 0.5f),
                        fx.fill(Fx.fire(u * 0.8f, (1 - u) * vis)));
            }
            c.drawRect(0, 0, w, 1.5f, fx.fill(Fx.alpha(col, 0.6f * vis)));
        } else {
            final float th = fx.settings.f("heat", "thickness");
            final Paint p = fx.fill(0xFFFFFFFF);
            p.setShader(new LinearGradient(0, 0, 0, th, Fx.alpha(col, 0.6f * vis), Fx.alpha(col, 0),
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, th, p);
            c.drawRect(0, 0, w, 2, fx.fill(Fx.alpha(col, 0.95f * vis)));
        }
    }

    // The circuit trace steps down on every third segment
    private static float circuitY(float x, float seg) {
        return 3 + (((int) Math.floor(x / seg)) % 3 == 1 ? 6 : 0);
    }

    // The circuit trace from one end of the edge out to distance to, in dp
    private Path tracePath(int side, float to, float half, float w, float seg) {
        final Path p = fx.path();
        for (float d = 0; d <= to; d += 2) {
            final float x = side < 0 ? d : w - d;
            if (d == 0) p.moveTo(x, circuitY(x, seg));
            else p.lineTo(x, circuitY(x, seg));
        }
        return p;
    }

    // endregion

    // region Combo counter

    private void comboHit(int amount) {
        if (!fx.on("heat") || !fx.settings.b("heat", "combo")) return;
        final long t = fx.now();
        final int before = comboN;
        if (t - comboLast > fx.D(fx.settings.f("heat", "window"))) comboN = 0;
        comboN += amount;
        comboLast = t;
        comboBumpAt = t;
        if (Fx.crossesTen(before, comboN) && comboN >= Math.round(fx.settings.f("heat", "showAt"))) {
            // A flash at the counter each time it passes a multiple of ten
            final float x = fx.layout().keyboard.right - fx.dp(46), y = fx.layout().keyboard.top - fx.dp(16);
            FxParts.ringPop(fx, x, y, fx.dp(36), fx.S("heat"));
            FxParts.emberBurst(fx, x, y, fx.S("heat"));
        }
        fx.kick();
    }

    @Override
    public boolean drawOver(Canvas c, long t) {
        if (comboN <= 0) return false;
        if (!fx.on("heat") || !fx.settings.b("heat", "combo")) {
            comboN = 0;
            return false;
        }
        final float s = fx.S("heat"), win = fx.D(fx.settings.f("heat", "window")), since = t - comboLast;
        // After the window the combo breaks and fades out
        final float fade = since <= win ? 1 : 1 - Fx.clamp01((since - win) / fx.D(400));
        if (fade <= 0) {
            comboN = 0;
            return false;
        }
        if (comboN < Math.round(fx.settings.f("heat", "showAt"))) return true;
        final float bump = 1 + 0.35f * (1 - Fx.easeOut((t - comboBumpAt) / fx.D(180)));
        final int col = Fx.mix(fx.accent(), 0xFFFF8A3D, comboN / 40f);
        final float x = fx.layout().keyboard.right - fx.dp(46), y = fx.layout().keyboard.top - fx.dp(16);
        if (shownN != comboN) {
            shownN = comboN;
            comboText = "×" + comboN;
        }
        fx.glowText(c, comboText, x, y, 20, bump, fade * s, col, 0);
        // The time left in the combo, as a shrinking bar under the number
        final float left = 1 - Fx.clamp01(since / win);
        c.drawRect(x - fx.dp(22), y + fx.dp(13), x - fx.dp(22) + fx.dp(44) * left, y + fx.dp(15.5f),
                fx.fill(Fx.alpha(col, 0.6f * fade * s)));
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
        final String style = fx.settings.s("wave", "style");
        if ("orbit".equals(style)) {
            waveOrbit(k, keys, s);
            return;
        }
        if ("firework".equals(style)) {
            waveFirework(k, keys, dists, s);
            return;
        }
        if ("firecracker".equals(style)) {
            waveFirecracker(keys, s);
            return;
        }
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

    /** Orbit: a light circles the pressed key once, lighting each neighbor as it passes. */
    private void waveOrbit(final FxKey k, final ArrayList<FxKey> keys, final float s) {
        final long t0 = fx.now();
        final float dur = fx.D(520), rad = Math.max(k.w(), k.h()) * 0.95f;
        final double tau = Math.PI * 2;
        final double[] ang = new double[keys.size()];
        for (int i = 0; i < ang.length; i++) {
            ang[i] = Math.atan2(keys.get(i).cy() - k.cy(), keys.get(i).cx() - k.cx());
        }
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float u = (t - t0) / dur;
                if (u >= 1.4f) return false;
                final double swept = tau * Fx.easeOut(Math.min(1, u));
                final double a = -Math.PI / 2 + swept;
                for (int i = 0; i < ang.length; i++) {
                    // Light a key for a moment once the orbit has swept past its angle
                    final double passed = ((a - ang[i]) % tau + tau) % tau;
                    final double since = swept >= ((ang[i] + Math.PI / 2 + tau) % tau) ? passed : -1;
                    if (since < 0 || since > 1.6) continue;
                    final FxKey n = keys.get(i);
                    fx.roundRect(c, n.left, n.top, n.right, n.bottom, 9,
                            fx.fill(Fx.alpha(fx.accent(), 0.35f * (1 - (float) since / 1.6f) * s)));
                }
                if (u < 1) {
                    final float x = k.cx() + (float) Math.cos(a) * rad, y = k.cy() + (float) Math.sin(a) * rad * 0.8f;
                    final float r = fx.dp(9);
                    final Paint p = fx.fill(0xFFFFFFFF);
                    p.setShader(new RadialGradient(x, y, r, Fx.alpha(0xFFFFFFFF, s), Fx.alpha(fx.accent(), 0),
                            Shader.TileMode.CLAMP));
                    c.drawCircle(x, y, r, p);
                }
                return true;
            }
        });
    }

    /** Firework: sparks shoot from the key to each neighbor and burst there. */
    private void waveFirework(final FxKey k, final ArrayList<FxKey> keys, ArrayList<Float> dists, final float s) {
        final long t0 = fx.now();
        final float fly = fx.D(200), speedK = 1 / Math.max(0.1f, fx.settings.f("wave", "speed"));
        final int n = keys.size();
        final float[] delay = new float[n], hue = new float[n];
        final float[][] rays = new float[n][7];
        for (int i = 0; i < n; i++) {
            // d is in px here, the lab's is in dp
            delay[i] = fx.D(dists.get(i) / fx.density * speedK * 0.4f);
            hue[i] = (Fx.hue(fx.accent()) + fx.rand(-40, 60) + 360) % 360;
            for (int j = 0; j < 7; j++) rays[i][j] = j * (float) Math.PI * 2 / 7 + fx.rand(-0.2f, 0.2f);
        }
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                boolean alive = false;
                for (int i = 0; i < n; i++) {
                    final FxKey q = keys.get(i);
                    final float e = t - t0 - delay[i];
                    if (e < 0) {
                        alive = true;
                        continue;
                    }
                    if (e < fly) {
                        alive = true;
                        final float p = Fx.easeOut(e / fly);
                        c.drawCircle(Fx.lerp(k.cx(), q.cx(), p),
                                Fx.lerp(k.cy(), q.cy(), p) - fx.dp(10) * (float) Math.sin(Math.PI * p), fx.dp(2),
                                fx.fill(Fx.alpha(Fx.hsl(hue[i], 0.95f, 0.72f), s)));
                        continue;
                    }
                    final float u = (e - fly) / fx.D(420);
                    if (u >= 1) continue;
                    alive = true;
                    final float r = fx.dp(4 + 14 * Fx.easeOut(u)), fall = fx.dp(12) * u * u;
                    final Paint sp = fx.stroke(Fx.alpha(Fx.hsl(hue[i], 0.95f, 0.70f), (1 - u) * s), fx.dp(1.6f));
                    sp.setStrokeCap(Paint.Cap.ROUND);
                    for (int j = 0; j < 7; j++) {
                        final float cs = (float) Math.cos(rays[i][j]), sn = (float) Math.sin(rays[i][j]);
                        c.drawLine(q.cx() + cs * r * 0.45f, q.cy() + sn * r * 0.45f + fall,
                                q.cx() + cs * r, q.cy() + sn * r + fall, sp);
                    }
                    sp.setStrokeCap(Paint.Cap.BUTT);
                }
                return alive;
            }
        });
    }

    /** Firecracker: the neighbors pop one after another in a random, rapid string. */
    private void waveFirecracker(ArrayList<FxKey> list, final float s) {
        final long t0 = fx.now();
        final ArrayList<FxKey> order = new ArrayList<>(list);
        Collections.shuffle(order, fx.rnd);
        final int n = order.size();
        final float[] at = new float[n];
        for (int i = 0; i < n; i++) at[i] = fx.D(i * 45 + fx.rand(0, 20));
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                boolean alive = false;
                for (int i = 0; i < n; i++) {
                    final FxKey q = order.get(i);
                    final float u = (t - t0 - at[i]) / fx.D(220);
                    if (u < 0) {
                        alive = true;
                        continue;
                    }
                    if (u >= 1) continue;
                    alive = true;
                    // Fresh jitter and spark angles every frame, so it crackles like the lab
                    final float j = fx.dp((1 - u) * 1.5f), jx = fx.rand(-j, j), jy = fx.rand(-j, j);
                    fx.roundRect(c, q.left + jx, q.top + jy, q.right + jx, q.bottom + jy, 9,
                            fx.fill(Fx.alpha(0xFFFFF5DC, 0.45f * (1 - u) * s)));
                    final Paint p = fx.fill(Fx.fire(u, (1 - u) * s));
                    final float d = fx.dp(6 + 12 * u), sz = fx.dp(1.6f);
                    for (int m = 0; m < 4; m++) {
                        final float a = fx.rand(0, (float) Math.PI * 2);
                        final float sx = q.cx() + (float) Math.cos(a) * d, sy = q.cy() + (float) Math.sin(a) * d;
                        c.drawRect(sx, sy, sx + sz, sy + sz, p);
                    }
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

    /** Water drop: rings ripple out from the touch point, kept inside the key. */
    private void drop(final FxKey k, final float x, final float y) {
        final long t0 = fx.now();
        final float dur = fx.D(fx.settings.f("drop", "duration")), s = fx.S("drop");
        final int n = Math.round(fx.settings.f("drop", "rings"));
        final float maxR = (float) Math.hypot(k.w(), k.h()) * 0.75f;
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = t - t0;
                if (e >= dur) return false;
                c.save();
                c.clipPath(fx.roundRectPath(k.left, k.top, k.right, k.bottom, 9));
                for (int i = 0; i < n; i++) {
                    final float u = (e - i * dur * 0.18f) / (dur * 0.7f);
                    if (u <= 0 || u >= 1) continue;
                    c.drawCircle(x, y, Math.max(0.1f, maxR * Fx.easeOut(u)),
                            fx.stroke(Fx.alpha(fx.accent(), (1 - u) * 0.8f * s), fx.dp(2 * (1 - u) + 0.5f)));
                }
                // A brief bright spot where the drop landed
                if (e < dur * 0.25f) {
                    final float r = fx.dp(10);
                    final Paint p = fx.fill(0xFFFFFFFF);
                    p.setShader(new RadialGradient(x, y, r, Fx.alpha(0xFFFFFFFF, (1 - e / (dur * 0.25f)) * 0.6f * s),
                            Fx.alpha(0xFFFFFFFF, 0), Shader.TileMode.CLAMP));
                    c.drawCircle(x, y, r, p);
                }
                c.restore();
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
