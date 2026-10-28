package com.theo.keyfx;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.ArrayList;

/**
 * Holding backspace, in two layers: what the key turns into while held (black hole, lava, portal,
 * a box that opens, a hole it fades into), and how each deleted letter gets to it (swirl, vacuum curves, tractor beam, or one press each).
 */
final class DeleteHoldFx extends FxModule {

    static Section[] sections() {
        return new Section[] {
                new Section("delHold", "Special keys", 230, "Backspace hold",
                        "Two layers: what the key turns into while held, and how each deleted letter gets to it.",
                        true, true,
                        Field.choice("button", "Key while held", "blackhole", "blackhole:Black hole", "lava:Lava rumble",
                                "box:Opens like a box", "void:Fades into a hole", "portal:Portal", "none:None"),
                        Field.choice("letters", "Deleted letters", "swirl", "swirl:Swirl in", "vacuum:Vacuum on curves",
                                "beam:Tractor beam", "press:One press each"),
                        Field.range("flight", "Letter travel time", 300, 2400, 10, "ms", 1100).whenNot("letters", "press"),
                        Field.range("offset", "Black hole position, key to center", 0, 1, 0.01f, "", 0)
                                .when("button", "blackhole"),
                        Field.range("size", "Black hole size", 16, 60, 1, "dp", 34).when("button", "blackhole"),
                        Field.range("swirl", "Swirl speed", 0.2f, 3, 0.05f, "×", 1).when("letters", "swirl"),
                        Field.toggle("shake", "Shake the key", true).when("button", "lava")
                                .requires(KeyFx.CAP_KEY_TRANSFORM),
                        // The three word fields only change which text the host deletes; keyfx declares them
                        Field.toggle("words", "After a few letters, delete whole words (like the module)", true)
                                .requires(KeyFx.CAP_WORD_DELETE),
                        Field.range("wordsAfter", "Switch to words after", 1, 30, 1, "letters", 8)
                                .when("words", "true").requires(KeyFx.CAP_WORD_DELETE),
                        Field.range("wordPace", "Time per word", 80, 600, 10, "ms", 220)
                                .when("words", "true").requires(KeyFx.CAP_WORD_DELETE),
                        Field.strength()),
        };
    }

    /** One letter on its way to the key. */
    private static final class Flying {
        String ch;
        float sx, sy, d0, a0, c1x, c1y, c2x, c2y;
        long t0;
    }

    private final class Hold extends Effect {
        final FxKey k;
        final String button, letters;
        final long start;
        long end, lastLaunch;
        float jx, jy;
        final ArrayList<Flying> parts = new ArrayList<>();
        final ArrayList<float[]> bubbles = new ArrayList<>(); // x, y, r, t0 (since hold start), speed
        final ArrayList<float[]> motes = new ArrayList<>(); // angle, distance, t0 (since hold start)

        Hold(FxKey k) {
            this.k = k;
            this.button = fx.settings.s("delHold", "button");
            this.letters = fx.settings.s("delHold", "letters");
            this.start = fx.now();
        }

        /** Where letters head: the black hole can sit anywhere from the key to the keyboard's center. */
        float tx() {
            final float o = "blackhole".equals(button) ? fx.settings.f("delHold", "offset") : 0;
            return Fx.lerp(k.cx(), fx.layout().keyboard.centerX(), o);
        }

        float ty() {
            final float o = "blackhole".equals(button) ? fx.settings.f("delHold", "offset") : 0;
            return Fx.lerp(k.cy(), fx.layout().keyboard.centerY(), o);
        }

