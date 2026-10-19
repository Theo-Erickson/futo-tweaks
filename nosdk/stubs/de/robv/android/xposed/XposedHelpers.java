package de.robv.android.xposed;
public final class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader classLoader) { throw new RuntimeException("Stub!"); }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) { throw new RuntimeException("Stub!"); }
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) { throw new RuntimeException("Stub!"); }
    public static Object getObjectField(Object obj, String fieldName) { throw new RuntimeException("Stub!"); }
    public static int getIntField(Object obj, String fieldName) { throw new RuntimeException("Stub!"); }
    public static Object getAdditionalInstanceField(Object obj, String key) { throw new RuntimeException("Stub!"); }
    public static Object setAdditionalInstanceField(Object obj, String key, Object value) { throw new RuntimeException("Stub!"); }
}
