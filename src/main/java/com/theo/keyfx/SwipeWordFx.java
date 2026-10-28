package com.theo.keyfx;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** What happens to the swiped letters and the word: letter hop, constellation, commit burst. */
final class SwipeWordFx extends FxModule {
    private static final String G = "Swipe word";

    static Section[] sections() {
        return new Section[] {
                new Section("hop", G, 510, "Letter hop",
                        "Picked letters gather on the number row. When the word lands, the letters that are in it and the extra ones that are not each get their own exit.",
                        true, true,
                        Field.choice("collect", "Gathering", "arc", "arc:Hop", "bounce:Drop and bounce", "zip:Zip", "spin:Spin"),
                        Field.choice("word", "Letters in the word", "join", "join:Join the suggestion", "rise:Rise away", "text:Fly into the text",
                                "form:Form the word", "glow:Glow and fade", "type:Typewriter"),
                        Field.choice("extra", "Extra letters", "fall", "fall:Fall", "burn:Burn up", "dust:Crumble to dust",
                                "fly:Fly to backspace", "shake:Shake off", "fade:Shrink away"),
                        Field.toggle("mark", "Grey out the extra letters first", true),
                        Field.toggle("highlight", "Light up the predicted word", true).requires(KeyFx.CAP_SUGGESTION_STYLE),
                        Field.range("grow", "Predicted letter growth", 1, 1.6f, 0.01f, "×", 1.22f)
                                .when("highlight", "true").requires(KeyFx.CAP_SUGGESTION_STYLE),
                        Field.strength()),
                new Section("constellation", G, 520, "Constellation",
                        "The picked letters become stars joined by lines, then fold into the suggestion bar.", true, false,
                        Field.range("star", "Star size", 2, 10, 0.5f, "dp", 5),
                        Field.range("glow", "Line glow", 0, 2, 0.05f, "×", 1),
                        Field.range("fold", "Fold time", 150, 1000, 10, "ms", 420),
                        Field.strength()),
                new Section("burst", G, 530, "Commit burst", "A small burst of confetti in the suggestion bar when the word lands.",
                        true, true,
                        Field.range("count", "Confetti", 4, 40, 1, "", 18),
                        Field.strength()),
        };
    }

    SwipeWordFx(KeyFx fx) {
        super(fx);
    }

    /** Where the word lands: the suggestion bar's center, or just above the keys. */
    private float[] wordPoint() {
        final RectF sg = fx.layout().suggestions;
        if (sg.width() > 0 && sg.height() > 0) return new float[] { sg.centerX(), sg.centerY() };
        final RectF kb = fx.layout().keyboard;
        return new float[] { kb.centerX(), Math.max(fx.dp(14), kb.top - fx.dp(20)) };
    }

    // region Letter hop
    private static final class HopLetter {
        String g;
        float fromX, fromY, x, y;
        long t0;
        float vx, spin, ph; // precomputed drift, tumble and wobble phase
        String wg; // the word's glyph this letter stands for
        int wi = -1; // index in the formed word, or -1 for an extra letter
        int order; // position among the word's letters, or among the extras
        boolean embers, landed, dusted, clacked;
    }

    private int grey() {
        return Fx.mix(fx.labelColor(), fx.keyFill(), 0.35f);
    }

    private static float easeOutBounce(float x) {
        x = Fx.clamp01(x);
        final float n = 7.5625f, d = 2.75f;
        if (x < 1 / d) return n * x * x;
        if (x < 2 / d) {
            x -= 1.5f / d;
            return n * x * x + 0.75f;
        }
        if (x < 2.5f / d) {
            x -= 2.25f / d;
            return n * x * x + 0.9375f;
        }
        x -= 2.625f / d;
        return n * x * x + 0.984375f;
    }

    private final class Hop extends Effect {
        final ArrayList<HopLetter> letters = new ArrayList<>();
        final FxKey[] row;
        boolean collecting = true;
        String wordMode, extraMode, word;
        boolean mark;
        int wordCount, wordLetters;
        long exitAt;
        float textX, textY;
        float[] wordAt = new float[2];

