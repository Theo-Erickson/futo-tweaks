package de.robv.android.xposed;

import android.content.SharedPreferences;

import java.util.Map;
import java.util.Set;

/** Compile-only stub of the Xposed API 82 class. Signatures mirror the real one; never packaged. */
public final class XSharedPreferences implements SharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) { throw new RuntimeException("Stub!"); }
    public synchronized boolean hasFileChanged() { throw new RuntimeException("Stub!"); }
    public synchronized void reload() { throw new RuntimeException("Stub!"); }
    @Override public Map<String, ?> getAll() { throw new RuntimeException("Stub!"); }
    @Override public String getString(String key, String defValue) { throw new RuntimeException("Stub!"); }
    @Override public Set<String> getStringSet(String key, Set<String> defValues) { throw new RuntimeException("Stub!"); }
    @Override public int getInt(String key, int defValue) { throw new RuntimeException("Stub!"); }
    @Override public long getLong(String key, long defValue) { throw new RuntimeException("Stub!"); }
    @Override public float getFloat(String key, float defValue) { throw new RuntimeException("Stub!"); }
    @Override public boolean getBoolean(String key, boolean defValue) { throw new RuntimeException("Stub!"); }
    @Override public boolean contains(String key) { throw new RuntimeException("Stub!"); }
    @Override public Editor edit() { throw new RuntimeException("Stub!"); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { throw new RuntimeException("Stub!"); }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { throw new RuntimeException("Stub!"); }
}
