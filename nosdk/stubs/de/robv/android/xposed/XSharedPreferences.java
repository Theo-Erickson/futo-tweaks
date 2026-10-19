package de.robv.android.xposed;
import android.content.SharedPreferences;
public final class XSharedPreferences implements SharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) { throw new RuntimeException("Stub!"); }
    public boolean getBoolean(String key, boolean defValue) { throw new RuntimeException("Stub!"); }
    public Editor edit() { throw new RuntimeException("Stub!"); }
    public synchronized boolean hasFileChanged() { throw new RuntimeException("Stub!"); }
    public synchronized void reload() { throw new RuntimeException("Stub!"); }
}