        Hop(FxKey[] row) {
            this.row = row;
        }

        @Override
        public boolean draw(Canvas c, long t) {
            if (row.length == 0) return false;
            final float s = fx.S("hop");
            final int n = letters.size();
            final float left = row[0].left, right = row[row.length - 1].right, slotY = row[0].cy();
            boolean alive = collecting;

            // Cover the row's labels while letters sit on it, so the two don't mix
            final float cover = collecting ? 1 : Fx.clamp01(1 - (t - exitAt) / fx.D(500));
            if (cover > 0) {
                final Paint p = fx.fill(Fx.alpha(fx.keyFill(), 0.85f * cover));
                for (FxKey k : row) fx.roundRect(c, k.left, k.top, k.right, k.bottom, 9, p);
            }

            final String style = fx.settings.s("hop", "collect");
            for (int i = 0; i < n; i++) {
                final HopLetter l = letters.get(i);
                if (collecting) {
                    final float tx = n <= row.length ? row[i].cx() : left + (i + 0.5f) * (right - left) / n;
                    collectLetter(c, l, tx, slotY, t, s, style);
                } else if (exitLetter(c, l, t, s)) {
                    alive = true;
                }
            }
            return alive || cover > 0;
        }

        /** A letter on its way from its key to its slot on the number row, then resting there. */
        void collectLetter(Canvas c, HopLetter l, float tx, float ty, long t, float s, String style) {
            final boolean zip = "zip".equals(style), bounce = "bounce".equals(style);
            final float dur = fx.D(zip ? 170 : bounce ? 420 : 280), p = Fx.clamp01((t - l.t0) / dur), ep = Fx.easeOut(p);
            if (p >= 1) {
                l.x += (tx - l.x) * 0.3f;
                l.y = ty;
                fx.glowText(c, l.g, l.x, l.y, 20, 1, s, fx.accent(), 0);
                return;
            }
            float x, y, rot = 0;
            if (bounce) {
                // Up above the slot, then drop onto it and bounce
                final float up = Fx.clamp01(p / 0.4f), down = Fx.clamp01((p - 0.4f) / 0.6f);
                x = Fx.lerp(l.fromX, tx, Fx.easeOut(up));
                y = p < 0.4f ? Fx.lerp(l.fromY, ty - fx.dp(34), Fx.easeOut(up))
                        : Fx.lerp(ty - fx.dp(34), ty, easeOutBounce(down));
            } else if (zip) {
                x = Fx.lerp(l.fromX, tx, ep);
                y = Fx.lerp(l.fromY, ty, ep);
                // A short streak behind it
                final float bp = Fx.easeOut(Math.max(0, p - 0.3f));
                final Paint sp = fx.stroke(Fx.alpha(fx.accent(), 0.5f * (1 - p) * s), fx.dp(3));
                c.drawLine(Fx.lerp(l.fromX, tx, bp), Fx.lerp(l.fromY, ty, bp), x, y, sp);
            } else {
                x = Fx.lerp(l.fromX, tx, ep);
                y = Fx.lerp(l.fromY, ty, ep) - fx.dp(24) * (float) Math.sin(Math.PI * ep);
                if ("spin".equals(style)) rot = (1 - ep) * (float) Math.PI * 2;
            }
            l.x = x;
            l.y = y;
            fx.glowText(c, l.g, x, y, 20, 0.8f + 0.2f * ep, Fx.clamp01(p * 4) * s, fx.accent(), rot);
        }

