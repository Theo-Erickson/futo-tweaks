package android.os;
public class Build {
    public static class VERSION {
        // Real field is final, but must not be inlined: read from the device at runtime
        public static int SDK_INT;
    }
    public static class VERSION_CODES {
        public static final int VANILLA_ICE_CREAM = 35;
    }
}
