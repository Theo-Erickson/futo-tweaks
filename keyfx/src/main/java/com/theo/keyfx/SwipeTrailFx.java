package com.theo.keyfx;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.Random;

/** Drawn along the finger while swiping a word: key trace, comet, ink brush, circuit, lightning, neon, arrows, water wake, key grow and stardust. */
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
                new Section("circuit", G, 321, "Circuit trail",
                        "The trail snaps to a grid like traces on a circuit board, with vias at the corners and pulses of light running along it.",
                        true, false,
                        Field.choice("angles", "Traces", "45", "45:Straight and 45°", "90:Right angles only"),
                        Field.range("grid", "Grid size", 6, 24, 1, "dp", 12),
                        Field.range("smooth", "Smoothing (fewer corners)", 1, 6, 1, "steps", 2),
                        Field.range("linger", "Linger", 150, 2000, 10, "ms", 700),
                        Field.toggle("pulse", "Pulses of light", true),
                        Field.toggle("stubs", "Side branches", true),
                        Field.strength()),
                new Section("lightning", G, 322, "Lightning",
                        "Jagged arcs jump between the letters you pick, with a live bolt to your finger.",
                        true, false,
                        Field.choice("color", "Color", "electric", "electric:Electric blue", "white:White", "green:Green",
                                "violet:Violet", "accent:Accent", "custom:Custom"),
                        Field.choice("custom", "Custom color", "#FFD24A", "#FFD24A:Gold", "#FF8A3D:Orange", "#FF3B5C:Red",
                                "#7FD8BE:Mint", "#C58AF9:Purple", "#FDD663:Yellow", "#FFFFFF:White").when("color", "custom"),
                        Field.range("jag", "Jaggedness", 2, 16, 0.5f, "dp", 7),
                        Field.toggle("branches", "Side forks", true),
                        Field.range("linger", "Linger", 150, 1500, 10, "ms", 450),
                        Field.strength()),
                new Section("neon", G, 323, "Neon tube", "A double-line glow like a bent neon sign, with a little flicker.",
                        true, false,
                        Field.choice("color", "Color", "pink", "pink:Pink", "cyan:Cyan", "lime:Lime", "rainbow:Rainbow", "accent:Accent"),
                        Field.range("length", "Tube length", 120, 1200, 10, "ms", 420),
                        Field.range("flicker", "Flicker", 0, 1, 0.05f, "", 0.35f),
                        Field.strength()),
                new Section("arrows", G, 324, "Arrow trail", "Chevrons along the path, pointing the way your finger went.",
                        true, false,
                        Field.choice("color", "Color", "accent", "accent:Accent", "white:White"),
                        Field.range("spacing", "Spacing", 12, 40, 1, "dp", 22),
                        Field.range("size", "Size", 3, 12, 0.5f, "dp", 6),
                        Field.range("length", "Length", 150, 1400, 10, "ms", 520),
                        Field.strength()),
                new Section("water", G, 325, "Water wake",
                        "Like dragging a finger through a puddle: ripples spread from the path and a wet streak fades.",
                        true, false,
                        Field.choice("color", "Color", "water", "water:Water", "accent:Accent"),
                        Field.range("spacing", "Ripple spacing", 8, 40, 1, "dp", 16),
                        Field.range("size", "Ripple size", 8, 40, 1, "dp", 18),
                        Field.range("life", "Lifetime", 300, 1600, 10, "ms", 800),
                        Field.strength()),
                new Section("grow", G, 326, "Key grow", "Each key swells as the swipe passes over it, then settles back.",
                        true, false,
                        Field.range("scale", "Grow", 1.05f, 1.6f, 0.01f, "×", 1.25f),
                        Field.range("duration", "Settle time", 120, 900, 10, "ms", 320),
                        Field.strength()).requires(KeyFx.CAP_KEY_TRANSFORM),
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
    // Circuit trail: grid-snapped points (x, y, time), side branches (x, y, x2, y2, time) and pulse start times
    private final ArrayList<float[]> cPts = new ArrayList<>();
    private final ArrayList<float[]> cStubs = new ArrayList<>();
    private final float[] cPulses = new float[64];
    private int cPulseN;
    // Reused while drawing pulses
    private final float[] pulseH = new float[2], pulseT = new float[2];
    private final Matrix pulseM = new Matrix();
    private RadialGradient pulseShader;
    private int pulseCol;
    private float pulseR;
    private float cLastPulse;
    // Lightning: picked letters (x, y, time) and scratch space for the bolts
    private final ArrayList<float[]> picks = new ArrayList<>();
    private final float[] boltBuf = new float[40], forkBuf = new float[40];
    private final int[] boltN = new int[1];
    // Water wake ripples: x, y, time
    private final ArrayList<float[]> ripples = new ArrayList<>();

    /** A key swelling as the swipe crossed it, with the settings it started with. */
    private static final class Swell {
        final FxKey k;
        final long t0;
        final float dur, scale;

        Swell(FxKey k, long t0, float dur, float scale) {
            this.k = k;
            this.t0 = t0;
            this.dur = dur;
            this.scale = scale;
        }
    }

    private final ArrayList<Swell> swells = new ArrayList<>();
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
        cPts.clear();
        cStubs.clear();
        cPulseN = 0;
        cLastPulse = 0;
        picks.clear();
        ripples.clear();
    }

    @Override
    public void onSwipePoint(float x, float y, long t) {
        if (!active) return;
        final float rt = t - base;
        final float[] l = pts.isEmpty() ? null : pts.get(pts.size() - 1);
        final float v = l == null ? 0 : (float) Math.hypot(x - l[0], y - l[1]) / fx.density / Math.max(1, rt - l[2]);
        pts.add(new float[] { x, y, rt, v });
        if (fx.on("circuit")) circuitAdd(x, y, rt);
        if (l != null && fx.on("water")) {
            // Drop a ripple every few dp along the path
            final float[] r = ripples.isEmpty() ? null : ripples.get(ripples.size() - 1);
            if (r == null || Math.hypot(x - r[0], y - r[1]) >= fx.dp(fx.settings.f("water", "spacing"))) {
                ripples.add(new float[] { x, y, rt });
            }
        }
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
        // Only record the swell when the host can scale its keys
        if ((fx.caps & KeyFx.CAP_KEY_TRANSFORM) != 0 && fx.on("grow")) {
            for (int i = 0; i < swells.size(); i++) {
                if (swells.get(i).k == k) {
                    swells.remove(i);
                    i--;
                }
            }
            swells.add(new Swell(k, fx.now(), fx.D(fx.settings.f("grow", "duration")),
                    1 + (fx.settings.f("grow", "scale") - 1) * fx.S("grow")));
            fx.kick();
        }
    }

    @Override
    public void onSwipePick(FxKey k) {
        picks.add(new float[] { k.cx(), k.cy(), fx.now() - base });
    }

    @Override
    public void reset() {
        pts.clear();
        dust.clear();
        cPts.clear();
        cStubs.clear();
        cPulseN = 0;
        picks.clear();
        ripples.clear();
        swells.clear();
        active = false;
    }

    // region Key grow

    /** The swell from 0 to 1 and back, peaking at 22% of the eased progress like the lab. NaN once it is over. */
    private static float swellAmount(Swell w, long elapsed) {
        final float p = elapsed / w.dur;
        if (p >= 1) return Float.NaN;
        final float e = bezier(0.3f, 0.7f, 0.4f, 1f, p);
        return e < 0.22f ? e / 0.22f : 1 - (e - 0.22f) / 0.78f;
    }

    /** CSS cubic-bezier(x1, y1, x2, y2) easing: solves x(u) = p with Newton steps, then returns y(u). */
    private static float bezier(float x1, float y1, float x2, float y2, float p) {
        float u = p;
        for (int i = 0; i < 6; i++) {
            final float v = 1 - u, x = 3 * v * v * u * x1 + 3 * v * u * u * x2 + u * u * u - p;
            final float dx = 3 * v * v * x1 + 6 * v * u * (x2 - x1) + 3 * u * u * (1 - x2);
            if (Math.abs(dx) < 1e-5f) break;
            u = Fx.clamp01(u - x / dx);
        }
        final float v = 1 - u;
        return 3 * v * v * u * y1 + 3 * v * u * u * y2 + u * u * u;
    }

    @Override
    public void look(FxKey k, KeyLook out, long t) {
        for (Swell w : swells) {
            if (w.k != k) continue;
            final float a = swellAmount(w, t - w.t0);
            if (!Float.isNaN(a)) out.scaleBy(1 + (w.scale - 1) * a);
        }
    }

    @Override
    public boolean keysMoving(long t) {
        for (int i = 0; i < swells.size(); i++) {
            if (Float.isNaN(swellAmount(swells.get(i), t - swells.get(i).t0))) {
                swells.remove(i);
                i--;
            }
        }
        return !swells.isEmpty();
    }

    // endregion

    @Override
    public boolean drawOver(Canvas c, long now) {
        if (pts.isEmpty() && dust.isEmpty()) return false;
        final float t = now - base, endRel = endAt - base;
        boolean alive = active;
        if (fx.on("circuit") && drawCircuit(c, t, endRel)) alive = true;
        if (fx.on("lightning") && drawLightning(c, t, endRel)) alive = true;
        if (fx.on("neon") && drawNeon(c, t, endRel)) alive = true;
        if (fx.on("arrows") && drawArrows(c, t)) alive = true;
        if (fx.on("water") && drawWater(c, t)) alive = true;
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
        if (!alive && !active) {
            pts.clear();
            picks.clear();
            ripples.clear();
        }
        return alive;
    }

    // region Circuit trail

    /** The nearest allowed trace direction for a step, or null for no step. */
    private static int[] dirOf(double dx, double dy, boolean eight) {
        if (dx == 0 && dy == 0) return null;
        final double a = Math.atan2(dy, dx), step = eight ? Math.PI / 4 : Math.PI / 2, q = Math.round(a / step) * step;
        return new int[] { (int) Math.round(Math.cos(q)), (int) Math.round(Math.sin(q)) };
    }

    /** The furthest grid point from from along direction d toward (sx, sy). */
    private static float[] circuitProject(float[] from, int[] d, float sx, float sy, float g, float t) {
        final float len = ((sx - from[0]) * d[0] + (sy - from[1]) * d[1]) / (d[0] * d[0] + d[1] * d[1]);
        final float k = Math.max(0, Math.round(len / g)) * g;
        return new float[] { from[0] + d[0] * k, from[1] + d[1] * k, t };
    }

    /** Snaps a finger point onto the grid, extending the straight run or turning with a via. */
    private void circuitAdd(float x, float y, float t) {
        final float g = fx.dp(fx.settings.f("circuit", "grid"));
        final boolean eight = "45".equals(fx.settings.s("circuit", "angles"));
        final float sx = Math.round(x / g) * g, sy = Math.round(y / g) * g;
        if (cPts.isEmpty()) {
            cPts.add(new float[] { sx, sy, t });
            return;
        }
        final float[] last = cPts.get(cPts.size() - 1), corner = cPts.size() > 1 ? cPts.get(cPts.size() - 2) : null;
        final int[] dir = dirOf(sx - last[0], sy - last[1], eight);
        if (dir == null || (Math.abs(sx - last[0]) < g && Math.abs(sy - last[1]) < g)) return;
        final int[] cur = corner != null ? dirOf(last[0] - corner[0], last[1] - corner[1], eight) : null;
        if (cur != null && cur[0] == dir[0] && cur[1] == dir[1]) {
            // Extend the straight run
            cPts.set(cPts.size() - 1, circuitProject(corner, dir, sx, sy, g, t));
            return;
        }
        // Turn: the current end becomes a via; maybe sprout a side branch from it
        final float[] next = circuitProject(last, dir, sx, sy, g, t);
        if (next[0] == last[0] && next[1] == last[1]) return;
        // Smoothing: a new corner needs a run of at least this many grid steps, so small wobbles don't add nodes
        if (cPts.size() > 1 && Math.hypot(next[0] - last[0], next[1] - last[1]) < fx.settings.f("circuit", "smooth") * g) return;
        if (cPts.size() > 1 && fx.settings.b("circuit", "stubs") && fx.rnd.nextFloat() < 0.45f) {
            final float side = fx.rnd.nextFloat() < 0.5f ? 1 : -1, len = g * (2 + (float) Math.floor(fx.rand(0, 3)));
            cStubs.add(new float[] { last[0], last[1], last[0] - dir[1] * side * len, last[1] + dir[0] * side * len, t });
        }
        cPts.add(next);
    }

    private float circuitLength() {
        float len = 0;
        for (int i = 1; i < cPts.size(); i++) {
            len += (float) Math.hypot(cPts.get(i)[0] - cPts.get(i - 1)[0], cPts.get(i)[1] - cPts.get(i - 1)[1]);
        }
        return len;
    }

    /** The point d px along the circuit, written to out. */
    private void circuitPointAt(float d, float[] out) {
        for (int i = 1; i < cPts.size(); i++) {
            final float[] a = cPts.get(i - 1), b = cPts.get(i);
            final float seg = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (d <= seg) {
                out[0] = Fx.lerp(a[0], b[0], seg != 0 ? d / seg : 0);
                out[1] = Fx.lerp(a[1], b[1], seg != 0 ? d / seg : 0);
                return;
            }
            d -= seg;
        }
        final float[] e = cPts.get(cPts.size() - 1);
        out[0] = e[0];
        out[1] = e[1];
    }

    private boolean drawCircuit(Canvas c, float t, float endRel) {
        final int n = cPts.size();
        if (n < 2) return active;
        final float linger = fx.D(fx.settings.f("circuit", "linger")), s = fx.S("circuit");
        final float fade = active ? 1 : 1 - Fx.clamp01((t - endRel) / linger);
        if (fade <= 0) {
            cPts.clear();
            cStubs.clear();
            cPulseN = 0;
            return false;
        }
        final float a = fade * s;
        final int col = fx.accent();
        final Path path = fx.path();
        for (int i = 0; i < n; i++) {
            final float[] p = cPts.get(i);
            if (i == 0) path.moveTo(p[0], p[1]);
            else path.lineTo(p[0], p[1]);
        }
        // Trace: a dark bed under a bright copper-style line
        c.drawPath(path, fx.stroke(Fx.alpha(0xFF000000, 0.35f * a), fx.dp(5)));
        final Paint glow = fx.stroke(Fx.alpha(col, 0.85f * a), fx.dp(2));
        glow.setShadowLayer(fx.dp(6), 0, 0, Fx.alpha(col, 0.8f * a));
        c.drawPath(path, glow);
        for (int i = 0; i < cStubs.size(); i++) {
            final float[] b = cStubs.get(i);
            final Paint sp = fx.stroke(Fx.alpha(col, 0.85f * a), fx.dp(2));
            sp.setShadowLayer(fx.dp(6), 0, 0, Fx.alpha(col, 0.8f * a));
            c.drawLine(b[0], b[1], b[2], b[3], sp);
            final Paint dot = fx.fill(Fx.alpha(col, 0.9f * a));
            dot.setShadowLayer(fx.dp(6), 0, 0, Fx.alpha(col, 0.8f * a));
            c.drawCircle(b[2], b[3], fx.dp(2.2f), dot);
        }
        // Vias at every corner, a pad at the start
        for (int i = 1; i < n - 1; i++) {
            final float[] p = cPts.get(i);
            c.drawCircle(p[0], p[1], fx.dp(3.2f), fx.fill(fx.background()));
            c.drawCircle(p[0], p[1], fx.dp(3.2f), fx.stroke(Fx.alpha(col, 0.95f * a), fx.dp(1.4f)));
        }
        final float[] first = cPts.get(0);
        c.drawRect(first[0] - fx.dp(4), first[1] - fx.dp(4), first[0] + fx.dp(4), first[1] + fx.dp(4),
                fx.fill(Fx.alpha(col, 0.95f * a)));
        // Light pulses run from the start toward the finger
        if (fx.settings.b("circuit", "pulse")) {
            if (active && t - cLastPulse > fx.D(260) && cPulseN < cPulses.length) {
                cPulses[cPulseN++] = t;
                cLastPulse = t;
            }
            final float len = circuitLength(), speed = fx.dp(0.7f) / fx.D(1), r = fx.dp(7), tail = fx.dp(14);
            for (int i = 0; i < cPulseN; i++) {
                if ((t - cPulses[i]) * speed >= len + fx.dp(20)) {
                    System.arraycopy(cPulses, i + 1, cPulses, i, cPulseN - i - 1);
                    cPulseN--;
                    i--;
                }
            }
            // One white-to-clear gradient at the origin, moved to each pulse with a matrix
            if (pulseShader == null || pulseCol != col || pulseR != r) {
                pulseShader = new RadialGradient(0, 0, r, 0xFFFFFFFF, Fx.alpha(col, 0), Shader.TileMode.CLAMP);
                pulseCol = col;
                pulseR = r;
            }
            for (int i = 0; i < cPulseN; i++) {
                final float d = (t - cPulses[i]) * speed;
                circuitPointAt(Math.min(d, len), pulseH);
                circuitPointAt(Math.max(0, Math.min(d, len) - tail), pulseT);
                final Paint g = fx.fill(0xFFFFFFFF);
                g.setShader(pulseShader);
                g.setAlpha(Math.round(255 * Fx.clamp01(0.95f * a)));
                pulseM.setTranslate(pulseH[0], pulseH[1]);
                pulseShader.setLocalMatrix(pulseM);
                c.drawCircle(pulseH[0], pulseH[1], r, g);
                c.drawLine(pulseT[0], pulseT[1], pulseH[0], pulseH[1], fx.stroke(Fx.alpha(0xFFFFFFFF, 0.6f * a), fx.dp(2)));
            }
        }
        return true;
    }

    // endregion

    // region Lightning

    /** A jagged bolt from a to b: midpoint displacement, appended to out (x, y pairs) after n[0] points. */
    private static void bolt(Random r, float ax, float ay, float bx, float by, float jag, int depth, float[] out, int[] n) {
        if (depth == 0) {
            out[n[0] * 2] = bx;
            out[n[0] * 2 + 1] = by;
            n[0]++;
            return;
        }
        final float mx = (ax + bx) / 2 + (r.nextFloat() * 2 - 1) * jag, my = (ay + by) / 2 + (r.nextFloat() * 2 - 1) * jag;
        bolt(r, ax, ay, mx, my, jag / 2, depth - 1, out, n);
        bolt(r, mx, my, bx, by, jag / 2, depth - 1, out, n);
    }

    /** A bolt of n points: a wide colored glow with a thin white core. */
    private static void strokeBolt(KeyFx fx, Canvas c, float[] pts, int n, int col, float a, float width) {
        final Path path = fx.path();
        for (int i = 0; i < n; i++) {
            if (i == 0) path.moveTo(pts[0], pts[1]);
            else path.lineTo(pts[i * 2], pts[i * 2 + 1]);
        }
        final Paint glow = fx.stroke(Fx.alpha(col, 0.75f * a), width * 2.2f);
        glow.setShadowLayer(fx.dp(12), 0, 0, Fx.alpha(col, a));
        c.drawPath(path, glow);
        c.drawPath(path, fx.stroke(Fx.alpha(0xFFFFFFFF, 0.95f * a), width * 0.7f));
    }

    private boolean drawLightning(Canvas c, float t, float endRel) {
        final String mode = fx.settings.s("lightning", "color");
        final int col = "accent".equals(mode) ? fx.accent() : "violet".equals(mode) ? 0xFFB98CFF
                : "white".equals(mode) ? 0xFFF2F6FF : "green".equals(mode) ? 0xFF5CFF9D
                : "custom".equals(mode) ? Fx.parseColor(fx.settings.s("lightning", "custom"), 0xFFFFD24A) : 0xFF7FE3FF;
        final float s = fx.S("lightning"), jag = fx.dp(fx.settings.f("lightning", "jag"));
        final float fade = active ? 1 : 1 - Fx.clamp01((t - endRel) / fx.D(fx.settings.f("lightning", "linger")));
        final int np = picks.size();
        if (fade <= 0 || np == 0) return active;
        final boolean forks = fx.settings.b("lightning", "branches");
        for (int i = 1; i < np; i++) {
            final float[] p0 = picks.get(i - 1), p1 = picks.get(i);
            // Each arc flashes bright as it forms, then settles to a flicker
            final float flash = 1 - Fx.clamp01((t - p1[2]) / fx.D(200));
            final float a = fade * s * (0.45f + 0.55f * flash) * (0.7f + 0.3f * fx.rnd.nextFloat());
            boltBuf[0] = p0[0];
            boltBuf[1] = p0[1];
            boltN[0] = 1;
            bolt(fx.rnd, p0[0], p0[1], p1[0], p1[1], jag * 2, 4, boltBuf, boltN);
            final int n = boltN[0];
            strokeBolt(fx, c, boltBuf, n, col, a, fx.dp(1.3f));
            if (forks && fx.rnd.nextFloat() < 0.35f) {
                final int at = (int) fx.rand(2, n - 2);
                final float fx0 = boltBuf[at * 2], fy0 = boltBuf[at * 2 + 1];
                forkBuf[0] = fx0;
                forkBuf[1] = fy0;
                boltN[0] = 1;
                bolt(fx.rnd, fx0, fy0, fx0 + fx.dp(fx.rand(-28, 28)), fy0 + fx.dp(fx.rand(-28, 28)), jag, 3, forkBuf, boltN);
                strokeBolt(fx, c, forkBuf, boltN[0], col, a * 0.6f, fx.dp(0.8f));
            }
        }
        // A live bolt from the last picked letter to the finger
        final float[] head = pts.isEmpty() ? null : pts.get(pts.size() - 1), last = picks.get(np - 1);
        if (active && head != null && Math.hypot(head[0] - last[0], head[1] - last[1]) > fx.dp(6)) {
            boltBuf[0] = last[0];
            boltBuf[1] = last[1];
            boltN[0] = 1;
            bolt(fx.rnd, last[0], last[1], head[0], head[1], jag * 1.5f, 4, boltBuf, boltN);
            strokeBolt(fx, c, boltBuf, boltN[0], col, s * 0.8f, fx.dp(1));
        }
        return true;
    }

    // endregion

    // region Neon tube

    private boolean drawNeon(Canvas c, float t, float endRel) {
        final String mode = fx.settings.s("neon", "color");
        final float s = fx.S("neon"), win = fx.D(fx.settings.f("neon", "length")), flicker = fx.settings.f("neon", "flicker");
        if (pts.size() < 2) return false;
        // Flicker: the whole tube dips now and then, like a failing transformer
        final float flick = fx.rnd.nextFloat() < flicker * 0.25f ? 0.35f : 1;
        final float head = active ? 1 : Fx.clamp01(1 - (t - endRel) / win);
        boolean any = false;
        if ("rainbow".equals(mode)) {
            // The same glass tube, with the hue running along it and drifting over time
            for (int i = 1; i < pts.size(); i++) {
                final float[] a = pts.get(i - 1), b = pts.get(i);
                if (t - b[2] > win) continue;
                any = true;
                final float hue = (i * 7 + t / 8) % 360, al = flick * head * s;
                final Paint tube = fx.stroke(Fx.alpha(Fx.hsl(hue, 1, 0.62f), 0.55f * al), fx.dp(9));
                tube.setShadowLayer(fx.dp(16), 0, 0, Fx.alpha(Fx.hsl(hue, 1, 0.6f), al));
                c.drawLine(a[0], a[1], b[0], b[1], tube);
                final Paint core = fx.stroke(Fx.alpha(0xFFFFFFFF, 0.85f * al), fx.dp(2));
                core.setShadowLayer(fx.dp(3), 0, 0, Fx.alpha(Fx.hsl(hue, 1, 0.6f), al));
                c.drawLine(a[0], a[1], b[0], b[1], core);
            }
            return any || active;
        }
        final int col = "accent".equals(mode) ? fx.accent() : "cyan".equals(mode) ? 0xFF3FE6FF
                : "lime".equals(mode) ? 0xFFB8FF4A : 0xFFFF4FD8;
        final Path path = fx.path();
        boolean started = false;
        for (int i = 0; i < pts.size(); i++) {
            final float[] p = pts.get(i);
            if (t - p[2] > win) continue;
            any = true;
            if (!started) {
                path.moveTo(p[0], p[1]);
                started = true;
            } else {
                path.lineTo(p[0], p[1]);
            }
        }
        if (started) {
            final Paint tube = fx.stroke(Fx.alpha(col, 0.55f * flick * head * s), fx.dp(9));
            tube.setShadowLayer(fx.dp(18), 0, 0, Fx.alpha(col, flick * head * s));
            c.drawPath(path, tube);
            // Two thin bright cores side by side read as glass tubing
            final Paint core = fx.stroke(Fx.alpha(0xFFFFFFFF, 0.9f * flick * head * s), fx.dp(2));
            core.setShadowLayer(fx.dp(4), 0, 0, Fx.alpha(col, flick * head * s));
            c.drawPath(path, core);
        }
        return any || active;
    }

    // endregion

    // region Arrow trail

    /** Chevrons placed along the path, each pointing the way the finger was moving. */
    private boolean drawArrows(Canvas c, float t) {
        if (pts.size() < 2) return active;
        final float s = fx.S("arrows"), win = fx.D(fx.settings.f("arrows", "length"));
        final float spacing = fx.dp(fx.settings.f("arrows", "spacing")), size = fx.dp(fx.settings.f("arrows", "size"));
        final int col = "white".equals(fx.settings.s("arrows", "color")) ? 0xFFFFFFFF : fx.accent();
        float carry = 0;
        boolean any = false;
        for (int i = 1; i < pts.size(); i++) {
            final float[] a = pts.get(i - 1), b = pts.get(i);
            final float len = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (len == 0) continue;
            final float ang = (float) Math.atan2(b[1] - a[1], b[0] - a[0]), cs = (float) Math.cos(ang), sn = (float) Math.sin(ang);
            float d = spacing - carry;
            while (d <= len) {
                final float age = t - Fx.lerp(a[2], b[2], d / len);
                if (age < win) {
                    any = true;
                    final float f = 1 - age / win, x = Fx.lerp(a[0], b[0], d / len), y = Fx.lerp(a[1], b[1], d / len);
                    final float z = size * (0.6f + 0.4f * f);
                    // (-z, -z), (0, 0), (-z, z) turned to face the way the finger went
                    final Path path = fx.path();
                    path.moveTo(x + (-z * cs + z * sn), y + (-z * sn - z * cs));
                    path.lineTo(x, y);
                    path.lineTo(x + (-z * cs - z * sn), y + (-z * sn + z * cs));
                    c.drawPath(path, fx.stroke(Fx.alpha(col, 0.9f * f * s), fx.dp(2)));
                }
                d += spacing;
            }
            carry = (carry + len) % spacing;
        }
        return any || active;
    }

    // endregion

    // region Water wake

    /** A wet streak behind the finger, and ripples spreading from points along the path. */
    private boolean drawWater(Canvas c, float t) {
        final float s = fx.S("water"), life = fx.D(fx.settings.f("water", "life")), size = fx.dp(fx.settings.f("water", "size"));
        final int col = "accent".equals(fx.settings.s("water", "color")) ? fx.accent() : 0xFF8FD0FF;
        final float streak = life * 0.6f;
        float[] prev = null;
        int recent = 0;
        for (int i = 0; i < pts.size(); i++) {
            final float[] p = pts.get(i);
            if (t - p[2] >= streak) continue;
            recent++;
            if (prev != null) {
                final float f = 1 - (t - p[2]) / streak;
                c.drawLine(prev[0], prev[1], p[0], p[1], fx.stroke(Fx.alpha(col, 0.18f * f * s), fx.dp(10 * f + 2)));
            }
            prev = p;
        }
        for (int i = 0; i < ripples.size(); i++) {
            final float[] q = ripples.get(i);
            final float u = (t - q[2]) / life;
            if (u >= 1) {
                ripples.remove(i);
                i--;
                continue;
            }
            for (int k = 0; k < 2; k++) {
                final float uu = u - k * 0.18f;
                if (uu <= 0) continue;
                final float r = size * Fx.easeOut(uu);
                c.drawOval(fx.rect(q[0] - r, q[1] - r * 0.55f, q[0] + r, q[1] + r * 0.55f),
                        fx.stroke(Fx.alpha(col, 0.6f * (1 - uu) * s), fx.dp(1.4f)));
            }
        }
        return active || !ripples.isEmpty() || recent > 1;
    }

    // endregion

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