        @Override
        public boolean draw(Canvas c, long t) {
            final float s = fx.S("delHold");
            final float held = (end != 0 ? end : t) - start;
            final float closing = end != 0 ? Fx.clamp01((t - end) / fx.D(260)) : 0;
            final float tx = tx(), ty = ty(), grow = Fx.clamp01(held / 1400f);

            if ("lava".equals(button) && end == 0) {
                // Shake harder the longer it is held
                final float amp = fx.dp(Math.min(3.5f, held / 350f));
                jx = fx.rand(-amp, amp);
                jy = fx.rand(-amp, amp) * 0.6f;
            }
            if ("blackhole".equals(button)) {
                drawBlackHole(c, tx, ty, fx.dp(Fx.lerp(7, fx.settings.f("delHold", "size"), grow)) * (1 - Fx.easeIn(closing)), t, s);
            } else if ("lava".equals(button)) {
                drawLava(c, t, Math.max(0.15f, grow) * (1 - closing), s);
            } else if ("box".equals(button)) {
                drawBox(c, end != 0 ? 1 - Fx.easeOut(closing) : Fx.easeOut(held / fx.D(200)), s);
            } else if ("void".equals(button)) {
                drawVoid(c, end != 0 ? 1 - Fx.easeOut(closing) : Fx.easeOut(held / fx.D(250)), s);
            } else if ("portal".equals(button)) {
                drawPortal(c, tx, ty, fx.dp(Fx.lerp(10, 26, grow)) * (1 - Fx.easeIn(closing)), t, s);
            }

            final float flight = fx.D(fx.settings.f("delHold", "flight")), swirl = fx.settings.f("delHold", "swirl");
            for (int i = 0; i < parts.size(); i++) {
                final Flying q = parts.get(i);
                // Waiting letters (launch still ahead) sit at their place in the text
                final float u = Math.max(0, (t - q.t0) / flight);
                if (u >= 1) {
                    arrive(tx, ty, s);
                    parts.remove(i);
                    i--;
                    continue;
                }
                float x, y, sc, rot;
                if ("beam".equals(letters)) {
                    // Tractor beam: drawn straight in along a cone of light, wobbling as it goes
                    final float p = Fx.easeIn(u);
                    x = Fx.lerp(q.sx, tx, p) + (float) Math.sin(u * 20) * fx.dp(3) * (1 - u);
                    y = Fx.lerp(q.sy, ty, p);
                    sc = 1 - 0.7f * p;
                    rot = (float) Math.sin(u * 14) * 0.2f;
                    final float nx = -(ty - q.sy), ny = tx - q.sx;
                    final float hy = (float) Math.hypot(nx, ny), nl = hy == 0 ? 1 : hy;
                    final float wv = fx.dp(14 * (1 - p) + 3);
                    final Path cone = fx.path();
                    cone.moveTo(tx, ty);
                    cone.lineTo(x + nx / nl * wv, y + ny / nl * wv);
                    cone.lineTo(x - nx / nl * wv, y - ny / nl * wv);
                    cone.close();
                    c.drawPath(cone, fx.fill(Fx.alpha(fx.accent(), 0.16f * (1 - u * 0.5f) * s)));
                } else if ("swirl".equals(letters)) {
                    final float dist = q.d0 * (float) Math.pow(1 - u, 1.4);
                    final float ang = q.a0 + u * (float) Math.PI * 3 * swirl;
                    x = tx + (float) Math.cos(ang) * dist;
                    y = ty + (float) Math.sin(ang) * dist;
                    sc = 1 - u * 0.85f;
                    rot = ang;
                } else {
                    // Cubic Bezier from the letter's place in the text to the key, speeding up as it goes
                    final float p = (float) Math.pow(u, 1.6), m = 1 - p;
                    x = m * m * m * q.sx + 3 * m * m * p * q.c1x + 3 * m * p * p * q.c2x + p * p * p * tx;
                    y = m * m * m * q.sy + 3 * m * m * p * q.c1y + 3 * m * p * p * q.c2y + p * p * p * ty;
                    sc = 1 - 0.7f * p;
                    rot = (q.c1x - q.sx) / fx.density * 0.004f * p;
                }
                fx.glowText(c, q.ch, x, y, 18, sc, (1 - u * 0.5f) * s, fx.accent(), rot);
            }
            return end == 0 || closing < 1 || !parts.isEmpty();
        }

        /** A letter reaching the key: lava burns it up, the others swallow it with a small flash. */
        private void arrive(float x, float y, float s) {
            if ("lava".equals(button)) FxParts.emberBurst(fx, x, y - fx.dp(6), s);
            else if ("box".equals(button) || "void".equals(button)) FxParts.ringPop(fx, x, y, fx.dp(12), s * 0.6f);
            else if ("blackhole".equals(button)) FxParts.ringPop(fx, x, y, fx.dp(8), s * 0.4f);
            else if ("portal".equals(button)) FxParts.ringPop(fx, x, y, fx.dp(14), s * 0.6f);
        }

