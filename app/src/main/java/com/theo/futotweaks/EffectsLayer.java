/*
 * FUTO Tweaks: every visual effect, drawn in one Drawable on the IME window's root overlay.
 *
 * The root overlay covers the whole keyboard window, suggestion bar included, so letters can
 * rise out of the keys and land above them. It draws in its own render node: animating an
 * effect only redraws this layer, never FUTO's keys, so it stays cheap while typing fast.
 *
 * All coordinates here are root-view coordinates. MainHook converts from keyboard view
 * coordinates before calling in. Each effect is a small object that draws itself for the
 * current time and reports whether it is still alive; the layer keeps the frame loop running
 * only while something is visible.
 */

package com.theo.futotweaks;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Random;

public final class EffectsLayer extends Drawable {

    /** One running effect. Returns false once it has finished and can be dropped. */
    interface Effect {
        boolean draw(Canvas canvas, long now);
    }

    // Hard cap so mashing keys can never pile up unbounded work
    private static final int MAX_EFFECTS = 220;

    // Heat streak colors: the accent warms toward orange, then a hot pink-red
    private static final int HEAT_WARM = 0xFFFF8A3D;
    private static final int HEAT_HOT = 0xFFFF3B5C;

    private final float dp;
    private int mAccent = Color.WHITE;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Random mRandom = new Random();

    private final ArrayList<Effect> mEffects = new ArrayList<>();
    // Spacebar drag trails, one per pointer id so two thumbs never join into one line
    private final SparseArray<ArrayList<TrailPoint>> mTrails = new SparseArray<>();
    private final Heat mHeat = new Heat();
    private final Trackpad mTrackpad = new Trackpad();
    private Cascade mCascade;

    public EffectsLayer(final float density) {
        dp = density;
        mFill.setStyle(Paint.Style.FILL);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeCap(Paint.Cap.ROUND);
        mStroke.setStrokeJoin(Paint.Join.ROUND);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setFakeBoldText(true);
    }

    /** Effects use the Material You accent so they match the wallpaper-based theme. */
    public void setAccent(final int color) {
        mAccent = color;
    }

    private boolean add(final Effect effect) {
        if (mEffects.size() >= MAX_EFFECTS) return false;
        mEffects.add(effect);
        invalidateSelf();
        return true;
    }

    /** Drops every effect, used when the keyboard window goes away. */
    public void clear() {
        mEffects.clear();
        mTrails.clear();
        mHeat.level = 0f;
        mTrackpad.target = 0f;
        mTrackpad.level = 0f;
        finishCascade();
        invalidateSelf();
    }

    // region Easing and color helpers

