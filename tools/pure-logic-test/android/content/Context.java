package android.content;

/**
 * Compile-only stub for the pure logic test.  LanProxyService's network
 * methods are not exercised here; this is just enough of Context for javac
 * to compile its token-store signatures without an Android SDK.
 */
public class Context {
    public static final int MODE_PRIVATE = 0;

    public Context getApplicationContext() {
        return this;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return null;
    }
}