        /** After the word lands: extras leave first (greyed if asked), then the word's own letters. */
        boolean exitLetter(Canvas c, HopLetter l, long t, float s) {
            if (l.wi < 0) return exitExtra(c, l, t, s);
            final boolean together = "join".equals(wordMode), typed = "type".equals(wordMode);
            final float delay = fx.D(mark ? 200 : 80) + (together ? 0 : l.order * fx.D(typed ? 90 : 40));
            final float e = (t - exitAt) - delay;
            if (e < 0) {
                fx.glowText(c, l.g, l.x, l.y, 20, 1.08f, s, fx.accent(), 0);
                return true;
            }
            final float[] wp = wordAt;
            final float ty = wp[1];
            final int m = wordCount;
            if ("text".equals(wordMode)) {
                // Each letter flies to its own spot in the text it just became
                final float u = e / fx.D(480);
                if (u >= 1) return false;
                final float p = Fx.easeOut(u), tx = textX + (l.wi - (m - 1) / 2f) * fx.dp(8.5f);
                fx.glowText(c, l.wg, Fx.lerp(l.x, tx, p), Fx.lerp(l.y, textY, p) - fx.dp(50) * (float) Math.sin(Math.PI * p),
                        20, 1 - 0.3f * p, (1 - u * u) * s, fx.accent(), 0);
                return true;
            } else if ("form".equals(wordMode)) {
                final float u = e / fx.D(260), hold = fx.D(380);
                final float tx = wp[0] + (l.wi - (m - 1) / 2f) * fx.dp(13);
                if (e > fx.D(260) + hold + fx.D(240)) return false;
                final float pu = Fx.clamp01(u), x = Fx.lerp(l.x, tx, Fx.easeOutBack(pu)), y = Fx.lerp(l.y, ty, Fx.easeOutBack(pu));
                final float popU = Fx.clamp01((e - fx.D(260) - hold) / fx.D(240));
                fx.glowText(c, l.wg, x, y, 20, 1.1f + 0.4f * popU, (1 - popU) * s, fx.accent(), 0);
                return true;
            } else if (typed) {
                // Typewriter: each letter is struck into the suggestion bar in order, with a carriage caret
                final float tx = wp[0] + (l.wi - (m - 1) / 2f) * fx.dp(12);
                final float fly = Fx.clamp01(e / fx.D(140)), endAt = fx.D(90) * (wordLetters - l.order) + fx.D(700);
                if (e > endAt + fx.D(250)) return false;
                final float out = Fx.clamp01((e - endAt) / fx.D(250));
                final float x = Fx.lerp(l.x, tx, Fx.easeIn(fly)), y = Fx.lerp(l.y, ty, Fx.easeIn(fly));
                final float stamp = fly >= 1 ? 1 + 0.4f * (1 - Fx.easeOut(Fx.clamp01((e - fx.D(140)) / fx.D(120)))) : 1;
                fx.glowText(c, l.wg, x, y, 20, stamp, (1 - out) * s, fx.accent(), 0);
                if (fly >= 1 && l.order == wordLetters - 1 && out < 1 && ((int) (e / 260)) % 2 == 0) {
                    // The caret blinks after the last letter
                    c.drawRect(tx + fx.dp(8), ty - fx.dp(9), tx + fx.dp(10), ty + fx.dp(9), fx.fill(Fx.alpha(fx.accent(), (1 - out) * s)));
                }
                return true;
            } else if (together) {
                // Pulled into their places in the suggested word, faster and faster, then the word fades
                final float tx = wp[0] + (l.wi - (m - 1) / 2f) * fx.dp(11.5f);
                final float pull = Fx.clamp01(e / fx.D(380));
                if (pull < 1) {
                    final float p = pull * pull * pull;
                    fx.glowText(c, l.g, Fx.lerp(l.x, tx, p), Fx.lerp(l.y, ty, p), 20, 1 - 0.15f * p, s, fx.accent(), 0);
                    return true;
                }
                if (l.order == 0 && !l.clacked) {
                    l.clacked = true;
                    FxParts.ringPop(fx, wp[0], ty, fx.dp(28), s * 0.7f);
                }
                final float f = Fx.clamp01((e - fx.D(380) - fx.D(160)) / fx.D(450));
                if (f >= 1) return false;
                fx.glowText(c, l.wg, tx, ty, 20, 0.85f, (1 - f) * s,
                        Fx.mix(0xFFFFFFFF, fx.accent(), Fx.clamp01((e - fx.D(380)) / fx.D(200))), 0);
                return true;
            } else if ("glow".equals(wordMode)) {
                final float u = e / fx.D(450);
                if (u >= 1) return false;
                fx.glowText(c, l.g, l.x, l.y, 20, 1 + 0.35f * Fx.easeOut(u), (1 - u) * s, Fx.mix(0xFFFFFFFF, fx.accent(), u), 0);
                return true;
            }
            // Rise: a little hop, then up and away in a wave
            final float u = e / fx.D(560);
            if (u >= 1) return false;
            final float y = l.y - fx.dp(10) * (float) Math.sin(Math.PI * Fx.clamp01(u * 3)) - fx.dp(80) * Fx.easeIn(u);
            fx.glowText(c, l.g, l.x, y, 20, 1 + 0.2f * u, (1 - u * u) * s, fx.accent(), 0);
            return true;
        }

