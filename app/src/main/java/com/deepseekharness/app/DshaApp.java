package com.deepseekharness.app;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

/** 跟随系统亮色 / 暗色。 */
public class DshaApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
