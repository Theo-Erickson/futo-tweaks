package com.theo.keyfx;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.WeakHashMap;

/** Enter styles, spacebar stars or puff, and what one backspace press does to the key and the letter. */
final class SpecialKeysFx extends FxModule {
    private static final String G = "Special keys";

    static Section[] sections() {
        return new Section[] {
                new Section("enter", G, 200, "Enter", "What enter does when it sends or starts a new line.",
                        true, true,
                        Field.choice("style", "Style", "sweep", "sweep:Sweep its row", "shockwave:Shockwave",
                                "rocket:Rocket", "rows:Row wave", "check:Check mark"),
                        Field.range("duration", "Duration", 150, 1200, 10, "ms", 380),
                        Field.range("width", "Band width", 10, 60, 1, "%", 30).when("style", "sweep"),
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
                        Field.choice("tap", "Effect", "fire", "fire:Fire", "slash:Slash", "shatter:Shatter",
                                "laser:Laser", "dust:Disintegrate", "rewind:Rewind", "glitch:Glitch",
                                "shred:Shredder", "swirl:Swirl in", "vacuum:Vacuum on curves", "none:None"),
                        Field.range("glitch", "Glitch amount", 1, 10, 0.5f, "dp", 4).when("tap", "glitch"),
                        Field.range("strips", "Strips", 3, 12, 1, "", 6).when("tap", "shred"),
                        Field.toggle("letterFx", "The deleted letters react too", true)
                                .whenNot("tap", "none"),
                        Field.choice("laserColor", "Beam color", "red", "red:Red", "green:Green", "accent:Accent")
                                .when("tap", "laser"),
                        Field.range("laserSpeed", "Beam travel time", 40, 400, 5, "ms", 120).when("tap", "laser"),
                        Field.range("laserWidth", "Beam width", 1, 6, 0.25f, "dp", 2.5f).when("tap", "laser"),
                        Field.range("dustSize", "Grain size", 0.6f, 3, 0.1f, "dp", 1.6f).when("tap", "dust"),
                        Field.range("dustLife", "Crumble time", 300, 1800, 10, "ms", 900).when("tap", "dust"),
                        Field.choice("dustWind", "Dust drifts", "right", "right:With the wind", "up:Upward",
                                "scatter:Scatter").when("tap", "dust"),
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
        final String style = fx.settings.s("enter", "style");
        if ("shockwave".equals(style)) {
            enterShockwave(fx, k, dur, s);
            return;
        }
        if ("rocket".equals(style)) {
            enterRocket(fx, k, dur, s);
            return;
        }
        if ("rows".equals(style)) {
            enterRows(fx, dur, s);
            return;
        }
        if ("check".equals(style)) {
            enterCheck(fx, k, dur, s);
            return;
        }
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

    /** Shockwave: a ring blasts out from enter across the whole keyboard. */
    private static void enterShockwave(final KeyFx fx, final FxKey k, final float dur, final float s) {
        final long t0 = fx.now();
        final RectF kb = fx.layout().keyboard;
        final float maxR = (float) Math.hypot(kb.width(), kb.height());
        addMove(fx, k, KeyMove.SQUISH, fx.now(), fx.D(180), 0, null);
        // One unit-radius gradient, scaled to the ring radius each frame; the band scales with it
        final Shader ring = new RadialGradient(0, 0, 1,
                new int[] { Fx.alpha(fx.accent(), 0), Fx.alpha(fx.accent(), 0), Fx.alpha(fx.accent(), 0.35f * s),
                        Fx.alpha(fx.accent(), 0) },
                new float[] { 0, 0.7f, 0.94f, 1 }, Shader.TileMode.CLAMP);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float u = (t - t0) / dur;
                if (u >= 1) return false;
                final float r = Math.max(1, maxR * Fx.easeOut(u));
                final Paint p = fx.fill(0xFFFFFFFF);
                p.setShader(ring);
                p.setAlpha((int) (255 * (1 - u)));
                c.save();
                c.translate(k.cx(), k.cy());
                c.scale(r, r);
                c.drawCircle(0, 0, 1, p);
                c.restore();
                c.drawCircle(k.cx(), k.cy(), r, fx.stroke(Fx.alpha(fx.accent(), 0.8f * (1 - u) * s), fx.dp(2)));
                return true;
            }
        });
    }