        /** An extra letter (not in the word): greyed first if asked, then its own exit. */
        boolean exitExtra(Canvas c, HopLetter l, long t, float s) {
            final float e = (t - exitAt) - ((mark ? fx.D(160) : 0) + l.order * fx.D(25));
            if (e < 0) {
                // Marked: the extras dim to grey for a moment, so it is clear they were not in the word
                final float k = mark ? Fx.clamp01((t - exitAt) / fx.D(120)) : 0;
                fx.glowText(c, l.g, l.x, l.y, 20, 1 - 0.1f * k, (1 - 0.35f * k) * s, Fx.mix(fx.accent(), grey(), k), 0);
                return true;
            }
            final int col = mark ? grey() : fx.accent();
            if ("burn".equals(extraMode)) {
                final float u = e / fx.D(520);
                if (u >= 1) return false;
                if (!l.embers) {
                    l.embers = true;
                    FxParts.emberBurst(fx, l.x, l.y, s);
                }
                fx.glowText(c, l.g, l.x + fx.dp((float) Math.sin(l.ph + u * 40)) * (1 - u), l.y - fx.dp(6) * u, 20, 1 - 0.4f * u,
                        (1 - u) * s, Fx.mix(col, 0xFFFF8A3D, Fx.clamp01(u * 2.5f)), 0);
                return true;
            } else if ("dust".equals(extraMode)) {
                if (!l.dusted) {
                    l.dusted = true;
                    crumble(l.g, l.x, l.y, col, s);
                }
                return false;
            } else if ("fly".equals(extraMode)) {
                final FxKey del = fx.layout().find(FxKey.DELETE);
                final float u = e / fx.D(420);
                if (u >= 1 || del == null) {
                    if (!l.landed && del != null) {
                        l.landed = true;
                        FxParts.pop(fx, del);
                    }
                    return false;
                }
                final float p = Fx.easeIn(u) * 0.6f + u * 0.4f;
                fx.glowText(c, l.g, Fx.lerp(l.x, del.cx(), p), Fx.lerp(l.y, del.cy(), p) - fx.dp(40) * (float) Math.sin(Math.PI * p),
                        20, 1 - 0.65f * p, (1 - u * 0.5f) * s, col, 0);
                return true;
            } else if ("shake".equals(extraMode)) {
                final float u = e / fx.D(420);
                if (u >= 1) return false;
                fx.glowText(c, l.g, l.x + fx.dp(4) * (float) Math.sin(u * 32) * (1 - u), l.y, 20, 1, (1 - u) * s,
                        Fx.mix(col, 0xFFF28B82, 0.6f), 0);
                return true;
            } else if ("fade".equals(extraMode)) {
                final float u = e / fx.D(300);
                if (u >= 1) return false;
                fx.glowText(c, l.g, l.x, l.y, 20, 1 - u, (1 - u) * s, col, 0);
                return true;
            }
            // Fall
            final float u = e / fx.D(700), sec = e / 1000f;
            if (u >= 1) return false;
            fx.glowText(c, l.g, l.x + fx.dp(l.vx * sec), l.y + fx.dp(-50 * sec + 900 * sec * sec), 20, 1, (1 - u) * s, col, l.spin * sec);
            return true;
        }

