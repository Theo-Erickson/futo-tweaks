package android.graphics.drawable;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
public abstract class Drawable {
    public Drawable() { throw new RuntimeException("Stub!"); }
    public abstract void draw(Canvas canvas);
    public abstract void setAlpha(int alpha);
    public abstract void setColorFilter(ColorFilter colorFilter);
    public abstract int getOpacity();
    public void invalidateSelf() { throw new RuntimeException("Stub!"); }
    public void setBounds(int left, int top, int right, int bottom) { throw new RuntimeException("Stub!"); }
    public final Rect getBounds() { throw new RuntimeException("Stub!"); }
}
