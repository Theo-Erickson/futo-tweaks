package android.graphics;
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 0x01;
    public Paint(int flags) { throw new RuntimeException("Stub!"); }
    public void setStyle(Style style) { throw new RuntimeException("Stub!"); }
    public void setStrokeCap(Cap cap) { throw new RuntimeException("Stub!"); }
    public void setStrokeJoin(Join join) { throw new RuntimeException("Stub!"); }
    public void setColor(int color) { throw new RuntimeException("Stub!"); }
    public void setStrokeWidth(float width) { throw new RuntimeException("Stub!"); }
    public ColorFilter setColorFilter(ColorFilter filter) { throw new RuntimeException("Stub!"); }
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Join { MITER, ROUND, BEVEL }
}