        void add(FxKey k) {
            if (!collecting || letters.size() >= 24) return;
            final HopLetter l = new HopLetter();
            l.g = k.glyph();
            l.fromX = l.x = k.cx();
            l.fromY = l.y = k.cy();
            l.t0 = fx.now();
            l.vx = fx.rand(-40, 40);
            l.spin = fx.rand(-4, 4);
            l.ph = fx.rand(0, 6);
            letters.add(l);
            fx.kick();
        }

        void finish(String w) {
            collecting = false;
            wordMode = fx.settings.s("hop", "word");
            extraMode = fx.settings.s("hop", "extra");
            mark = fx.settings.b("hop", "mark");
            exitAt = fx.now();
            if (w == null || w.isEmpty()) {
                // No word arrived: treat the picked letters themselves as the word
                final StringBuilder sb = new StringBuilder();
                for (HopLetter l : letters) sb.append(l.g);
                w = sb.toString();
            }
            word = w;
            wordCount = Math.max(1, w.codePointCount(0, w.length()));
            // Roughly where the text field sits: above the suggestion bar, or above the keys when there is none
            final RectF sg = fx.layout().suggestions;
            textY = sg.width() > 0 && sg.height() > 0 ? Math.max(fx.dp(14), sg.top - fx.dp(30)) : wordPoint()[1];
            wordAt = wordPoint();
            textX = wordAt[0];
            // Match the word's letters to picked letters in order: matched ones are "in the word"
            int j = 0;
            for (int wi = 0, cp = 0; cp < w.length(); wi++) {
                final int cpv = w.codePointAt(cp);
                final String ch = new String(Character.toChars(cpv));
                cp += Character.charCount(cpv);
                for (int k = j; k < letters.size(); k++) {
                    if (letters.get(k).g.equalsIgnoreCase(ch)) {
                        letters.get(k).wi = wi;
                        letters.get(k).wg = ch;
                        j = k + 1;
                        break;
                    }
                }
            }
            int a = 0, b = 0;
            for (HopLetter l : letters) l.order = l.wi >= 0 ? a++ : b++;
            wordLetters = a;
            fx.kick();
        }
    }

