package android.content;
import android.content.res.Resources;
/** Compile-only stub. Signatures mirror the real framework; never packaged. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0x0000;
    public static final int MODE_WORLD_READABLE = 0x0001;
    public abstract Resources getResources();
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
    public final int getColor(int id) { throw new RuntimeException("Stub!"); }
}
