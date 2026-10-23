package com.theo.futotweaks;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputConnection;

import com.theo.keyfx.FxKey;
import com.theo.keyfx.FxLayout;
import com.theo.keyfx.FxLetter;
import com.theo.keyfx.KeyFx;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
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
 * FUTO release builds keep class and member names (-dontobfuscate) but still run R8, which
 * can inline small private or final methods away. So every FUTO touchpoint here is one that
 * R8 cannot remove:
 *  - framework classes (InputMethodService, the input connection's own class),
 *  - methods called through an interface with several implementations
 *    (KeyboardActionListener on LatinIMELegacy),
 *  - View overrides (MainKeyboardView.onTouchEvent) and constructors,
 *  - fields the keyboard itself reads (see KeyMap).
 *
 * The effects themselves live in keyfx (KeyFx and its modules), which knows nothing about FUTO
 * or Xposed. This class only translates FUTO's events into keyfx events.
 *
 * Every hook installs on its own and every handler catches its own errors, so if a FUTO
 * update breaks one feature the others keep working, and typing is never interrupted.
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "FX for FUTO Keyboard: ";

    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList(
            "org.futo.inputmethod.latin",
            "org.futo.inputmethod.latin.playstore",
            "org.futo.inputmethod.latin.unstable"));

    private static final String KEYBOARD_VIEW = "org.futo.inputmethod.keyboard.MainKeyboardView";
    private static final String LEGACY_IME = "org.futo.inputmethod.latin.LatinIMELegacy";
    private static final String SETTINGS = "org.futo.inputmethod.latin.settings.Settings";
    private static final String FX_FIELD = "futoTweaksFx";

    // Spacebar drag tuning, in dp
    private static final float ACTIVATE_DP = 20f;  // vertical travel before the drag takes over
    private static final float LINE_STEP_DP = 24f; // smaller than a key row: little room below the spacebar
    private static final float CHAR_STEP_DP = 16f; // matches FUTO's own horizontal step
    private static final float HOLD_SLOP_DP = 10f; // movement that cancels a long-press charge

    private static XSharedPreferences sPrefs;
    private static ClassLoader sClassLoader;
    private static InputMethodService sIme;
    private static WeakReference<View> sKeyboardView = new WeakReference<>(null);
    private static KeyFx sFx;
    private static BroadcastReceiver sReloadReceiver;

    // The module's own features, refreshed whenever settings change
    private static boolean sVerticalCursor, sCursorHaptics, sHideNavBar, sHoldWords;
    private static int sWordsAfter = 8;
    private static long sWordPaceMs = 220;
    private static boolean sAnimationsOn = true;
    private static long sLongPressMs = 300;

    // Keyboard view position in the layer (the IME window root), updated at every touch-down
    private static final int[] sLoc = new int[2], sRootLoc = new int[2];
    private static float sOffX, sOffY;
    private static float sDownX, sDownY;

    /** State of the current spacebar drag. Only one keyboard view exists at a time. */
    private static final class SpaceDrag {
        boolean tracking;     // finger went down on the spacebar
        boolean active;       // we took the drag over from FUTO
        boolean futoCursor;   // FUTO's own horizontal cursor drag is running
        boolean composingDone;
        int pointerId;
        float downX, downY, anchorX, anchorY;

        void reset() {
            tracking = active = futoCursor = composingDone = false;
        }

        boolean cursorMode() {
            return active || futoCursor;
        }
    }

    private static final SpaceDrag sDrag = new SpaceDrag();

    // Primary finger: the long-press charge, a potential swipe, and a held backspace
    private static boolean sCharging, sSwiping, sDeleteHolding;
    // Points from touch-down until FUTO confirms a swipe: layer x, y and time
    private static final ArrayList<float[]> sSwipeBuffer = new ArrayList<>();
    private static long sSwipeBase;

    // Backspace: text before the cursor and where its letters were, read before FUTO deletes
    private static String sDelBefore;
    private static FxLetter[] sDelLetters;
    // Backspace hold: single deletes so far, and when the last whole word went
    private static int sHoldDeletes;
    private static long sLastWordAt;

    // Input connection classes already hooked for reading the swiped word
    private static final Set<Class<?>> sHookedConnections = new HashSet<>();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGETS.contains(lpparam.packageName)) return;

        sClassLoader = lpparam.classLoader;
        sPrefs = new XSharedPreferences(Prefs.MODULE_PACKAGE, Prefs.FILE);
        readHostPrefs();

        hookImeService();
        hookKeyboardViewCreation(lpparam.classLoader);
        hookKeyboardTouches(lpparam.classLoader);
        hookKeyboardActions(lpparam.classLoader);
    }

    // region Settings and live reload

    /** Re-reads settings if the file changed (or always, when forced by the settings screen). */
    private static void refreshPrefs(final boolean force) {
        boolean changed = force;
        try {
            if (!changed) changed = sPrefs.hasFileChanged();
        } catch (Throwable ignored) {
            // Older framework builds may not support change detection
            changed = true;
        }
        if (changed) {
            sPrefs.reload();
            readHostPrefs();
            if (sFx != null) sFx.reload();
        }
        // Respect Android's "Remove animations" accessibility setting
        try {
            sAnimationsOn = ValueAnimator.areAnimatorsEnabled();
        } catch (Throwable t) {
            sAnimationsOn = true;
        }
        if (sFx != null) sFx.setEnabled(sAnimationsOn);
        sLongPressMs = readLongPressTimeout();
    }

    private static void readHostPrefs() {
        sVerticalCursor = sPrefs.getBoolean(Prefs.key(Prefs.VERTICAL_CURSOR), true);
        sCursorHaptics = sPrefs.getBoolean(Prefs.key(Prefs.CURSOR_HAPTICS), true);
        sHideNavBar = sPrefs.getBoolean(Prefs.key(Prefs.HIDE_NAV_BAR), true);
        sHoldWords = sPrefs.getBoolean(Prefs.key(Prefs.HOLD_WORDS), true);
        sWordsAfter = Math.round(sPrefs.getFloat(Prefs.key(Prefs.WORDS_AFTER), 8));
        sWordPaceMs = Math.round(sPrefs.getFloat(Prefs.key(Prefs.WORD_PACE), 220));
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

    /**
     * The settings screen broadcasts after every change, so edits show up while the keyboard
     * is open. Anyone may send it, but all it does is re-read the settings file.
     */
    private static void registerReloadReceiver(final Context context) {
        if (sReloadReceiver != null) return;
        sReloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context c, final Intent intent) {
                try {
                    refreshPrefs(true);
                    if (sIme != null) applyNavBar(sIme);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "settings reload failed: " + t);
                }
            }
        };
        try {
            final IntentFilter filter = new IntentFilter(Prefs.ACTION_RELOAD);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(sReloadReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(sReloadReceiver, filter);
            }
        } catch (Throwable t) {
            sReloadReceiver = null;
            XposedBridge.log(TAG + "live reload unavailable, settings apply when the keyboard reopens: " + t);
        }
    }

    // endregion

    // region Effects layer and coordinates

    /** The effects engine on the IME window's root overlay, created on first use. */
    private static KeyFx fxFor(final View keyboardView) {
        final View root = keyboardView.getRootView();
        KeyFx fx = (KeyFx) XposedHelpers.getAdditionalInstanceField(root, FX_FIELD);
        if (fx == null) {
            fx = new KeyFx(keyboardView.getResources().getDisplayMetrics().density, sPrefs, Prefs.CAPS);
            try {
                fx.setSystemAccent(keyboardView.getContext().getColor(android.R.color.system_accent1_300));
            } catch (Throwable ignored) {
                // No Material You palette: keyfx keeps its default blue
            }
            fx.setEnabled(sAnimationsOn);
            root.getOverlay().add(fx);
            XposedHelpers.setAdditionalInstanceField(root, FX_FIELD, fx);
        }
        sFx = fx;
        if (fx.getBounds().width() != root.getWidth() || fx.getBounds().height() != root.getHeight()) {
            fx.setBounds(0, 0, root.getWidth(), root.getHeight());
        }
        return fx;
    }

    /** Records where the keyboard view sits in the window, and refreshes the layout for keyfx. */
    private static FxLayout updateLayout(final View view, final KeyFx fx) {
        view.getLocationInWindow(sLoc);
        view.getRootView().getLocationInWindow(sRootLoc);
        sOffX = sLoc[0] - sRootLoc[0];
        sOffY = sLoc[1] - sRootLoc[1];
        final FxLayout layout = KeyMap.layout(view, sOffX, sOffY);
        fx.setLayout(layout);
        return layout;
    }

    private static float dp(final View view, final float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }

    /** Runs on the keyboard's UI thread, after the current event finishes. */
    private static void post(final Runnable action) {
        final View view = sKeyboardView.get();
        if (view != null) view.post(action);
    }

    /** Runs now when already on the UI thread, otherwise posts. */
    private static void onUi(final Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else post(action);
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

    // region IME service: nav bar, settings, show animation, word capture

    private static void hookImeService() {
        try {
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    sIme = (InputMethodService) param.thisObject;
                    registerReloadReceiver(sIme);
                    Caret.hook(sIme.getClass());
                }
            });

            // FUTO's overrides call super, so these base-class hooks fire on every keyboard show
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onWindowShown", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    sIme = (InputMethodService) param.thisObject;
                    refreshPrefs(false);
                    applyNavBar(sIme);
                    if (sAnimationsOn) {
                        post(new SafeRunnable() {
                            @Override
                            void safeRun() {
                                startShow();
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
                            refreshPrefs(false);
                            applyNavBar(sIme);
                            final InputConnection ic = sIme.getCurrentInputConnection();
                            hookConnectionClass(ic);
                            Caret.request(ic);
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
        sCharging = sSwiping = sDeleteHolding = false;
        sSwipeBuffer.clear();
        if (sFx != null) sFx.reset();
        final View view = sKeyboardView.get();
        if (view != null) view.setAlpha(1f); // never leave the keys hidden by an interrupted show
    }

    /** Snapshots the keyboard and lets keyfx animate it in; the real keys show once it is done. */
    private static void startShow() {
        final View view = sKeyboardView.get();
        if (view == null || !view.isShown() || view.getWidth() == 0 || view.getHeight() == 0) return;
        final KeyFx fx = fxFor(view);
        if (!fx.isEnabled() || "none".equals(fx.settings.s("show", "mode"))) return;
        updateLayout(view, fx);

        final Bitmap snapshot;
        try {
            snapshot = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(snapshot));
        } catch (Throwable t) {
            return; // out of memory or a draw failure: just show the keyboard normally
        }
        final Runnable restore = new Runnable() {
            @Override
            public void run() {
                view.setAlpha(1f);
            }
        };
        view.setAlpha(0f);
        if (!fx.show(snapshot, restore)) {
            view.setAlpha(1f);
            return;
        }
        // Safety net: the keys come back even if the animation never finishes
        view.postDelayed(restore, 2500);
    }

    /**
     * Hooks the input connection's class to read the word a swipe produces. Only peeks at
     * the text, never changes it, and only acts in the short window after a swipe ends.
     */
    private static void hookConnectionClass(final InputConnection ic) {
        if (ic == null) return;
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
        final KeyFx fx = sFx;
        if (fx == null || sSwiping || !(arg instanceof CharSequence) || !fx.awaitingWord()) return;
        final String word = lettersOnly(arg.toString());
        if (word.isEmpty()) return;
        // A swipe starts on its word's first letter, which filters out unrelated commits
        final String first = fx.firstPicked();
        if (first != null && !new String(Character.toChars(word.codePointAt(0))).equalsIgnoreCase(first)) return;
        onUi(new SafeRunnable() {
            @Override
            void safeRun() {
                fx.swipeWord(word);
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

                            KeyFx fx = null;
                            FxLayout layout = FxLayout.EMPTY;
                            try {
                                fx = fxFor(view);
                                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                    layout = updateLayout(view, fx);
                                } else {
                                    layout = fx.layout();
                                }
                                if (fx.isEnabled()) trackTouch(view, fx, layout, event);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "touch effects failed: " + t);
                            }

                            try {
                                if (handleSpaceDrag(param, view, event, fx, layout)) {
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

    /**
     * Follows the fingers for keyfx: key-down effects, the long-press charge, a held backspace
     * ending, and the points of a potential swipe. Points are buffered from touch-down until
     * FUTO confirms a swipe, then flushed, so the first letters are never missed.
     */
    private static void trackTouch(final View view, final KeyFx fx, final FxLayout layout, final MotionEvent event) {
        final int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                endCharge(fx);
                final int index = event.getActionIndex();
                final float x = event.getX(index) + sOffX, y = event.getY(index) + sOffY;
                final FxKey key = layout.hit(x, y, dp(view, 2));
                fx.keyDown(key, x, y);
                if (action != MotionEvent.ACTION_DOWN) break;

                sDownX = x;
                sDownY = y;
                sSwiping = false;
                sSwipeBuffer.clear();
                sSwipeBase = event.getEventTime();
                if (key != null && key.isLetter()) sSwipeBuffer.add(new float[] { x, y, 0 });
                if (key != null && key.longPress && key.type != FxKey.DELETE) {
                    fx.longPressStart(key, x, sLongPressMs);
                    sCharging = true;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (event.getPointerCount() != 1) break;
                final float x = event.getX() + sOffX, y = event.getY() + sOffY;
                if (sCharging && Math.hypot(x - sDownX, y - sDownY) > dp(view, HOLD_SLOP_DP)) endCharge(fx);
                if (sSwiping) {
                    fx.swipePoint(x, y, event.getEventTime());
                } else if (!sSwipeBuffer.isEmpty() && sSwipeBuffer.size() < 400) {
                    sSwipeBuffer.add(new float[] { x, y, event.getEventTime() - sSwipeBase });
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                endCharge(fx);
                if (sSwiping) fx.swipePoint(event.getX() + sOffX, event.getY() + sOffY, event.getEventTime());
                if (sDeleteHolding) {
                    sDeleteHolding = false;
                    fx.deleteHoldEnd();
                }
                break;
            }
            default:
                break;
        }
    }

    private static void endCharge(final KeyFx fx) {
        if (!sCharging) return;
        sCharging = false;
        fx.longPressEnd();
    }

    // endregion

    // region Spacebar: vertical cursor and trackpad

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
                                           final MotionEvent event, final KeyFx fx, final FxLayout layout) throws Throwable {
        final SpaceDrag d = sDrag;
        final float density = view.getResources().getDisplayMetrics().density;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                d.reset();
                final float x = event.getX(), y = event.getY();
                final FxKey key = layout.hit(x + sOffX, y + sOffY, 0);
                if (key != null && key.type == FxKey.SPACE) {
                    d.tracking = true;
                    d.pointerId = event.getPointerId(0);
                    d.downX = d.anchorX = x;
                    d.downY = d.anchorY = y;
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
                if (fx != null && d.cursorMode()) fx.cursorMove(x + sOffX, y + sOffY);

                if (!d.active) {
                    if (!sVerticalCursor) return false;
                    final float dx = x - d.downX, dy = y - d.downY;
                    // Clearly vertical: enough travel, and noticeably more vertical than sideways
                    if (Math.abs(dy) < ACTIVATE_DP * density || Math.abs(dy) < Math.abs(dx) * 1.2f) {
                        return false;
                    }
                    cancelFutoTouch(param, view, event);
                    final boolean wasCursor = d.cursorMode();
                    d.active = true;
                    if (fx != null) {
                        endCharge(fx);
                        if (!wasCursor) fx.cursorMode(true, x + sOffX, y + sOffY);
                    }
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
                final boolean wasActive = d.active, wasCursor = d.cursorMode();
                d.reset();
                if (fx != null && wasCursor) fx.cursorMode(false, 0, 0);
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

    // endregion

    // region Keyboard actions: typed keys, backspace, swipe lifecycle, FUTO's cursor drag

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
            protected void beforeHookedMethod(final MethodHookParam param) {
                sDelBefore = null;
                if (param.args.length < 4) return;
                final int code = (Integer) param.args[0];
                if (code != KeyMap.CODE_DELETE) return;
                try {
                    beforeDelete(param, (Boolean) param.args[3]);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "backspace handling failed: " + t);
                }
            }

            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                final KeyFx fx = sFx;
                if (fx == null || !fx.isEnabled() || param.args.length < 4) return;
                final int code = (Integer) param.args[0];
                final boolean repeat = (Boolean) param.args[3];
                // Backspace: whatever FUTO actually removed, one letter or a whole word
                FxLetter[] deleted = null;
                if (code == KeyMap.CODE_DELETE && sDelBefore != null && sIme != null) {
                    final String after = Caret.textBefore(sIme.getCurrentInputConnection());
                    deleted = deletedLetters(Caret.deletedLength(sDelBefore, after));
                }
                final FxLetter[] letters = deleted;
                post(new SafeRunnable() {
                    @Override
                    void safeRun() {
                        onKeyTyped(fx, code, repeat, letters);
                    }
                });
            }
        });

        hookAll(legacy, "onStartBatchInput", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                onUi(new SafeRunnable() {
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
                onUi(new SafeRunnable() {
                    @Override
                    void safeRun() {
                        if (!sSwiping) return;
                        sSwiping = false;
                        if (sFx != null) sFx.swipeEnd();
                    }
                });
            }
        });

        hookAll(legacy, "onCancelBatchInput", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                onUi(new SafeRunnable() {
                    @Override
                    void safeRun() {
                        sSwiping = false;
                        if (sFx != null) sFx.swipeCancel();
                    }
                });
            }
        });

        // FUTO's own horizontal spacebar cursor drag: show the trackpad for it too
        hookAll(legacy, "onMovePointer", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                onUi(new SafeRunnable() {
                    @Override
                    void safeRun() {
                        final KeyFx fx = sFx;
                        if (fx == null || !sDrag.tracking || sDrag.cursorMode()) return;
                        sDrag.futoCursor = true;
                        endCharge(fx);
                        fx.cursorMode(true, sDrag.downX + sOffX, sDrag.downY + sOffY);
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

    /**
     * Before FUTO handles backspace: note the text and where its letters are, so the effects can
     * use exactly what gets deleted. Once a hold has deleted enough single letters, take over the
     * repeats and delete a whole word at a steady pace instead.
     */
    private static void beforeDelete(final XC_MethodHook.MethodHookParam param, final boolean repeat) throws Throwable {
        if (!repeat) {
            sHoldDeletes = 0;
            sLastWordAt = 0;
        }
        final InputMethodService ime = sIme;
        if (ime == null) return;
        final InputConnection ic = ime.getCurrentInputConnection();
        final KeyFx fx = sFx;
        final boolean wantEffects = fx != null && fx.isEnabled();
        final boolean words = repeat && sHoldWords && sHoldDeletes >= sWordsAfter;
        if (!wantEffects && !words) {
            if (repeat) sHoldDeletes++;
            return;
        }
        final String before = Caret.textBefore(ic);
        sDelBefore = before;
        if (wantEffects) {
            final View view = sKeyboardView.get();
            sDelLetters = Caret.lettersBefore(before, Caret.WINDOW, view != null ? view.getRootView() : null);
        }
        if (!words) {
            if (repeat) sHoldDeletes++;
            return;
        }

        // Word mode: FUTO's repeats come faster than words should go, so most are skipped
        param.setResult(null);
        final long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastWordAt < sWordPaceMs || before == null) return;
        sLastWordAt = now;
        // Delete through FUTO itself, one press at a time, so its own word tracking stays right.
        // Stops early if FUTO removes more per press (a whole swiped word, say).
        final int target = wordLength(before);
        for (int i = 0; i < target; i++) {
            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
            final String now2 = Caret.textBefore(ic);
            if (now2 == null || Caret.deletedLength(before, now2) >= target || now2.equals(before)) break;
        }
    }

    /** Chars to delete for one word back: spaces, then the word (or a run of punctuation, or one line break). */
    private static int wordLength(final String text) {
        int i = text.length();
        while (i > 0 && text.charAt(i - 1) == ' ') i--;
        if (i == 0) return text.length();
        final char last = text.charAt(i - 1);
        if (last == '\n') return text.length() - i + 1;
        final boolean word = isWordChar(last);
        while (i > 0) {
            final char c = text.charAt(i - 1);
            if (c == ' ' || c == '\n' || isWordChar(c) != word) break;
            i--;
        }
        return text.length() - i;
    }

    private static boolean isWordChar(final char c) {
        return Character.isLetterOrDigit(c) || c == '\'' || c == '\u2019' || Character.isSurrogate(c);
    }

    /** The positioned letters for the last deleted chars, in text order, without line breaks. */
    private static FxLetter[] deletedLetters(final int chars) {
        final FxLetter[] all = sDelLetters;
        final String before = sDelBefore;
        if (all == null || before == null || chars <= 0) return new FxLetter[0];
        final String tail = before.substring(Math.max(0, before.length() - chars));
        final int n = Math.min(all.length, tail.codePointCount(0, tail.length()));
        final ArrayList<FxLetter> out = new ArrayList<>();
        for (int i = all.length - n; i < all.length; i++) {
            final FxLetter l = all[i];
            // A lone deleted space shows as a dot; inside a word deletion it's just a gap
            if (l == null || (n > 1 && "\u00B7".equals(l.ch))) continue;
            out.add(l);
        }
        return out.toArray(new FxLetter[0]);
    }

    /**
     * A key was typed. Backspace's first press is a tap; FUTO's repeats while it is held
     * become the hold, with each deleted letter flying into the key, word deletions included.
     */
    private static void onKeyTyped(final KeyFx fx, final int code, final boolean repeat, final FxLetter[] deleted) {
        final FxLayout layout = fx.layout();
        final FxKey key = KeyMap.findByCode(layout, code, sDownX, sDownY);
        if (key == null) return;

        if (code == KeyMap.CODE_DELETE) {
            if (!repeat) {
                fx.deleteTap(key, deleted);
            } else {
                if (!sDeleteHolding) {
                    sDeleteHolding = true;
                    fx.deleteHoldStart(key);
                }
                // Right to left, the order they were deleted in
                if (deleted != null) for (int i = deleted.length - 1; i >= 0; i--) fx.deleteHoldLetter(deleted[i]);
            }
            return;
        }
        if (repeat) return;
        final String glyph = code > KeyMap.CODE_SPACE && Character.isValidCodePoint(code)
                ? new String(Character.toChars(code)) : null;
        fx.keyTyped(key, glyph, key.type == FxKey.SPACE ? sDownX : key.cx());
    }

    /** FUTO confirmed a swipe: start it in keyfx and flush the points buffered since touch-down. */
    private static void onSwipeStarted() {
        final KeyFx fx = sFx;
        if (fx == null || !fx.isEnabled()) return;
        endCharge(fx);
        sSwiping = true;
        fx.swipeStart();
        for (final float[] p : sSwipeBuffer) fx.swipePoint(p[0], p[1], sSwipeBase + (long) p[2]);
        sSwipeBuffer.clear();
    }

    // endregion
}