    /** Rocket: a little rocket launches off enter, arcing up and away with a smoke trail. */
    private static void enterRocket(final KeyFx fx, final FxKey k, final float dur, final float s) {
        final long t0 = fx.now();
        final float life = dur * 1.4f, puffLife = fx.D(500), step = 16;
        // The smoke puffs are laid out up front: one every frame or so, most of the time
        final int n = (int) (life / step) + 1;
        final float[] px = new float[n], py = new float[n], pr = new float[n], jx = new float[n];
        final boolean[] has = new boolean[n];
        final float rise = k.cy() + fx.dp(30);
        for (int i = 0; i < n; i++) {
            final float p = Fx.easeIn(Math.min(1, i * step / life));
            px[i] = k.cx() - fx.dp(40) * p * p;
            py[i] = k.cy() - rise * p + fx.dp(8);
            pr[i] = fx.dp(fx.rand(2, 4));
            jx[i] = fx.dp(fx.rand(-0.5f, 0.5f));
            has[i] = fx.rnd.nextFloat() < 0.8f;
        }
        final int smoke = fx.dark() ? 0xFFC9CED8 : 0xFF9AA0AA;
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = t - t0, u = e / life;
                if (u >= 1 && e - life >= puffLife) return false;
                for (int i = 0; i < n; i++) {
                    if (!has[i]) continue;
                    final float a = (e - i * step) / puffLife;
                    if (a < 0 || a >= 1) continue;
                    c.drawCircle(px[i] + jx[i], py[i] + fx.dp(10) * a, pr[i] * (1 + a * 1.5f),
                            fx.fill(Fx.alpha(smoke, 0.35f * (1 - a) * s)));
                }
                if (u < 1) {
                    final float p = Fx.easeIn(u);
                    final float x = k.cx() - fx.dp(40) * p * p, y = k.cy() - rise * p;
                    final float ang = (float) Math.atan2(-rise, -fx.dp(80) * p) + (float) Math.PI / 2;
                    final float flick = fx.dp(2) * (1 + (float) Math.sin((t - t0) * 0.06f));
                    c.save();
                    c.translate(x, y);
                    c.rotate((float) Math.toDegrees(ang));
                    final Path flame = fx.path();
                    flame.moveTo(-fx.dp(3), fx.dp(6));
                    flame.lineTo(0, fx.dp(13) + flick);
                    flame.lineTo(fx.dp(3), fx.dp(6));
                    flame.close();
                    c.drawPath(flame, fx.fill(Fx.fire(0.2f, s)));
                    final Path body = fx.path();
                    body.moveTo(0, -fx.dp(9));
                    body.quadTo(fx.dp(5), -fx.dp(2), fx.dp(4), fx.dp(6));
                    body.lineTo(-fx.dp(4), fx.dp(6));
                    body.quadTo(-fx.dp(5), -fx.dp(2), 0, -fx.dp(9));
                    c.drawPath(body, fx.fill(Fx.alpha(fx.accent(), s)));
                    c.restore();
                }
                return true;
            }
        });
    }

    /** Row wave: each row lights up in turn from the bottom, like the page feeding a new line. */
    private static void enterRows(final KeyFx fx, final float dur, final float s) {
        final long t0 = fx.now();
        final FxKey[] keys = fx.layout().keys;
        final float[] tops = fx.layout().rowTops(fx.dp(8));
        final int n = tops.length;
        // Rows listed bottom first, each with the span of its keys
        final float[] top = new float[n], bot = new float[n];
        for (int i = 0; i < n; i++) {
            top[i] = Float.MAX_VALUE;
            bot[i] = -Float.MAX_VALUE;
        }
        for (FxKey q : keys) {
            int row = 0;
            for (int i = 0; i < n; i++) if (tops[i] <= q.top + 0.5f) row = i;
            top[row] = Math.min(top[row], q.top);
            bot[row] = Math.max(bot[row], q.bottom);
        }
        // Drop empty rows, so the stagger counts only rows that light up
        final float[] rt = new float[n], rb = new float[n];
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (top[i] > bot[i]) continue;
            rt[cnt] = top[i];
            rb[cnt] = bot[i];
            cnt++;
        }
        final int rows = cnt;
        final RectF kb = fx.layout().keyboard;
        final float kbL = kb.left, kbR = kb.right;
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                boolean alive = false;
                for (int i = 0; i < rows; i++) {
                    final int row = rows - 1 - i;
                    final float u = (t - t0 - i * dur * 0.16f) / (dur * 0.5f);
                    if (u < 0) {
                        alive = true;
                        continue;
                    }
                    if (u >= 1) continue;
                    alive = true;
                    c.drawRect(kbL, rt[row] - fx.dp(2), kbR, rb[row] + fx.dp(2),
                            fx.fill(Fx.alpha(fx.accent(), 0.3f * (float) Math.sin(Math.PI * u) * s)));
                }
                return alive;
            }
        });
    }

    /** Check mark: a check draws itself over enter, then pops. */
    private static void enterCheck(final KeyFx fx, final FxKey k, final float dur, final float s) {
        final long t0 = fx.now();
        final float draw = dur * 0.5f, sc = k.h() * 1.6f;
        final float[] ax = { -0.28f, -0.06f, 0.3f }, ay = { 0.02f, 0.24f, -0.22f };
        final float[] x = new float[3], y = new float[3];
        for (int i = 0; i < 3; i++) {
            x[i] = k.cx() + ax[i] * sc;
            y[i] = k.cy() + ay[i] * sc;
        }
        final float l1 = (float) Math.hypot(x[1] - x[0], y[1] - y[0]), l2 = (float) Math.hypot(x[2] - x[1], y[2] - y[1]);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = t - t0;
                if (e >= dur) return false;
                final float p = Fx.easeOut(Fx.clamp01(e / draw)) * (l1 + l2), fade = 1 - Fx.clamp01((e - draw) / (dur - draw));
                final float pop = 1 + 0.25f * (float) Math.sin(Math.PI * Fx.clamp01((e - draw) / (dur * 0.3f)));
                c.save();
                c.scale(pop, pop, k.cx(), k.cy());
                final Path path = fx.path();
                path.moveTo(x[0], y[0]);
                if (p <= l1) {
                    path.lineTo(Fx.lerp(x[0], x[1], p / l1), Fx.lerp(y[0], y[1], p / l1));
                } else {
                    path.lineTo(x[1], y[1]);
                    path.lineTo(Fx.lerp(x[1], x[2], (p - l1) / l2), Fx.lerp(y[1], y[2], (p - l1) / l2));
                }
                final Paint line = fx.stroke(Fx.alpha(fx.accent(), fade * s), fx.dp(4));
                line.setShadowLayer(fx.dp(10), 0, 0, Fx.alpha(fx.accent(), fade * s));
                c.drawPath(path, line);
                c.restore();
                return true;
            }
        });
    }

    // endregion

    // region Moves on the real key

    /** A short move of a real key (host with CAP_KEY_TRANSFORM only): a squish, a press or a jitter. */
    private static final class KeyMove {
        static final int SQUISH = 0, PRESS = 1, JITTER = 2;
        final FxKey k;
        final long t0;
        final float dur, amount;
        final int kind;
        final float[] table;

        KeyMove(FxKey k, long t0, float dur, int kind, float amount, float[] table) {
            this.k = k;
            this.t0 = t0;
            this.dur = dur;
            this.kind = kind;
            this.amount = amount;
            this.table = table;
        }
    }

    // Per host, because the backspace hold also starts taps through the static deleteTap
    private static final WeakHashMap<KeyFx, ArrayList<KeyMove>> MOVES = new WeakHashMap<>();

    private static void addMove(KeyFx fx, FxKey k, int kind, long start, float dur, float amount, float[] table) {
        if ((fx.caps & KeyFx.CAP_KEY_TRANSFORM) == 0) return;
        ArrayList<KeyMove> list = MOVES.get(fx);
        if (list == null) {
            list = new ArrayList<>();
            MOVES.put(fx, list);
        }
        // A repeat replaces the one still running, so moves never stack
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).k == k && list.get(i).kind == kind) {
                list.remove(i);
                i--;
            }
        }
        list.add(new KeyMove(k, start, dur, kind, amount, table));
        fx.kick();
    }

    @Override
    public void look(FxKey k, KeyLook out, long t) {
        final ArrayList<KeyMove> list = MOVES.get(fx);
        if (list == null) return;
        for (KeyMove m : list) {
            if (m.k != k) continue;
            if (t < m.t0 || t >= m.t0 + m.dur) continue;
            final float u = (t - m.t0) / m.dur;
            if (m.kind == KeyMove.SQUISH) out.scaleBy(0.92f + 0.08f * u);
            else if (m.kind == KeyMove.PRESS) out.move(0, m.amount * (1 - Math.abs(2 * u - 1)));
            else out.move(m.table[Math.min(m.table.length - 1, (int) (u * m.table.length))], 0);
        }
    }

    @Override
    public boolean keysMoving(long t) {
        final ArrayList<KeyMove> list = MOVES.get(fx);
        if (list == null) return false;
        for (int i = 0; i < list.size(); i++) {
            if (t - list.get(i).t0 >= list.get(i).dur) {
                list.remove(i);
                i--;
            }
        }
        return !list.isEmpty();
    }

    @Override
    public void reset() {
        MOVES.remove(fx);
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
        else if ("dust".equals(tap)) dustKey(fx, k, s);
        else if ("glitch".equals(tap)) glitchKey(fx, k, s);
        else if ("shred".equals(tap)) shredKey(fx, k, s);
        else if ("rewind".equals(tap)) FxParts.pop(fx, k);
        else if ("swirl".equals(tap) || "vacuum".equals(tap)) {
            // The key reacts when the letters arrive
        } else if ("laser".equals(tap)) {
            // The laser needs a target: no letters known means it shoots at the cursor area
            if (letters.length == 0 || !withLetters) laserShot(fx, k, null, s, 0);
        } else return;
        if (!withLetters) return;
        final float step = fx.D("laser".equals(tap) ? 70 : 28);
        for (int i = 0; i < letters.length; i++) {
            final FxLetter letter = letters[i];
            if (letter == null) continue;
            final float delay = (letters.length - 1 - i) * step;
            if ("fire".equals(tap)) burnLetter(fx, letter, s, delay);
            else if ("slash".equals(tap)) cutLetter(fx, letter, ang, s, delay);
            else if ("shatter".equals(tap)) shatterLetter(fx, letter, s, delay);
            else if ("dust".equals(tap)) disintegrate(fx, letter, s, delay);
            else if ("laser".equals(tap)) {
                laserShot(fx, k, letter, s, delay);
                vaporizeLetter(fx, letter, s, delay);
            } else if ("rewind".equals(tap)) rewindLetter(fx, letter, k, s, delay);
            else if ("glitch".equals(tap)) glitchLetter(fx, letter, s, delay);
            else if ("shred".equals(tap)) shredLetter(fx, letter, s, delay);
            else flyToKey(fx, letter, k, tap, s, delay);
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

    private static int laserColor(KeyFx fx) {
        final String c = fx.settings.s("del", "laserColor");
        return "green".equals(c) ? 0xFF4CFF8F : "accent".equals(c) ? fx.accent() : 0xFFFF3B5C;
    }

    /** A beam from the backspace icon to the letter: it reaches out, holds, then thins away with sparks at the hit. */
    private static void laserShot(final KeyFx fx, final FxKey k, FxLetter L, final float s, float delay) {
        final long t0 = fx.now() + (long) delay;
        final float travel = fx.D(fx.settings.f("del", "laserSpeed")), hold = fx.D(90), fade = fx.D(160);
        final float width = fx.dp(fx.settings.f("del", "laserWidth"));
        final int col = laserColor(fx);
        final float sx = k.cx(), sy = k.top + k.h() * 0.42f;
        // No letter known: aim at the middle of the keyboard's top edge
        final float tx = L != null ? L.x : (fx.layout().keyboard.left + fx.layout().keyboard.right) / 2;
        final float ty = L != null ? L.y : Math.max(fx.dp(8), fx.layout().keyboard.top - fx.dp(24));
        final float[] sa = new float[10], sv = new float[10];
        for (int i = 0; i < 10; i++) {
            sa[i] = (float) Math.atan2(sy - ty, sx - tx) + fx.rand(-1.3f, 1.3f);
            sv[i] = fx.dp(fx.rand(60, 170));
        }
        final float total = travel + hold + fx.D(420), after = fx.D(420), gravity = fx.dp(260);
        addMove(fx, k, KeyMove.PRESS, t0, fx.D(140), fx.dp(1.5f), null);
        // The muzzle gradient is built once; each frame only changes the paint alpha
        final Shader muzzle = new RadialGradient(sx, sy, fx.dp(10),
                new int[] { Fx.alpha(0xFFFFFFFF, 0.9f * s), Fx.alpha(col, 0.6f * s), Fx.alpha(col, 0) },
                new float[] { 0, 0.4f, 1 }, Shader.TileMode.CLAMP);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = t - t0;
                if (e < 0) return true;
                if (e >= total) return false;
                final float reach = Fx.easeIn(Fx.clamp01(e / travel));
                final float beamA = e < travel + hold ? 1 : 1 - Fx.clamp01((e - travel - hold) / fade);
                if (beamA > 0) {
                    final float hx = Fx.lerp(sx, tx, reach), hy = Fx.lerp(sy, ty, reach);
                    final Paint glow = fx.stroke(Fx.alpha(col, 0.85f * beamA * s), width * 1.8f * (0.5f + 0.5f * beamA));
                    glow.setShadowLayer(fx.dp(14), 0, 0, Fx.alpha(col, s));
                    c.drawLine(sx, sy, hx, hy, glow);
                    final Paint core = fx.stroke(Fx.alpha(0xFFFFFFFF, 0.9f * beamA * s), width * 0.55f);
                    c.drawLine(sx, sy, hx, hy, core);
                    // Muzzle glow on the backspace icon
                    final Paint mz = fx.fill(0xFFFFFFFF);
                    mz.setShader(muzzle);
                    mz.setAlpha((int) (255 * beamA));
                    c.drawCircle(sx, sy, fx.dp(10), mz);
                }
                if (e >= travel) {
                    final float u = (e - travel) / after, sec = (e - travel) / 1000f;
                    c.drawCircle(tx, ty, fx.dp(3) + fx.dp(14) * Fx.easeOut(u),
                            fx.stroke(Fx.alpha(col, (1 - u) * 0.9f * s), fx.dp(2) * (1 - u) + fx.dp(0.5f)));
                    final Paint spark = fx.fill(Fx.alpha(col, (1 - u) * s));
                    for (int i = 0; i < 10; i++) {
                        c.drawCircle(tx + (float) Math.cos(sa[i]) * sv[i] * sec,
                                ty + (float) Math.sin(sa[i]) * sv[i] * sec + gravity * sec * sec, fx.dp(1.5f), spark);
                    }
                }
                return true;
            }
        });
    }

    /** The letter the beam hits glows white-hot, swells and vaporizes. */
    private static void vaporizeLetter(final KeyFx fx, final FxLetter L, final float s, float delay) {
        final long t0 = fx.now() + (long) delay + (long) fx.D(fx.settings.f("del", "laserSpeed"));
        final float life = fx.D(380);
        final int label = fx.labelColor(), col = laserColor(fx);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = t - t0;
                if (e < 0) {
                    fx.plainText(c, L.ch, L.x, L.y, L.size, Fx.alpha(label, s), 0, 1);
                    return true;
                }
                final float u = e / life;
                if (u >= 1) return false;
                final int hot = u < 0.3f ? Fx.mix(label, 0xFFFFFFFF, u / 0.3f) : Fx.mix(0xFFFFFFFF, col, (u - 0.3f) / 0.7f);
                final float sc = 1 + 0.3f * Fx.easeOut(u);
                // The glow behind the glyph stands in for the lab's shadow blur
                fx.glowText(c, L.ch, L.x, L.y, L.size / fx.density, sc, (1 - u) * 0.6f * s, col, 0);
                fx.plainText(c, L.ch, L.x, L.y, L.size, Fx.alpha(hot, (float) Math.pow(1 - u, 1.5) * s), 0, sc);
                return true;
            }
        });
    }

    private static Bitmap grainBmp;
    private static int[] grainPx;
    private static Paint grainPaint;

    /** The glyph is rasterized once; each lit pixel becomes a grain at (dx, dy) from the glyph center. Returns {dx, dy, w}. */
    private static float[][] glyphGrains(String str, float size, float step) {
        final int w = (int) Math.ceil(size * 1.4f * Math.max(1, str.codePointCount(0, str.length())));
        final int h = (int) Math.ceil(size * 1.6f);
        // One scratch bitmap, buffer and paint, grown as needed (UI thread only)
        if (grainBmp == null || grainBmp.getWidth() < w || grainBmp.getHeight() < h) {
            if (grainBmp != null) grainBmp.recycle();
            grainBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        if (grainPx == null || grainPx.length < w * h) grainPx = new int[w * h];
        if (grainPaint == null) {
            grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            grainPaint.setColor(0xFFFFFFFF);
            grainPaint.setTextAlign(Paint.Align.CENTER);
        }
        final Bitmap bmp = grainBmp;
        final int[] px = grainPx;
        bmp.eraseColor(0);
        grainPaint.setTextSize(size);
        new Canvas(bmp).drawText(str, w / 2f, h / 2f + size * 0.36f, grainPaint);
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        final ArrayList<float[]> out = new ArrayList<>();
        for (float y = 0; y < h; y += step) {
            for (float x = 0; x < w; x += step) {
                if ((px[(int) Math.floor(y) * w + (int) Math.floor(x)] >>> 24) > 110) out.add(new float[] { x - w / 2f, y - h / 2f });
            }
        }
        out.add(new float[] { w, 0 });
        return out.toArray(new float[0][]);
    }

    /** The glyph crumbles from left to right into grains that drift on the wind and fade. */
    private static void disintegrate(final KeyFx fx, final FxLetter L, final float s, float delay) {
        if (L.ch == null || !(L.size > 0)) return;
        final long t0 = fx.now() + (long) delay;
        final float life = fx.D(fx.settings.f("del", "dustLife"));
        final String wind = fx.settings.s("del", "dustWind");
        final float[][] all = glyphGrains(L.ch, L.size, fx.dp(1.5f));
        final int n = all.length - 1;
        final float w = all[n][0];
        final float[] gx = new float[n], gy = new float[n], start = new float[n], vx = new float[n], vy = new float[n], ph = new float[n];
        for (int i = 0; i < n; i++) {
            gx[i] = all[i][0];
            gy[i] = all[i][1];
            start[i] = Fx.clamp01((gx[i] + w / 2) / w) * 0.45f + fx.rand(0, 0.12f);
            if ("up".equals(wind)) {
                vx[i] = fx.dp(fx.rand(-15, 15));
                vy[i] = -fx.dp(fx.rand(25, 60));
            } else if ("scatter".equals(wind)) {
                final float a = fx.rand(0, (float) Math.PI * 2), v = fx.dp(fx.rand(25, 80));
                vx[i] = (float) Math.cos(a) * v;
                vy[i] = (float) Math.sin(a) * v;
            } else {
                vx[i] = fx.dp(fx.rand(25, 70));
                vy[i] = -fx.dp(fx.rand(5, 30));
            }
            ph[i] = fx.rand(0, 6);
        }
        final float gs = fx.dp(fx.settings.f("del", "dustSize")), tiny = fx.dp(1.6f);
        final int color = fx.labelColor();
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float u = (t - t0) / life;
                if (u >= 1) return false;
                for (int i = 0; i < n; i++) {
                    final float local = (u - start[i]) / (1 - start[i]);
                    if (local <= 0) {
                        c.drawRect(L.x + gx[i] - tiny / 2, L.y + gy[i] - tiny / 2, L.x + gx[i] + tiny / 2,
                                L.y + gy[i] + tiny / 2, fx.fill(Fx.alpha(color, s)));
                        continue;
                    }
                    final float sec = local * life / 1000f;
                    final float x = L.x + gx[i] + vx[i] * sec + (float) Math.sin(ph[i] + sec * 9) * fx.dp(3);
                    final float y = L.y + gy[i] + vy[i] * sec;
                    final int col = local < 0.15f ? Fx.mix(color, fx.accent(), local / 0.15f) : fx.accent();
                    final float z = gs * (1 - local * 0.5f);
                    c.drawRect(x - z / 2, y - z / 2, x + z / 2, y + z / 2, fx.fill(Fx.alpha(col, (1 - local) * 0.9f * s)));
                }
                return true;
            }
        });
    }

    /** The key itself sheds a little dust from its edges. */
    private static void dustKey(final KeyFx fx, final FxKey k, final float s) {
        final long t0 = fx.now();
        final float life = fx.D(fx.settings.f("del", "dustLife") * 0.8f), size = fx.dp(fx.settings.f("del", "dustSize")) * 1.4f;
        final boolean up = "up".equals(fx.settings.s("del", "dustWind"));
        final float[] x = new float[26], y = new float[26], vx = new float[26], vy = new float[26], d = new float[26];
        for (int i = 0; i < 26; i++) {
            final float[] p = edgePoint(k, fx.rand(0, 2 * (k.w() + k.h())));
            x[i] = p[0];
            y[i] = p[1];
            vx[i] = up ? fx.dp(fx.rand(-10, 10)) : fx.dp(fx.rand(15, 55));
            vy[i] = -fx.dp(fx.rand(10, 45));
            d[i] = fx.rand(0, 0.3f);
        }
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float u = (t - t0) / life;
                if (u >= 1) return false;
                for (int i = 0; i < 26; i++) {
                    final float l = (u - d[i]) / (1 - d[i]);
                    if (l <= 0) continue;
                    final float sec = l * life / 1000f, px = x[i] + vx[i] * sec - fx.dp(1), py = y[i] + vy[i] * sec - fx.dp(1);
                    c.drawRect(px, py, px + size, py + size, fx.fill(Fx.alpha(fx.accent(), (1 - l) * 0.8f * s)));
                }
                return true;
            }
        });
    }

    /** Rewind: the letter flies back down into the key that typed it, like letter float in reverse. */
    private static void rewindLetter(final KeyFx fx, final FxLetter L, FxKey delKey, final float s, float delay) {
        final long t0 = fx.now() + (long) delay;
        final float dur = fx.D(520);
        final int label = fx.labelColor();
        FxKey home = null;
        for (FxKey q : fx.layout().keys) {
            if (q.type == FxKey.CHAR && L.ch.equalsIgnoreCase(q.glyph())) {
                home = q;
                break;
            }
        }
        final FxKey k = home != null ? home : delKey;
        final float cx = (L.x + k.cx()) / 2 + fx.dp(fx.rand(-30, 30)), cy = Math.min(L.y, k.cy()) - fx.dp(30);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float u = (t - t0) / dur;
                if (u >= 1) {
                    FxParts.pop(fx, k);
                    return false;
                }
                if (u < 0) {
                    fx.plainText(c, L.ch, L.x, L.y, L.size, Fx.alpha(label, s), 0, 1);
                    return true;
                }
                final float p = Fx.easeIn(u * 0.7f + 0.3f * u * u), m = 1 - p;
                // Quadratic curve: up and over, then down into the key
                final float x = m * m * L.x + 2 * m * p * cx + p * p * k.cx(), y = m * m * L.y + 2 * m * p * cy + p * p * k.cy();
                final int col = u < 0.3f ? Fx.alpha(Fx.mix(label, fx.accent(), u / 0.3f), s) : Fx.alpha(fx.accent(), (1 - p * 0.6f) * s);
                fx.plainText(c, L.ch, x, y, L.size, col, 0, 1 + 0.25f * (float) Math.sin(Math.PI * u) - 0.4f * p);
                return true;
            }
        });
    }

    // Steps of random jitter laid out ahead of a glitch, so it flickers the same at any frame rate
    private static final int GLITCH_STEPS = 14;

    /** Glitch: the letter splits into red, green and blue copies, jitters in slices, and blinks out. */
    private static void glitchLetter(final KeyFx fx, final FxLetter L, final float s, float delay) {
        final long t0 = fx.now() + (long) delay;
        final float dur = fx.D(420), glitch = fx.dp(fx.settings.f("del", "glitch"));
        final int label = fx.labelColor();
        final boolean[] blink = new boolean[GLITCH_STEPS];
        final float[][] ch = new float[GLITCH_STEPS][6], sl = new float[GLITCH_STEPS][9];
        for (int i = 0; i < GLITCH_STEPS; i++) {
            blink[i] = fx.rnd.nextFloat() < 0.35f;
            for (int j = 0; j < 3; j++) {
                ch[i][j] = fx.rand(0.5f, 1.2f);
                ch[i][3 + j] = fx.rand(-1, 1);
                sl[i][j] = fx.rand(0, 20);
                sl[i][3 + j] = fx.rand(2, 5);
                sl[i][6 + j] = fx.rand(-1, 1);
            }
        }
        final int[] cols = { 0xFFFF2A55, 0xFF22FF88, 0xFF3A7BFF };
        final Paint add = new Paint();
        add.setXfermode(FxParts.ADD);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float u = (t - t0) / dur;
                if (u >= 1) return false;
                if (u < 0) {
                    fx.plainText(c, L.ch, L.x, L.y, L.size, Fx.alpha(label, s), 0, 1);
                    return true;
                }
                final int st = Math.min(GLITCH_STEPS - 1, (int) (u * GLITCH_STEPS));
                // Blink: a couple of dropped frames near the end
                if (u > 0.6f && blink[st]) return true;
                final float amp = glitch * (0.4f + u);
                final float pad = amp + L.size;
                c.saveLayer(L.x - pad, L.y - L.size, L.x + pad, L.y + L.size, add);
                for (int j = 0; j < 3; j++) {
                    fx.plainText(c, L.ch, L.x + (j - 1) * amp * ch[st][j], L.y + ch[st][3 + j] * amp * 0.3f, L.size,
                            Fx.alpha(cols[j], (1 - u) * 0.9f * s), 0, 1);
                }
                c.restore();
                // A few displaced horizontal slices of the real glyph
                for (int j = 0; j < 3; j++) {
                    final float sy = L.y - fx.dp(10) + fx.dp(sl[st][j]), sh = fx.dp(sl[st][3 + j]), off = sl[st][6 + j] * amp * 1.5f;
                    c.save();
                    c.clipRect(L.x - fx.dp(20) + off, sy, L.x + fx.dp(20) + off, sy + sh);
                    fx.plainText(c, L.ch, L.x + off, L.y, L.size, Fx.alpha(label, (1 - u) * s), 0, 1);
                    c.restore();
                }
                return true;
            }
        });
    }

    /** The key face glitches: displaced slices of the key flicker over it, and the real key jitters sideways. */
    private static void glitchKey(final KeyFx fx, final FxKey k, final float s) {
        final long t0 = fx.now();
        final float dur = fx.D(260), glitch = fx.dp(fx.settings.f("del", "glitch"));
        final float[][] strip = new float[GLITCH_STEPS][4 * 6];
        final float[] jitter = new float[GLITCH_STEPS];
        for (int i = 0; i < GLITCH_STEPS; i++) {
            for (int j = 0; j < 4; j++) {
                strip[i][j * 6] = fx.rand(0, k.h() - fx.dp(4));
                strip[i][j * 6 + 1] = fx.dp(fx.rand(2, 6));
                strip[i][j * 6 + 2] = fx.rand(-1, 1) * glitch * 2;
                strip[i][j * 6 + 3] = fx.rnd.nextFloat() < 0.5f ? 1 : 0;
                strip[i][j * 6 + 4] = fx.rand(0.3f, 1);
                // Its own offset for the scanline
                strip[i][j * 6 + 5] = fx.dp(fx.rand(-3, 3));
            }
            jitter[i] = fx.rand(-1, 1) * glitch * 2;
        }
        // The opaque key-colored slices only suit hosts that move the real key too
        final boolean slices = (fx.caps & KeyFx.CAP_KEY_TRANSFORM) != 0;
        addMove(fx, k, KeyMove.JITTER, t0, dur, 0, jitter);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float u = (t - t0) / dur;
                if (u >= 1) return false;
                final float[] a = strip[Math.min(GLITCH_STEPS - 1, (int) (u * GLITCH_STEPS))];
                for (int j = 0; j < 4; j++) {
                    final float y = k.top + a[j * 6], off = a[j * 6 + 2];
                    if (slices) {
                        c.drawRect(k.left + off, y, k.right + off, y + a[j * 6 + 1], fx.fill(Fx.alpha(fx.keyFill(), (1 - u) * s)));
                    }
                    final float lx = k.left + off + a[j * 6 + 5];
                    c.drawRect(lx, y, lx + k.w() * a[j * 6 + 4], y + fx.dp(1),
                            fx.fill(Fx.alpha(a[j * 6 + 3] > 0 ? 0xFFFF2A55 : 0xFF22D3FF, 0.5f * (1 - u) * s)));
                }
                return true;
            }
        });
    }

    /** Shredder: the letter is cut into vertical strips that drop out of a slot, each at its own pace. */
    private static void shredLetter(final KeyFx fx, final FxLetter L, final float s, float delay) {
        final long t0 = fx.now() + (long) delay;
        final float dur = fx.D(750);
        final int n = Math.round(fx.settings.f("del", "strips")), label = fx.labelColor();
        final float w = Math.max(L.w, fx.dp(10)) + fx.dp(4), sw = w / n, half = Math.max(fx.dp(14), L.size * 0.7f);
        final float[] x0 = new float[n], vy = new float[n], spin = new float[n], d = new float[n];
        for (int i = 0; i < n; i++) {
            x0[i] = L.x - w / 2 + i * sw;
            vy[i] = fx.dp(fx.rand(140, 260));
            spin[i] = fx.rand(-1.2f, 1.2f);
            d[i] = fx.rand(0, 0.12f);
        }
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float u = (t - t0) / dur;
                if (u >= 1) return false;
                if (u < 0) {
                    fx.plainText(c, L.ch, L.x, L.y, L.size, Fx.alpha(label, s), 0, 1);
                    return true;
                }
                for (int i = 0; i < n; i++) {
                    final float lu = Fx.clamp01((u - d[i]) / (1 - d[i])), sec = lu * dur / 1000f;
                    c.save();
                    c.translate(x0[i] + sw / 2, L.y + vy[i] * sec * sec * 3);
                    c.rotate((float) Math.toDegrees(spin[i] * lu));
                    c.clipRect(-sw / 2 + fx.dp(0.4f), -half, sw / 2 - fx.dp(0.4f), half);
                    c.translate(-(x0[i] + sw / 2), -L.y);
                    fx.plainText(c, L.ch, L.x, L.y, L.size,
                            Fx.alpha(lu < 0.1f ? Fx.mix(label, fx.accent(), lu * 10) : fx.accent(), (1 - lu) * s), 0, 1);
                    c.restore();
                }
                return true;
            }
        });
    }

    /** The shredder slot: a bright line across the key with teeth. */
    private static void shredKey(final KeyFx fx, final FxKey k, final float s) {
        final long t0 = fx.now();
        final float dur = fx.D(300);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float u = (t - t0) / dur;
                if (u >= 1) return false;
                final float y = k.top + k.h() * 0.5f;
                c.drawLine(k.left + fx.dp(6), y, k.right - fx.dp(6), y, fx.stroke(Fx.alpha(fx.accent(), (1 - u) * s), fx.dp(2)));
                final Paint teeth = fx.stroke(Fx.alpha(fx.accent(), (1 - u) * s), fx.dp(1));
                for (float x = k.left + fx.dp(8); x < k.right - fx.dp(6); x += fx.dp(5)) {
                    c.drawLine(x, y, x + fx.dp(2), y + fx.dp(3) * (1 - u), teeth);
                }
                return true;
            }
        });
    }

    /** One tap's letters fly into backspace: swirling in, or along a curve. The key flashes as each lands. */
    private static void flyToKey(final KeyFx fx, final FxLetter L, final FxKey k, String tap, final float s, float delay) {
        final long t0 = fx.now() + (long) delay;
        final float dur = fx.D(620), dx = L.x - k.cx(), dy = L.y - k.cy(), d0 = (float) Math.hypot(dx, dy);
        final float a0 = (float) Math.atan2(dy, dx);
        final boolean swirl = "swirl".equals(tap);
        final float c1x = L.x + fx.dp(fx.rand(-60, 60)), c1y = L.y + fx.dp(fx.rand(20, 60));
        final float c2x = k.cx() + fx.dp(fx.rand(-60, 60)), c2y = k.cy() - fx.dp(fx.rand(50, 110));
        final int label = fx.labelColor();
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float u = (t - t0) / dur;
                if (u >= 1) {
                    FxParts.ringPop(fx, k.cx(), k.cy(), fx.dp(10), s * 0.6f);
                    FxParts.pop(fx, k);
                    return false;
                }
                if (u < 0) {
                    fx.plainText(c, L.ch, L.x, L.y, L.size, Fx.alpha(label, s), 0, 1);
                    return true;
                }
                final float x, y, sc;
                if (swirl) {
                    final float dist = d0 * (float) Math.pow(1 - u, 1.4), ang = a0 + u * (float) Math.PI * 2.5f;
                    x = k.cx() + (float) Math.cos(ang) * dist;
                    y = k.cy() + (float) Math.sin(ang) * dist;
                    sc = 1 - 0.8f * u;
                } else {
                    final float p = (float) Math.pow(u, 1.5), m = 1 - p;
                    x = m * m * m * L.x + 3 * m * m * p * c1x + 3 * m * p * p * c2x + p * p * p * k.cx();
                    y = m * m * m * L.y + 3 * m * m * p * c1y + 3 * m * p * p * c2y + p * p * p * k.cy();
                    sc = 1 - 0.65f * p;
                }
                fx.glowText(c, L.ch, x, y, 18, sc, (1 - u * 0.4f) * s, fx.accent(), 0);
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