        /** Portal: spinning rings around a glowing center, with motes pulled inward. */
        private void drawPortal(Canvas c, float x, float y, float r, long t, float s) {
            if (r <= fx.dp(0.5f)) return;
            final float spin = (t - start) / 400f;
            if (end == 0 && fx.rnd.nextFloat() < 0.5f) {
                motes.add(new float[] { fx.rand(0, (float) Math.PI * 2), r * 2.4f, t - start });
            }
            final Paint g = fx.fill(0xFFFFFFFF);
            g.setShader(new RadialGradient(x, y, r,
                    new int[] { Fx.alpha(0xFFFFFFFF, 0.9f * s), Fx.alpha(fx.accent(), 0.8f * s), Fx.alpha(0xFF7A3CFF, 0) },
                    new float[] { 0, 0.35f, 1 }, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, g);
            for (int i = 0; i < 3; i++) {
                final Paint ring = fx.stroke(Fx.alpha(i == 1 ? 0xFFB98CFF : fx.accent(), (0.7f - i * 0.15f) * s), fx.dp(2));
                ring.setPathEffect(new DashPathEffect(new float[] { fx.dp(6 + i * 3), fx.dp(5) },
                        -spin * fx.dp(20 + i * 12) * (i % 2 == 1 ? -1 : 1)));
                c.drawCircle(x, y, r * (1 + i * 0.28f), ring);
                ring.setPathEffect(null);
            }
            final Paint dot = fx.fill(0xFFFFFFFF);
            for (int i = 0; i < motes.size(); i++) {
                final float[] m = motes.get(i);
                final float u = ((t - start) - m[2]) / 700f;
                if (u >= 1) {
                    motes.remove(i);
                    i--;
                    continue;
                }
                final float d = m[1] * (1 - Fx.easeIn(u)), a = m[0] + u * 2;
                dot.setColor(Fx.alpha(fx.accent(), (1 - u) * s));
                final float mx = x + (float) Math.cos(a) * d, my = y + (float) Math.sin(a) * d;
                c.drawRect(mx - fx.dp(1), my - fx.dp(1), mx + fx.dp(1), my + fx.dp(1), dot);
            }
        }

        private void drawBlackHole(Canvas c, float x, float y, float r, long t, float s) {
            if (r <= fx.dp(0.5f)) return;
            final float spin = (t - start) / 1000f * 3 * fx.settings.f("delHold", "swirl");
            c.save();
            c.translate(x, y);
            // Accretion disk: tilted rings rotating around the core
            for (int i = 0; i < 3; i++) {
                c.save();
                c.rotate((float) Math.toDegrees(spin * (1 - i * 0.2f) + i));
                final float rx = r * (1.25f + i * 0.22f), ry = r * (0.42f + i * 0.07f);
                c.drawOval(fx.rect(-rx, -ry, rx, ry), fx.stroke(Fx.alpha(i == 1 ? 0xFFFF9E5E : fx.accent(),
                        (0.55f - i * 0.12f) * s), fx.dp(2 - i * 0.4f)));
                c.restore();
            }
            final Paint core = fx.fill(0xFFFFFFFF);
            core.setShader(new RadialGradient(0, 0, r,
                    new int[] { 0xFF000000, 0xFF000000, 0xF2000000, Fx.alpha(fx.accent(), 0.85f * s), Fx.alpha(fx.accent(), 0) },
                    new float[] { 0, 0.2f, 0.75f, 0.92f, 1 }, Shader.TileMode.CLAMP));
            c.drawCircle(0, 0, r, core);
            c.restore();
        }

        /** The key turns molten: a glowing gradient with bubbles rising and popping. */
        private void drawLava(Canvas c, long t, float I, float s) {
            if (I <= 0.01f) return;
            c.save();
            c.translate(jx, jy);
            c.save();
            c.clipPath(fx.roundRectPath(k.left, k.top, k.right, k.bottom, 9));
            final Paint g = fx.fill(0xFFFFFFFF);
            g.setShader(new LinearGradient(0, k.top, 0, k.bottom,
                    new int[] { Fx.alpha(0xFFFFBE5A, 0.85f * I * s), Fx.alpha(0xFFF05A28, 0.9f * I * s),
                            Fx.alpha(0xFF96141E, 0.95f * I * s) },
                    new float[] { 0, 0.5f, 1 }, Shader.TileMode.CLAMP));
            c.drawRect(k.left, k.top, k.right, k.bottom, g);
            if (end == 0 && fx.rnd.nextFloat() < 0.35f * I) {
                bubbles.add(new float[] { fx.rand(k.left + fx.dp(5), k.right - fx.dp(5)), k.bottom,
                        fx.dp(fx.rand(1.5f, 4)), t - start, fx.dp(fx.rand(20, 45)) });
            }
            final Paint ring = fx.stroke(Fx.alpha(0xFFFFE696, 0.8f * I * s), fx.dp(1));
            for (int i = 0; i < bubbles.size(); i++) {
                final float[] b = bubbles.get(i);
                final float y = b[1] - b[4] * ((t - start) - b[3]) / 1000f;
                if (y < k.top + fx.dp(4)) {
                    bubbles.remove(i);
                    i--;
                    continue;
                }
                c.drawCircle(b[0], y, b[2], ring);
            }
            c.restore();
            final float o = fx.dp(2);
            fx.roundRect(c, k.left - o, k.top - o, k.right + o, k.bottom + o, 11,
                    fx.stroke(Fx.alpha(0xFFFF7832, 0.7f * I * s), fx.dp(2 + 2 * I)));
            c.restore();
        }

        /** The key opens like a box: two lids swing outward from the middle over a dark inside. */
        private void drawBox(Canvas c, float open, float s) {
            if (open <= 0.01f) return;
            final float in = fx.dp(2);
            fx.roundRect(c, k.left + in, k.top + in, k.right - in, k.bottom - in, 7, fx.fill(Fx.alpha(0xFF000000, 0.9f * open)));
            fx.roundRect(c, k.left + in, k.top + in, k.right - in, k.bottom - in, 7,
                    fx.stroke(Fx.alpha(fx.accent(), 0.35f * open * s), fx.dp(1)));
            // Lids foreshorten as they open, lifting slightly off the keyboard
            final float fw = k.w() / 2 * (float) Math.cos(open * Math.PI * 0.42), lift = fx.dp(7) * open;
            for (int side = -1; side <= 1; side += 2) {
                final float x = side < 0 ? k.left - lift * 0.6f : k.right - fw + lift * 0.6f;
                fx.roundRect(c, x, k.top - lift, x + fw, k.bottom - lift, 6, fx.fill(fx.keyFill()));
                fx.roundRect(c, x, k.top - lift, x + fw, k.bottom - lift, 6, fx.fill(Fx.alpha(0xFFFFFFFF, 0.1f * open)));
                fx.roundRect(c, x, k.top - lift, x + fw, k.bottom - lift, 6,
                        fx.stroke(Fx.alpha(fx.accent(), 0.6f * open * s), fx.dp(1.2f)));
            }
        }

        /** The key fades away into a soft-edged hole in the keyboard, and back out on release. */
        private void drawVoid(Canvas c, float a, float s) {
            if (a <= 0.01f) return;
            final float R = Math.max(k.w(), k.h()) * 0.62f * a;
            c.save();
            c.translate(k.cx(), k.cy());
            c.scale(1, 0.8f);
            final Paint g = fx.fill(0xFFFFFFFF);
            g.setShader(new RadialGradient(0, 0, R,
                    new int[] { Fx.alpha(0xFF000000, 0.95f * Math.min(1, s)), Fx.alpha(0xFF000000, 0.85f * Math.min(1, s)), 0 },
                    new float[] { 0, 0.7f, 1 }, Shader.TileMode.CLAMP));
            c.drawCircle(0, 0, R, g);
            c.drawCircle(0, 0, R * 0.82f, fx.stroke(Fx.alpha(fx.accent(), 0.35f * a * s), fx.dp(1) / 0.9f));
            c.restore();
        }
    }

