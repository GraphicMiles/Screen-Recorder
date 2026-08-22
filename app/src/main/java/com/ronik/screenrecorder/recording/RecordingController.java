package com.ronik.screenrecorder.recording;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.ContextCompat;

public final class RecordingController {
    public enum State {
        IDLE,
        STARTING,
        RECORDING,
        STOPPING,
        SAVING,
        ERROR
    }

    public static final String PREFS_NAME = "screen_recorder_prefs";
    public static final String ACTION_RECORDING_EVENT = "com.ronik.screenrecorder.RECORDING_EVENT";
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_STATE = "state";

    private static final String KEY_QUALITY = "video_quality";

    private static volatile State currentState = State.IDLE;
    private static volatile String lastMessage = "Ready";
    private static volatile String lastError = null;
    private static volatile String lastSavedUri = null;
    private static volatile long updatedAtMs = System.currentTimeMillis();

    private RecordingController() {
    }

    public static synchronized State getCurrentState() {
        return currentState;
    }

    public static synchronized String getLastError() {
        return lastError == null ? "" : lastError;
    }

    public static synchronized String getLastSavedUri() {
        return lastSavedUri == null ? "" : lastSavedUri;
    }

    public static synchronized void startService(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, ScreenRecordingService.class);
        intent.setAction(ScreenRecordingService.ACTION_START);
        intent.putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, data);
        ContextCompat.startForegroundService(context, intent);
    }

    public static synchronized void stopService(Context context, String reason) {
        Intent intent = new Intent(context, ScreenRecordingService.class);
        intent.setAction(ScreenRecordingService.ACTION_STOP);
        intent.putExtra(ScreenRecordingService.EXTRA_STOP_REASON, reason);
        context.startService(intent);
    }

    public static synchronized void updateState(Context context, State newState, String message) {
        currentState = newState;
        lastMessage = message;
        updatedAtMs = System.currentTimeMillis();
        emit(context, "recordingStateChanged", message);
    }

    public static synchronized void reportStarted(Context context) {
        currentState = State.RECORDING;
        lastMessage = "Recording";
        lastError = null;
        updatedAtMs = System.currentTimeMillis();
        emit(context, "recordingStarted", "Recording started.");
    }

    public static synchronized void reportStopped(Context context, String message) {
        currentState = State.STOPPING;
        lastMessage = message;
        updatedAtMs = System.currentTimeMillis();
        emit(context, "recordingStopped", message);
    }

    public static synchronized void reportSaved(Context context, Uri uri, String displayName) {
        currentState = State.IDLE;
        lastSavedUri = uri == null ? null : uri.toString();
        lastMessage = "Saved to device";
        lastError = null;
        updatedAtMs = System.currentTimeMillis();
        emit(context, "recordingSaved", "Recording saved to device.");
    }

    public static synchronized void reportError(Context context, String message) {
        currentState = State.ERROR;
        lastError = message;
        lastMessage = message;
        updatedAtMs = System.currentTimeMillis();
        emit(context, "recordingError", message);
    }

    public static synchronized void moveToIdle(Context context) {
        currentState = State.IDLE;
        lastMessage = "Ready";
        updatedAtMs = System.currentTimeMillis();
        emit(context, "recordingIdle", "Ready");
    }

    public static synchronized String getQualityPreset(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_QUALITY, "automatic");
    }

    public static synchronized void setQualityPreset(Context context, String quality) {
        String sanitized;
        if ("high".equalsIgnoreCase(quality)) {
            sanitized = "high";
        } else if ("standard".equalsIgnoreCase(quality)) {
            sanitized = "standard";
        } else {
            sanitized = "automatic";
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_QUALITY, sanitized)
                .apply();
    }

    public static String getQualityLabel(Context context) {
        return qualityLabel(getQualityPreset(context));
    }

    public static String getStateLabel(State state) {
        switch (state) {
            case STARTING:
                return "Preparing";
            case RECORDING:
                return "Recording";
            case STOPPING:
                return "Stopping";
            case SAVING:
                return "Saving";
            case ERROR:
                return "Error";
            case IDLE:
            default:
                return "Ready";
        }
    }

    private static String qualityLabel(String quality) {
        if ("high".equals(quality)) {
            return "High";
        }
        if ("standard".equals(quality)) {
            return "Standard";
        }
        return "Automatic";
    }

    private static void emit(Context context, String type, String message) {
        Intent intent = new Intent(ACTION_RECORDING_EVENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_TYPE, type);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_STATE, currentState.name());
        context.sendBroadcast(intent);
    }
}
