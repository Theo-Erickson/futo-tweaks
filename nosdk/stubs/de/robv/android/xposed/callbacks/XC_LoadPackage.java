package de.robv.android.xposed.callbacks;
public abstract class XC_LoadPackage extends XCallback {
    public static final class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public ClassLoader classLoader;
    }
}
