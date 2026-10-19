package com.theo.futotweaks;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputConnection;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks for FUTO Keyboard. Design rule: depend on as little of FUTO's code as possible.
 *
 *  - Nav bar hiding only touches the framework's InputMethodService, so it survives any
 *    FUTO update (and would work in other keyboards too).
 *  - The spacebar and effects hooks use only MainKeyboardView.onTouchEvent, onKeyPressed,
 *    the mSpaceKey field and Key's x/y/width/height fields. FUTO builds with -dontobfuscate,
 *    so these names are stable across releases.
 *  - Cursor movement is sent as plain DPAD key events through the input connection, the
 *    same thing a hardware keyboard's arrow keys do, so no FUTO internals are involved.
 *
 * Every hook is installed on its own, so if a FUTO update breaks one, the rest keep working.
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "FutoTweaks: ";

    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList(
            "org.futo.inputmethod.latin",
            "org.futo.inputmethod.latin.playstore",
            "org.futo.inputmethod.latin.unstable"));

    private static final String KEYBOARD_VIEW = "org.futo.inputmethod.keyboard.MainKeyboardView";
    private static final String EFFECTS_FIELD = "futoTweaksEffects";

    // Spacebar drag tuning, in dp
    private static final float ACTIVATE_DP = 20f;  // vertical travel before the drag takes over
    private static final float LINE_STEP_DP = 24f; // smaller than a key row: little room below the spacebar
    private static final float CHAR_STEP_DP = 16f; // matches FUTO's own horizontal step
    private static final float TRAIL_START_DP = 12f;

    private static XSharedPreferences sPrefs;
    private static InputMethodService sIme;

    // Cached settings, refreshed whenever the keyboard window opens
    private static boolean sVerticalCursor, sCursorHaptics, sHideNavBar;
    private static boolean sRipple, sGlow, sParticles, sTrail;

    /** State of the current spacebar drag. Only one keyboard view exists at a time. */
    private static final class SpaceDrag {
        boolean tracking;     // finger went down on the spacebar
        boolean active;       // we took the drag over from FUTO
        boolean composingDone;
        boolean trailStarted;
        int pointerId;
        float downX, downY, anchorX, anchorY;

        void reset() {
            tracking = active = composingDone = trailStarted = false;
        }
    }

    private static final SpaceDrag sDrag = new SpaceDrag();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGETS.contains(lpparam.packageName)) return;

        sPrefs = new XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE);
        refreshPrefs();

        hookImeService();
        hookKeyboardTouches(lpparam.classLoader);
        hookKeyPresses(lpparam.classLoader);
    }

    // region Settings

    private static void refreshPrefs() {
        try {
            if (sPrefs.hasFileChanged()) sPrefs.reload();
        } catch (Throwable ignored) {
            // Older framework builds may not support change detection, reload unconditionally
            sPrefs.reload();
        }
        sVerticalCursor = get(Prefs.VERTICAL_CURSOR);
        sCursorHaptics = get(Prefs.CURSOR_HAPTICS);
        sHideNavBar = get(Prefs.HIDE_NAV_BAR);
        sRipple = get(Prefs.EFFECT_RIPPLE);
        sGlow = get(Prefs.EFFECT_GLOW);
        sParticles = get(Prefs.EFFECT_PARTICLES);
        sTrail = get(Prefs.EFFECT_TRAIL);
    }

    private static boolean get(final String key) {
        return sPrefs.getBoolean(key, Prefs.defaultFor(key));
    }

    // endregion

    // region IME service: capture the instance, hide the nav bar

    private static void hookImeService() {
        try {
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    sIme = (InputMethodService) param.thisObject;
                }
            });

            // FUTO's overrides call super, so these base-class hooks fire on every keyboard show
            final XC_MethodHook onShow = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    sIme = (InputMethodService) param.thisObject;
                    refreshPrefs();
                    applyNavBar(sIme);
                }
            };
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onWindowShown", onShow);
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onStartInputView",
                    android.view.inputmethod.EditorInfo.class, boolean.class, onShow);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "IME service hooks failed: " + t);
        }
    }

    /**
     * Android 15+ lets the IME hide the IME navigation bar (collapse arrow and switcher)
     * by hiding captionBar insets on its own window. Older versions ignore the request.
     */
    private static void applyNavBar(final InputMethodService ime) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return;
        try {
            final Window window = ime.getWindow().getWindow();
            if (window == null) return;
            final WindowInsetsController controller = window.getInsetsController();
            if (controller == null) return;
            if (sHideNavBar) {
                controller.hide(WindowInsets.Type.captionBar());
            } else {
                controller.show(WindowInsets.Type.captionBar());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "nav bar update failed: " + t);
        }
    }

    // endregion

    // region Keyboard touches: vertical spacebar cursor, ripple, particles, trail

    private static void hookKeyboardTouches(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(KEYBOARD_VIEW, cl, "onTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(final MethodHookParam param) {
                            final View view = (View) param.thisObject;
                            final MotionEvent event = (MotionEvent) param.args[0];
                            try {
                                drawTouchEffects(view, event);
                                if (handleSpaceDrag(param, view, event)) {
                                    // We own this gesture now, FUTO never sees the event
                                    param.setResult(true);
                                }
                            } catch (Throwable t) {
                                // Never break typing over a cosmetic or optional feature
                                sDrag.reset();
                                XposedBridge.log(TAG + "touch handling failed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "keyboard touch hook failed: " + t);
        }
    }

    /**
     * Watches spacebar drags. FUTO keeps handling them as usual (horizontal cursor drag,
     * language swipe, long press) until the finger has clearly moved vertically. At that
     * point FUTO's touch is cancelled, so it neither types a space nor opens the language
     * picker, and this hook moves the cursor by DPAD keys for the rest of the drag:
     * up and down by line, and sideways by character, whichever axis moved further.
     *
     * @return true when the event belongs to a drag we have taken over
     */
    private static boolean handleSpaceDrag(final XC_MethodHook.MethodHookParam param,
                                           final View view, final MotionEvent event) throws Throwable {
        final SpaceDrag d = sDrag;
        final float density = view.getResources().getDisplayMetrics().density;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                d.reset();
                if (sVerticalCursor || sTrail) {
                    final float x = event.getX(), y = event.getY();
                    if (isOnSpace(view, x, y)) {
                        d.tracking = true;
                        d.pointerId = event.getPointerId(0);
                        d.downX = d.anchorX = x;
                        d.downY = d.anchorY = y;
                    }
                }
                return false;
            }

            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger: leave untracked drags to FUTO, swallow it if we own the drag
                if (!d.active) d.tracking = false;
                return d.active;

            case MotionEvent.ACTION_MOVE: {
                if (!d.tracking) return false;
                final int index = event.findPointerIndex(d.pointerId);
                if (index < 0) return d.active;
                final float x = event.getX(index), y = event.getY(index);

                updateTrail(view, d, x, y, density);

                if (!d.active) {
                    if (!sVerticalCursor) return false;
                    final float dx = x - d.downX, dy = y - d.downY;
                    // Clearly vertical: enough travel, and noticeably more vertical than sideways
                    if (Math.abs(dy) < ACTIVATE_DP * density || Math.abs(dy) < Math.abs(dx) * 1.2f) {
                        return false;
                    }
                    cancelFutoTouch(param, view, event);
                    d.active = true;
                    // The activating movement counts as the first line step
                    sendCursorKeys(view, dy < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, 1);
                    d.anchorX = x;
                    d.anchorY = y;
                    return true;
                }

                // Already ours: step on whichever axis travelled further since the last step
                final float lineStep = LINE_STEP_DP * density;
                final float charStep = CHAR_STEP_DP * density;
                final float dx = x - d.anchorX, dy = y - d.anchorY;
                if (Math.abs(dy) >= lineStep && Math.abs(dy) >= Math.abs(dx)) {
                    final int steps = (int) (dy / lineStep);
                    sendCursorKeys(view, steps < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN,
                            Math.abs(steps));
                    d.anchorY += steps * lineStep;
                    d.anchorX = x; // discard sideways drift so lines don't nudge the cursor
                } else if (Math.abs(dx) >= charStep) {
                    final int steps = (int) (dx / charStep);
                    sendCursorKeys(view, steps < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT,
                            Math.abs(steps));
                    d.anchorX += steps * charStep;
                    d.anchorY = y;
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                return d.active;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                final boolean wasActive = d.active;
                d.reset();
                return wasActive;
            }

            default:
                return d.active;
        }
    }

    /** Sends FUTO a cancel for the current touch, so the spacebar press is dropped cleanly. */
    private static void cancelFutoTouch(final XC_MethodHook.MethodHookParam param,
                                        final View view, final MotionEvent event) throws Throwable {
        final MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try {
            XposedBridge.invokeOriginalMethod(param.method, view, new Object[] { cancel });
        } finally {
            cancel.recycle();
        }
    }

    /** Moves the cursor with DPAD keys, like a hardware keyboard's arrow keys. */
    private static void sendCursorKeys(final View view, final int keyCode, final int count) {
        final InputMethodService ime = sIme;
        if (ime == null || count <= 0) return;
        final InputConnection ic = ime.getCurrentInputConnection();
        if (ic == null) return;

        // Commit FUTO's in-progress word once, so moving away doesn't leave a stale composing region
        if (!sDrag.composingDone) {
            ic.finishComposingText();
            sDrag.composingDone = true;
        }
        for (int i = 0; i < count; i++) {
            ime.sendDownUpKeyEvents(keyCode);
        }
        if (sCursorHaptics) {
            view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE);
        }
    }

    /** True when (x, y) in view coordinates falls on the current layout's spacebar. */
    private static boolean isOnSpace(final View view, final float x, final float y) {
        final Object key = XposedHelpers.getObjectField(view, "mSpaceKey");
        if (key == null) return false; // layouts like emoji have no spacebar
        final Rect r = keyBounds(view, key);
        return r.contains((int) x, (int) y);
    }

    /** A key's bounds in view coordinates. Keys are laid out relative to the view's padding. */
    private static Rect keyBounds(final View view, final Object key) {
        final int left = XposedHelpers.getIntField(key, "x") + view.getPaddingLeft();
        final int top = XposedHelpers.getIntField(key, "y") + view.getPaddingTop();
        return new Rect(left, top,
                left + XposedHelpers.getIntField(key, "width"),
                top + XposedHelpers.getIntField(key, "height"));
    }

    private static void drawTouchEffects(final View view, final MotionEvent event) {
        if (!sRipple && !sParticles) return;
        final int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return;

        final int index = event.getActionIndex();
        final float x = event.getX(index), y = event.getY(index);
        final KeyEffectsDrawable effects = effectsFor(view);
        if (sRipple) effects.addRipple(x, y);
        if (sParticles) effects.addParticleBurst(x, y);
    }

    private static void updateTrail(final View view, final SpaceDrag d,
                                    final float x, final float y, final float density) {
        if (!sTrail) return;
        final KeyEffectsDrawable effects = effectsFor(view);
        if (!d.trailStarted) {
            // Wait for a real drag, so plain spacebar taps stay clean
            if (Math.hypot(x - d.downX, y - d.downY) < TRAIL_START_DP * density) return;
            d.trailStarted = true;
            effects.addTrailPoint(d.pointerId, d.downX, d.downY);
        }
        effects.addTrailPoint(d.pointerId, x, y);
    }

    // endregion

    // region Key presses: glow

    private static void hookKeyPresses(final ClassLoader cl) {
        try {
            final Class<?> viewClass = XposedHelpers.findClass(KEYBOARD_VIEW, cl);
            // hookAllMethods avoids depending on the exact parameter list
            XposedBridge.hookAllMethods(viewClass, "onKeyPressed", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (!sGlow || param.args.length == 0 || param.args[0] == null) return;
                    try {
                        final View view = (View) param.thisObject;
                        final Rect r = keyBounds(view, param.args[0]);
                        effectsFor(view).addGlow(r.left, r.top, r.right, r.bottom);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "glow failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "key press hook failed: " + t);
        }
    }

    // endregion

    // region Effects overlay

    /**
     * Returns the effects drawable for this keyboard view, attaching it to the view's
     * overlay on first use and keeping its bounds matched to the view's size.
     */
    private static KeyEffectsDrawable effectsFor(final View view) {
        KeyEffectsDrawable effects =
                (KeyEffectsDrawable) XposedHelpers.getAdditionalInstanceField(view, EFFECTS_FIELD);
        if (effects == null) {
            effects = new KeyEffectsDrawable(view.getResources().getDisplayMetrics().density);
            effects.setColor(accentColor(view.getContext()));
            view.getOverlay().add(effects);
            XposedHelpers.setAdditionalInstanceField(view, EFFECTS_FIELD, effects);
        }
        final Rect b = effects.getBounds();
        if (b.width() != view.getWidth() || b.height() != view.getHeight()) {
            effects.setBounds(0, 0, view.getWidth(), view.getHeight());
        }
        return effects;
    }

    /** The system Material You accent, so effects match the wallpaper-based theme. */
    private static int accentColor(final Context context) {
        try {
            return context.getColor(android.R.color.system_accent1_300);
        } catch (Throwable t) {
            return Color.WHITE;
        }
    }

    // endregion
}