    private Hold hold;

    DeleteHoldFx(KeyFx fx) {
        super(fx);
    }

    @Override
    public void onDeleteHoldStart(FxKey k) {
        if (!fx.on("delHold")) return;
        hold = new Hold(k);
        fx.add(hold);
    }

    @Override
    public void onDeleteHoldLetter(FxLetter L) {
        final Hold h = hold;
        if (h == null) return;
        if ("press".equals(h.letters)) {
            // As if backspace were tapped once for this letter
            FxParts.pop(fx, h.k);
            if (fx.on("del")) SpecialKeysFx.deleteTap(fx, h.k, new FxLetter[] { L });
            return;
        }
        final float tx = h.tx(), ty = h.ty(), dx = L.x - tx, dy = L.y - ty;
        final Flying q = new Flying();
        q.ch = L.ch;
        q.sx = L.x;
        q.sy = L.y;
        // Letters of a word deleted at once leave one after another, not in a clump
        q.t0 = Math.max(fx.now(), h.lastLaunch + (long) fx.D(35));
        h.lastLaunch = q.t0;
        q.d0 = (float) Math.hypot(dx, dy);
        q.a0 = (float) Math.atan2(dy, dx);
        // Vacuum: an S-curve that dips below the letter, then swoops down into the key from above
        q.c1x = L.x + fx.dp(fx.rand(-70, 70));
        q.c1y = L.y + fx.dp(fx.rand(20, 70));
        q.c2x = tx + fx.dp(fx.rand(-80, 80));
        q.c2y = ty - fx.dp(fx.rand(60, 130));
        h.parts.add(q);
        fx.kick();
    }

    @Override
    public void onDeleteHoldEnd() {
        if (hold == null) return;
        hold.end = fx.now();
        hold = null;
        fx.kick();
    }

    /** Lava shakes the real key with the same jitter its glow uses (hosts with CAP_KEY_TRANSFORM). */
    private boolean shaking(Hold h) {
        return h != null && "lava".equals(h.button) && fx.settings.b("delHold", "shake");
    }

    @Override
    public void look(FxKey k, KeyLook out, long t) {
        final Hold h = hold;
        if (shaking(h) && h.k == k) out.move(h.jx, h.jy);
    }

    @Override
    public boolean keysMoving(long t) {
        return shaking(hold);
    }

    @Override
    public void reset() {
        hold = null;
    }
}
