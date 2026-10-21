package android.view;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
public class View {
    public View(Context context) { throw new RuntimeException("Stub!"); }
    public Resources getResources() { throw new RuntimeException("Stub!"); }
    public final Context getContext() { throw new RuntimeException("Stub!"); }
    public View getRootView() { throw new RuntimeException("Stub!"); }
    public void getLocationInWindow(int[] outLocation) { throw new RuntimeException("Stub!"); }
    public int getPaddingLeft() { throw new RuntimeException("Stub!"); }
    public int getPaddingTop() { throw new RuntimeException("Stub!"); }
    public void setPadding(int left, int top, int right, int bottom) { throw new RuntimeException("Stub!"); }
    public boolean performHapticFeedback(int feedbackConstant) { throw new RuntimeException("Stub!"); }
    public ViewOverlay getOverlay() { throw new RuntimeException("Stub!"); }
    public final int getWidth() { throw new RuntimeException("Stub!"); }
    public final int getHeight() { throw new RuntimeException("Stub!"); }
    public boolean isShown() { throw new RuntimeException("Stub!"); }
    public void draw(Canvas canvas) { throw new RuntimeException("Stub!"); }
    public void setAlpha(float alpha) { throw new RuntimeException("Stub!"); }
    public boolean post(Runnable action) { throw new RuntimeException("Stub!"); }
    public boolean postDelayed(Runnable action, long delayMillis) { throw new RuntimeException("Stub!"); }
    public void setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener listener) { throw new RuntimeException("Stub!"); }
    public interface OnApplyWindowInsetsListener {
        WindowInsets onApplyWindowInsets(View v, WindowInsets insets);
    }
}
