package com.theo.keyfx;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The keyboard effects engine: one Drawable that covers the keyboard's window and draws
 * every effect, in layer coordinates (the Drawable's own).
 *
 * A host (the Xposed module, or the FUTO fork) adds it to a view overlay, keeps the layout
 * current with setLayout(), and reports what the user does through the event methods.
 * Everything here is plain Android graphics: no FUTO or Xposed code, so any keyboard can host it.
 *
 * Hosts declare what else they can do with CAP_* flags; settings that need a missing
 * capability are hidden from that host's settings screen.
 */
public final class KeyFx extends Drawable {
    private static final String TAG = "KeyFx";

    /** The host can move, scale or hide the real keys (not just draw over them). */
    public static final int CAP_KEY_TRANSFORM = 1;
    /** The host can restyle the suggestion bar, to light up the predicted word. */
    public static final int CAP_SUGGESTION_STYLE = 2;
    /** The host can lay out the long-press popup (bloom arc). */
    public static final int CAP_POPUP_LAYOUT = 4;
    /** The host knows the swiped word exactly, rather than by watching committed text. */
    public static final int CAP_EXACT_WORD = 8;
    /** The host can hold the keyboard on screen for a hide animation. */
    public static final int CAP_HIDE_ANIMATION = 16;

    // How long after a swipe ends to wait for its word before telling modules there is none
    private static final long WORD_WAIT_MS = 900;
    private static final int MAX_EFFECTS = 500;

    // region Schema

    /** Applies to every effect. "system" accent means the host's Material You color. */
    public static final Section GLOBAL = new Section("global", "Look and feel", 0, "Look and feel",
            "Applies to every effect.", false, true,
            Field.choice("accent", "Accent color", "system", "system:System", "#8AB4F8:Blue",
                    "#C58AF9:Purple", "#7FD8BE:Mint", "#F28B82:Coral", "#FDD663:Yellow", "#FF9E5E:Orange"),
            Field.choice("kbTheme", "Keyboard theme", "dark", "dark:Dark", "light:Light"),
            Field.range("speed", "Animation speed", 0.25f, 2f, 0.05f, "×", 1f),
            Field.range("intensity", "Overall intensity", 0f, 1.5f, 0.05f, "×", 1f));

    /** Which swipe letters count as picked, for effects that follow the word's letters. */
    public static final Section PICK = new Section("pick", "Swipe word", 500, "Swipe letter picking",
            "Which letters of a swipe count for letter hop and constellation. Key trace still lights every key you cross.",
            false, true,
            Field.choice("mode", "Pick letters", "corners", "corners:Where you turn", "dwell:Where you pause",
                    "all:Every key crossed"),
            Field.range("angle", "Turn needed", 30, 120, 1, "°", 55).when("mode", "corners"),
            Field.range("dwell", "Pause needed", 20, 300, 5, "ms", 90).when("mode", "dwell"));

    /** Every section, core and modules, in display order. */
    public static List<Section> schema() {
        final ArrayList<Section> out = new ArrayList<>();
        out.add(GLOBAL);
        out.add(PICK);
        Collections.addAll(out, Modules.sections());
        Collections.sort(out, new Comparator<Section>() {
            @Override
            public int compare(Section a, Section b) {
                return a.order - b.order;
            }
        });
        return out;
    }

    // endregion

    public final float density;
    public final int caps;
    public final FxSettings settings;
    public final Random rnd = new Random();
    private final FxModule[] modules;
    private final ArrayList<Effect> effects = new ArrayList<>();

    private FxLayout layout = FxLayout.EMPTY;
    private int systemAccent = 0xFF8AB4F8;
    private boolean enabled = true;

    // Settings read every frame, cached on reload()
    private int accent;
    private float speed = 1f, intensity = 1f;
    private boolean dark = true;

    // Shared drawing objects: drawing is single threaded, so effects reuse these
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    public KeyFx(float density, SharedPreferences prefs, int caps) {
        this.density = density;
        this.caps = caps;
        this.settings = new FxSettings(prefs, schema());
        this.modules = Modules.create(this);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        refresh();
    }

    // region Host API: settings, layout, state

    /** Re-reads settings. Call after the host learns they changed; running effects keep going. */
    public void reload() {
        settings.reload();
        refresh();
        invalidateSelf();
    }

    private void refresh() {
        final String a = settings.s("global", "accent");
        accent = "system".equals(a) ? systemAccent : Fx.parseColor(a, systemAccent);
        speed = Math.max(0.1f, settings.f("global", "speed"));
        intensity = settings.f("global", "intensity");
        dark = !"light".equals(settings.s("global", "kbTheme"));
    }

    /** The Material You accent, used when the accent setting is "system". */
    public void setSystemAccent(int color) {
        systemAccent = color | 0xFF000000;
        refresh();
    }

    /** Turns everything off without losing settings, for example when animations are disabled. */
    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) reset();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setLayout(FxLayout l) {
        layout = l != null ? l : FxLayout.EMPTY;
    }

    public FxLayout layout() {
        return layout;
    }

    /** Drops every running effect, for example when the keyboard hides. */
    public void reset() {
        effects.clear();
        swipe.active = false;
        wordDeadline = 0;
        for (FxModule m : modules) {
            try {
                m.reset();
            } catch (Throwable t) {
                fail(m, t);
            }
        }
        invalidateSelf();
    }

    // endregion

    // region Helpers for effects

    public long now() {
        return SystemClock.uptimeMillis();
    }

    public float dp(float v) {
        return v * density;
    }

    /** A duration scaled by the global animation speed. */
    public float D(float ms) {
        return ms / speed;
    }

    /** An effect's intensity: its own strength times the global intensity. */
    public float S(String id) {
        return settings.f(id, "strength") * intensity;
    }

    public boolean on(String id) {
        return settings.on(id);
    }

    public float rand(float a, float b) {
        return a + rnd.nextFloat() * (b - a);
    }

    public int accent() {
        return accent;
    }

    public boolean dark() {
        return dark;
    }

    /** Approximate keyboard colors from the theme setting, for effects that cover a key. */
    public int keyFill() {
        return dark ? 0xFF3B3F49 : 0xFFFFFFFF;
    }

    public int labelColor() {
        return dark ? 0xFFE8EAED : 0xFF1F1F1F;
    }

    public int background() {
        return dark ? 0xFF202124 : 0xFFE8EAED;
    }

    /** The shared fill paint, reset to a plain color. */
    public Paint fill(int color) {
        reset(fill);
        fill.setColor(color);
        return fill;
    }

    /** The shared stroke paint, reset to a plain color and width in px. */
    public Paint stroke(int color, float width) {
        reset(stroke);
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        return stroke;
    }

    private static void reset(Paint p) {
        p.setShader(null);
        p.setXfermode(null);
        p.clearShadowLayer();
        p.setAlpha(255);
    }

    public RectF rect(float l, float t, float r, float b) {
        rect.set(l, t, r, b);
        return rect;
    }

    /** The shared path, emptied. */
    public Path path() {
        path.reset();
        return path;
    }

    public void roundRect(Canvas c, float l, float t, float r, float b, float radiusDp, Paint p) {
        final float rad = dp(radiusDp);
        c.drawRoundRect(rect(l, t, r, b), rad, rad, p);
    }

    /** The shared path as a rounded rectangle, for clipping. */
    public Path roundRectPath(float l, float t, float r, float b, float radiusDp) {
        final Path p = path();
        final float rad = dp(radiusDp);
        p.addRoundRect(rect(l, t, r, b), rad, rad, Path.Direction.CW);
        return p;
    }

    /** A star or sparkle centered on (cx, cy); rot in radians. */
    public void star(Canvas c, float cx, float cy, float r, int points, float inner, float rot, Paint p) {
        if (r <= 0) return;
        final Path s = path();
        for (int i = 0; i < points * 2; i++) {
            final double a = rot + i * Math.PI / points - Math.PI / 2;
            final float rr = (i % 2) == 1 ? r * inner : r;
            final float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
            if (i == 0) s.moveTo(x, y);
            else s.lineTo(x, y);
        }
        s.close();
        c.drawPath(s, p);
    }

    /** A glowing glyph centered on (x, y), like the effects' own lettering. sizeDp is the text size. */
    public void glowText(Canvas c, String str, float x, float y, float sizeDp, float scale, float alpha,
                         int color, float rotRad) {
        if (alpha <= 0 || str == null) return;
        reset(text);
        text.setTextSize(dp(sizeDp));
        text.setFakeBoldText(true);
        text.setColor(Fx.alpha(color, alpha));
        text.setShadowLayer(dp(5), 0, 0, Fx.alpha(color, 0.8f * alpha));
        drawGlyph(c, str, x, y, scale, rotRad);
    }

    /** A plain glyph, like text in the app. sizePx is the text size; color is final (alpha included). */
    public void plainText(Canvas c, String str, float x, float y, float sizePx, int color, float rotRad, float scale) {
        if (str == null) return;
        reset(text);
        text.setTextSize(sizePx);
        text.setFakeBoldText(false);
        text.setColor(color);
        drawGlyph(c, str, x, y, scale, rotRad);
    }

    private void drawGlyph(Canvas c, String str, float x, float y, float scale, float rotRad) {
        c.save();
        c.translate(x, y);
        if (rotRad != 0) c.rotate((float) Math.toDegrees(rotRad));
        if (scale != 1) c.scale(scale, scale);
        // Vertically center on the glyph's x-height rather than its baseline
        c.drawText(str, 0, text.getTextSize() * 0.36f, text);
        c.restore();
    }

    /** Adds a running effect and starts drawing. */
    public void add(Effect e) {
        if (!enabled) return;
        if (effects.size() < MAX_EFFECTS) effects.add(e);
        invalidateSelf();
    }

    /** Asks for another frame, for modules whose persistent state just changed. */
    public void kick() {
        invalidateSelf();
    }

    // endregion

    // region Drawing

    @Override
    public void draw(Canvas c) {
        if (!enabled) return;
        final long t = now();
        boolean alive = false;

        // A swipe whose word never arrived: tell the modules, so they can finish without it
        if (wordDeadline != 0) {
            if (t >= wordDeadline) {
                wordDeadline = 0;
                dispatchWord(null);
            } else {
                alive = true;
            }
        }

        for (FxModule m : modules) {
            try {
                if (m.drawUnder(c, t)) alive = true;
            } catch (Throwable e) {
                fail(m, e);
            }
        }
        for (int i = 0; i < effects.size(); i++) {
            boolean keep;
            try {
                keep = effects.get(i).draw(c, t);
            } catch (Throwable e) {
                Log.w(TAG, "effect failed", e);
                keep = false;
            }
            if (keep) {
                alive = true;
            } else {
                effects.remove(i);
                i--;
            }
        }
        for (FxModule m : modules) {
            try {
                if (m.drawOver(c, t)) alive = true;
            } catch (Throwable e) {
                fail(m, e);
            }
        }
        if (alive) invalidateSelf();
    }

    @Override
    public void setAlpha(int alpha) {
        // Effects manage their own alpha
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Not supported: effects use the accent color directly
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    // endregion

    // region Events from the host

    public void keyDown(FxKey k, float x, float y) {
        if (!enabled || k == null) return;
        for (FxModule m : modules) {
            try {
                m.onKeyDown(k, x, y);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    /** A key typed glyph (as typed, so capitals included). x is where the finger was. */
    public void keyTyped(FxKey k, String glyph, float x) {
        if (!enabled || k == null) return;
        for (FxModule m : modules) {
            try {
                m.onKeyTyped(k, glyph, x);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    public void deleteTap(FxKey k, FxLetter letter) {
        if (!enabled || k == null) return;
        for (FxModule m : modules) {
            try {
                m.onDeleteTap(k, letter);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    public void deleteHoldStart(FxKey k) {
        if (!enabled || k == null) return;
        for (FxModule m : modules) {
            try {
                m.onDeleteHoldStart(k);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    public void deleteHoldLetter(FxLetter letter) {
        if (!enabled || letter == null) return;
        for (FxModule m : modules) {
            try {
                m.onDeleteHoldLetter(letter);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    public void deleteHoldEnd() {
        for (FxModule m : modules) {
            try {
                m.onDeleteHoldEnd();
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    public void longPressStart(FxKey k, float x, long timeoutMs) {
        if (!enabled || k == null) return;
        for (FxModule m : modules) {
            try {
                m.onLongPressStart(k, x, timeoutMs);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    public void longPressEnd() {
        for (FxModule m : modules) {
            try {
                m.onLongPressEnd();
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    public void cursorMode(boolean on, float x, float y) {
        if (!enabled && on) return;
        for (FxModule m : modules) {
            try {
                m.onCursorMode(on, x, y);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    public void cursorMove(float x, float y) {
        if (!enabled) return;
        for (FxModule m : modules) {
            try {
                m.onCursorMove(x, y);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    /**
     * The keyboard appeared. snapshot shows the keyboard view (layout.keyboard exactly). If a
     * module animates it, the host should hide the real view until onDone runs, and the call
     * returns true. Otherwise the snapshot is recycled and the call returns false.
     */
    public boolean show(Bitmap snapshot, Runnable onDone) {
        if (enabled && snapshot != null) {
            for (FxModule m : modules) {
                try {
                    if (m.onShow(snapshot, onDone)) return true;
                } catch (Throwable e) {
                    fail(m, e);
                }
            }
        }
        if (snapshot != null) snapshot.recycle();
        return false;
    }

    /** Like show(), for hosts with CAP_HIDE_ANIMATION: onDone runs when the host may hide. */
    public boolean hide(Bitmap snapshot, Runnable onDone) {
        if (enabled && snapshot != null) {
            for (FxModule m : modules) {
                try {
                    if (m.onHide(snapshot, onDone)) return true;
                } catch (Throwable e) {
                    fail(m, e);
                }
            }
        }
        if (snapshot != null) snapshot.recycle();
        return false;
    }

    // endregion

    // region Swipes: crossing, letter picking, and the word that follows

    private static final class Swipe {
        boolean active, started;
        final ArrayList<FxKey> crossed = new ArrayList<>();
        final ArrayList<FxKey> picked = new ArrayList<>();
        FxKey lastKey;
        // Corner picking: resampled path, and the sharpest sample of the current turn
        final ArrayList<float[]> samples = new ArrayList<>();
        int bestSample = -1;
        float bestTurn;
        // Pause picking
        FxKey dwellKey;
        long dwellAt;
    }

    private final Swipe swipe = new Swipe();
    private long wordDeadline;

    /** A swipe was recognized. Then feed every point from touch-down on through swipePoint(). */
    public void swipeStart() {
        if (!enabled) return;
        swipe.active = true;
        swipe.started = false;
        swipe.crossed.clear();
        swipe.picked.clear();
        swipe.samples.clear();
        swipe.lastKey = null;
        swipe.bestSample = -1;
        swipe.bestTurn = 0;
        swipe.dwellKey = null;
        wordDeadline = 0;
        for (FxModule m : modules) {
            try {
                m.onSwipeStart();
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    public void swipePoint(float x, float y, long t) {
        if (!swipe.active) return;
        for (FxModule m : modules) {
            try {
                m.onSwipePoint(x, y, t);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
        final String mode = settings.s("pick", "mode");

        if (!swipe.started) {
            // The first point: a swipe always starts on its word's first letter
            swipe.started = true;
            swipe.samples.add(new float[] { x, y });
            final FxKey k = layout.hit(x, y, dp(4));
            swipe.lastKey = k;
            swipe.dwellKey = k;
            swipe.dwellAt = t;
            if (k != null && k.isLetter()) {
                cross(k);
                pick(k);
            }
            return;
        }

        if ("corners".equals(mode)) cornerStream(x, y);
        // Pause picking: a key counts once the finger has stayed on it long enough
        if ("dwell".equals(mode) && swipe.dwellKey != null && t - swipe.dwellAt >= settings.f("pick", "dwell")) {
            pick(swipe.dwellKey);
        }
        final FxKey k = layout.hit(x, y, dp(4));
        if (k == null || k == swipe.lastKey) return;
        swipe.lastKey = k;
        swipe.dwellKey = k;
        swipe.dwellAt = t;
        if (!k.isLetter()) return;
        cross(k);
        if ("all".equals(mode)) pick(k);
    }

    /** The finger lifted. The word may follow through swipeWord(). */
    public void swipeEnd() {
        if (!swipe.active) return;
        swipe.active = false;
        if ("corners".equals(settings.s("pick", "mode"))) flushCorner();
        if (!swipe.crossed.isEmpty()) pick(swipe.crossed.get(swipe.crossed.size() - 1));
        for (FxModule m : modules) {
            try {
                m.onSwipeEnd();
            } catch (Throwable e) {
                fail(m, e);
            }
        }
        wordDeadline = now() + WORD_WAIT_MS;
        invalidateSelf();
    }

    /** The word the last swipe produced. Ignored once the wait after the swipe is over. */
    public void swipeWord(String word) {
        if (wordDeadline == 0) return;
        wordDeadline = 0;
        dispatchWord(word);
    }

    /** True while a swiped word is still expected, so hosts know a commit may be that word. */
    public boolean awaitingWord() {
        return wordDeadline != 0 && now() < wordDeadline;
    }

    /** The first letter picked in the current or last swipe, or null. */
    public String firstPicked() {
        return swipe.picked.isEmpty() ? null : swipe.picked.get(0).glyph();
    }

    public void swipeCancel() {
        swipe.active = false;
        wordDeadline = 0;
        for (FxModule m : modules) {
            try {
                m.onSwipeCancel();
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    public boolean swiping() {
        return swipe.active;
    }

    private void dispatchWord(String word) {
        for (FxModule m : modules) {
            try {
                m.onSwipeWord(word);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
        invalidateSelf();
    }

    private void cross(FxKey k) {
        final int index = swipe.crossed.size();
        swipe.crossed.add(k);
        for (FxModule m : modules) {
            try {
                m.onSwipeCross(k, index);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    private void pick(FxKey k) {
        if (k == null || !k.isLetter()) return;
        if (!swipe.picked.isEmpty() && swipe.picked.get(swipe.picked.size() - 1) == k) return;
        swipe.picked.add(k);
        for (FxModule m : modules) {
            try {
                m.onSwipePick(k);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    /**
     * Corner picking, live: the path is resampled every 12dp and each sample's turn is measured
     * against the samples two either side. A run of sharp samples is one corner; its sharpest
     * sample decides the key.
     */
    private void cornerStream(float x, float y) {
        final ArrayList<float[]> s = swipe.samples;
        final float[] last = s.get(s.size() - 1);
        if (Math.hypot(x - last[0], y - last[1]) < dp(12)) return;
        s.add(new float[] { x, y });
        final int i = s.size() - 3;
        if (i < 2) return;
        final float[] a = s.get(i - 2), b = s.get(i), c = s.get(i + 2);
        double turn = Math.abs(Math.atan2(c[1] - b[1], c[0] - b[0]) - Math.atan2(b[1] - a[1], b[0] - a[0]));
        if (turn > Math.PI) turn = Math.PI * 2 - turn;
        if (turn >= Math.toRadians(settings.f("pick", "angle"))) {
            if (turn > swipe.bestTurn) {
                swipe.bestTurn = (float) turn;
                swipe.bestSample = i;
            }
        } else {
            flushCorner();
        }
    }

    private void flushCorner() {
        if (swipe.bestSample >= 0) {
            final float[] b = swipe.samples.get(swipe.bestSample);
            pick(layout.hit(b[0], b[1], dp(4)));
        }
        swipe.bestSample = -1;
        swipe.bestTurn = 0;
    }

    // endregion

    private static void fail(FxModule m, Throwable t) {
        Log.w(TAG, m.getClass().getSimpleName() + " failed", t);
    }
}
