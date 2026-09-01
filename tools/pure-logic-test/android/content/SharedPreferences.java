package android.content;

/** Compile-only SharedPreferences surface used by LanProxyService. */
public interface SharedPreferences {
    String getString(String key, String defValue);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        void apply();
    }
}
