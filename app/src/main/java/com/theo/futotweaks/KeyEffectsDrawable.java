/*
 * FUTO Tweaks: keypress visual effects, attached to FUTO's keyboard view by MainHook.
 *
 * This drawable lives in the keyboard view's ViewOverlay, so it renders on top of the keys
 * in its own render node. Animating it only invalidates the overlay, never the key display
 * list, which keeps the effects cheap even while typing quickly.
 *
 * Four independent effects, each toggled in settings:
 *  - Ripple:    a ring that expands and fades from the touch point
 *  - Glow:      the pressed key flashes a soft rounded highlight that fades out
 *  - Particles: a small burst of sparks from the touch point, with a little gravity
 *  - Trail:     a fading line that follows the finger while dragging the spacebar
 */

package com.theo.futotweaks;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.SparseArray;


import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

public final class KeyEffectsDrawable extends Drawable {
    // Effect lifetimes in milliseconds
    private static final long RIPPLE_DURATION_MS = 380;
    private static final long GLOW_DURATION_MS = 260;
    private static final long PARTICLE_DURATION_MS = 520;
    private static final long TRAIL_POINT_LIFETIME_MS = 320;

    private static final int PARTICLES_PER_BURST = 10;
    // Hard cap so mashing keys can never pile up unbounded work
    private static final int MAX_LIVE_EFFECTS = 160;

    private final float mDensity;
    private final Random mRandom = new Random();
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    private int mColor = Color.WHITE;

    // Live effects, oldest first
    private final ArrayList<Ripple> mRipples = new ArrayList<>();
    private final ArrayList<Glow> mGlows = new ArrayList<>();
    private final ArrayList<Particle> mParticles = new ArrayList<>();
    // One trail per pointer id, so two thumbs never join into a single line
    private final SparseArray<ArrayList<TrailPoint>> mTrails = new SparseArray<>();

    private static final class Ripple { float x, y, maxRadius; long start; }
    private static final class Glow { final RectF bounds = new RectF(); float corner; long start; }
    private static final class Particle { float x, y, vx, vy, size; long start; }
    private static final class TrailPoint { float x, y; long time; }

