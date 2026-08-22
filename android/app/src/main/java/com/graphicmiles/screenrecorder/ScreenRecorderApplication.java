package com.graphicmiles.screenrecorder;

import android.app.Application;

import com.graphicmiles.screenrecorder.recording.DebugLogStore;

public class ScreenRecorderApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DebugLogStore.append(this, "App started", null);

        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            DebugLogStore.append(
                    ScreenRecorderApplication.this,
                    "UNCAUGHT EXCEPTION on thread " + thread.getName(),
                    throwable
            );
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }
}
