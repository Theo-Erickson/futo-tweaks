package android.view;
import android.content.Context;
public abstract class ViewGroup extends View {
    public ViewGroup(Context context) { super(context); throw new RuntimeException("Stub!"); }
    public void addView(View child) { throw new RuntimeException("Stub!"); }
    public void addView(View child, LayoutParams params) { throw new RuntimeException("Stub!"); }
    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;
        public LayoutParams(int width, int height) { throw new RuntimeException("Stub!"); }
    }
}
