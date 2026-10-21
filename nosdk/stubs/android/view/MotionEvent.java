package android.view;
public final class MotionEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;
    public static MotionEvent obtain(MotionEvent other) { throw new RuntimeException("Stub!"); }
    public final int getActionMasked() { throw new RuntimeException("Stub!"); }
    public final int getActionIndex() { throw new RuntimeException("Stub!"); }
    public final float getX() { throw new RuntimeException("Stub!"); }
    public final float getY() { throw new RuntimeException("Stub!"); }
    public final float getX(int pointerIndex) { throw new RuntimeException("Stub!"); }
    public final float getY(int pointerIndex) { throw new RuntimeException("Stub!"); }
    public final int getPointerId(int pointerIndex) { throw new RuntimeException("Stub!"); }
    public final int findPointerIndex(int pointerId) { throw new RuntimeException("Stub!"); }
    public final int getPointerCount() { throw new RuntimeException("Stub!"); }
    public final void setAction(int action) { throw new RuntimeException("Stub!"); }
    public final void recycle() { throw new RuntimeException("Stub!"); }
}
