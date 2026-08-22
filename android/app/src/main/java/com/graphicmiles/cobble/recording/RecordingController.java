package com.graphicmiles.cobble.recording;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;

public final class RecordingController {
    public enum State {
        IDLE,
        STARTING,
        RECORDING,
        STOPPING,
        SAVING,
        ERROR
    }

    public static final String PREFS_NAME = "cobble_prefs";
    private static final String KEY_QUALITY = "video_quality";

    private static volatile State currentState = State.IDLE;
    private static volatile String lastMessage = "Ready";
    private static volatile String lastError = null;
    private static volatile String lastSavedUri = null;
    private static volatile long updatedAtMs = System.currentTimeMillis();
    private static EventChannel.EventSink eventSink;

    private RecordingController() {
    }

    public static synchronized void setEventSink(EventChannel.EventSink sink) {
        eventSink = sink;
    }

    public static synchronized State getCurrentState() {
        return currentState;
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
        emit(buildEvent("recordingStateChanged", message));
    }

    public static synchronized void reportStarted(Context context) {
        currentState = State.RECORDING;
        lastMessage = "Recording";
        lastError = null;
        updatedAtMs = System.currentTimeMillis();
        emit(buildEvent("recordingStarted", "Recording started."));
    }

    public static synchronized void reportStopped(Context context, String message) {
        currentState = State.STOPPING;
        lastMessage = message;
        updatedAtMs = System.currentTimeMillis();
        emit(buildEvent("recordingStopped", message));
    }

    public static synchronized void reportSaved(Context context, Uri uri, String displayName) {
        currentState = State.IDLE;
        lastSavedUri = uri == null ? null : uri.toString();
        lastMessage = "Saved to device";
        updatedAtMs = System.currentTimeMillis();

        Map<String, Object> event = buildEvent("recordingSaved", "Recording saved to device.");
        event.put("uri", lastSavedUri);
        event.put("displayName", displayName);
        emit(event);
    }

    public static synchronized void reportError(Context context, String message) {
        currentState = State.ERROR;
        lastError = message;
        lastMessage = message;
        updatedAtMs = System.currentTimeMillis();
        emit(buildEvent("recordingError", message));
    }

    public static synchronized void moveToIdle() {
        currentState = State.IDLE;
        lastMessage = "Ready";
        updatedAtMs = System.currentTimeMillis();
    }

    public static synchronized Map<String, Object> getStatus(Context context) {
        Map<String, Object> status = new HashMap<>();
        status.put("state", currentState.name());
        status.put("stateLabel", stateLabel(currentState));
        status.put("message", lastMessage);
        status.put("lastError", lastError == null ? "" : lastError);
        status.put("lastSavedUri", lastSavedUri == null ? "" : lastSavedUri);
        status.put("updatedAtMs", updatedAtMs);
        status.put("supportsGlobalTripleTap", false);
        status.put(
                "globalTapSupportMessage",
                "Android does not expose invisible system-wide raw triple-tap detection to ordinary apps without overlays or elevated privileges. Cobble uses a Quick Settings tile and the recording notification as the reliable global fallback."
        );
        status.put("requiresPerSessionCaptureConsent", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        return status;
    }

    public static synchronized Map<String, Object> getSettings(Context context) {
        StorageManager storageManager = new StorageManager(context);
        Map<String, Object> settings = new HashMap<>();
        String quality = getQualityPreset(context);
        settings.put("quality", quality);
        settings.put("qualityLabel", qualityLabel(quality));
        settings.put("saveMode", StorageManager.getSaveMode(context));
        settings.put("saveModeLabel", storageManager.getSaveModeLabel());
        settings.put("customLocationDescription", storageManager.getCustomLocationDescription());
        settings.put("audioMode", "none");
        settings.put("audioModeLabel", "No audio");
        return settings;
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

    private static String qualityLabel(String quality) {
        if ("high".equals(quality)) {
            return "High";
        }
        if ("standard".equals(quality)) {
            return "Standard";
        }
        return "Automatic";
    }

    private static String stateLabel(State state) {
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

    private static Map<String, Object> buildEvent(String type, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", type);
        payload.put("message", message);
        payload.put("state", currentState.name());
        payload.put("stateLabel", stateLabel(currentState));
        payload.put("updatedAtMs", updatedAtMs);
        return payload;
    }

    private static void emit(Map<String, Object> payload) {
        if (eventSink != null) {
            eventSink.success(payload);
        }
    }
}
