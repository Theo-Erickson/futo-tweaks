package de.robv.android.xposed;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.util.Set;
public final class XposedBridge {
    public static void log(String text) { throw new RuntimeException("Stub!"); }
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { throw new RuntimeException("Stub!"); }
    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        throw new RuntimeException("Stub!");
    }
}
