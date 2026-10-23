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
                        "Picked letters collect on the number row (or the top row without one). On commit they burn up, fly into backspace, or form the word.",
                        true, true,
                        Field.choice("exit", "On commit", "burn", "burn:Burn up", "fly:Fly to backspace", "form:Form the word"),
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
        int wi = -1; // index in the formed word, or -1
        boolean embers, landed;
    }

    private final class Hop extends Effect {
        final ArrayList<HopLetter> letters = new ArrayList<>();
        final FxKey[] row;
        boolean collecting = true;
        String exit, word;
        long exitAt;

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

            for (int i = 0; i < n; i++) {
                final HopLetter l = letters.get(i);
                if (collecting) {
                    final float p = Fx.clamp01((t - l.t0) / fx.D(280));
                    final float tx = n <= row.length ? row[i].cx() : left + (i + 0.5f) * (right - left) / n;
                    final float ep = Fx.easeOut(p);
                    l.x = p < 1 ? Fx.lerp(l.fromX, tx, ep) : l.x + (tx - l.x) * 0.3f;
                    l.y = p < 1 ? Fx.lerp(l.fromY, slotY, ep) - fx.dp(24) * (float) Math.sin(Math.PI * ep) : slotY;
                    fx.glowText(c, l.g, l.x, l.y, 20, 0.8f + 0.2f * ep, Fx.clamp01(p * 4) * s, fx.accent(), 0);
                    continue;
                }
                final float e = t - exitAt - i * fx.D("burn".equals(exit) ? 28 : 34);
                if (e < 0) {
                    alive = true;
                    fx.glowText(c, l.g, l.x, l.y, 20, 1, s, fx.accent(), 0);
                    continue;
                }
                if ("burn".equals(exit)) {
                    final float u = e / fx.D(520);
                    if (u >= 1) continue;
                    alive = true;
                    if (!l.embers) {
                        l.embers = true;
                        FxParts.emberBurst(fx, l.x, l.y, s);
                    }
                    fx.glowText(c, l.g, l.x + fx.dp(fx.rand(-1, 1)) * (1 - u), l.y - fx.dp(6) * u, 20, 1 - 0.4f * u,
                            (1 - u) * s, Fx.mix(fx.accent(), 0xFFFF8A3D, Fx.clamp01(u * 2.5f)), 0);
                } else if ("fly".equals(exit)) {
                    final FxKey del = fx.layout().find(FxKey.DELETE);
                    final float u = e / fx.D(420);
                    if (u >= 1 || del == null) {
                        if (!l.landed && del != null) {
                            l.landed = true;
                            FxParts.pop(fx, del);
                        }
                        continue;
                    }
                    alive = true;
                    final float p = Fx.easeIn(u) * 0.6f + u * 0.4f;
                    final float x = Fx.lerp(l.x, del.cx(), p), y = Fx.lerp(l.y, del.cy(), p) - fx.dp(40) * (float) Math.sin(Math.PI * p);
                    fx.glowText(c, l.g, x, y, 20, 1 - 0.65f * p, (1 - u * 0.5f) * s, fx.accent(), 0);
                } else {
                    // Form: slide into the word above the keys, hold, then pop
                    final float u = e / fx.D(260), hold = fx.D(380);
                    if (l.wi < 0) {
                        final float fu = Fx.clamp01(u);
                        if (fu >= 1) continue;
                        alive = true;
                        fx.glowText(c, l.g, l.x, l.y + fx.dp(300) * fu * fu, 20, 1, (1 - fu) * s, fx.accent(), 0);
                        continue;
                    }
                    if (e > fx.D(260) + hold + fx.D(240)) continue;
                    alive = true;
                    final float[] wp = wordPoint();
                    final int m = word.codePointCount(0, word.length());
                    final float tx = wp[0] + (l.wi - (m - 1) / 2f) * fx.dp(13), ty = wp[1];
                    final float pu = Fx.clamp01(u);
                    final float x = Fx.lerp(l.x, tx, Fx.easeOutBack(pu)), y = Fx.lerp(l.y, ty, Fx.easeOutBack(pu));
                    final float popU = Fx.clamp01((e - fx.D(260) - hold) / fx.D(240));
                    fx.glowText(c, glyphAt(word, l.wi), x, y, 20, 1.1f + 0.4f * popU, (1 - popU) * s, fx.accent(), 0);
                }
            }
            return alive || cover > 0;
        }

        void add(FxKey k) {
            if (!collecting || letters.size() >= 24) return;
            final HopLetter l = new HopLetter();
            l.g = k.glyph();
            l.fromX = l.x = k.cx();
            l.fromY = l.y = k.cy();
            l.t0 = fx.now();
            letters.add(l);
            fx.kick();
        }

        void finish(String w) {
            collecting = false;
            exit = fx.settings.s("hop", "exit");
            exitAt = fx.now();
            if (w == null || w.isEmpty()) {
                // No word arrived: form from the picked letters themselves
                final StringBuilder sb = new StringBuilder();
                for (HopLetter l : letters) sb.append(l.g);
                w = sb.toString();
            }
            word = w;
            if ("form".equals(exit)) {
                // Match each of the word's letters to a picked letter, in order
                int j = 0;
                final String lower = w.toLowerCase();
                for (int wi = 0, cp = 0; cp < lower.length(); wi++) {
                    final String ch = new String(Character.toChars(lower.codePointAt(cp)));
                    cp += Character.charCount(lower.codePointAt(cp));
                    for (int k = j; k < letters.size(); k++) {
                        if (letters.get(k).g.equalsIgnoreCase(ch)) {
                            letters.get(k).wi = wi;
                            j = k + 1;
                            break;
                        }
                    }
                }
            }
            fx.kick();
        }
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
        if (fx.on("burst")) burst();
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
