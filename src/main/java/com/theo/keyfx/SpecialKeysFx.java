package com.theo.keyfx;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/** Enter sweep, spacebar stars or puff, and what one backspace press does to the key and the letter. */
final class SpecialKeysFx extends FxModule {
    private static final String G = "Special keys";

    static Section[] sections() {
        return new Section[] {
                new Section("enter", G, 200, "Enter sweep", "A band of light sweeps across the enter key's row.",
                        true, true,
                        Field.range("duration", "Sweep time", 150, 900, 10, "ms", 380),
                        Field.range("width", "Band width", 10, 60, 1, "%", 30),
                        Field.strength()),
                new Section("space", G, 210, "Spacebar", "Stars launch off the spacebar, or it puffs out a little cloud.",
                        true, true,
                        Field.choice("mode", "Style", "stars", "stars:Stars", "puff:Puff", "shimmer:Shimmer"),
                        Field.range("count", "Amount", 3, 24, 1, "", 9).whenNot("mode", "shimmer"),
                        Field.range("spread", "Spread", 0.3f, 2, 0.05f, "×", 1),
                        Field.range("life", "Lifetime", 300, 1600, 10, "ms", 760),
                        Field.strength()),
                new Section("del", G, 220, "Backspace tap", "What one press does to the key, and to the letter it deletes.",
                        true, true,
                        Field.choice("tap", "Effect", "fire", "fire:Fire", "slash:Slash", "shatter:Shatter", "none:None"),
                        Field.toggle("letterFx", "The deleted letter burns, splits or shatters too", true)
                                .whenNot("tap", "none"),
                        Field.range("fireHeight", "Flame height", 0.4f, 2.5f, 0.05f, "×", 1).when("tap", "fire"),
                        Field.range("fireCount", "Flames", 6, 50, 1, "", 22).when("tap", "fire"),
                        Field.range("fireLife", "Burn time", 250, 1400, 10, "ms", 620).when("tap", "fire"),
                        Field.range("slashSpeed", "Slash speed", 20, 300, 5, "ms", 70).when("tap", "slash"),
                        Field.range("slashOver", "Overshoot past the key", 0, 0.8f, 0.01f, "", 0.25f).when("tap", "slash"),
                        Field.range("slashTilt", "Random tilt", 0, 45, 1, "°", 15).when("tap", "slash"),
                        Field.range("shards", "Shards", 4, 16, 1, "", 8).when("tap", "shatter"),
                        Field.strength()),
        };
    }

    SpecialKeysFx(KeyFx fx) {
        super(fx);
    }

    @Override
    public void onKeyTyped(FxKey k, String glyph, float x) {
        if (k.type == FxKey.ENTER && fx.on("enter")) enter(k);
        else if (k.type == FxKey.SPACE && fx.on("space")) space(k, x);
    }

    @Override
    public void onDeleteTap(FxKey k, FxLetter[] letters) {
        if (fx.on("del")) deleteTap(fx, k, letters);
    }

    // region Enter and space

