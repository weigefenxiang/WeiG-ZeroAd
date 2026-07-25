package com.weig.rootad;

import android.app.Application;

public final class ZeroAdApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
    }
}
