package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XCallback;
import java.lang.reflect.Member;
public abstract class XC_MethodHook extends XCallback {
    public XC_MethodHook() { throw new RuntimeException("Stub!"); }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public static final class MethodHookParam extends XCallback.Param {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public void setResult(Object result) { throw new RuntimeException("Stub!"); }
    }
    public class Unhook {}
}