    /** An extra letter crumbles into grains that drift up and twinkle out. */
    private void crumble(String g, final float x, final float y, final int color, final float s) {
        final long t0 = fx.now();
        final float life = fx.D(800), w = fx.dp(12), h = fx.dp(14);
        final int n = 28;
        final float[] dx = new float[n], dy = new float[n], start = new float[n], vx = new float[n], vy = new float[n], ph = new float[n];
        for (int i = 0; i < n; i++) {
            dx[i] = fx.rand(-w / 2, w / 2);
            dy[i] = fx.rand(-h / 2, h / 2);
            start[i] = Fx.clamp01((dx[i] + w / 2) / w) * 0.45f + fx.rand(0, 0.12f);
            vx[i] = fx.dp(fx.rand(-15, 15));
            vy[i] = -fx.dp(fx.rand(25, 60));
            ph[i] = fx.rand(0, 6);
        }
        final float gs = fx.dp(1.8f);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float u = (t - t0) / life;
                if (u >= 1) return false;
                for (int i = 0; i < n; i++) {
                    final float local = (u - start[i]) / (1 - start[i]);
                    if (local <= 0) {
                        c.drawRect(x + dx[i] - 1, y + dy[i] - 1, x + dx[i] + 1, y + dy[i] + 1, fx.fill(Fx.alpha(color, s)));
                        continue;
                    }
                    final float sec = local * life / 1000f;
                    final float px = x + dx[i] + vx[i] * sec + fx.dp(3) * (float) Math.sin(ph[i] + sec * 9), py = y + dy[i] + vy[i] * sec;
                    final int col = local < 0.15f ? Fx.mix(color, fx.accent(), local / 0.15f) : fx.accent();
                    final float z = gs * (1 - local * 0.5f);
                    c.drawRect(px - z / 2, py - z / 2, px + z / 2, py + z / 2, fx.fill(Fx.alpha(col, (1 - local) * 0.9f * s)));
                }
                return true;
            }
        });
    }

    private static String glyphAt(String s, int index) {
        final int at = s.offsetByCodePoints(0, Math.min(index, s.codePointCount(0, s.length()) - 1));
        return new String(Character.toChars(s.codePointAt(at)));
    }

    /** The number row, or the top letter row when the layout has none. */
    private FxKey[] hopRow() {
        final FxLayout layout = fx.layout();
        if (layout.numberRow.length > 0) return layout.numberRow;
        float top = Float.MAX_VALUE;
        for (FxKey k : layout.keys) if (k.type == FxKey.CHAR && k.isLetter()) top = Math.min(top, k.top);
        final ArrayList<FxKey> row = new ArrayList<>();
        for (FxKey k : layout.keys) if (k.type == FxKey.CHAR && Math.abs(k.top - top) < fx.dp(4)) row.add(k);
        final FxKey[] out = row.toArray(new FxKey[0]);
        Arrays.sort(out, new Comparator<FxKey>() {
            @Override
            public int compare(FxKey a, FxKey b) {
                return Float.compare(a.left, b.left);
            }
        });
        return out;
    }

    private Hop hop;

    // endregion

    // region Constellation

    private final class Constellation extends Effect {
        final ArrayList<float[]> pts = new ArrayList<>(); // x, y, time since base
        final long base = fx.now();
        long foldAt;

        @Override
        public boolean draw(Canvas c, long t) {
            final float s = fx.S("constellation"), starR = fx.dp(fx.settings.f("constellation", "star"));
            final float foldDur = fx.D(fx.settings.f("constellation", "fold"));
            final float[] target = wordPoint();
            float fold = 0;
            if (foldAt != 0) {
                fold = Fx.clamp01((t - foldAt) / foldDur);
                if (fold >= 1) {
                    final float fp = Fx.clamp01((t - foldAt - foldDur) / fx.D(260));
                    if (fp >= 1) return false;
                    fx.star(c, target[0], target[1], fx.dp(6 + 18 * Fx.easeOut(fp)), 4, 0.25f, fp,
                            fx.fill(Fx.alpha(fx.accent(), (1 - fp) * s)));
                    return true;
                }
            }
            if (pts.isEmpty()) return foldAt == 0;
            final float ef = Fx.easeIn(fold);
            final Path line = new Path();
            for (int i = 0; i < pts.size(); i++) {
                final float x = Fx.lerp(pts.get(i)[0], target[0], ef), y = Fx.lerp(pts.get(i)[1], target[1], ef);
                if (i == 0) line.moveTo(x, y);
                else line.lineTo(x, y);
            }
            final Paint lp = fx.stroke(Fx.alpha(fx.accent(), 0.55f * (1 - fold * 0.5f) * s), fx.dp(1.4f));
            final float glow = fx.settings.f("constellation", "glow");
            if (glow > 0) lp.setShadowLayer(fx.dp(4) * glow, 0, 0, Fx.alpha(fx.accent(), s));
            c.drawPath(line, lp);
            for (float[] p : pts) {
                final float x = Fx.lerp(p[0], target[0], ef), y = Fx.lerp(p[1], target[1], ef);
                final float age = t - base - p[2];
                final float grow = Fx.easeOutBack(Fx.clamp01(age / fx.D(220)));
                final Paint sp = fx.fill(Fx.alpha(0xFFFFFFFF, 0.95f * s));
                if (glow > 0) sp.setShadowLayer(fx.dp(4) * glow, 0, 0, Fx.alpha(fx.accent(), s));
                fx.star(c, x, y, starR * grow * (1 - fold * 0.5f), 4, 0.35f, age / 600f, sp);
            }
            return true;
        }
    }

    private Constellation cons;

    // The highlight (hosts with CAP_SUGGESTION_STYLE): letters crossed so far and the host's guess
    private final StringBuilder crossed = new StringBuilder();
    private String guess;

    private void updateWord() {
        final boolean wanted = (fx.caps & KeyFx.CAP_SUGGESTION_STYLE) != 0 && fx.on("hop")
                && fx.settings.b("hop", "highlight") && guess != null && !guess.isEmpty();
        final int lit = wanted ? Fx.litLetters(guess, crossed.toString()) : 0;
        fx.setWord(lit > 0 ? new WordLook(guess, lit, fx.settings.f("hop", "grow"), fx.accent()) : null);
    }

    // endregion

    @Override
    public void onSwipeStart() {
        crossed.setLength(0);
        guess = null;
        fx.setWord(null);
        // A previous word still collecting gives way to the new swipe
        if (hop != null && hop.collecting) hop.finish(null);
        hop = null;
        cons = null;
        if (fx.on("hop")) {
            final FxKey[] row = hopRow();
            if (row.length > 0) {
                hop = new Hop(row);
                fx.add(hop);
            }
        }
        if (fx.on("constellation")) {
            cons = new Constellation();
            fx.add(cons);
        }
    }

    @Override
    public void onSwipeCross(FxKey k, int index) {
        if ((fx.caps & KeyFx.CAP_SUGGESTION_STYLE) == 0) return;
        final String g = k.glyph();
        if (g != null) crossed.append(g);
        updateWord();
    }

    @Override
    public void onSwipeGuess(String word) {
        if ((fx.caps & KeyFx.CAP_SUGGESTION_STYLE) == 0) return;
        guess = word;
        updateWord();
    }

    @Override
    public void onSwipePick(FxKey k) {
        if (hop != null) hop.add(k);
        if (cons != null && cons.foldAt == 0) {
            cons.pts.add(new float[] { k.cx(), k.cy(), fx.now() - cons.base });
            fx.kick();
        }
    }

    @Override
    public void onSwipeEnd() {
        // The constellation folds as soon as the finger lifts; the hop waits for the word
        if (cons != null) {
            cons.foldAt = fx.now();
            cons = null;
        }
    }

    @Override
    public void onSwipeWord(String word) {
        fx.setWord(null);
        if (hop != null) {
            hop.finish(word);
            hop = null;
        }
        if (word != null && fx.on("burst")) burst();
    }

    @Override
    public void onSwipeCancel() {
        fx.setWord(null);
        if (hop != null) {
            hop.finish(null);
            hop = null;
        }
        if (cons != null) {
            cons.foldAt = fx.now();
            cons = null;
        }
    }

    @Override
    public void reset() {
        hop = null;
        cons = null;
        crossed.setLength(0);
        guess = null;
    }

    // region Commit burst

    private void burst() {
        final long t0 = fx.now();
        final float[] at = wordPoint();
        final float s = fx.S("burst"), life = fx.D(900), base = Fx.hue(fx.accent()), gravity = fx.dp(420);
        final int n = Math.round(fx.settings.f("burst", "count"));
        final float[] vx = new float[n], vy = new float[n], rot = new float[n], spin = new float[n];
        final int[] col = new int[n];
        for (int i = 0; i < n; i++) {
            vx[i] = fx.dp(fx.rand(-150, 150));
            vy[i] = -fx.dp(fx.rand(80, 220));
            rot[i] = fx.rand(0, 6);
            spin[i] = fx.rand(-10, 10);
            col[i] = Fx.hsl((base + fx.rand(-40, 120) + 360) % 360, 0.9f, 0.66f);
        }
        final float hw = fx.dp(2), hh = fx.dp(3.5f);
        fx.add(new Effect() {
            @Override
            public boolean draw(Canvas c, long t) {
                final float e = (t - t0) / 1000f, p = (t - t0) / life;
                if (p >= 1) return false;
                for (int i = 0; i < n; i++) {
                    c.save();
                    c.translate(at[0] + vx[i] * e, at[1] + vy[i] * e + gravity * e * e);
                    c.rotate((float) Math.toDegrees(rot[i] + spin[i] * e));
                    c.drawRect(-hw, -hh, hw, hh, fx.fill(Fx.alpha(col[i], (1 - p) * s)));
                    c.restore();
                }
                return true;
            }
        });
    }

    // endregion
}
