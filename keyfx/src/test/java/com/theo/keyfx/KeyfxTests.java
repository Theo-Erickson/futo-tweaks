package com.theo.keyfx;

/** Plain JVM tests for keyfx's Android-free helpers. Run by test.sh; exits non-zero on failure. */
public final class KeyfxTests {
    private static int failed;

    public static void main(String[] args) {
        keyLookStartsPlain();
        keyLookCombines();
        keyLookLabel();
        popupArcSingle();
        popupArcThree();
        popupArcClamps();
        popupLayoutsStayInside();
        popupColumnStacksUp();
        popupWheelAndGridShape();
        popupLayoutsScaleWithDensity();
        litLettersCounts();
        migrates();
        comboCrossesTen();
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

    static void keyLookLabel() {
        final KeyLook l = new KeyLook();
        check(l.label == null, "a new KeyLook has no label override");
        l.label = "A";
        check(!l.isPlain(), "a KeyLook with only a label is not plain");
        l.reset();
        check(l.label == null && l.isPlain(), "reset clears the label");
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

    // region Popup layouts

    static void popupLayoutsStayInside() {
        final String[] styles = { "bloom", "wheel", "column", "grid" };
        final float[][] keys = { { 200, 700 }, { 20, 100 }, { 380, 760 }, { 200, 400 } };
        for (String style : styles) {
            for (float[] key : keys) {
                for (int n = 1; n <= 9; n++) {
                    final float[] out = new float[2 * n];
                    check(Fx.popupLayout(style, key[0], key[1] - 20, key[1] - 20, n, 70, 0, 0, 400, 800, 1, out),
                            style + " lays out " + n);
                    for (int i = 0; i < n; i++) {
                        final String what = style + " n=" + n + " accent " + i + " inside the bounds";
                        check(out[i * 2] >= 22 && out[i * 2] <= 378, what + " (x " + out[i * 2] + ")");
                        check(out[i * 2 + 1] >= 26 && out[i * 2 + 1] <= 774, what + " (y " + out[i * 2 + 1] + ")");
                    }
                }
            }
        }
        check(!Fx.popupLayout("row", 200, 300, 280, 3, 70, 0, 0, 400, 800, 1, new float[6]), "row is left to the host");
    }

    static void popupColumnStacksUp() {
        final float[] out = new float[6];
        Fx.popupLayout("column", 200, 700, 680, 3, 70, 0, 0, 400, 800, 1, out);
        check(near(out[0], 200) && near(out[2], 200) && near(out[4], 200), "column keeps the key's x");
        check(near(out[1], 654) && near(out[3], 614) && near(out[5], 574), "column steps up 40 from the key, nearest first");
    }

    static void popupLayoutsScaleWithDensity() {
        // At 2 px per dp the margins and spacings double, and every layout still stays inside
        final String[] styles = { "bloom", "wheel", "column", "grid" };
        for (String style : styles) {
            for (int n = 1; n <= 9; n++) {
                final float[] out = new float[2 * n];
                Fx.popupLayout(style, 40, 1500, 1480, n, 140, 0, 0, 800, 1600, 2, out);
                for (int i = 0; i < n; i++) {
                    check(out[i * 2] >= 44 && out[i * 2] <= 756, style + " n=" + n + " x " + out[i * 2] + " inside at 2x");
                    check(out[i * 2 + 1] >= 52 && out[i * 2 + 1] <= 1548, style + " n=" + n + " y " + out[i * 2 + 1] + " inside at 2x");
                }
            }
        }
        final float[] col = new float[4];
        Fx.popupLayout("column", 400, 700, 680, 2, 140, 0, 0, 800, 1600, 2, col);
        check(near(col[1], 628) && near(col[3], 548), "column spacing doubles at 2x");
    }

    static void popupWheelAndGridShape() {
        final float[] out = new float[8];
        Fx.popupLayout("wheel", 200, 400, 380, 4, 70, 0, 0, 400, 800, 1, out);
        check(near(out[0], 200) && near(out[1], 330), "wheel starts at the top");
        check(near(out[2], 270) && near(out[3], 400), "wheel goes clockwise");
        Fx.popupLayout("grid", 200, 400, 380, 4, 70, 0, 0, 400, 800, 1, out);
        check(near(out[0], out[2] - 42) && near(out[1], out[3]), "grid fills a row left to right");
        check(near(out[5] - out[1], 42) && near(out[0], out[4]), "grid has a second row below the first");
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
        check(Fx.litLetters("don't", "dont") == 4, "an apostrophe is skipped, not counted");
        check(Fx.litLetters("don't", "don") == 3, "the count does not stop at the apostrophe's position");
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

    static void migrates() {
        check("rise".equals(Fx.migrate("float.mode", "grow")), "grow float becomes rise");
        check("join".equals(Fx.migrate("hop.word", "magnet")), "magnet hop becomes join");
        check("blackhole".equals(Fx.migrate("delHold.button", "storm")), "storm becomes black hole");
        check("rails".equals(Fx.migrate("trackpad.look", "grid")), "the moving grid becomes rails");
        check("ghost".equals(Fx.migrate("float.mode", "ghost")), "current values are kept");
        check("form".equals(Fx.hopFromExit("form", true)) && "fall".equals(Fx.hopFromExit("form", false)), "old form exit");
        check("rise".equals(Fx.hopFromExit("burn", true)) && "burn".equals(Fx.hopFromExit("burn", false)), "old burn exit");
    }

    static void comboCrossesTen() {
        check(Fx.crossesTen(9, 10), "9 to 10 flashes");
        check(Fx.crossesTen(8, 13), "a swiped word jumping past 10 flashes");
        check(!Fx.crossesTen(10, 11), "11 doesn't flash");
        check(Fx.crossesTen(19, 20) && !Fx.crossesTen(20, 20), "20 flashes once");
    }

    // endregion
}
