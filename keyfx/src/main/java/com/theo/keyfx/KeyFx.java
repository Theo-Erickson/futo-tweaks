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
import android.text.InputType;
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
    /**
     * The host's backspace hold reads delHold.words, wordsAfter and wordPace and switches to
     * deleting whole words. Hosts with their own setting for this leave it out.
     */
    public static final int CAP_WORD_DELETE = 32;

    /** Every capability, in the order settings screens list them. */
    public static final int[] CAPS = { CAP_KEY_TRANSFORM, CAP_SUGGESTION_STYLE, CAP_POPUP_LAYOUT, CAP_EXACT_WORD,
            CAP_HIDE_ANIMATION, CAP_WORD_DELETE };

    /** A short heading for what a capability covers, for settings screens. */
    public static String capTitle(int cap) {
        switch (cap) {
            case CAP_KEY_TRANSFORM: return "The real keys";
            case CAP_SUGGESTION_STYLE: return "The suggestion bar";
            case CAP_POPUP_LAYOUT: return "The accent popup";
            case CAP_EXACT_WORD: return "The exact swiped word";
            case CAP_HIDE_ANIMATION: return "Keyboard hiding";
            case CAP_WORD_DELETE: return "Deleting whole words";
            default: return "";
        }
    }

    /** Why a host without this capability can't offer its settings, in one sentence. */
    public static String capReason(int cap) {
        switch (cap) {
            case CAP_KEY_TRANSFORM: return "The keyboard draws its keys itself, so effects can only draw on top of them.";
            case CAP_SUGGESTION_STYLE: return "The keyboard draws the suggestion bar itself, so effects can't restyle its words.";
            case CAP_POPUP_LAYOUT: return "The keyboard lays out the accent popup itself, so effects can't rearrange it.";
            case CAP_EXACT_WORD: return "The swiped word is only guessed from the text that appears.";
            case CAP_HIDE_ANIMATION: return "The keyboard can't be held on screen while it slides away.";
            case CAP_WORD_DELETE: return "This keyboard has its own setting for deleting whole words.";
            default: return "";
        }
    }

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
            Field.choice("mode", "Pick letters", "dwell", "corners:Where you turn", "dwell:Where you pause",
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
    // On while animations are allowed and the field isn't private; see setEnabled(), setPrivateField()
    private boolean enabled = true;
    private boolean animationsOn = true;
    private boolean privateField;
    private FxHost host;
    private boolean keysWereMoving;
    private WordLook word;

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
        this.settings = new FxSettings(prefs, schema(), caps);
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
        // A settings change can change key looks without any animation
        if ((caps & CAP_KEY_TRANSFORM) != 0 && host != null) host.redrawKeys();
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
        animationsOn = on;
        applyEnabled();
    }

    /**
     * Whether the field being typed in is private, like a password (see isPrivateInput()).
     * keyfx draws nothing there, so no effect hints at what is typed, and isEnabled() turns
     * false so hosts that check it also stop reading the field's text.
     */
    public void setPrivateField(boolean isPrivate) {
        privateField = isPrivate;
        applyEnabled();
    }

    /** False while animations are off or the field is private: send no events and read no text. */
    public boolean isEnabled() {
        return enabled;
    }

    private void applyEnabled() {
        enabled = animationsOn && !privateField;
        if (!enabled) reset();
    }

    /**
     * Whether an EditorInfo.inputType is a password field, shown or hidden, text or number.
     * Hosts pass the result to setPrivateField() when input starts.
     */
    public static boolean isPrivateInput(int inputType) {
        final int variation = inputType & InputType.TYPE_MASK_VARIATION;
        switch (inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_TEXT:
                return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
            case InputType.TYPE_CLASS_NUMBER:
                return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
            default:
                return false;
        }
    }

    public void setLayout(FxLayout l) {
        layout = l != null ? l : FxLayout.EMPTY;
    }

    public FxLayout layout() {
        return layout;
    }

    /** The host's callbacks, for hosts that draw more than this overlay. May stay null. */
    public void setHost(FxHost h) {
        host = h;
    }

    /**
     * How the host should draw this real key now. Resets out, lets every module adjust it, and
     * returns false when the key draws as normal. Hosts with CAP_KEY_TRANSFORM call it per key.
     */
    public boolean look(FxKey k, KeyLook out) {
        out.reset();
        if (!enabled || k == null) return false;
        final long t = now();
        for (FxModule m : modules) {
            try {
                m.look(k, out, t);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
        return !out.isPlain();
    }

    /** How the suggestion bar should show the predicted word, or null for normal. */
    public WordLook word() {
        return word;
    }

    void setWord(WordLook w) {
        if (w == null ? word == null : w.sameAs(word)) return;
        word = w;
        if (host != null) host.wordChanged();
    }

    /**
     * Where to put n accents for a long press on k (hosts with CAP_POPUP_LAYOUT). With the bloom
     * layouts (bloom, wheel, column, grid), fills outXY with each accent's center (x0, y0, x1, y1,
     * ...) inside bounds and returns true; returns false for the row layout, meaning the host lays
     * it out as usual.
     */
    public boolean popupLayout(FxKey k, int n, RectF bounds, float[] outXY) {
        if (!enabled || k == null || n <= 0 || outXY.length < 2 * n) return false;
        return Fx.popupLayout(settings.s("popup", "style"), k.cx(), k.cy(), k.top, n, dp(settings.f("popup", "radius")),
                bounds.left, bounds.top, bounds.right, bounds.bottom, dp(1), outXY);
    }

    /**
     * How accent i of a bloom popup looks msSinceOpen after it opened: it flies out from the key
     * center (keyCx, keyCy) to its place (itemX, itemY), growing and fading in. Returns false once
     * it has settled.
     */
    public boolean popupLook(int i, long msSinceOpen, float keyCx, float keyCy, float itemX, float itemY, KeyLook out) {
        out.reset();
        if (!enabled) return false;
        final float u = (msSinceOpen - D(22 * i)) / D(220);
        if (u >= 1) return false;
        final float p = Fx.clamp01(u), e = Fx.easeOutBack(p);
        out.scaleBy(0.3f + 0.7f * e);
        out.fade(p);
        out.move((keyCx - itemX) * (1 - e), (keyCy - itemY) * (1 - e));
        return true;
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
        setWord(null);
        // draw() won't run while disabled, so the keys must be restored here
        keysWereMoving = false;
        if (host != null && (caps & CAP_KEY_TRANSFORM) != 0) host.redrawKeys();
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
        // An effect may have changed these on the shared paint; every stroke starts round and solid
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setPathEffect(null);
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
        // Hosts that draw real keys redraw them while a module moves them, and once more after
        if ((caps & CAP_KEY_TRANSFORM) != 0) {
            boolean moving = false;
            for (FxModule m : modules) {
                try {
                    if (m.keysMoving(t)) moving = true;
                } catch (Throwable e) {
                    fail(m, e);
                }
            }
            if ((moving || keysWereMoving) && host != null) host.redrawKeys();
            keysWereMoving = moving;
            if (moving) alive = true;
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

    /** Backspace tapped once; letters is what it deleted, in text order (none, one, or a whole word). */
    public void deleteTap(FxKey k, FxLetter... letters) {
        if (!enabled || k == null) return;
        final FxLetter[] list = letters != null ? letters : new FxLetter[0];
        for (FxModule m : modules) {
            try {
                m.onDeleteTap(k, list);
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

    /** The cursor moved dx characters or dy lines while the spacebar is a trackpad. */
    public void cursorStep(int dx, int dy) {
        if (!enabled) return;
        for (FxModule m : modules) {
            try {
                m.onCursorStep(dx, dy);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
    }

    /**
     * False when the show animation moves the real keys itself through look() (hosts with
     * CAP_KEY_TRANSFORM and "Animate each key" on, or a style that only works on real keys: piano,
     * decode and flap, whatever that toggle says): call show(null, onDone) and keep the view
     * visible; onDone still runs when the keys settle.
     */
    public boolean showNeedsSnapshot() {
        if ((caps & CAP_KEY_TRANSFORM) == 0) return true;
        final String mode = settings.s("show", "mode");
        if ("piano".equals(mode) || "decode".equals(mode) || "flap".equals(mode)) return false;
        return !settings.b("show", "perKey");
    }

    /**
     * The keyboard appeared. snapshot shows the keyboard view (layout.keyboard exactly). If a
     * module animates it, the host should (when it passed a snapshot) hide the real view until onDone runs, and the call
     * returns true. Otherwise the snapshot is recycled and the call returns false.
     * With a null snapshot (see showNeedsSnapshot) the keys animate through look() instead.
     */
    public boolean show(Bitmap snapshot, Runnable onDone) {
        if (enabled && (snapshot != null || !showNeedsSnapshot())) {
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

    /** The host's live best guess at the word during a swipe. Hosts without one never call it. */
    public void swipeGuess(String word) {
        if (!swipe.active) return;
        for (FxModule m : modules) {
            try {
                m.onSwipeGuess(word);
            } catch (Throwable e) {
                fail(m, e);
            }
        }
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