    private static float clamp01(final float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float easeOutCubic(final float t) {
        final float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Overshoots slightly past 1 before settling, for a springy landing. */
    private static float easeOutBack(final float t) {
        final float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private static float lerp(final float a, final float b, final float t) {
        return a + (b - a) * t;
    }

    private static int withAlpha(final int color, final float alpha) {
        final int a = Math.max(0, Math.min(255, (int) (Color.alpha(color) * alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static int lerpColor(final int a, final int b, final float t) {
        final int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        final int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        final int r = (int) (ar + (br - ar) * t), g = (int) (ag + (bg - ag) * t), bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Draws centered text with an accent glow, scaled around its center. */
    private void drawGlowText(final Canvas canvas, final String text, final float cx, final float cy,
                              final float size, final float scale, final float alpha) {
        if (alpha <= 0f) return;
        mText.setTextSize(size);
        mText.setColor(withAlpha(mAccent, alpha));
        mText.setShadowLayer(6f * dp, 0f, 0f, withAlpha(mAccent, 0.7f * alpha));
        canvas.save();
        canvas.scale(scale, scale, cx, cy);
        // Shift the baseline so the glyph is centered on cy rather than sitting on it
        canvas.drawText(text, cx, cy + size * 0.36f, mText);
        canvas.restore();
    }

    // endregion

    // region Frame loop

    @Override
    public void draw(final Canvas canvas) {
        final long now = SystemClock.uptimeMillis();
        boolean animating = false;

        // The cascade sits underneath everything else: it stands in for the keys while they arrive
        if (mCascade != null) {
            if (mCascade.draw(canvas, now)) {
                animating = true;
            } else {
                mCascade.release();
                mCascade = null;
            }
        }

        if (mTrackpad.draw(canvas, now)) animating = true;

        // Index loop on purpose: an effect may add another (a finished charge ring adds its pop)
        for (int i = 0; i < mEffects.size(); i++) {
            if (mEffects.get(i).draw(canvas, now)) {
                animating = true;
            } else {
                mEffects.remove(i);
                i--;
            }
        }

        if (drawTrails(canvas, now)) animating = true;
        if (mHeat.draw(canvas, now)) animating = true;

        // Keep the frame loop running only while something is still visible
        if (animating) invalidateSelf();
    }

    @Override
    public void setAlpha(final int alpha) {
        // Per-effect alpha is computed in draw()
    }

    @Override
    public void setColorFilter(final ColorFilter colorFilter) {
        mFill.setColorFilter(colorFilter);
        mStroke.setColorFilter(colorFilter);
        mText.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    // endregion

    // region Classic effects: ripple, glow, particles, spacebar trail

    /** A ring that expands and fades from (x, y). */
    public void addRipple(final float x, final float y, final float maxRadius) {
        final long start = SystemClock.uptimeMillis();
        add(new Effect() {
            @Override
            public boolean draw(final Canvas canvas, final long now) {
                final float t = (now - start) / 380f;
                if (t >= 1f) return false;
                mStroke.setStrokeWidth((3f - 2f * t) * dp);
                mStroke.setColor(withAlpha(mAccent, 0.7f * (1f - t)));
                canvas.drawCircle(x, y, maxRadius * easeOutCubic(t), mStroke);
                return true;
            }
        });
    }

    /** The pressed key flashes a soft rounded highlight that fades out. */
    public void addGlow(final float left, final float top, final float right, final float bottom) {
        final long start = SystemClock.uptimeMillis();
        final float inset = 2f * dp, corner = 8f * dp;
        add(new Effect() {
            @Override
            public boolean draw(final Canvas canvas, final long now) {
                final float t = (now - start) / 260f;
                if (t >= 1f) return false;
                final float fade = (1f - t) * (1f - t);
                mRect.set(left + inset, top + inset, right - inset, bottom - inset);
                mFill.setColor(withAlpha(mAccent, 0.35f * fade));
                canvas.drawRoundRect(mRect, corner, corner, mFill);
                mStroke.setStrokeWidth(2f * dp);
                mStroke.setColor(withAlpha(mAccent, 0.8f * fade));
                canvas.drawRoundRect(mRect, corner, corner, mStroke);
                return true;
            }
        });
    }

    /** A small burst of sparks from (x, y), with a little gravity. */
    public void addParticleBurst(final float x, final float y) {
        final int count = 10;
        final float[] vx = new float[count], vy = new float[count], size = new float[count];
        for (int i = 0; i < count; i++) {
            // Spread around the full circle, biased upward so sparks rise off the key
            final double angle = mRandom.nextDouble() * Math.PI * 2.0;
            final float speed = (60f + mRandom.nextFloat() * 90f) * dp; // px per second
            vx[i] = (float) Math.cos(angle) * speed;
            vy[i] = (float) Math.sin(angle) * speed - 60f * dp;
            size[i] = (1.5f + mRandom.nextFloat() * 1.5f) * dp;
        }
        final long start = SystemClock.uptimeMillis();
        add(new Effect() {
            @Override
            public boolean draw(final Canvas canvas, final long now) {
                final float t = (now - start) / 520f;
                if (t >= 1f) return false;
                final float s = (now - start) / 1000f, gravity = 320f * dp;
                mFill.setColor(withAlpha(mAccent, 1f - t));
                for (int i = 0; i < count; i++) {
                    canvas.drawCircle(x + vx[i] * s, y + vy[i] * s + 0.5f * gravity * s * s,
                            size[i] * (1f - 0.5f * t), mFill);
                }
                return true;
            }
        });
    }

    private static final class TrailPoint {
        float x, y;
        long time;
    }

    /** Adds a point to the spacebar drag trail for this pointer. */
    public void addTrailPoint(final int pointerId, final float x, final float y) {
        ArrayList<TrailPoint> trail = mTrails.get(pointerId);
        if (trail == null) {
            trail = new ArrayList<>();
            mTrails.put(pointerId, trail);
        }
        final TrailPoint p = new TrailPoint();
        p.x = x;
        p.y = y;
        p.time = SystemClock.uptimeMillis();
        trail.add(p);
        invalidateSelf();
    }

    private boolean drawTrails(final Canvas canvas, final long now) {
        final long lifetime = 320;
        boolean alive = false;
        for (int i = mTrails.size() - 1; i >= 0; i--) {
            final ArrayList<TrailPoint> trail = mTrails.valueAt(i);
            // Drop points that have fully faded
            while (!trail.isEmpty() && now - trail.get(0).time > lifetime) trail.remove(0);
            if (trail.isEmpty()) {
                mTrails.removeAt(i);
                continue;
            }
            alive = true;
            // Segment by segment, so each part fades by its own age and thins toward the tail
            for (int j = 1; j < trail.size(); j++) {
                final TrailPoint a = trail.get(j - 1), b = trail.get(j);
                final float life = 1f - (now - b.time) / (float) lifetime;
                mStroke.setStrokeWidth((1f + 4f * life) * dp);
                mStroke.setColor(withAlpha(mAccent, 0.85f * life));
                canvas.drawLine(a.x, a.y, b.x, b.y, mStroke);
            }
        }
        return alive;
    }

    // endregion

    // region Typing: key pop, letter float, heat streak

    /** The key's highlight swells past its edges, then springs back with a small dip. */
    public void addPop(final float left, final float top, final float right, final float bottom) {
        final long start = SystemClock.uptimeMillis();
        final float corner = 9f * dp;
        add(new Effect() {
            @Override
            public boolean draw(final Canvas canvas, final long now) {
                final float t = (now - start) / 260f;
                if (t >= 1f) return false;
                // Grow out and back over the first 35%, then a small rebound inward
                final float grow = t < 0.35f
                        ? 6f * dp * (float) Math.sin(Math.PI * t / 0.35f)
                        : -1.5f * dp * (float) Math.sin(Math.PI * (t - 0.35f) / 0.65f);
                final float fade = 1f - t;
                mRect.set(left - grow, top - grow, right + grow, bottom + grow);
                mFill.setColor(withAlpha(mAccent, 0.2f * fade));
                canvas.drawRoundRect(mRect, corner, corner, mFill);
                mStroke.setStrokeWidth(1.5f * dp);
                mStroke.setColor(withAlpha(mAccent, 0.65f * fade));
                canvas.drawRoundRect(mRect, corner, corner, mStroke);
                return true;
            }
        });
    }

    /** The typed letter rises off its key, grows a little and fades. */
    public void addLetterFloat(final String text, final float cx, final float cy, final float size) {
        final long start = SystemClock.uptimeMillis();
        final float rise = 38f * dp;
        add(new Effect() {
            @Override
            public boolean draw(final Canvas canvas, final long now) {
                final float t = (now - start) / 480f;
                if (t >= 1f) return false;
                final float p = easeOutCubic(t);
                drawGlowText(canvas, text, cx, cy - rise * p, size, 1f + 0.25f * p, (1f - t) * (1f - t * 0.3f));
                return true;
            }
        });
    }

    /**
     * Registers one keystroke for the heat streak and tells it where the keyboard's top edge is.
     * Each stroke adds heat, which decays continuously, so steady fast typing holds it warm.
     */
    public void addHeat(final float left, final float top, final float right) {
        final long now = SystemClock.uptimeMillis();
        mHeat.decayTo(now);
        mHeat.level = Math.min(1f, mHeat.level + 0.085f);
        mHeat.left = left;
        mHeat.top = top;
        mHeat.right = right;
        invalidateSelf();
    }

    private final class Heat {
        float level;
        long last;
        float left, top, right;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int shaderColor;

        void decayTo(final long now) {
            if (last != 0) {
                // About 0.9 s time constant: 6 keys a second holds roughly 0.45, 10 a second about 0.75
                level *= (float) Math.exp(-(now - last) / 900.0);
            }
            last = now;
        }

        boolean draw(final Canvas canvas, final long now) {
            decayTo(now);
            if (level < 0.015f) {
                level = 0f;
                return false;
            }
            // Invisible at a relaxed pace, it only shows once typing is genuinely quick
            final float visible = clamp01((level - 0.12f) / 0.5f);
            if (visible > 0f) {
                final int color = level < 0.5f
                        ? lerpColor(mAccent, HEAT_WARM, level / 0.5f)
                        : lerpColor(HEAT_WARM, HEAT_HOT, (level - 0.5f) / 0.5f);
                final int glow = withAlpha(color, 0.55f * visible);
                // Rebuild the gradient only when its color actually changes
                if (glow != shaderColor) {
                    paint.setShader(new LinearGradient(0f, top, 0f, top + 18f * dp,
                            glow, glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
                    shaderColor = glow;
                }
                canvas.drawRect(left, top, right, top + 18f * dp, paint);
                mFill.setColor(withAlpha(color, 0.9f * visible));
                canvas.drawRect(left, top, right, top + 2f * dp, mFill);
            }
            return true;
        }
    }

    // endregion

    // region Special keys: backspace crumble, enter sweep, space shimmer

    /** The key crumbles: small fragments break off, tumble and fall. */
    public void addCrumble(final float left, final float top, final float right, final float bottom, final int count) {
        final float[] x = new float[count], y = new float[count], vx = new float[count], vy = new float[count];
        final float[] rot = new float[count], spin = new float[count], size = new float[count];
        for (int i = 0; i < count; i++) {
            x[i] = lerp(left, right, mRandom.nextFloat());
            y[i] = lerp(top, bottom, mRandom.nextFloat());
            vx[i] = (mRandom.nextFloat() - 0.5f) * 120f * dp;
            vy[i] = (mRandom.nextFloat() - 0.7f) * 90f * dp; // some pop up before falling
            rot[i] = mRandom.nextFloat() * 360f;
            spin[i] = (mRandom.nextFloat() - 0.5f) * 720f;
            size[i] = (2.5f + mRandom.nextFloat() * 2.5f) * dp;
        }
        final long start = SystemClock.uptimeMillis();
        add(new Effect() {
            @Override
            public boolean draw(final Canvas canvas, final long now) {
                final float t = (now - start) / 520f;
                if (t >= 1f) return false;
                final float s = (now - start) / 1000f, gravity = 900f * dp;
                mFill.setColor(withAlpha(mAccent, 1f - t * t));
                for (int i = 0; i < count; i++) {
                    final float px = x[i] + vx[i] * s;
                    final float py = y[i] + vy[i] * s + 0.5f * gravity * s * s;
                    final float half = size[i] * 0.5f;
                    canvas.save();
                    canvas.rotate(rot[i] + spin[i] * s, px, py);
                    canvas.drawRect(px - half, py - half, px + half, py + half, mFill);
                    canvas.restore();
                }
                return true;
            }
        });
    }

    /** A soft band of light sweeps right to left across a row. */
    public void addSweep(final float left, final float top, final float right, final float bottom) {
        final float bandWidth = Math.max(60f * dp, (right - left) * 0.3f);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(bandShader(bandWidth, 0.45f));
        final long start = SystemClock.uptimeMillis();
        add(new Effect() {
            @Override
            public boolean draw(final Canvas canvas, final long now) {
                final float t = (now - start) / 380f;
                if (t >= 1f) return false;
                // Travels from just past the right edge to just past the left edge
                final float center = lerp(right + bandWidth * 0.5f, left - bandWidth * 0.5f, easeOutCubic(t));
                drawBand(canvas, paint, center, bandWidth, top, bottom, (float) Math.sin(Math.PI * t));
                return true;
            }
        });
    }

    /** A narrow highlight glides left to right across the spacebar, clipped to the key. */
    public void addShimmer(final float left, final float top, final float right, final float bottom) {
        final float bandWidth = Math.max(36f * dp, (right - left) * 0.22f);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(bandShader(bandWidth, 0.55f));
        final long start = SystemClock.uptimeMillis();
        add(new Effect() {
            @Override
            public boolean draw(final Canvas canvas, final long now) {
                final float t = (now - start) / 320f;
                if (t >= 1f) return false;
                final float center = lerp(left - bandWidth * 0.5f, right + bandWidth * 0.5f, easeOutCubic(t));
                canvas.save();
                canvas.clipRect(left, top, right, bottom);
                drawBand(canvas, paint, center, bandWidth, top, bottom, 1f - t * t);
                canvas.restore();
                return true;
            }
        });
    }

    /** Horizontal gradient from transparent to accent and back, starting at x = 0. */
    private Shader bandShader(final float width, final float peakAlpha) {
        final int peak = withAlpha(mAccent, peakAlpha), clear = mAccent & 0x00FFFFFF;
        return new LinearGradient(0f, 0f, width, 0f,
                new int[] { clear, peak, clear }, null, Shader.TileMode.CLAMP);
    }

    private void drawBand(final Canvas canvas, final Paint paint, final float center, final float width,
                          final float top, final float bottom, final float alpha) {
        paint.setAlpha((int) (255 * clamp01(alpha)));
        canvas.save();
        // The shader lives in local coordinates, so translating moves the gradient with the band
        canvas.translate(center - width * 0.5f, 0f);
        canvas.drawRect(0f, top, width, bottom, paint);
        canvas.restore();
    }

    // endregion

    // region Swipe typing: key trace and letter hop

    /** A key the swipe passed over lights up, then fades. */
    public void addTrace(final float left, final float top, final float right, final float bottom) {
        final long start = SystemClock.uptimeMillis();
        final float corner = 8f * dp;
        add(new Effect() {
            @Override
            public boolean draw(final Canvas canvas, final long now) {
                final float t = (now - start) / 620f;
                if (t >= 1f) return false;
                // Snaps in fast, then lingers while fading
                final float inset = 3f * dp * (1f - easeOutCubic(clamp01(t / 0.15f)));
                final float fade = (float) Math.pow(1f - t, 1.5);
                mRect.set(left + inset, top + inset, right - inset, bottom - inset);
                mFill.setColor(withAlpha(mAccent, 0.32f * fade));
                canvas.drawRoundRect(mRect, corner, corner, mFill);
                mStroke.setStrokeWidth(2f * dp);
                mStroke.setColor(withAlpha(mAccent, 0.8f * fade));
                canvas.drawRoundRect(mRect, corner, corner, mStroke);
                return true;
            }
        });
    }

    /**
     * Starts a letter hop for one swipe. Letters gather in a row at baselineY, centered on
     * centerX; when the swipe's word arrives they snap together into it.
     */
    public Hop startHop(final float centerX, final float baselineY, final float maxWidth, final float textSize) {
        final Hop hop = new Hop(centerX, baselineY, maxWidth, textSize);
        if (!add(hop)) return null;
        return hop;
    }

    public final class Hop implements Effect {
        private static final int COLLECTING = 0, FORMING = 1, FALLING = 2;
        private static final long FLIGHT_MS = 300, FORM_MS = 240, HOLD_MS = 380, POP_MS = 240, FALL_MS = 420;
        private static final long MAX_LIFETIME_MS = 6000;

        private final float centerX, baselineY, maxWidth, size;
        private final ArrayList<Letter> letters = new ArrayList<>();
        private final long created = SystemClock.uptimeMillis();
        private int state = COLLECTING;
        private long stateTime;
        private int wordLength;

        private final class Letter {
            String glyph;
            float fromX, fromY;
            long launch;
            float x, y, scale = 1f, alpha;
            int wordIndex = -1;
            boolean spawned;  // a word letter the swipe never crossed (double letters, decoder fixes)
            boolean falling;
            float formFromX, formFromY, vy;
            long fallStart;
        }

        Hop(final float centerX, final float baselineY, final float maxWidth, final float size) {
            this.centerX = centerX;
            this.baselineY = baselineY;
            this.maxWidth = maxWidth;
            this.size = size;
        }

        /** Launches a letter from (fromX, fromY), after an optional delay for a staggered flush. */
        public void addLetter(final String glyph, final float fromX, final float fromY, final long delayMs) {
            if (state != COLLECTING || letters.size() >= 24) return;
            final Letter l = new Letter();
            l.glyph = glyph;
            l.fromX = l.x = l.formFromX = fromX;
            l.fromY = l.y = l.formFromY = fromY;
            l.launch = SystemClock.uptimeMillis() + delayMs;
            letters.add(l);
            invalidateSelf();
        }

        public boolean isCollecting() {
            return state == COLLECTING;
        }

        /**
         * The swipe's word arrived. Crossed letters that spell it (matched in order) slide
         * into place, letters it needs but the swipe never crossed fade in, the rest fall away.
         */
        public void finishWithWord(final String rawWord) {
            if (state != COLLECTING) return;
            final String word = rawWord.length() > 24 ? rawWord.substring(0, 24) : rawWord;
            final long now = SystemClock.uptimeMillis();
            final ArrayList<Letter> spawned = new ArrayList<>();
            int next = 0;
            int index = 0;
            for (int i = 0; i < word.length(); ) {
                final int cp = word.codePointAt(i);
                final String ch = new String(Character.toChars(cp));
                Letter match = null;
                for (int k = next; k < letters.size(); k++) {
                    final Letter l = letters.get(k);
                    if (l.glyph.equalsIgnoreCase(ch)) {
                        match = l;
                        next = k + 1;
                        break;
                    }
                }
                if (match != null) {
                    match.wordIndex = index;
                    match.glyph = ch; // take the word's capitalization
                } else {
                    final Letter l = new Letter();
                    l.glyph = ch;
                    l.wordIndex = index;
                    l.spawned = true;
                    l.launch = now;
                    spawned.add(l);
                }
                index++;
                i += Character.charCount(cp);
            }
            wordLength = index;
            for (final Letter l : letters) {
                l.formFromX = l.x;
                l.formFromY = l.y;
                if (l.wordIndex < 0) startFalling(l, now);
            }
            letters.addAll(spawned);
            state = FORMING;
            stateTime = now;
            invalidateSelf();
        }

        /** No word could be read: the crossed letters themselves form the "word". */
        public void finishNoWord() {
            if (state != COLLECTING) return;
            final StringBuilder sb = new StringBuilder();
            for (final Letter l : letters) sb.append(l.glyph);
            if (sb.length() == 0) {
                cancel();
            } else {
                finishWithWord(sb.toString());
            }
        }

        /** The swipe was cancelled: everything drops away. */
        public void cancel() {
            if (state == FALLING) return;
            final long now = SystemClock.uptimeMillis();
            for (final Letter l : letters) startFalling(l, now);
            state = FALLING;
            stateTime = now;
            invalidateSelf();
        }

        private void startFalling(final Letter l, final long now) {
            if (l.falling) return;
            l.falling = true;
            l.fallStart = now;
            l.vy = -80f * dp;
        }

        /** Row slot for the i-th of n gathered letters. */
        private float slotX(final int i, final int n, final float spacing) {
            return centerX + (i - (n - 1) * 0.5f) * spacing;
        }

        @Override
        public boolean draw(final Canvas canvas, final long now) {
            if (state == COLLECTING && now - created > MAX_LIFETIME_MS) cancel();

            boolean alive = false;
            final float wordSpacing = Math.min(size * 0.82f, maxWidth / Math.max(1, wordLength));

            // Count gathered letters so the row stays centered as it grows
            int gathered = 0;
            for (final Letter l : letters) if (!l.falling && !l.spawned) gathered++;
            final float rowSpacing = Math.min(size * 0.72f, maxWidth / Math.max(1, gathered));

            int slot = 0;
            for (final Letter l : letters) {
                if (l.falling) {
                    final float t = (now - l.fallStart) / (float) FALL_MS;
                    if (t >= 1f) continue;
                    // Ballistic drop from wherever the letter was when it started falling
                    final float s = (now - l.fallStart) / 1000f;
                    final float py = l.formFromY + l.vy * s + 0.5f * 1100f * dp * s * s;
                    drawGlowText(canvas, l.glyph, l.formFromX, py, size, 1f - 0.3f * t, l.alpha * (1f - t));
                    alive = true;
                    continue;
                }

                if (state == COLLECTING) {
                    final int mySlot = l.spawned ? -1 : slot++;
                    if (now < l.launch) {
                        alive = true;
                        continue;
                    }
                    final float targetX = slotX(mySlot, gathered, rowSpacing);
                    final float t = (now - l.launch) / (float) FLIGHT_MS;
                    if (t < 1f) {
                        // Hop: an arc from the key up to its slot in the row
                        final float p = easeOutCubic(t);
                        l.x = lerp(l.fromX, targetX, p);
                        l.y = lerp(l.fromY, baselineY, p) - 26f * dp * (float) Math.sin(Math.PI * p);
                        l.scale = 0.8f + 0.2f * p;
                        l.alpha = clamp01(t * 4f);
                    } else {
                        // Resting: drift to the slot as the row re-centers, with a faint bob
                        l.x += (targetX - l.x) * 0.3f;
                        l.y = baselineY + 1.5f * dp * (float) Math.sin((now - l.launch) / 180.0);
                        l.scale = 1f;
                        l.alpha = 1f;
                    }
                    // Remember where it is, for the fall if the swipe gets cancelled
                    l.formFromX = l.x;
                    l.formFromY = l.y;
                    drawGlowText(canvas, l.glyph, l.x, l.y, size, l.scale, l.alpha);
                    alive = true;
                    continue;
                }

                // FORMING: slide into the word, hold, then pop away together
                final float targetX = slotX(l.wordIndex, wordLength, wordSpacing);
                final long since = now - stateTime;
                if (since < FORM_MS) {
                    final float p = easeOutBack(since / (float) FORM_MS);
                    if (l.spawned) {
                        l.x = targetX;
                        l.y = baselineY;
                        l.alpha = clamp01(since / (float) FORM_MS);
                    } else {
                        l.x = lerp(l.formFromX, targetX, p);
                        l.y = lerp(l.formFromY, baselineY, p);
                        l.alpha = 1f;
                    }
                    l.scale = 1f + 0.15f * clamp01(p);
                } else if (since < FORM_MS + HOLD_MS) {
                    l.x = targetX;
                    l.y = baselineY;
                    l.scale = 1.15f;
                    l.alpha = 1f;
                } else {
                    final float t = (since - FORM_MS - HOLD_MS) / (float) POP_MS;
                    if (t >= 1f) continue;
                    l.scale = 1.15f + 0.35f * easeOutCubic(t);
                    l.alpha = 1f - t;
                }
                drawGlowText(canvas, l.glyph, l.x, l.y, size, l.scale, l.alpha);
                alive = true;
            }
            return alive;
        }
    }

    // endregion

    // region Long press: charge ring

    /**
     * Starts a ring that fills around a key over the long-press timeout. It stays hidden for
     * the first part of the hold, so ordinary taps never show it, and pops when it completes.
     */
    public ChargeRing startChargeRing(final float cx, final float cy, final float radius,
                                      final long appearMs, final long durationMs) {
        final ChargeRing ring = new ChargeRing(cx, cy, radius, appearMs, durationMs);
        return add(ring) ? ring : null;
    }

    public final class ChargeRing implements Effect {
        private final float cx, cy, radius;
        private final long start = SystemClock.uptimeMillis(), appearMs, durationMs;
        private boolean cancelled;

        ChargeRing(final float cx, final float cy, final float radius, final long appearMs, final long durationMs) {
            this.cx = cx;
            this.cy = cy;
            this.radius = radius;
            this.appearMs = appearMs;
            this.durationMs = Math.max(durationMs, appearMs + 1);
        }

        public void cancel() {
            cancelled = true;
            invalidateSelf();
        }

        @Override
        public boolean draw(final Canvas canvas, final long now) {
            if (cancelled) return false;
            final long elapsed = now - start;
            if (elapsed >= durationMs) {
                // Charged: burst outward as the long-press action fires
                addRipple(cx, cy, radius * 1.7f);
                return false;
            }
            if (elapsed < appearMs) return true;
            final float progress = (elapsed - appearMs) / (float) (durationMs - appearMs);
            final float fadeIn = clamp01((elapsed - appearMs) / 80f);
            mRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
            mStroke.setStrokeWidth(3f * dp);
            mStroke.setColor(withAlpha(mAccent, 0.18f * fadeIn));
            canvas.drawCircle(cx, cy, radius, mStroke);
            mStroke.setColor(withAlpha(mAccent, 0.9f * fadeIn));
            canvas.drawArc(mRect, -90f, 360f * progress, false, mStroke);
            return true;
        }
    }

    // endregion

    // region Spacebar trackpad

    /** Dims the keys over this rectangle and shows a faint grid, for spacebar cursor mode. */
    public void showTrackpad(final float left, final float top, final float right, final float bottom) {
        mTrackpad.left = left;
        mTrackpad.top = top;
        mTrackpad.right = right;
        mTrackpad.bottom = bottom;
        mTrackpad.target = 1f;
        invalidateSelf();
    }

    public void moveTrackpadFinger(final float x, final float y) {
        mTrackpad.fingerX = x;
        mTrackpad.fingerY = y;
        if (mTrackpad.level > 0f) invalidateSelf();
    }

    public void hideTrackpad() {
        mTrackpad.target = 0f;
        invalidateSelf();
    }

    public boolean isTrackpadShown() {
        return mTrackpad.target > 0f;
    }

    private final class Trackpad {
        float left, top, right, bottom, fingerX, fingerY;
        float level, target;
        long last;

        boolean draw(final Canvas canvas, final long now) {
            // Fade in over 150 ms, out over 200 ms
            final float dt = last == 0 ? 0f : (now - last);
            last = now;
            if (level < target) {
                level = Math.min(target, level + dt / 150f);
            } else if (level > target) {
                level = Math.max(target, level - dt / 200f);
            }
            if (level <= 0f) {
                last = 0;
                return false;
            }

            mFill.setColor(withAlpha(0xFF000000, 0.38f * level));
            canvas.drawRect(left, top, right, bottom, mFill);

            final float step = 22f * dp;
            mStroke.setStrokeWidth(1f * dp);
            mStroke.setColor(withAlpha(mAccent, 0.14f * level));
            for (float x = left + step; x < right; x += step) canvas.drawLine(x, top, x, bottom, mStroke);
            for (float y = top + step; y < bottom; y += step) canvas.drawLine(left, y, right, y, mStroke);

            mFill.setColor(withAlpha(mAccent, 0.5f * level));
            canvas.drawCircle(fingerX, fingerY, 10f * dp, mFill);
            mStroke.setStrokeWidth(1.5f * dp);
            mStroke.setColor(withAlpha(mAccent, 0.3f * level));
            canvas.drawCircle(fingerX, fingerY, 18f * dp, mStroke);
            return level != target;
        }
    }

    // endregion

    // region Keyboard show: row cascade

    /**
     * Plays the row cascade from a snapshot of the keyboard. Rows rise into place bottom
     * first with a springy landing, while the real keyboard view is hidden by the caller.
     *
     * @param rowEdges ascending y positions inside the snapshot, 0 first and its height last
     * @param onDone   called once, as soon as every row has landed (restore the real view)
     */
    public void startCascade(final Bitmap snapshot, final float left, final float top,
                             final int[] rowEdges, final Runnable onDone) {
        finishCascade();
        mCascade = new Cascade(snapshot, left, top, rowEdges, onDone);
        invalidateSelf();
    }

    public boolean isCascadeRunning() {
        return mCascade != null;
    }

    /** Ends the cascade immediately, for example when a finger lands on the keyboard. */
    public void finishCascade() {
        if (mCascade != null) {
            mCascade.finish();
            mCascade.release();
            mCascade = null;
            invalidateSelf();
        }
    }

    private final class Cascade {
        private static final long ROW_MS = 280, STAGGER_MS = 45, LINGER_MS = 50;
        private final Bitmap bitmap;
        private final float left, top;
        private final int[] edges;
        private final Runnable onDone;
        private final long start = SystemClock.uptimeMillis();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect(), dst = new Rect();
        private long finishedAt;

        Cascade(final Bitmap bitmap, final float left, final float top, final int[] edges, final Runnable onDone) {
            this.bitmap = bitmap;
            this.left = left;
            this.top = top;
            this.edges = edges;
            this.onDone = onDone;
        }

        void finish() {
            if (finishedAt == 0) {
                finishedAt = SystemClock.uptimeMillis();
                if (onDone != null) onDone.run();
            }
        }

        void release() {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }

        boolean draw(final Canvas canvas, final long now) {
            // After landing, keep drawing briefly so the real view is back before this disappears
            if (finishedAt != 0) {
                if (now - finishedAt > LINGER_MS) return false;
            }
            final int rows = edges.length - 1;
            final int width = bitmap.getWidth();
            boolean allLanded = true;
            for (int band = 0; band < rows; band++) {
                // Bottom row first
                final int order = rows - 1 - band;
                final float t = (now - start - order * STAGGER_MS) / (float) ROW_MS;
                if (t <= 0f) {
                    allLanded = false;
                    continue;
                }
                if (t < 1f) allLanded = false;
                final float p = easeOutBack(clamp01(t));
                final int bandTop = edges[band], bandBottom = edges[band + 1];
                final int dy = Math.round((1f - p) * (bandBottom - bandTop) * 0.9f);
                src.set(0, bandTop, width, bandBottom);
                dst.set(Math.round(left), Math.round(top) + bandTop + dy,
                        Math.round(left) + width, Math.round(top) + bandBottom + dy);
                paint.setAlpha((int) (255 * clamp01(t * 2.2f)));
                canvas.drawBitmap(bitmap, src, dst, paint);
            }
            if (allLanded) finish();
            return true;
        }
    }

    // endregion
}