    public KeyEffectsDrawable(final float density) {
        mDensity = density;
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mFillPaint.setStyle(Paint.Style.FILL);
        mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        mStrokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Effects use the theme accent (the swipe trail color) so they match every theme. */
    public void setColor(final int color) {
        mColor = color;
    }

    private int liveCount() {
        return mRipples.size() + mGlows.size() + mParticles.size();
    }

    public void addRipple(final float x, final float y) {
        if (liveCount() > MAX_LIVE_EFFECTS) return;
        final Ripple r = new Ripple();
        r.x = x;
        r.y = y;
        r.maxRadius = 36f * mDensity;
        r.start = SystemClock.uptimeMillis();
        mRipples.add(r);
        invalidateSelf();
    }

    public void addGlow(final float left, final float top, final float right, final float bottom) {
        if (liveCount() > MAX_LIVE_EFFECTS) return;
        final Glow g = new Glow();
        // Inset slightly so the glow sits inside the key's visual background
        final float inset = 2f * mDensity;
        g.bounds.set(left + inset, top + inset, right - inset, bottom - inset);
        g.corner = 8f * mDensity;
        g.start = SystemClock.uptimeMillis();
        mGlows.add(g);
        invalidateSelf();
    }

    public void addParticleBurst(final float x, final float y) {
        if (liveCount() > MAX_LIVE_EFFECTS) return;
        final long now = SystemClock.uptimeMillis();
        for (int i = 0; i < PARTICLES_PER_BURST; i++) {
            final Particle p = new Particle();
            // Spread around the full circle, biased upward so sparks rise off the key
            final double angle = mRandom.nextDouble() * Math.PI * 2.0;
            final float speed = (60f + mRandom.nextFloat() * 90f) * mDensity; // px per second
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(angle) * speed;
            p.vy = (float) Math.sin(angle) * speed - 60f * mDensity;
            p.size = (1.5f + mRandom.nextFloat() * 1.5f) * mDensity;
            p.start = now;
            mParticles.add(p);
        }
        invalidateSelf();
    }

    /** Adds a point to the spacebar trail for this pointer. */
    public void addTrailPoint(final int pointerId, final float x, final float y) {
        ArrayList<TrailPoint> trail = mTrails.get(pointerId);
        if (trail == null) {
            trail = new ArrayList<>();
            mTrails.put(pointerId, trail);
        }
        final TrailPoint t = new TrailPoint();
        t.x = x;
        t.y = y;
        t.time = SystemClock.uptimeMillis();
        trail.add(t);
        invalidateSelf();
    }

    /** Clears every effect, used when effects are switched off or the keyboard changes. */
    public void clear() {
        mRipples.clear();
        mGlows.clear();
        mParticles.clear();
        mTrails.clear();
        invalidateSelf();
    }

    private static int withAlpha(final int color, final float alpha) {
        final int a = Math.max(0, Math.min(255, (int) (Color.alpha(color) * alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    @Override
    public void draw(final Canvas canvas) {
        final long now = SystemClock.uptimeMillis();
        boolean animating = false;

        // Glows first, so ripples and sparks render above them
        for (Iterator<Glow> it = mGlows.iterator(); it.hasNext(); ) {
            final Glow g = it.next();
            final float t = (now - g.start) / (float) GLOW_DURATION_MS;
            if (t >= 1f) { it.remove(); continue; }
            animating = true;
            // Ease out: bright on press, fading quickly
            final float fade = (1f - t) * (1f - t);
            mFillPaint.setColor(withAlpha(mColor, 0.35f * fade));
            canvas.drawRoundRect(g.bounds, g.corner, g.corner, mFillPaint);
            mStrokePaint.setStrokeWidth(2f * mDensity);
            mStrokePaint.setColor(withAlpha(mColor, 0.8f * fade));
            canvas.drawRoundRect(g.bounds, g.corner, g.corner, mStrokePaint);
        }

        for (Iterator<Ripple> it = mRipples.iterator(); it.hasNext(); ) {
            final Ripple r = it.next();
            final float t = (now - r.start) / (float) RIPPLE_DURATION_MS;
            if (t >= 1f) { it.remove(); continue; }
            animating = true;
            // Ease out cubic for the radius, linear fade for the ring
            final float eased = 1f - (1f - t) * (1f - t) * (1f - t);
            mStrokePaint.setStrokeWidth((3f - 2f * t) * mDensity);
            mStrokePaint.setColor(withAlpha(mColor, 0.7f * (1f - t)));
            canvas.drawCircle(r.x, r.y, r.maxRadius * eased, mStrokePaint);
        }

        for (Iterator<Particle> it = mParticles.iterator(); it.hasNext(); ) {
            final Particle p = it.next();
            final float seconds = (now - p.start) / 1000f;
            final float t = (now - p.start) / (float) PARTICLE_DURATION_MS;
            if (t >= 1f) { it.remove(); continue; }
            animating = true;
            // Simple ballistic motion with a light gravity so sparks arc downward
            final float gravity = 320f * mDensity;
            final float px = p.x + p.vx * seconds;
            final float py = p.y + p.vy * seconds + 0.5f * gravity * seconds * seconds;
            mFillPaint.setColor(withAlpha(mColor, 1f - t));
            canvas.drawCircle(px, py, p.size * (1f - 0.5f * t), mFillPaint);
        }

        for (int i = mTrails.size() - 1; i >= 0; i--) {
            final ArrayList<TrailPoint> trail = mTrails.valueAt(i);
            // Drop points that have fully faded
            while (!trail.isEmpty() && now - trail.get(0).time > TRAIL_POINT_LIFETIME_MS) {
                trail.remove(0);
            }
            if (trail.isEmpty()) { mTrails.removeAt(i); continue; }
            animating = true;
            // Draw segment by segment so each part fades by its own age, thinning toward the tail
            for (int j = 1; j < trail.size(); j++) {
                final TrailPoint a = trail.get(j - 1);
                final TrailPoint b = trail.get(j);
                final float life = 1f - (now - b.time) / (float) TRAIL_POINT_LIFETIME_MS;
                mStrokePaint.setStrokeWidth((1f + 4f * life) * mDensity);
                mStrokePaint.setColor(withAlpha(mColor, 0.85f * life));
                canvas.drawLine(a.x, a.y, b.x, b.y, mStrokePaint);
            }
        }

        // Keep the frame loop running only while something is still visible
        if (animating) invalidateSelf();
    }

    @Override
    public void setAlpha(final int alpha) {
        // Per-effect alpha is computed in draw()
    }

    @Override
    public void setColorFilter(final ColorFilter colorFilter) {
        mStrokePaint.setColorFilter(colorFilter);
        mFillPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
