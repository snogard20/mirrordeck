package com.mirrordeck.app;

import android.app.Application;

public class MirrorDeckApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.initialize(this);
        CrashLog.record(this, "Application started", null);
    }
}
