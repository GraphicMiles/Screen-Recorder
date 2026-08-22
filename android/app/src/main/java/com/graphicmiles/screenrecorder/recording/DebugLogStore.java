package com.graphicmiles.screenrecorder.recording;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DebugLogStore {
    private static final String KEY_DEBUG_LOGS = "debug_logs";
    private static final int MAX_CHARS = 60000;

    private DebugLogStore() {
    }

    public static synchronized String append(Context context, String message, Throwable throwable) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
        StringBuilder entry = new StringBuilder();
        entry.append(timestamp).append("  ").append(message == null ? "(no message)" : message);
        if (throwable != null) {
            entry.append("\n").append(stackTrace(throwable));
            Log.e("ScreenRecorder", message, throwable);
        } else {
            Log.d("ScreenRecorder", message == null ? "(no message)" : message);
        }
        entry.append("\n");

        SharedPreferences preferences = context.getSharedPreferences(
                RecordingController.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        String existing = preferences.getString(KEY_DEBUG_LOGS, "");
        String combined = existing + entry;
        if (combined.length() > MAX_CHARS) {
            combined = combined.substring(combined.length() - MAX_CHARS);
            int newlineIndex = combined.indexOf('\n');
            if (newlineIndex >= 0 && newlineIndex < combined.length() - 1) {
                combined = combined.substring(newlineIndex + 1);
            }
        }
        preferences.edit().putString(KEY_DEBUG_LOGS, combined).apply();
        return entry.toString().trim();
    }

    public static synchronized String getLogs(Context context) {
        return context.getSharedPreferences(RecordingController.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DEBUG_LOGS, "");
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(RecordingController.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_DEBUG_LOGS)
                .apply();
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
