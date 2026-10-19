package android.view;
import android.content.Context;
import android.content.res.Resources;
public class View {
    public View(Context context) { throw new RuntimeException("Stub!"); }
    public Resources getResources() { throw new RuntimeException("Stub!"); }
    public final Context getContext() { throw new RuntimeException("Stub!"); }
    public int getPaddingLeft() { throw new RuntimeException("Stub!"); }
    public int getPaddingTop() { throw new RuntimeException("Stub!"); }
    public void setPadding(int left, int top, int right, int bottom) { throw new RuntimeException("Stub!"); }
    public boolean performHapticFeedback(int feedbackConstant) { throw new RuntimeException("Stub!"); }
    public ViewOverlay getOverlay() { throw new RuntimeException("Stub!"); }
    public final int getWidth() { throw new RuntimeException("Stub!"); }
    public final int getHeight() { throw new RuntimeException("Stub!"); }
    public void setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener listener) { throw new RuntimeException("Stub!"); }
    public interface OnApplyWindowInsetsListener {
        WindowInsets onApplyWindowInsets(View v, WindowInsets insets);
    }
}