    private void enter(final FxKey k) {
        final long t0 = fx.now();
        final float dur = fx.D(fx.settings.f("enter", "duration")), s = fx.S("enter");
        final float kbL = fx.layout().keyboard.left, kbR = fx.layout().keyboard.right;
        final float bw = Math.max(fx.dp(50), (kbR - kbL) * fx.settings.f("enter", "width") / 100);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float p = (t - t0) / dur;
                if (p >= 1) return false;
                final float cx = Fx.lerp(kbR + bw / 2, kbL - bw / 2, Fx.easeOut(p));
                FxParts.band(fx, c, cx - bw / 2, k.top, bw, k.h(), 0.5f * (float) Math.sin(Math.PI * p) * s);
                return true;
            }
        });
    }

    private void space(final FxKey k, float tapX) {
        final long t0 = fx.now();
        final float life = fx.D(fx.settings.f("space", "life")), s = fx.S("space");
        final String mode = fx.settings.s("space", "mode");
        final float spread = fx.settings.f("space", "spread");
        if ("shimmer".equals(mode)) {
            final float bw = Math.max(fx.dp(36), k.w() * 0.22f), dur = fx.D(320);
            fx.add(new Effect() {
                @Override
                public boolean draw(Canvas c, long t) {
                    final float p = (t - t0) / dur;
                    if (p >= 1) return false;
                    c.save();
                    c.clipPath(fx.roundRectPath(k.left, k.top, k.right, k.bottom, 9));
                    FxParts.band(fx, c, Fx.lerp(k.left - bw, k.right, Fx.easeOut(p)), k.top, bw, k.h(),
                            0.6f * (1 - p * p) * s);
                    c.restore();
                    return true;
                }
            });
            return;
        }
        final boolean stars = "stars".equals(mode);
        final int n = Math.round(fx.settings.f("space", "count"));
        final float x0 = Float.isNaN(tapX) ? k.cx() : tapX;
        final float[] px = new float[n], py = new float[n], vx = new float[n], vy = new float[n];
        final float[] r = new float[n], rot = new float[n], spin = new float[n], tw = new float[n];
        final boolean[] white = new boolean[n];
        for (int i = 0; i < n; i++) {
            px[i] = x0 + fx.rand(-k.w() * 0.25f, k.w() * 0.25f) * spread * 0.6f;
            py[i] = k.top + k.h() * 0.4f;
            vx[i] = fx.dp(fx.rand(-70, 70)) * spread;
            vy[i] = stars ? -fx.dp(fx.rand(90, 200)) : -fx.dp(fx.rand(15, 45));
            r[i] = stars ? fx.dp(fx.rand(3, 7)) : fx.dp(fx.rand(7, 13));
            rot[i] = fx.rand(0, (float) Math.PI);
            spin[i] = fx.rand(-4, 4);
            tw[i] = fx.rand(0, 6);
            white[i] = fx.rnd.nextFloat() < 0.35f;
        }
        final float gravity = fx.dp(140);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = (t - t0) / 1000f, p = (t - t0) / life;
                if (p >= 1) return false;
                for (int i = 0; i < n; i++) {
                    if (stars) {
                        final float x = px[i] + vx[i] * e, y = py[i] + vy[i] * e + gravity * e * e;
                        final float twinkle = 0.6f + 0.4f * (float) Math.sin(tw[i] + e * 18);
                        final float a = (1 - p) * twinkle * s;
                        final Paint paint = fx.fill(white[i] ? Fx.alpha(0xFFFFF8DC, a) : Fx.alpha(fx.accent(), a));
                        paint.setShadowLayer(fx.dp(4), 0, 0, Fx.alpha(fx.accent(), 0.8f * (1 - p) * s));
                        fx.star(c, x, y, r[i] * (1 - p * 0.4f), 5, 0.45f, rot[i] + spin[i] * e, paint);
                    } else {
                        // Puff: soft rounded clouds that swell and drift up
                        final float x = px[i] + vx[i] * e * 0.5f, y = py[i] + vy[i] * e;
                        final float rr = r[i] * (1 + 1.8f * Fx.easeOut(p));
                        final Paint paint = fx.fill(0xFFFFFFFF);
                        paint.setShader(new RadialGradient(x, y, rr, Fx.alpha(fx.accent(), 0.45f * (1 - p) * s),
                                Fx.alpha(fx.accent(), 0), Shader.TileMode.CLAMP));
                        c.drawCircle(x, y, rr, paint);
                    }
                }
                return true;
            }
        });
    }

    // endregion

    // region Backspace tap

    /**
     * One backspace press: an effect on the key, and optionally the same treatment for every
     * letter it deleted. A whole word goes letter by letter from the right, like it was erased.
     */
    static void deleteTap(KeyFx fx, FxKey k, FxLetter[] letters) {
        final String tap = fx.settings.s("del", "tap");
        final float s = fx.S("del");
        final boolean withLetters = fx.settings.b("del", "letterFx");
        float ang = 0;
        if ("fire".equals(tap)) fireOnKey(fx, k, s);
        else if ("slash".equals(tap)) ang = slashOnKey(fx, k, s);
        else if ("shatter".equals(tap)) shatterKey(fx, k, s);
        else return;
        if (!withLetters) return;
        final float step = fx.D(28);
        for (int i = 0; i < letters.length; i++) {
            final FxLetter letter = letters[i];
            if (letter == null) continue;
            final float delay = (letters.length - 1 - i) * step;
            if ("fire".equals(tap)) burnLetter(fx, letter, s, delay);
            else if ("slash".equals(tap)) cutLetter(fx, letter, ang, s, delay);
            else shatterLetter(fx, letter, s, delay);
        }
    }

    private static void fireOnKey(final KeyFx fx, final FxKey k, final float s) {
        final long t0 = fx.now();
        final float life = fx.D(fx.settings.f("del", "fireLife")), height = fx.settings.f("del", "fireHeight");
        final int n = Math.round(fx.settings.f("del", "fireCount"));
        final float[] x = new float[n], y = new float[n], vy = new float[n], ph = new float[n], r = new float[n], d = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = fx.rand(k.left + fx.dp(6), k.right - fx.dp(6));
            y[i] = k.bottom - fx.dp(fx.rand(2, 10));
            vy[i] = -fx.dp(fx.rand(50, 130)) * height;
            ph[i] = fx.rand(0, 6);
            r[i] = fx.dp(fx.rand(3, 7));
            d[i] = fx.rand(0, life * 0.2f);
        }
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                boolean alive = false;
                for (int i = 0; i < n; i++) {
                    final float e = t - t0 - d[i], p = e / life;
                    if (p < 0) {
                        alive = true;
                        continue;
                    }
                    if (p >= 1) continue;
                    alive = true;
                    final float sec = e / 1000f;
                    final Paint paint = fx.fill(Fx.fire(p, (1 - p) * 0.9f * s));
                    paint.setXfermode(FxParts.ADD);
                    c.drawCircle(x[i] + (float) Math.sin(ph[i] + sec * 14) * fx.dp(4) * p, y[i] + vy[i] * sec,
                            r[i] * (1 - p * 0.7f), paint);
                }
                return alive;
            }
        });
    }

    /** The deleted letter heats from its own color to flame, rises a little and chars away. */
    private static void burnLetter(final KeyFx fx, final FxLetter L, final float s, final float delay) {
        final long t0 = fx.now() + (long) delay;
        final float life = fx.D(fx.settings.f("del", "fireLife") * 1.2f), height = fx.settings.f("del", "fireHeight");
        final float[] dx = new float[8], vy = new float[8], r = new float[8], d = new float[8];
        for (int i = 0; i < 8; i++) {
            dx[i] = fx.rand(-L.w / 2, L.w / 2);
            vy[i] = -fx.dp(fx.rand(30, 80)) * height;
            r[i] = fx.dp(fx.rand(1.5f, 3.5f));
            d[i] = fx.rand(0, life * 0.3f);
        }
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                // Before its turn (u < 0) the letter just waits where it was
                final float u = Math.max(0, (t - t0) / life);
                if (u >= 1) return false;
                final int color = u < 0.15f ? Fx.alpha(Fx.mix(fx.labelColor(), 0xFFFFD36B, u / 0.15f), s)
                        : Fx.fire((u - 0.15f) / 0.85f, (float) Math.pow(1 - u, 1.3) * s);
                fx.plainText(c, L.ch, L.x, L.y - fx.dp(4) * u, L.size, color, 0,
                        1 + 0.12f * (float) Math.sin(u * Math.PI));
                for (int i = 0; i < 8; i++) {
                    final float e = (t - t0 - d[i]) / 1000f, p = (t - t0 - d[i]) / life;
                    if (p < 0 || p >= 1) continue;
                    final Paint paint = fx.fill(Fx.fire(p, (1 - p) * 0.8f * s));
                    paint.setXfermode(FxParts.ADD);
                    c.drawCircle(L.x + dx[i], L.y + fx.dp(6) + vy[i] * e, r[i] * (1 - p * 0.6f), paint);
                }
                return true;
            }
        });
    }

    /** A blade stroke across the key at a randomly tilted angle. Returns the angle, so the letter can be cut along it. */
    private static float slashOnKey(final KeyFx fx, final FxKey k, final float s) {
        final long t0 = fx.now();
        final float draw = fx.D(fx.settings.f("del", "slashSpeed")), life = draw + fx.D(380);
        final float tilt = fx.settings.f("del", "slashTilt");
        final float ang = (float) (Math.atan2(-k.h(), k.w()) + Math.toRadians(fx.rand(-tilt, tilt)));
        final float half = 0.5f * (float) Math.hypot(k.w(), k.h()) * (1 + fx.settings.f("del", "slashOver"));
        final float dx = (float) Math.cos(ang) * half, dy = (float) Math.sin(ang) * half;
        final float x1 = k.cx() - dx, y1 = k.cy() - dy, x2 = k.cx() + dx, y2 = k.cy() + dy;
        final float[] sa = new float[12], sv = new float[12];
        for (int i = 0; i < 12; i++) {
            sa[i] = ang + fx.rand(-0.6f, 0.6f);
            sv[i] = fx.dp(fx.rand(80, 220));
        }
        final float gravity = fx.dp(200);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = t - t0;
                if (e >= life) return false;
                final float p = Fx.clamp01(e / draw), fade = 1 - Fx.clamp01((e - draw) / (life - draw));
                final Paint blade = fx.stroke(Fx.alpha(0xFFFFFFFF, fade * s), fx.dp(2.5f) * (0.4f + 0.6f * fade));
                blade.setShadowLayer(fx.dp(6), 0, 0, Fx.alpha(fx.accent(), fade * s));
                final float ep = Fx.easeOut(p);
                c.drawLine(x1, y1, Fx.lerp(x1, x2, ep), Fx.lerp(y1, y2, ep), blade);
                if (p >= 1) {
                    final float sec = (e - draw) / 1000f;
                    final Paint spark = fx.fill(Fx.alpha(fx.accent(), fade * s));
                    for (int i = 0; i < 12; i++) {
                        c.drawCircle(x2 + (float) Math.cos(sa[i]) * sv[i] * sec,
                                y2 + (float) Math.sin(sa[i]) * sv[i] * sec + gravity * sec * sec, fx.dp(1.6f), spark);
                    }
                }
                return true;
            }
        });
        return ang;
    }

    /** The deleted letter is cut along the slash: the halves slide apart, tumble and fall. */
    private static void cutLetter(final KeyFx fx, final FxLetter L, final float ang, final float s, final float delay) {
        final long t0 = fx.now() + (long) delay;
        final float cutAt = fx.D(fx.settings.f("del", "slashSpeed")), life = cutAt + fx.D(650);
        final float nx = -(float) Math.sin(ang), ny = (float) Math.cos(ang), spin = fx.rand(0.6f, 1.4f);
        final float box = Math.max(L.size, L.w) * 2.4f, gravity = fx.dp(260), apart = fx.dp(14);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = t - t0;
                if (e >= life) return false;
                if (e < cutAt) {
                    fx.plainText(c, L.ch, L.x, L.y, L.size, Fx.alpha(fx.labelColor(), s), 0, 1);
                    return true;
                }
                final float u = (e - cutAt) / (life - cutAt), sec = (e - cutAt) / 1000f, eu = Fx.easeOut(u);
                for (int side = -1; side <= 1; side += 2) {
                    c.save();
                    c.translate(L.x + nx * side * apart * eu, L.y + ny * side * apart * eu + gravity * sec * sec);
                    c.rotate((float) Math.toDegrees(side * spin * u * 0.8f));
                    // Keep only this side of the cut line
                    c.rotate((float) Math.toDegrees(ang));
                    c.clipRect(-box, side < 0 ? -box : 0, box, side < 0 ? 0 : box);
                    c.rotate(-(float) Math.toDegrees(ang));
                    final int col = u < 0.08f ? Fx.alpha(fx.accent(), s) : Fx.alpha(fx.labelColor(), (1 - u) * s);
                    fx.plainText(c, L.ch, 0, 0, L.size, col, 0, 1);
                    c.restore();
                }
                if (u < 0.25f) {
                    final float a = 1 - u / 0.25f, hx = (float) Math.cos(ang) * fx.dp(12), hy = (float) Math.sin(ang) * fx.dp(12);
                    c.drawLine(L.x - hx, L.y - hy, L.x + hx, L.y + hy, fx.stroke(Fx.alpha(fx.accent(), a * s), fx.dp(1.5f)));
                }
                return true;
            }
        });
    }

    /** A point on the key's outline, walking clockwise from the top-left corner. */
    private static float[] edgePoint(FxKey k, float d) {
        final float w = k.w(), h = k.h(), per = 2 * (w + h);
        d = ((d % per) + per) % per;
        if (d < w) return new float[] { k.left + d, k.top };
        d -= w;
        if (d < h) return new float[] { k.right, k.top + d };
        d -= h;
        if (d < w) return new float[] { k.right - d, k.bottom };
        d -= w;
        return new float[] { k.left, k.bottom - d };
    }

    /**
     * The key face cracks from the impact point and breaks into shards that fly off. The real key
     * is covered with the keyboard background while the shards fly, then shows through again.
     */
    private static void shatterKey(final KeyFx fx, final FxKey k, final float s) {
        final long t0 = fx.now();
        final int n = Math.round(fx.settings.f("del", "shards"));
        final float cx = k.cx() + fx.dp(fx.rand(-4, 4)), cy = k.cy() + fx.dp(fx.rand(-4, 4));
        final float per = 2 * (k.w() + k.h());
        final float[][] pts = new float[n][];
        for (int i = 0; i < n; i++) pts[i] = edgePoint(k, ((i + fx.rand(-0.3f, 0.3f)) / n) * per);
        // Each shard: a triangle around its centroid, and its flight
        final float[][] tri = new float[n][];
        final float[] gx = new float[n], gy = new float[n], vx = new float[n], vy = new float[n], spin = new float[n];
        for (int i = 0; i < n; i++) {
            final float[] p = pts[i], q = pts[(i + 1) % n];
            gx[i] = (cx + p[0] + q[0]) / 3;
            gy[i] = (cy + p[1] + q[1]) / 3;
            tri[i] = new float[] { cx - gx[i], cy - gy[i], p[0] - gx[i], p[1] - gy[i], q[0] - gx[i], q[1] - gy[i] };
            final float dx = gx[i] - cx, dy = gy[i] - cy, len = Math.max(1, (float) Math.hypot(dx, dy));
            final float sp = fx.dp(fx.rand(60, 150));
            vx[i] = dx / len * sp;
            vy[i] = dy / len * sp - fx.dp(60);
            spin[i] = fx.rand(-6, 6);
        }
        final float crack = fx.D(80), life = crack + fx.D(620), gravity = fx.dp(500);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = t - t0;
                if (e >= life) return false;
                if (e < crack) {
                    // The intact face with cracks spreading from the impact
                    fx.roundRect(c, k.left, k.top, k.right, k.bottom, 9, fx.fill(fx.keyFill()));
                    final Paint line = fx.stroke(Fx.alpha(fx.accent(), 0.9f * s), fx.dp(1.2f));
                    final float g = e / crack;
                    for (float[] p : pts) c.drawLine(cx, cy, Fx.lerp(cx, p[0], g), Fx.lerp(cy, p[1], g), line);
                    return true;
                }
                final float u = (e - crack) / (life - crack), sec = (e - crack) / 1000f;
                // The hole the key left, closing again as it fades back in
                final float hole = u < 0.4f ? 1 : 1 - (u - 0.4f) / 0.6f;
                fx.roundRect(c, k.left, k.top, k.right, k.bottom, 9, fx.fill(Fx.alpha(fx.background(), hole)));
                for (int i = 0; i < n; i++) {
                    c.save();
                    c.translate(gx[i] + vx[i] * sec, gy[i] + vy[i] * sec + gravity * sec * sec);
                    c.rotate((float) Math.toDegrees(spin[i] * sec));
                    final Path path = fx.path();
                    path.moveTo(tri[i][0], tri[i][1]);
                    path.lineTo(tri[i][2], tri[i][3]);
                    path.lineTo(tri[i][4], tri[i][5]);
                    path.close();
                    c.drawPath(path, fx.fill(Fx.alpha(fx.keyFill(), (1 - u) * s)));
                    c.drawPath(path, fx.stroke(Fx.alpha(fx.accent(), (1 - u) * 0.7f * s), fx.dp(1)));
                    c.restore();
                }
                return true;
            }
        });
    }

    /** The deleted letter breaks into four pieces that scatter. */
    private static void shatterLetter(final KeyFx fx, final FxLetter L, final float s, final float delay) {
        final long t0 = fx.now() + (long) delay;
        final float life = fx.D(600), box = Math.max(L.size, L.w) * 1.2f, gravity = fx.dp(300);
        final int[] sx = { -1, 1, -1, 1 }, sy = { -1, -1, 1, 1 };
        final float[] vx = new float[4], vy = new float[4], spin = new float[4];
        for (int i = 0; i < 4; i++) {
            vx[i] = sx[i] * fx.dp(fx.rand(30, 70));
            vy[i] = sy[i] * fx.dp(fx.rand(20, 50)) - fx.dp(40);
            spin[i] = fx.rand(-5, 5);
        }
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float u = (t - t0) / life;
                if (u >= 1) return false;
                if (u < 0) {
                    // Waiting for its turn
                    fx.plainText(c, L.ch, L.x, L.y, L.size, Fx.alpha(fx.labelColor(), s), 0, 1);
                    return true;
                }
                final float sec = (t - t0) / 1000f;
                for (int i = 0; i < 4; i++) {
                    c.save();
                    c.translate(L.x + vx[i] * sec, L.y + vy[i] * sec + gravity * sec * sec);
                    c.rotate((float) Math.toDegrees(spin[i] * sec));
                    c.clipRect(sx[i] < 0 ? -box : 0, sy[i] < 0 ? -box : 0, sx[i] < 0 ? 0 : box, sy[i] < 0 ? 0 : box);
                    fx.plainText(c, L.ch, 0, 0, L.size, Fx.alpha(fx.labelColor(), (1 - u) * s), 0, 1);
                    c.restore();
                }
                return true;
            }
        });
    }

    // endregion
}
