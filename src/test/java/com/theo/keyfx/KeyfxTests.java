package com.theo.keyfx;

/** Plain JVM tests for keyfx's Android-free helpers. Run by test.sh; exits non-zero on failure. */
public final class KeyfxTests {
    private static int failed;

    public static void main(String[] args) {
        keyLookStartsPlain();
        keyLookCombines();
        popupArcSingle();
        popupArcThree();
        popupArcClamps();
        litLettersCounts();
        if (failed > 0) {
            System.out.println(failed + " failed");
            System.exit(1);
        }
        System.out.println("All keyfx tests passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    // region KeyLook

    static void keyLookStartsPlain() {
        check(new KeyLook().isPlain(), "a new KeyLook is plain");
    }

    static void keyLookCombines() {
        final KeyLook l = new KeyLook();
        l.scaleBy(1.2f);
        l.scaleBy(1.5f);
        l.move(2, 3);
        l.move(1, -1);
        l.fade(0.5f);
        l.fadeLabel(0.4f);
        l.fadeLabel(0.5f);
        check(near(l.scale, 1.8f), "scales multiply");
        check(near(l.dx, 3) && near(l.dy, 2), "moves add");
        check(near(l.alpha, 0.5f) && near(l.labelAlpha, 0.2f), "fades multiply");
        check(!l.isPlain(), "a changed KeyLook is not plain");
        l.reset();
        check(l.isPlain(), "reset makes it plain again");
    }

    // endregion

    // region Popup arc

    static void popupArcSingle() {
        final float[] out = new float[2];
        Fx.popupArc(200, 300, 1, 70, 0, 400, 0, out);
        check(near(out[0], 200) && near(out[1], 230), "one accent sits straight above the key");
    }

    static void popupArcThree() {
        final float[] out = new float[6];
        Fx.popupArc(200, 300, 3, 70, 0, 400, 0, out);
        check(near(out[2], 200) && near(out[3], 230), "the middle of three is straight above");
        check(out[0] < 200 && out[4] > 200, "the others fan out to either side");
        check(near(out[0] + out[4], 400) && near(out[1], out[5]), "the arc is symmetric");
        check(out[1] > 230, "the outer accents sit lower than the middle one");
    }

    static void popupArcClamps() {
        final float[] out = new float[6];
        Fx.popupArc(10, 40, 3, 70, 22, 378, 26, out);
        for (int i = 0; i < 3; i++) check(out[i * 2] >= 22 && out[i * 2 + 1] >= 26, "accent " + i + " stays inside");
    }

    // endregion

    // region Lit letters

    static void litLettersCounts() {
        check(Fx.litLetters("hello", "hgfdsewrtyuiklo") == 5, "raw letters: the doubled l lights with the first");
        check(Fx.litLetters("hello", "hel") == 4, "h, e, l, l lit; o not");
        check(Fx.litLetters("world", "wer") == 1, "stops at the first letter not crossed");
        check(Fx.litLetters("Hello", "h") == 1, "case doesn't matter");
        check(Fx.litLetters("", "abc") == 0 && Fx.litLetters(null, "abc") == 0, "no guess, nothing lit");
        check(Fx.litLetters("abc", null) == 0, "nothing crossed, nothing lit");
        check(Fx.litLetters("abc", "") == 0, "an empty swipe lights nothing");
        check(Fx.litLetters("aba", "ab") == 2, "a repeat that is not consecutive needs its own crossing");
        check(Fx.litLetters("aba", "aba") == 3, "a repeat crossed again counts");
        final java.util.Locale saved = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr"));
            check(Fx.litLetters("I", "i") == 1, "case folding ignores the default locale");
        } finally {
            java.util.Locale.setDefault(saved);
        }
    }

    // endregion
}
