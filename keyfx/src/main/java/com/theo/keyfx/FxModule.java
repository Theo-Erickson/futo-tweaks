package com.theo.keyfx;

import android.graphics.Bitmap;
import android.graphics.Canvas;

/**
 * A group of effects. KeyFx forwards every keyboard event to each module; a module reacts
 * to the events it cares about by adding effects, or by updating persistent state it draws
 * every frame. All coordinates are layer coordinates.
 */
public abstract class FxModule {
    protected final KeyFx fx;

    protected FxModule(KeyFx fx) {
        this.fx = fx;
    }

    /** A finger went down on a key. */
    public void onKeyDown(FxKey k, float x, float y) {}

    /** A key typed something. glyph is what was typed (capitals included), x the touch point. */
    public void onKeyTyped(FxKey k, String glyph, float x) {}

    /**
     * Backspace tapped once. letters is everything that press deleted, in text order: one letter
     * usually, a whole word when the keyboard deletes one (like undoing a swiped word), or empty
     * when the host can't tell.
     */
    public void onDeleteTap(FxKey k, FxLetter[] letters) {}

    public void onDeleteHoldStart(FxKey k) {}

    /** One letter deleted during a hold. A word deleted at once arrives as one call per letter, right to left. */
    public void onDeleteHoldLetter(FxLetter letter) {}

    public void onDeleteHoldEnd() {}

    /** A key with a long-press action is being held; the action fires after timeoutMs. */
    public void onLongPressStart(FxKey k, float x, long timeoutMs) {}

    public void onLongPressEnd() {}

    public void onSwipeStart() {}

    public void onSwipePoint(float x, float y, long t) {}

    /** The swipe crossed a letter key; index counts crossed keys from 0. */
    public void onSwipeCross(FxKey k, int index) {}

    /** The swipe picked this letter (where it turned or paused, per the picking setting). */
    public void onSwipePick(FxKey k) {}

    /** The finger lifted; the word may follow in onSwipeWord. */
    public void onSwipeEnd() {}

    /** The word the swipe produced, or null when the host could not tell in time. */
    public void onSwipeWord(String word) {}

    /**
     * The host's live best guess at the word while the swipe is still going. The word may be
     * null or empty when the host has no guess yet.
     */
    public void onSwipeGuess(String word) {}

    public void onSwipeCancel() {}

    /** The spacebar started or stopped acting as a trackpad. */
    public void onCursorMode(boolean on, float x, float y) {}

    public void onCursorMove(float x, float y) {}

    /**
     * The keyboard appeared. A module that animates it from the snapshot returns true and calls
     * onDone once the real keys may show again. The snapshot covers layout.keyboard exactly.
     */
    public boolean onShow(Bitmap snapshot, Runnable onDone) {
        return false;
    }

    /** The keyboard is about to hide (hosts with KeyFx.CAP_HIDE_ANIMATION only). Same contract as onShow. */
    public boolean onHide(Bitmap snapshot, Runnable onDone) {
        return false;
    }

    /** Persistent state drawn beneath the per-event effects. Return true while animating. */
    public boolean drawUnder(Canvas c, long t) {
        return false;
    }

    /** Persistent state drawn above the per-event effects. Return true while animating. */
    public boolean drawOver(Canvas c, long t) {
        return false;
    }

    /**
     * How this module changes a real key right now (hosts with KeyFx.CAP_KEY_TRANSFORM only).
     * Combine into out with scaleBy, move, fade and fadeLabel; never reset it.
     */
    public void look(FxKey k, KeyLook out, long t) {}

    /**
     * True while this module is changing how any real key looks, so the host keeps redrawing.
     * Call fx.kick() when keys start moving: draw() only runs while invalidated.
     */
    public boolean keysMoving(long t) {
        return false;
    }

    /** Drop all state, for example when the keyboard hides. */
    public void reset() {}
}
