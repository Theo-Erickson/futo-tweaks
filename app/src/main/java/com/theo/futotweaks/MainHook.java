package com.theo.futotweaks;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputConnection;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks for FUTO Keyboard. Design rule: depend on as little of FUTO's code as possible.
 *
 * FUTO release builds keep class and member names (-dontobfuscate) but still run R8, which
 * can inline small private or final methods away. So every FUTO touchpoint here is one that
 * R8 cannot remove:
 *  - framework classes (InputMethodService, the input connection's own class),
 *  - methods called through an interface with several implementations
 *    (KeyboardActionListener on LatinIMELegacy, DrawingProxy.onKeyPressed),
 *  - View overrides (MainKeyboardView.onTouchEvent) and constructors,
 *  - fields the keyboard itself reads (see KeyMap).
 *
 * Every hook installs on its own and every handler catches its own errors, so if a FUTO
 * update breaks one feature the others keep working, and typing is never interrupted.
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "FutoTweaks: ";

    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList(
            "org.futo.inputmethod.latin",
            "org.futo.inputmethod.latin.playstore",
            "org.futo.inputmethod.latin.unstable"));

    private static final String KEYBOARD_VIEW = "org.futo.inputmethod.keyboard.MainKeyboardView";
    private static final String LEGACY_IME = "org.futo.inputmethod.latin.LatinIMELegacy";
    private static final String SETTINGS = "org.futo.inputmethod.latin.settings.Settings";
    private static final String LAYER_FIELD = "futoTweaksLayer";

    // FUTO key codes, inherited unchanged from AOSP LatinIME's Constants
    private static final int CODE_SPACE = ' ';
    private static final int CODE_ENTER = '\n';
    private static final int CODE_DELETE = -5;

    // Spacebar drag tuning, in dp
    private static final float ACTIVATE_DP = 20f;  // vertical travel before the drag takes over
    private static final float LINE_STEP_DP = 24f; // smaller than a key row: little room below the spacebar
    private static final float CHAR_STEP_DP = 16f; // matches FUTO's own horizontal step
    private static final float TRAIL_START_DP = 12f;
    private static final float RING_SLOP_DP = 10f; // movement that cancels a long-press charge

    // How long after a swipe ends to wait for its word before the hop forms without one
    private static final long WORD_WAIT_MS = 900;

    private static XSharedPreferences sPrefs;
    private static ClassLoader sClassLoader;
    private static InputMethodService sIme;
    private static WeakReference<View> sKeyboardView = new WeakReference<>(null);

    // Cached settings, refreshed whenever the keyboard window opens
    private static boolean sVerticalCursor, sCursorHaptics, sHideNavBar;
    private static boolean sPop, sLetterFloat, sHeat, sSpecialKeys;
    private static boolean sKeyTrace, sLetterHop, sChargeRing, sTrackpad, sCascade;
    private static boolean sRipple, sGlow, sParticles, sTrail;
    private static boolean sAnyEffect;
    private static boolean sAnimationsOn = true;
    private static long sLongPressMs = 300;

    // Keyboard view position inside the IME window, updated at every touch-down and key event
    private static final int[] sLoc = new int[2], sRootLoc = new int[2];
    private static float sOffX, sOffY;
    private static float sLastDownX, sLastDownY;

    /** State of the current spacebar drag. Only one keyboard view exists at a time. */
    private static final class SpaceDrag {
        boolean tracking;     // finger went down on the spacebar
        boolean active;       // we took the drag over from FUTO
        boolean futoCursor;   // FUTO's own horizontal cursor drag is running
        boolean composingDone;
        boolean trailStarted;
        int pointerId;
        float downX, downY, anchorX, anchorY;

        void reset() {
            tracking = active = futoCursor = composingDone = trailStarted = false;
        }
    }

    private static final SpaceDrag sDrag = new SpaceDrag();

    // Swipe typing state
    private static final ArrayList<KeyMap.KeyInfo> sSwipeKeys = new ArrayList<>();
    private static KeyMap.KeyInfo sLastSwipeKey;
    private static volatile boolean sGestureActive;
    private static volatile long sWordDeadline;
    private static volatile EffectsLayer.Hop sHop;
    private static volatile String sHopFirstGlyph;
    // UI thread only: true once the buffered keys were flushed into the hop for this swipe
    private static boolean sSwipeFlushed;

    // Long-press charge ring for the primary pointer
    private static EffectsLayer.ChargeRing sRing;

    // Input connection classes already hooked for reading the swiped word
    private static final Set<Class<?>> sHookedConnections = new HashSet<>();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGETS.contains(lpparam.packageName)) return;

        sClassLoader = lpparam.classLoader;
        sPrefs = new XSharedPreferences(Prefs.MODULE_PACKAGE, Prefs.FILE);
        refreshPrefs();

        hookImeService();
        hookKeyboardViewCreation(lpparam.classLoader);
        hookKeyboardTouches(lpparam.classLoader);
        hookKeyPresses(lpparam.classLoader);
        hookKeyboardActions(lpparam.classLoader);
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
        sPop = get(Prefs.EFFECT_POP);
        sLetterFloat = get(Prefs.EFFECT_LETTER_FLOAT);
        sHeat = get(Prefs.EFFECT_HEAT);
        sSpecialKeys = get(Prefs.EFFECT_SPECIAL_KEYS);
        sKeyTrace = get(Prefs.EFFECT_KEY_TRACE);
        sLetterHop = get(Prefs.EFFECT_LETTER_HOP);
        sChargeRing = get(Prefs.EFFECT_CHARGE_RING);
        sTrackpad = get(Prefs.EFFECT_TRACKPAD);
        sCascade = get(Prefs.EFFECT_CASCADE);
        sRipple = get(Prefs.EFFECT_RIPPLE);
        sGlow = get(Prefs.EFFECT_GLOW);
        sParticles = get(Prefs.EFFECT_PARTICLES);
        sTrail = get(Prefs.EFFECT_TRAIL);
        sAnyEffect = sPop || sLetterFloat || sHeat || sSpecialKeys || sKeyTrace || sLetterHop
                || sChargeRing || sTrackpad || sCascade || sRipple || sGlow || sParticles || sTrail;

        // Respect Android's "Remove animations" accessibility setting
        try {
            sAnimationsOn = ValueAnimator.areAnimatorsEnabled();
        } catch (Throwable t) {
            sAnimationsOn = true;
        }
        sLongPressMs = readLongPressTimeout();
    }

    private static boolean get(final String key) {
        return sPrefs.getBoolean(key, Prefs.defaultFor(key));
    }

    /** FUTO's own long-press delay (user adjustable in its settings), read from fields only. */
    private static long readLongPressTimeout() {
        try {
            final Class<?> settings = XposedHelpers.findClass(SETTINGS, sClassLoader);
            final Object instance = XposedHelpers.getStaticObjectField(settings, "sInstance");
            final Object values = XposedHelpers.getObjectField(instance, "mSettingsValues");
            final int ms = XposedHelpers.getIntField(values, "mKeyLongpressTimeout");
            if (ms >= 50 && ms <= 3000) return ms;
        } catch (Throwable ignored) {
            // Settings not loaded yet, or a FUTO update renamed something: use FUTO's default
        }
        return 300;
    }

    private static boolean effectsOn() {
        return sAnyEffect && sAnimationsOn;
    }

    // endregion

    // region Effects layer and coordinates

    /** The effects layer on the IME window's root overlay, created on first use. */
    private static EffectsLayer layerFor(final View keyboardView) {
        final View root = keyboardView.getRootView();
        EffectsLayer layer = (EffectsLayer) XposedHelpers.getAdditionalInstanceField(root, LAYER_FIELD);
        if (layer == null) {
            layer = new EffectsLayer(keyboardView.getResources().getDisplayMetrics().density);
            layer.setAccent(accentColor(keyboardView.getContext()));
            root.getOverlay().add(layer);
            XposedHelpers.setAdditionalInstanceField(root, LAYER_FIELD, layer);
        }
        if (layer.getBounds().width() != root.getWidth() || layer.getBounds().height() != root.getHeight()) {
            layer.setBounds(0, 0, root.getWidth(), root.getHeight());
        }
        return layer;
    }

    /** Records where the keyboard view sits in the window, to convert view to layer coordinates. */
    private static void updateOffset(final View view) {
        view.getLocationInWindow(sLoc);
        view.getRootView().getLocationInWindow(sRootLoc);
        sOffX = sLoc[0] - sRootLoc[0];
        sOffY = sLoc[1] - sRootLoc[1];
    }

    /** The system Material You accent, so effects match the wallpaper-based theme. */
    private static int accentColor(final Context context) {
        try {
            return context.getColor(android.R.color.system_accent1_300);
        } catch (Throwable t) {
            return Color.WHITE;
        }
    }

    private static float dp(final View view, final float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }

    /** Runs on the keyboard's UI thread, after the current event finishes. */
    private static void post(final Runnable action) {
        final View view = sKeyboardView.get();
        if (view != null) view.post(action);
    }

    /** Wraps an action so a bug in one effect is logged instead of crashing the keyboard. */
    private abstract static class SafeRunnable implements Runnable {
        @Override
        public final void run() {
            try {
                safeRun();
            } catch (Throwable t) {
                XposedBridge.log(TAG + "effect failed: " + t);
            }
        }

        abstract void safeRun() throws Throwable;
    }

    // endregion

    // region IME service: nav bar, settings refresh, row cascade, word capture

    private static void hookImeService() {
        try {
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    sIme = (InputMethodService) param.thisObject;
                }
            });

            // FUTO's overrides call super, so these base-class hooks fire on every keyboard show
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onWindowShown", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    sIme = (InputMethodService) param.thisObject;
                    refreshPrefs();
                    applyNavBar(sIme);
                    if (sCascade && sAnimationsOn) {
                        post(new SafeRunnable() {
                            @Override
                            void safeRun() {
                                startCascade();
                            }
                        });
                    }
                }
            });
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onStartInputView",
                    android.view.inputmethod.EditorInfo.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            sIme = (InputMethodService) param.thisObject;
                            refreshPrefs();
                            applyNavBar(sIme);
                            hookConnectionClass(sIme.getCurrentInputConnection());
                        }
                    });
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onWindowHidden", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    post(new SafeRunnable() {
                        @Override
                        void safeRun() {
                            resetEffects();
                        }
                    });
                }
            });
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

    /** Keyboard hidden: drop every running effect and leftover gesture state. */
    private static void resetEffects() {
        sDrag.reset();
        sGestureActive = false;
        sHop = null;
        sRing = null;
        sSwipeKeys.clear();
        sSwipeFlushed = false;
        final View view = sKeyboardView.get();
        if (view == null) return;
        final EffectsLayer layer =
                (EffectsLayer) XposedHelpers.getAdditionalInstanceField(view.getRootView(), LAYER_FIELD);
        if (layer != null) layer.clear();
        view.setAlpha(1f); // never leave the keys hidden by an interrupted cascade
    }

    /**
     * Row cascade: snapshot the keyboard, hide the real view, and let the layer slide the rows
     * in from the snapshot. Row bands come from the actual key rows, so they match any layout.
     */
    private static void startCascade() {
        final View view = sKeyboardView.get();
        if (view == null || !view.isShown() || view.getWidth() == 0 || view.getHeight() == 0) return;
        final int width = view.getWidth(), height = view.getHeight();

        // Distinct row tops, merging keys that sit within a few px of each other
        final TreeSet<Integer> tops = new TreeSet<>();
        for (final KeyMap.KeyInfo k : KeyMap.keys(view)) tops.add(Math.round(k.hitTop));
        final ArrayList<Integer> edges = new ArrayList<>();
        edges.add(0);
        final float merge = dp(view, 8f);
        boolean first = true;
        for (final int top : tops) {
            // The first row's band starts at 0, so padding above it travels with it
            if (first) {
                first = false;
                continue;
            }
            if (top <= 0 || top >= height) continue;
            if (top - edges.get(edges.size() - 1) < merge) continue;
            edges.add(top);
        }
        edges.add(height);
        if (edges.size() < 3) return; // fewer than two rows: nothing to cascade

        final int[] rowEdges = new int[edges.size()];
        for (int i = 0; i < rowEdges.length; i++) rowEdges[i] = edges.get(i);

        final Bitmap snapshot;
        try {
            snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(snapshot));
        } catch (Throwable t) {
            return; // out of memory or a draw failure: just show the keyboard normally
        }

        updateOffset(view);
        final EffectsLayer layer = layerFor(view);
        final Runnable restore = new Runnable() {
            @Override
            public void run() {
                view.setAlpha(1f);
            }
        };
        view.setAlpha(0f);
        layer.startCascade(snapshot, sOffX, sOffY, rowEdges, new Runnable() {
            @Override
            public void run() {
                // Called from inside a draw pass: restore on the next loop, not mid-draw
                view.post(restore);
            }
        });
        // Safety net: the keys come back even if the animation never finishes
        view.postDelayed(restore, 1500);
    }

    /**
     * Hooks the input connection's class to read the word a swipe produces. Only peeks at
     * the text, never changes it, and only acts in the short window after a swipe ends.
     */
    private static void hookConnectionClass(final InputConnection ic) {
        if (ic == null || !(sLetterHop && sAnimationsOn)) return;
        final Class<?> cls = ic.getClass();
        synchronized (sHookedConnections) {
            if (!sHookedConnections.add(cls)) return;
        }
        try {
            final XC_MethodHook peek = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    try {
                        onTextFromSwipe(param.args.length > 0 ? param.args[0] : null);
                    } catch (Throwable ignored) {
                        // Purely cosmetic, never interfere with text input
                    }
                }
            };
            XposedBridge.hookAllMethods(cls, "setComposingText", peek);
            XposedBridge.hookAllMethods(cls, "commitText", peek);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "input connection hook failed: " + t);
        }
    }

    private static void onTextFromSwipe(final Object arg) {
        final EffectsLayer.Hop hop = sHop;
        if (hop == null || sGestureActive || !(arg instanceof CharSequence)) return;
        if (SystemClock.uptimeMillis() > sWordDeadline) return;

        final String word = lettersOnly(arg.toString());
        if (word.isEmpty()) return;
        // A swipe starts on its word's first letter, which filters out unrelated commits
        final String firstGlyph = sHopFirstGlyph;
        if (firstGlyph != null) {
            final String first = new String(Character.toChars(word.codePointAt(0)));
            if (!first.equalsIgnoreCase(firstGlyph)) return;
        }
        sWordDeadline = 0;
        post(new SafeRunnable() {
            @Override
            void safeRun() {
                hop.finishWithWord(word);
            }
        });
    }

    private static String lettersOnly(final String text) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp) || cp == '\'') sb.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    // endregion

    // region Keyboard view: creation, touches

    private static void hookKeyboardViewCreation(final ClassLoader cl) {
        try {
            XposedBridge.hookAllConstructors(XposedHelpers.findClass(KEYBOARD_VIEW, cl), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    sKeyboardView = new WeakReference<>((View) param.thisObject);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "keyboard view constructor hook failed: " + t);
        }
    }

    private static void hookKeyboardTouches(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(KEYBOARD_VIEW, cl, "onTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(final MethodHookParam param) {
                            final View view = (View) param.thisObject;
                            final MotionEvent event = (MotionEvent) param.args[0];
                            if (sKeyboardView.get() != view) sKeyboardView = new WeakReference<>(view);

                            EffectsLayer layer = null;
                            try {
                                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                    updateOffset(view);
                                    sLastDownX = event.getX();
                                    sLastDownY = event.getY();
                                }
                                if (effectsOn()) {
                                    layer = layerFor(view);
                                    // A finger on the keys ends the show animation at once
                                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN && layer.isCascadeRunning()) {
                                        layer.finishCascade();
                                    }
                                    drawTouchEffects(view, layer, event);
                                    trackSwipeAndHold(view, layer, event);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "touch effects failed: " + t);
                            }

                            try {
                                if (handleSpaceDrag(param, view, event, layer)) {
                                    // We own this gesture now, FUTO never sees the event
                                    param.setResult(true);
                                }
                            } catch (Throwable t) {
                                // Never break typing over an optional feature
                                sDrag.reset();
                                XposedBridge.log(TAG + "spacebar drag failed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "keyboard touch hook failed: " + t);
        }
    }

    private static void drawTouchEffects(final View view, final EffectsLayer layer, final MotionEvent event) {
        if (!sRipple && !sParticles) return;
        final int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return;
        final int index = event.getActionIndex();
        final float x = event.getX(index) + sOffX, y = event.getY(index) + sOffY;
        if (sRipple) layer.addRipple(x, y, dp(view, 36f));
        if (sParticles) layer.addParticleBurst(x, y);
    }

    /**
     * Follows the primary finger for two things: the long-press charge ring, and the keys a
     * potential swipe crosses. Crossed keys are buffered until FUTO confirms a swipe, then
     * flushed into the trace and the letter hop, so the first letters are never missed.
     */
    private static void trackSwipeAndHold(final View view, final EffectsLayer layer, final MotionEvent event) {
        final int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                cancelRing();
                final float x = event.getX(), y = event.getY();
                final KeyMap.KeyInfo key = KeyMap.hit(view, x, y);

                sSwipeKeys.clear();
                sSwipeFlushed = false;
                sLastSwipeKey = key;
                if (key != null && key.isLetter()) sSwipeKeys.add(key);

                if (sChargeRing && key != null && key.hasLongPress) {
                    final boolean space = key.code == CODE_SPACE;
                    final float cx = (space ? x : key.centerX()) + sOffX;
                    final float cy = key.centerY() + sOffY;
                    final float radius = space ? key.height() * 0.6f : Math.max(key.width(), key.height()) * 0.55f;
                    sRing = layer.startChargeRing(cx, cy, radius, (long) (sLongPressMs * 0.35f), sLongPressMs);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (event.getPointerCount() != 1) break;
                final float x = event.getX(), y = event.getY();
                if (sRing != null && Math.hypot(x - sLastDownX, y - sLastDownY) > dp(view, RING_SLOP_DP)) {
                    cancelRing();
                }
                if (sDrag.tracking || !(sKeyTrace || sLetterHop)) break;
                final KeyMap.KeyInfo key = KeyMap.hit(view, x, y);
                if (key == null || key == sLastSwipeKey) break;
                sLastSwipeKey = key;
                if (!key.isLetter()) break;
                if (sSwipeFlushed) {
                    emitSwipeKey(layer, key, 0);
                } else if (sSwipeKeys.size() < 40) {
                    sSwipeKeys.add(key);
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelRing();
                break;
            default:
                break;
        }
    }

    private static void cancelRing() {
        if (sRing != null) {
            sRing.cancel();
            sRing = null;
        }
    }

    /** Lights a crossed key and launches its letter into the current hop. */
    private static void emitSwipeKey(final EffectsLayer layer, final KeyMap.KeyInfo key, final long delayMs) {
        if (sKeyTrace) {
            layer.addTrace(key.left + sOffX, key.top + sOffY, key.right + sOffX, key.bottom + sOffY);
        }
        final EffectsLayer.Hop hop = sHop;
        if (sLetterHop && hop != null) {
            final String glyph = key.glyph();
            if (glyph == null) return;
            if (sHopFirstGlyph == null) sHopFirstGlyph = glyph;
            hop.addLetter(glyph, key.centerX() + sOffX, key.centerY() + sOffY, delayMs);
        }
    }

    // endregion

    // region Spacebar: vertical cursor, trail, trackpad

    /**
     * Watches spacebar drags. FUTO keeps handling them as usual (horizontal cursor drag,
     * language swipe, long press) until the finger has clearly moved vertically. At that
     * point FUTO's touch is cancelled, so it neither types a space nor opens the language
     * picker, and this hook moves the cursor by DPAD keys for the rest of the drag:
     * up and down by line, and sideways by character, whichever axis moved further.
     *
     * @return true when the event belongs to a drag we have taken over
     */
    private static boolean handleSpaceDrag(final XC_MethodHook.MethodHookParam param, final View view,
                                           final MotionEvent event, final EffectsLayer layer) throws Throwable {
        final SpaceDrag d = sDrag;
        final float density = view.getResources().getDisplayMetrics().density;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                d.reset();
                if (layer != null) layer.hideTrackpad();
                if (sVerticalCursor || sTrail || sTrackpad) {
                    final float x = event.getX(), y = event.getY();
                    final KeyMap.KeyInfo key = KeyMap.hit(view, x, y);
                    if (key != null && key.code == CODE_SPACE) {
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

                if (layer != null) {
                    updateTrail(layer, d, x, y, density);
                    layer.moveTrackpadFinger(x + sOffX, y + sOffY);
                }

                if (!d.active) {
                    if (!sVerticalCursor) return false;
                    final float dx = x - d.downX, dy = y - d.downY;
                    // Clearly vertical: enough travel, and noticeably more vertical than sideways
                    if (Math.abs(dy) < ACTIVATE_DP * density || Math.abs(dy) < Math.abs(dx) * 1.2f) {
                        return false;
                    }
                    cancelFutoTouch(param, view, event);
                    d.active = true;
                    cancelRing();
                    showTrackpad(view, layer, x, y);
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
                if (layer != null) layer.hideTrackpad();
                return wasActive;
            }

            default:
                return d.active;
        }
    }

    /** Dims the keys and shows the grid while the spacebar is acting as a trackpad. */
    private static void showTrackpad(final View view, final EffectsLayer layer, final float x, final float y) {
        if (!sTrackpad || layer == null) return;
        layer.moveTrackpadFinger(x + sOffX, y + sOffY);
        layer.showTrackpad(sOffX, sOffY, sOffX + view.getWidth(), sOffY + view.getHeight());
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

    private static void updateTrail(final EffectsLayer layer, final SpaceDrag d,
                                    final float x, final float y, final float density) {
        if (!sTrail) return;
        if (!d.trailStarted) {
            // Wait for a real drag, so plain spacebar taps stay clean
            if (Math.hypot(x - d.downX, y - d.downY) < TRAIL_START_DP * density) return;
            d.trailStarted = true;
            layer.addTrailPoint(d.pointerId, d.downX + sOffX, d.downY + sOffY);
        }
        layer.addTrailPoint(d.pointerId, x + sOffX, y + sOffY);
    }

    // endregion

    // region Key presses: glow, pop

    private static void hookKeyPresses(final ClassLoader cl) {
        try {
            final Class<?> viewClass = XposedHelpers.findClass(KEYBOARD_VIEW, cl);
            // hookAllMethods avoids depending on the exact parameter list
            XposedBridge.hookAllMethods(viewClass, "onKeyPressed", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (!(sGlow || sPop) || !effectsOn() || param.args.length == 0 || param.args[0] == null) return;
                    try {
                        final View view = (View) param.thisObject;
                        final Object key = param.args[0];
                        updateOffset(view);
                        final int padLeft = view.getPaddingLeft(), padTop = view.getPaddingTop();
                        int hGap = 0;
                        try {
                            hGap = XposedHelpers.getIntField(key, "horizontalGap");
                        } catch (Throwable ignored) {
                            // Gap unknown: the highlight is a few px wider, still fine
                        }
                        // Same drawn rectangle KeyMap uses, converted to layer coordinates
                        final float left = padLeft + XposedHelpers.getIntField(key, "x") + hGap / 2 + sOffX;
                        final float top = padTop + XposedHelpers.getIntField(key, "y") + sOffY;
                        final float right = left + XposedHelpers.getIntField(key, "width");
                        final float bottom = top + XposedHelpers.getIntField(key, "height");
                        final EffectsLayer layer = layerFor(view);
                        if (sGlow) layer.addGlow(left, top, right, bottom);
                        if (sPop) layer.addPop(left, top, right, bottom);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "key press effect failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "key press hook failed: " + t);
        }
    }

    // endregion

    // region Keyboard actions: typed keys, swipe lifecycle, FUTO's cursor drag

    /**
     * Hooks LatinIMELegacy's KeyboardActionListener methods. They are called through the
     * interface, which has two implementations, so R8 has to keep them as real methods.
     */
    private static void hookKeyboardActions(final ClassLoader cl) {
        final Class<?> legacy;
        try {
            legacy = XposedHelpers.findClass(LEGACY_IME, cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "LatinIMELegacy not found, typing and swipe effects disabled: " + t);
            return;
        }

        hookAll(legacy, "onCodeInput", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                if (!effectsOn() || param.args.length < 4) return;
                final int code = (Integer) param.args[0];
                final boolean repeat = (Boolean) param.args[3];
                post(new SafeRunnable() {
                    @Override
                    void safeRun() {
                        onKeyTyped(code, repeat);
                    }
                });
            }
        });

        hookAll(legacy, "onStartBatchInput", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                sGestureActive = true;
                if (!effectsOn()) return;
                post(new SafeRunnable() {
                    @Override
                    void safeRun() {
                        onSwipeStarted();
                    }
                });
            }
        });

        hookAll(legacy, "onEndBatchInput", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) {
                // Set synchronously: FUTO may commit the word before a posted runnable runs
                sGestureActive = false;
                sWordDeadline = SystemClock.uptimeMillis() + WORD_WAIT_MS;
                final EffectsLayer.Hop hop = sHop;
                if (hop == null) return;
                final View view = sKeyboardView.get();
                if (view == null) return;
                view.postDelayed(new SafeRunnable() {
                    @Override
                    void safeRun() {
                        // No word arrived in time: form the crossed letters instead
                        if (hop.isCollecting()) hop.finishNoWord();
                    }
                }, WORD_WAIT_MS);
            }
        });

        hookAll(legacy, "onCancelBatchInput", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                sGestureActive = false;
                final EffectsLayer.Hop hop = sHop;
                if (hop == null) return;
                post(new SafeRunnable() {
                    @Override
                    void safeRun() {
                        hop.cancel();
                    }
                });
            }
        });

        // FUTO's own horizontal spacebar cursor drag: show the trackpad for it too
        hookAll(legacy, "onMovePointer", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                if (!sTrackpad || !effectsOn()) return;
                post(new SafeRunnable() {
                    @Override
                    void safeRun() {
                        final View view = sKeyboardView.get();
                        if (view == null || !sDrag.tracking || sDrag.futoCursor) return;
                        sDrag.futoCursor = true;
                        cancelRing();
                        showTrackpad(view, layerFor(view), sDrag.downX, sDrag.downY);
                    }
                });
            }
        });
    }

    private static void hookAll(final Class<?> cls, final String method, final XC_MethodHook hook) {
        try {
            if (XposedBridge.hookAllMethods(cls, method, hook).isEmpty()) {
                XposedBridge.log(TAG + method + " not found, its effects are disabled");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + method + " hook failed: " + t);
        }
    }

    /** A key was typed: letter float, special-key effects and the heat streak. */
    private static void onKeyTyped(final int code, final boolean repeat) {
        final View view = sKeyboardView.get();
        if (view == null) return;
        updateOffset(view);
        final EffectsLayer layer = layerFor(view);
        final KeyMap.KeyInfo key = KeyMap.findByCode(view, code, sLastDownX, sLastDownY);

        if (code == CODE_DELETE) {
            // Held backspace repeats: a lighter crumble per repeat so it doesn't flood the screen
            if (sSpecialKeys && key != null) {
                layer.addCrumble(key.left + sOffX, key.top + sOffY, key.right + sOffX, key.bottom + sOffY,
                        repeat ? 3 : 10);
            }
        } else if (code == CODE_ENTER) {
            if (sSpecialKeys) {
                // Sweep the enter key's row, or the spacebar row if the action key types another code
                final KeyMap.KeyInfo row = key != null ? key
                        : KeyMap.findByCode(view, CODE_SPACE, sLastDownX, sLastDownY);
                if (row != null) {
                    layer.addSweep(sOffX, row.top + sOffY, sOffX + view.getWidth(), row.bottom + sOffY);
                }
            }
        } else if (code == CODE_SPACE) {
            if (sSpecialKeys && key != null) {
                layer.addShimmer(key.left + sOffX, key.top + sOffY, key.right + sOffX, key.bottom + sOffY);
            }
        } else if (code > CODE_SPACE && !repeat && sLetterFloat && key != null) {
            final String glyph = key.glyph();
            if (glyph != null) {
                layer.addLetterFloat(glyph, key.centerX() + sOffX, key.centerY() + sOffY, key.height() * 0.42f);
            }
        }

        if (sHeat && !repeat) layer.addHeat(sOffX, sOffY, sOffX + view.getWidth());
    }

    /** FUTO confirmed a swipe: start the hop and flush the keys crossed so far. */
    private static void onSwipeStarted() {
        final View view = sKeyboardView.get();
        if (view == null) return;
        cancelRing();
        updateOffset(view);
        final EffectsLayer layer = layerFor(view);

        // A previous word still on screen gives way to the new swipe
        final EffectsLayer.Hop previous = sHop;
        if (previous != null && previous.isCollecting()) previous.cancel();
        sHop = null;
        sHopFirstGlyph = null;

        if (sLetterHop) {
            // Letters gather in the suggestion bar, just above the keys
            final float baseline = Math.max(dp(view, 14f), sOffY - dp(view, 22f));
            sHop = layer.startHop(sOffX + view.getWidth() * 0.5f, baseline,
                    view.getWidth() * 0.9f, dp(view, 22f));
        }
        for (int i = 0; i < sSwipeKeys.size(); i++) {
            // Staggered so the buffered letters still hop up one after another
            emitSwipeKey(layer, sSwipeKeys.get(i), i * 28L);
        }
        sSwipeKeys.clear();
        sSwipeFlushed = true;
    }

    // endregion
}
