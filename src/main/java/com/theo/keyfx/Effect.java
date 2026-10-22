package com.theo.keyfx;

import android.graphics.Canvas;

/** One running effect. draw() returns false once it has finished and can be dropped. */
public abstract class Effect {
    public abstract boolean draw(Canvas canvas, long now);
}
