package com.theo.keyfx;

/**
 * How a host should draw one of its real keys right now: scaled about its center, moved, and
 * faded. Several modules can touch the same key, so they combine into it (scales and fades
 * multiply, moves add). Hosts reuse one instance; see KeyFx.look().
 */
public final class KeyLook {
    public float scale, dx, dy, alpha, labelAlpha;

    public KeyLook() {
        reset();
    }

    public void reset() {
        scale = 1;
        dx = 0;
        dy = 0;
        alpha = 1;
        labelAlpha = 1;
    }

    public void scaleBy(float s) {
        scale *= s;
    }

    public void move(float x, float y) {
        dx += x;
        dy += y;
    }

    /** Fades the whole key, background and label. */
    public void fade(float a) {
        alpha *= a;
    }

    /** Fades only the label. */
    public void fadeLabel(float a) {
        labelAlpha *= a;
    }

    /** True when the key draws exactly as normal, so the host can skip the transform. */
    public boolean isPlain() {
        return scale == 1 && dx == 0 && dy == 0 && alpha == 1 && labelAlpha == 1;
    }
}
