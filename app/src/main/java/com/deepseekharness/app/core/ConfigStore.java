package com.deepseekharness.app.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.deepseekharness.app.data.KeyVault;
import com.deepseekharness.app.util.Constants;

/**
 * 配置的唯一读写入口：SharedPreferences + Keystore 加密的 API key。
 * 所有「设置」页的开关最终都落到这里，键名沿用历史值保证升级不丢。
 */
public class ConfigStore {

    private final SharedPreferences prefs;
    private final KeyVault vault;

    public ConfigStore(Context ctx) {
        this.prefs = ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
        this.vault = new KeyVault(ctx);
    }

    public boolean isWelcomed() {
        return prefs.getBoolean(Constants.KEY_WELCOMED, false);
    }

    public void setWelcomed(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_WELCOMED, v).apply();
    }

    // ================= 接入 =================

    public String getApiKey() {
        return vault.decrypt(prefs.getString(Constants.KEY_API_KEY, ""));
    }

    public void setApiKey(String v) {
        prefs.edit().putString(Constants.KEY_API_KEY, vault.encrypt(v)).apply();
    }

    public String getPort() {
        return String.valueOf(getPortInt());
    }

    public int getPortInt() {
        int p = parsePort(prefs.getString(Constants.KEY_PORT, String.valueOf(Constants.DSH_WEB_PORT)));
        return p == Constants.LAN_BRIDGE_PORT ? Constants.DSH_WEB_PORT : p;
    }

    public void setPort(String v) {
        int p = parsePort(v);
        prefs.edit().putString(Constants.KEY_PORT, String.valueOf(p)).apply();
    }

    private int parsePort(String v) {
        try {
            int p = Integer.parseInt(v == null ? "" : v.trim());
            return (p >= 1 && p <= 65535) ? p : Constants.DSH_WEB_PORT;
        } catch (NumberFormatException e) {
            return Constants.DSH_WEB_PORT;
        }
    }

    // ================= 行为 =================

    public boolean isConfirmShell() {
        return prefs.getBoolean(Constants.KEY_CONFIRM_SHELL, true);
    }

    public void setConfirmShell(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_CONFIRM_SHELL, v).apply();
    }

    public boolean isRootShellAllowed() {
        return prefs.getBoolean(Constants.KEY_ALLOW_ROOT_SHELL, false);
    }

    public void setRootShellAllowed(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, v).apply();
    }

    public boolean isCheckUpdate() {
        return prefs.getBoolean(Constants.KEY_CHECK_UPDATE, true);
    }

    public void setCheckUpdate(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_CHECK_UPDATE, v).apply();
    }

    public boolean isDesktopMode() {
        return prefs.getBoolean(Constants.KEY_DESKTOP_MODE, false);
    }

    public void setDesktopMode(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_DESKTOP_MODE, v).apply();
    }

    public boolean isBackupKey() {
        return prefs.getBoolean(Constants.KEY_BACKUP_KEY, true);
    }

    public void setBackupKey(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_BACKUP_KEY, v).apply();
    }

    public boolean isGeckoCore() {
        return prefs.getBoolean(Constants.KEY_GECKO_CORE, false);
    }

    public void setGeckoCore(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_GECKO_CORE, v).apply();
    }

    /** 默认 proroot；关掉用传统 proot。 */
    public boolean isProroot() {
        return "proroot".equals(prefs.getString(Constants.KEY_CONTAINER_RUNTIME, "proot"));
    }

    public void setProroot(boolean v) {
        prefs.edit().putString(Constants.KEY_CONTAINER_RUNTIME, v ? "proroot" : "proot").apply();
    }

    public boolean isLanMode() {
        return prefs.getBoolean(Constants.KEY_LAN_MODE, false);
    }

    public void setLanMode(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_LAN_MODE, v).apply();
    }

    public int getAutoBackupLaunches() {
        try {
            return Integer.parseInt(prefs.getString(Constants.KEY_AUTO_BACKUP, "5"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void setAutoBackupLaunches(int v) {
        prefs.edit().putString(Constants.KEY_AUTO_BACKUP, String.valueOf(Math.max(0, v))).apply();
    }

    // ================= 其他 =================

    public String getPermissionMode() {
        return prefs.getString(Constants.KEY_PERMISSION_MODE, "danger-full-access");
    }

    public void setPermissionMode(String v) {
        prefs.edit().putString(Constants.KEY_PERMISSION_MODE, v).apply();
    }

    public String getWorkdir() {
        return prefs.getString(Constants.KEY_WORKDIR, Constants.DEFAULT_WORKDIR);
    }

    public void setWorkdir(String v) {
        prefs.edit().putString(Constants.KEY_WORKDIR, v).apply();
    }

    public String getUiTheme() {
        return com.deepseekharness.app.util.UiThemePreference.normalize(prefs.getString("ui_theme", "system"));
    }

    public void setUiTheme(String v) {
        prefs.edit().putString("ui_theme", com.deepseekharness.app.util.UiThemePreference.normalize(v)).apply();
    }

    public boolean isSheetImmersive() {
        return prefs.getBoolean("sheet_immersive", true);
    }

    public void setSheetImmersive(boolean v) {
        prefs.edit().putBoolean("sheet_immersive", v).apply();
    }
}
