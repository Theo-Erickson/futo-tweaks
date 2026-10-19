package android.content;
public interface SharedPreferences {
    boolean getBoolean(String key, boolean defValue);
    Editor edit();
    interface Editor {
        Editor putBoolean(String key, boolean value);
        void apply();
    }
}
