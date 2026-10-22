package com.theo.keyfx;

/**
 * The effect modules keyfx ships, in one place. Adding a module means adding it to both lists.
 */
final class Modules {
    private Modules() {}

    static Section[] sections() {
        return concat(
                TypingFx.sections(),
                SpecialKeysFx.sections(),
                DeleteHoldFx.sections(),
                SwipeTrailFx.sections(),
                SwipeWordFx.sections(),
                HoldFx.sections(),
                ShowFx.sections());
    }

    static FxModule[] create(KeyFx fx) {
        return new FxModule[] {
                new TypingFx(fx),
                new SpecialKeysFx(fx),
                new DeleteHoldFx(fx),
                new SwipeTrailFx(fx),
                new SwipeWordFx(fx),
                new HoldFx(fx),
                new ShowFx(fx),
        };
    }

    private static Section[] concat(Section[]... groups) {
        int n = 0;
        for (Section[] g : groups) n += g.length;
        final Section[] out = new Section[n];
        int i = 0;
        for (Section[] g : groups) for (Section s : g) out[i++] = s;
        return out;
    }
}
