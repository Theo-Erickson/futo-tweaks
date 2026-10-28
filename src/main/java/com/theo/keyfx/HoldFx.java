package com.theo.keyfx;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;

/** Long press charge (liquid, ring, orbit, fuse or clock), the accent popup layout, and the spacebar trackpad. */
final class HoldFx extends FxModule {
    private static final String G = "Long press and spacebar";

    static Section[] sections() {
        return new Section[] {
                new Section("ring", G, 600, "Hold charge", "Fills while you hold a key that has a long-press action.",
                        true, true,
                        Field.choice("style", "Style", "liquid", "liquid:Liquid fill", "ring:Ring",
                                "orbit:Orbiting sparks", "fuse:Burning fuse", "clock:Clock wipe"),
                        Field.range("appear", "Appears after", 0, 80, 1, "%", 35),
                        Field.range("thickness", "Ring thickness", 1, 8, 0.5f, "dp", 3).when("style", "ring"),
                        Field.range("wave", "Liquid wave", 0, 8, 0.25f, "dp", 3).when("style", "liquid"),
                        Field.strength()),
                // Read by hosts that lay out the popup themselves; keyfx draws nothing for it
                new Section("popup", G, 610, "Accent popup", "How the accents lay out around a held key.",
                        false, true,
                        Field.choice("style", "Layout", "bloom", "bloom:Bloom arc", "wheel:Wheel", "column:Column",
                                "grid:Grid", "row:Row"),
                        Field.range("radius", "Radius", 40, 120, 1, "dp", 70).when("style", "bloom", "wheel"))
                        .requires(KeyFx.CAP_POPUP_LAYOUT),
                new Section("trackpad", G, 620, "Spacebar trackpad",
                        "While the spacebar moves the cursor, the keys fade back behind a pattern that shows the cursor moving. "
                                + "Line rails step with each line and letter the cursor moves.", true, true,
                        Field.choice("look", "Look", "rails", "rails:Line rails", "dots:Dot field", "fixed:Still grid",
                                "ripple:Sonar", "glass:Spotlight"),
                        Field.range("dim", "Dim keys", 0, 0.9f, 0.01f, "", 0.6f),
                        Field.range("labels", "Fade labels", 0, 1, 0.01f, "", 0.85f).requires(KeyFx.CAP_KEY_TRANSFORM),
                        Field.range("grid", "Pattern", 0, 1, 0.01f, "", 0.5f).whenNot("look", "glass"),
                        Field.range("halo", "Finger halo", 0, 60, 1, "dp", 26),
                        Field.toggle("steps", "Pulse on each cursor step", true),
                        Field.toggle("warp", "Warp streaks in the direction you move", false),
                        Field.toggle("chevrons", "Arrows pointing where the cursor goes", false),
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
        // Random values for the fuse's sparkle, picked once when the hold starts
        final float[] sparkX = new float[48], sparkY = new float[48], sparkHeat = new float[48];
        boolean cancelled;

        Charge(FxKey k, float x, long timeout) {
            this.k = k;
            this.timeout = timeout;
            if ("fuse".equals(fx.settings.s("ring", "style"))) for (int i = 0; i < 48; i++) {
                sparkX[i] = fx.dp(fx.rand(-6, 6));
                sparkY[i] = fx.dp(fx.rand(-6, 6));
                sparkHeat[i] = fx.rand(0, 1);
            }
            final boolean space = k.type == FxKey.SPACE;
            cx = space && !Float.isNaN(x) ? x : k.cx();
            cy = k.cy();
            rad = space ? k.h() * 0.6f : Math.max(k.w(), k.h()) * 0.55f;
        }

        @Override
        public boolean draw(Canvas c, long t) {
            if (cancelled) return false;
            final float s = fx.S("ring"), e = t - t0;
            final String style = fx.settings.s("ring", "style");
            final boolean liquid = "liquid".equals(style);
            if (e >= timeout) {
                if (liquid) splash(k, s);
                else if ("fuse".equals(style)) {
                    FxParts.emberBurst(fx, k.cx(), k.top, s);
                    FxParts.ringPop(fx, cx, cy, rad * 1.5f, s);
                } else FxParts.ringPop(fx, cx, cy, rad * 1.7f, s);
                charge = null;
                return false;
            }
            final float appear = timeout * fx.settings.f("ring", "appear") / 100;
            if (e < appear) return true;
            final float p = (e - appear) / (timeout - appear), fadeIn = Fx.clamp01((e - appear) / 80);
            if ("orbit".equals(style)) {
                // Sparks circle the key, faster and brighter as the hold charges
                final float sr = fx.dp(6 + 3 * p);
                for (int i = 0; i < 3; i++) {
                    final double a = -Math.PI / 2 + (t - t0) / fx.D(260 - 160 * p) + i * Math.PI * 2 / 3;
                    final float x = cx + (float) Math.cos(a) * rad, y = cy + (float) Math.sin(a) * rad * 0.85f;
                    final Paint g = fx.fill(0xFFFFFFFF);
                    g.setShader(new RadialGradient(x, y, sr, Fx.alpha(0xFFFFFFFF, fadeIn * s),
                            Fx.alpha(fx.accent(), 0), Shader.TileMode.CLAMP));
                    c.drawCircle(x, y, sr, g);
                }
            } else if ("fuse".equals(style)) {
                // A spark burns along the key's outline; the burnt part glows behind it
                final float per = 2 * (k.w() + k.h()), start = k.w() / 2, burnt = per * p;
                final Path path = fx.path();
                for (float d = 0; d <= burnt; d += fx.dp(3)) {
                    final float[] q = edgePoint(k, start + d);
                    if (d == 0) path.moveTo(q[0], q[1]);
                    else path.lineTo(q[0], q[1]);
                }
                c.drawPath(path, fx.stroke(Fx.alpha(0xFFFF8A3D, 0.8f * fadeIn * s), fx.dp(2)));
                final float[] sp = edgePoint(k, start + burnt);
                c.drawCircle(sp[0], sp[1], fx.dp(2.5f), fx.fill(Fx.alpha(0xFFFFF0B4, fadeIn * s)));
                final int base = (int) (((t - t0) / 40) % 16) * 3;
                for (int i = 0; i < 3; i++) {
                    final float px = sp[0] + sparkX[base + i], py = sp[1] + sparkY[base + i], sz = fx.dp(1.5f);
                    c.drawRect(px, py, px + sz, py + sz, fx.fill(Fx.fire(sparkHeat[base + i], fadeIn * s)));
                }
            } else if ("clock".equals(style)) {
                // A clock hand sweeps around, wiping the key with light
                c.save();
                c.clipPath(fx.roundRectPath(k.left, k.top, k.right, k.bottom, 9));
                final float r = (float) Math.hypot(k.w(), k.h()), kx = k.cx(), ky = k.cy();
                final Path wedge = fx.path();
                wedge.moveTo(kx, ky);
                wedge.arcTo(fx.rect(kx - r, ky - r, kx + r, ky + r), -90, 360 * p);
                wedge.close();
                c.drawPath(wedge, fx.fill(Fx.alpha(fx.accent(), 0.4f * fadeIn * s)));
                final double ha = -Math.PI / 2 + Math.PI * 2 * p;
                c.drawLine(kx, ky, kx + (float) Math.cos(ha) * r, ky + (float) Math.sin(ha) * r,
                        fx.stroke(Fx.alpha(0xFFFFFFFF, 0.8f * fadeIn * s), fx.dp(1.5f)));
                c.restore();
            } else if ("ring".equals(style)) {
                final float th = fx.dp(fx.settings.f("ring", "thickness"));
                c.drawCircle(cx, cy, rad, fx.stroke(Fx.alpha(fx.accent(), 0.18f * fadeIn * s), th));
                final RectF oval = fx.rect(cx - rad, cy - rad, cx + rad, cy + rad);
                c.drawArc(oval, -90, 360 * p, false, fx.stroke(Fx.alpha(fx.accent(), 0.9f * fadeIn * s), th));
            } else {
                // Liquid: the key fills from the bottom with a moving wave on top
                c.save();
                c.clipPath(fx.roundRectPath(k.left, k.top, k.right, k.bottom, 9));
                final float level = k.top + k.h() * (1 - p), ph = (t - t0) / 140f;
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
    // Smoothed finger velocity in dp per ms, and when the finger last moved
    private float vx, vy;
    private long moveAt;
    // Rail anchors: where the drag began, ratcheting by one line or letter per cursor step; d* ease toward them
    private float ax, ay, dax, day;
    // When the trackpad opened; effect clocks run from here so floats stay precise on long uptimes
    private long tpAt;
    // Warp streaks (x, y, start, ux, uy, length), a fixed pool of which streakCount are live, and step pulses (start, dx, dy, x, y)
    private final float[][] streaks = new float[60][6];
    private int streakCount;
    private static final float[] GLASS_STOPS = { 20f / 120f, 1 };
    private final int[] glassColors = new int[2];
    private final ArrayList<float[]> pulses = new ArrayList<float[]>();
    // Random values for new streaks, picked once when the trackpad opens
    private final float[] streakX = new float[128], streakY = new float[128];
    private int streakAt;

    @Override
    public void onCursorMode(boolean on, float x, float y) {
        cancelCharge();
        target = on && fx.on("trackpad") ? 1 : 0;
        if (on) {
            tpX = x;
            tpY = y;
            tpAt = fx.now();
            vx = vy = 0;
            ax = dax = x;
            ay = day = y;
            streakCount = 0;
            pulses.clear();
            for (int i = 0; i < streakX.length; i++) {
                streakX[i] = fx.rand(0, 1);
                streakY[i] = fx.rand(0, 1);
            }
        }
        fx.kick();
    }

    @Override
    public void onCursorMove(float x, float y) {
        // Smoothed finger velocity, for the streaks and arrows
        final long t = fx.now();
        final float dt = Math.max(8, t - (moveAt != 0 ? moveAt : t - 16)), unit = fx.dp(1);
        vx = vx * 0.7f + ((x - tpX) / unit / dt) * 0.3f;
        vy = vy * 0.7f + ((y - tpY) / unit / dt) * 0.3f;
        moveAt = t;
        tpX = x;
        tpY = y;
        if (level > 0) fx.kick();
    }

    @Override
    public void onCursorStep(int dx, int dy) {
        if (target <= 0 || !fx.on("trackpad")) return;
        final int sx = Integer.signum(dx), sy = Integer.signum(dy);
        ax += sx * fx.dp(14);
        ay += sy * fx.dp(24);
        if (fx.settings.b("trackpad", "steps")) pulses.add(new float[] { fx.now() - tpAt, sx, sy, tpX, tpY });
        fx.kick();
    }

    @Override
    public boolean drawUnder(Canvas c, long t) {
        // The first frame of a fade counts as one frame, and a pause can't make it jump
        final float dt = last != 0 ? Math.min(64, t - last) : 16;
        last = t;
        level = level < target ? Math.min(1, level + dt / 150) : Math.max(target, level - dt / 200);
        if (level <= 0 && target <= 0) {
            last = 0;
            return false;
        }
        final float s = fx.S("trackpad"), L = level;
        final RectF kb = fx.layout().keyboard;
        final float dim = fx.settings.f("trackpad", "dim");
        final int dimColor = fx.dark() ? 0xFF0A0B0E : 0xFFE4E7EC;
        final float dimA = dim * L * s;
        if ("glass".equals(fx.settings.s("trackpad", "look"))) {
            final Paint g = fx.fill(0xFFFFFFFF);
            glassColors[0] = Fx.alpha(dimColor, dimA * 0.15f);
            glassColors[1] = Fx.alpha(dimColor, dimA);
            g.setShader(new RadialGradient(tpX, tpY, fx.dp(120), glassColors, GLASS_STOPS, Shader.TileMode.CLAMP));
            c.drawRect(kb, g);
        } else {
            c.drawRect(kb, fx.fill(Fx.alpha(dimColor, dimA)));
        }
        // Ease the rail anchors toward their stepped positions, so the ratchet slides rather than jumps
        dax += (ax - dax) * 0.35f;
        day += (ay - day) * 0.35f;
        drawLook(c, t, kb, L, s);

        final float halo = fx.dp(fx.settings.f("trackpad", "halo"));
        if (halo > 0) {
            final Paint g = fx.fill(0xFFFFFFFF);
            g.setShader(new RadialGradient(tpX, tpY, halo * 1.6f, Fx.alpha(fx.accent(), 0.55f * L * s),
                    Fx.alpha(fx.accent(), 0), Shader.TileMode.CLAMP));
            c.drawCircle(tpX, tpY, halo * 1.6f, g);
            c.drawCircle(tpX, tpY, halo, fx.stroke(Fx.alpha(fx.accent(), 0.5f * L * s), fx.dp(1.5f)));
        }
        final float speed = (float) Math.hypot(vx, vy);
        final boolean still = t - moveAt > 120;
        final float rel = t - tpAt;
        final float ux = speed > 0 ? vx / speed : 0, uy = speed > 0 ? vy / speed : 0;
        if (fx.settings.b("trackpad", "warp")) {
            // Streaks race past against the direction of travel, faster and longer the faster you drag
            if (!still && speed > 0.05f && streakCount < streaks.length) {
                for (int i = 0; i < 2 && streakCount < streaks.length; i++) {
                    final float rx = streakX[streakAt % streakX.length], ry = streakY[streakAt % streakY.length];
                    streakAt++;
                    final float[] q = streaks[streakCount++];
                    q[0] = kb.left + rx * kb.width();
                    q[1] = kb.top + ry * kb.height();
                    q[2] = rel;
                    q[3] = ux;
                    q[4] = uy;
                    q[5] = fx.dp(20 + speed * 60);
                }
            }
            final float life = fx.D(380);
            for (int i = streakCount - 1; i >= 0; i--) {
                final float[] q = streaks[i];
                final float u = (rel - q[2]) / life;
                if (u >= 1) {
                    // Swap the last live streak into this slot
                    streaks[i] = streaks[streakCount - 1];
                    streaks[--streakCount] = q;
                    continue;
                }
                final float d = u * fx.dp(140), x = q[0] - q[3] * d, y = q[1] - q[4] * d;
                c.drawLine(x, y, x - q[3] * q[5], y - q[4] * q[5],
                        fx.stroke(Fx.alpha(fx.accent(), 0.55f * (1 - u) * L * s), fx.dp(1.5f)));
            }
        }
        if (fx.settings.b("trackpad", "chevrons") && !still && speed > 0.03f) {
            // Three arrows ahead of the finger, rippling outward in the travel direction
            final float ang = (float) Math.toDegrees(Math.atan2(uy, ux));
            for (int i = 0; i < 3; i++) {
                final float ph = ((rel / fx.D(300)) + i / 3f) % 1, d = fx.dp(34) + ph * fx.dp(40);
                c.save();
                c.translate(tpX + ux * d, tpY + uy * d);
                c.rotate(ang);
                chevron(c, fx.stroke(Fx.alpha(fx.accent(), (1 - ph) * 0.9f * L * s), fx.dp(2.5f)), -5, 4, 8);
                c.restore();
            }
        }
        return level != target || target > 0;
    }

    /** The trackpad's pattern, by look. Every look stays put under the finger. */
    private void drawLook(Canvas c, long t, RectF kb, float L, float s) {
        final float rel = t - tpAt;
        final String look = fx.settings.s("trackpad", "look");
        final float a = fx.settings.f("trackpad", "grid") * L * s;
        final int col = fx.accent();
        final float fx0 = tpX, fy0 = tpY;
        if ("fixed".equals(look)) {
            // A still grid; the lines near the finger light up
            final float step = fx.dp(22);
            for (float x = kb.left + step / 2; x < kb.right; x += step) {
                c.drawLine(x, kb.top, x, kb.bottom,
                        fx.stroke(Fx.alpha(col, (0.12f + 0.75f * near(Math.abs(x - fx0), fx.dp(70))) * a), fx.dp(1)));
            }
            for (float y = kb.top + step / 2; y < kb.bottom; y += step) {
                c.drawLine(kb.left, y, kb.right, y,
                        fx.stroke(Fx.alpha(col, (0.12f + 0.75f * near(Math.abs(y - fy0), fx.dp(70))) * a), fx.dp(1)));
            }
        } else if ("dots".equals(look)) {
            // A still dot matrix that swells and brightens around the finger
            final float step = fx.dp(18);
            for (float y = kb.top + step / 2; y < kb.bottom; y += step) {
                for (float x = kb.left + step / 2; x < kb.right; x += step) {
                    final float n = near((float) Math.hypot(x - fx0, y - fy0), fx.dp(90));
                    c.drawCircle(x, y, fx.dp(1.1f + 2.4f * n * n), fx.fill(Fx.alpha(col, (0.18f + 0.82f * n) * a)));
                }
            }
        } else if ("ripple".equals(look)) {
            // Sonar: rings keep pulsing out from the finger
            for (int i = 0; i < 4; i++) {
                final float r = ((rel / fx.D(14)) + i * 30) % 120;
                c.drawCircle(fx0, fy0, fx.dp(r), fx.stroke(Fx.alpha(col, near(r, 120) * 0.8f * a), fx.dp(1.5f)));
            }
        } else if (!"glass".equals(look)) {
            // Line rails: one rail per text line, spaced like the cursor steps; the rail under the
            // finger is the current line, with a tick for every letter step along it
            final float lineH = fx.dp(24), charW = fx.dp(14);
            final float first = day - (float) Math.ceil((day - kb.top) / lineH) * lineH;
            float active = Float.NaN, best = Float.MAX_VALUE;
            for (float y = first; y < kb.bottom; y += lineH) {
                final float d = Math.abs(y - fy0);
                if (d < best) {
                    best = d;
                    active = y;
                }
            }
            for (float y = first; y < kb.bottom; y += lineH) {
                if (y < kb.top) continue;
                final boolean on = y == active;
                c.drawLine(kb.left + fx.dp(8), y, kb.right - fx.dp(8), y, fx.stroke(Fx.alpha(col,
                        (on ? 0.95f : 0.16f + 0.3f * near(Math.abs(y - fy0), fx.dp(80))) * a), fx.dp(on ? 2 : 1)));
            }
            if (!Float.isNaN(active)) {
                final float firstX = dax - (float) Math.ceil((dax - kb.left) / charW) * charW;
                for (float x = firstX; x < kb.right; x += charW) {
                    final float n = near(Math.abs(x - fx0), fx.dp(110));
                    if (n <= 0) continue;
                    c.drawLine(x, active - fx.dp(3 + 3 * n), x, active + fx.dp(3 + 3 * n),
                            fx.stroke(Fx.alpha(col, 0.85f * n * a), fx.dp(1.5f)));
                }
            }
        }
        // Step pulses: a flash in the direction the cursor just moved
        if (fx.settings.b("trackpad", "steps")) {
            final float life = fx.D(320);
            for (int i = pulses.size() - 1; i >= 0; i--) {
                final float[] q = pulses.get(i);
                final float u = (rel - q[0]) / life;
                if (u >= 1) {
                    pulses.remove(i);
                    continue;
                }
                final float d = fx.dp(10) + fx.dp(26) * Fx.easeOut(u);
                c.save();
                c.translate(q[3] + q[1] * d, q[4] + q[2] * d);
                c.rotate((float) Math.toDegrees(Math.atan2(q[2], q[1])));
                chevron(c, fx.stroke(Fx.alpha(col, (1 - u) * L * s), fx.dp(2.5f)), -4, 3, 7);
                c.restore();
            }
        }
    }

    /** An arrowhead pointing along +x: back and tip in dp, half is its half height in dp. */
    private void chevron(Canvas c, Paint p, float back, float tip, float half) {
        final Path path = fx.path();
        path.moveTo(fx.dp(back), -fx.dp(half));
        path.lineTo(fx.dp(tip), 0);
        path.lineTo(fx.dp(back), fx.dp(half));
        c.drawPath(path, p);
    }

    private static float near(float d, float r) {
        return Math.max(0, 1 - d / r);
    }

    // Reused result of edgePoint, so sampling an outline allocates nothing
    private final float[] edge = new float[2];

    /** A point on the key's outline, walking clockwise from the top-left corner. */
    private float[] edgePoint(FxKey k, float d) {
        final float w = k.w(), h = k.h(), per = 2 * (w + h);
        d = ((d % per) + per) % per;
        if (d < w) {
            edge[0] = k.left + d;
            edge[1] = k.top;
        } else if (d < w + h) {
            edge[0] = k.right;
            edge[1] = k.top + d - w;
        } else if (d < 2 * w + h) {
            edge[0] = k.right - (d - w - h);
            edge[1] = k.bottom;
        } else {
            edge[0] = k.left;
            edge[1] = k.bottom - (d - 2 * w - h);
        }
        return edge;
    }

    @Override
    public void look(FxKey k, KeyLook out, long t) {
        if (level > 0) out.fadeLabel(1 - fx.settings.f("trackpad", "labels") * level);
    }

    @Override
    public boolean keysMoving(long t) {
        return level != target;
    }

    // endregion

    @Override
    public void reset() {
        cancelCharge();
        level = target = 0;
        vx = vy = 0;
        moveAt = 0;
        streakCount = 0;
        pulses.clear();
        last = 0;
    }
}
