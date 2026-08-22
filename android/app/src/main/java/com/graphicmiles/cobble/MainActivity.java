package com.graphicmiles.cobble;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.graphicmiles.cobble.recording.RecordingController;
import com.graphicmiles.cobble.recording.StorageManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class MainActivity extends FlutterActivity implements MethodChannel.MethodCallHandler {
    public static final String ACTION_START_RECORDING_FLOW =
            "com.graphicmiles.cobble.action.START_RECORDING_FLOW";

    private static final String METHOD_CHANNEL = "com.graphicmiles.cobble/recorder";
    private static final String EVENT_CHANNEL = "com.graphicmiles.cobble/events";
    private static final int REQUEST_SCREEN_CAPTURE = 701;
    private static final int REQUEST_SAVE_LOCATION = 702;
    private static final int REQUEST_POST_NOTIFICATIONS = 703;

    private MethodChannel.Result pendingScreenCaptureResult;
    private MethodChannel.Result pendingSaveLocationResult;
    private MediaProjectionManager mediaProjectionManager;
    private Intent pendingCaptureData;
    private int pendingCaptureResultCode = Activity.RESULT_CANCELED;
    private boolean autoStartAfterCaptureGrant = false;
    private boolean continueCaptureAfterNotificationRequest = false;
    private boolean autoStartIntentPending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mediaProjectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        handleLaunchIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (autoStartIntentPending) {
            autoStartIntentPending = false;
            requestScreenCaptureInternal(true, null);
        }
    }

    private void handleLaunchIntent(Intent intent) {
        autoStartIntentPending = intent != null
                && ACTION_START_RECORDING_FLOW.equals(intent.getAction())
                && RecordingController.getCurrentState() == RecordingController.State.IDLE;
    }

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);

        new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), METHOD_CHANNEL)
                .setMethodCallHandler(this);

        new EventChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), EVENT_CHANNEL)
                .setStreamHandler(new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object arguments, EventChannel.EventSink events) {
                        RecordingController.setEventSink(events);
                    }

                    @Override
                    public void onCancel(Object arguments) {
                        RecordingController.setEventSink(null);
                    }
                });
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "requestScreenCapture":
                requestScreenCaptureInternal(false, result);
                break;
            case "startRecording":
                startRecording(result);
                break;
            case "stopRecording":
                RecordingController.stopService(this, "Stopped by user");
                result.success(true);
                break;
            case "getRecordingStatus":
                result.success(RecordingController.getStatus(this));
                break;
            case "getDisplayInfo":
                result.success(buildDisplayInfo());
                break;
            case "chooseSaveLocation":
                chooseSaveLocation(result);
                break;
            case "getSavedRecordings":
                List<Map<String, Object>> recordings = new StorageManager(this).listSavedRecordings();
                result.success(recordings);
                break;
            case "getSettings":
                result.success(RecordingController.getSettings(this));
                break;
            case "saveSettings":
                saveSettings(call, result);
                break;
            case "openRecording":
                openRecording(call, result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void requestScreenCaptureInternal(boolean autoStart, MethodChannel.Result result) {
        pendingScreenCaptureResult = result;
        autoStartAfterCaptureGrant = autoStart;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            continueCaptureAfterNotificationRequest = true;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS
            );
            return;
        }

        launchScreenCaptureIntent();
    }

    private void launchScreenCaptureIntent() {
        if (mediaProjectionManager == null) {
            resolveScreenCaptureResult(false, "Screen capture is not available on this device.");
            return;
        }
        try {
            Intent captureIntent = mediaProjectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, REQUEST_SCREEN_CAPTURE);
        } catch (Exception error) {
            resolveScreenCaptureResult(false, "Could not open Android's screen-capture prompt.");
        }
    }

    private void resolveScreenCaptureResult(boolean granted, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("granted", granted);
        payload.put("message", message);
        if (pendingScreenCaptureResult != null) {
            pendingScreenCaptureResult.success(payload);
            pendingScreenCaptureResult = null;
        }
    }

    private void startRecording(MethodChannel.Result result) {
        if (pendingCaptureData == null) {
            result.error(
                    "permission_required",
                    "Screen-capture consent is required before each new recording session.",
                    null
            );
            return;
        }

        RecordingController.startService(this, pendingCaptureResultCode, pendingCaptureData);
        pendingCaptureData = null;
        pendingCaptureResultCode = Activity.RESULT_CANCELED;
        result.success(true);
    }

    @SuppressWarnings("unchecked")
    private void saveSettings(MethodCall call, MethodChannel.Result result) {
        Map<String, Object> arguments = (Map<String, Object>) call.arguments;
        if (arguments != null) {
            String quality = stringValue(arguments.get("quality"));
            if (quality != null) {
                RecordingController.setQualityPreset(this, quality);
            }
            String saveMode = stringValue(arguments.get("saveMode"));
            if (saveMode != null) {
                StorageManager.setSaveMode(this, saveMode);
            }
        }
        result.success(RecordingController.getSettings(this));
    }

    private void chooseSaveLocation(MethodChannel.Result result) {
        pendingSaveLocationResult = result;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_SAVE_LOCATION);
    }

    @SuppressWarnings("unchecked")
    private void openRecording(MethodCall call, MethodChannel.Result result) {
        Map<String, Object> arguments = (Map<String, Object>) call.arguments;
        String uriValue = arguments == null ? null : stringValue(arguments.get("uri"));
        if (uriValue == null || uriValue.isEmpty()) {
            result.error("bad_uri", "No recording URI was provided.", null);
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(uriValue), "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            result.success(true);
        } catch (ActivityNotFoundException error) {
            result.error("viewer_missing", "No video player is available for MP4 playback.", null);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS && continueCaptureAfterNotificationRequest) {
            continueCaptureAfterNotificationRequest = false;
            launchScreenCaptureIntent();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                pendingCaptureResultCode = resultCode;
                pendingCaptureData = data;
                resolveScreenCaptureResult(true, "Screen-capture permission granted.");
                if (autoStartAfterCaptureGrant) {
                    RecordingController.startService(this, pendingCaptureResultCode, pendingCaptureData);
                    pendingCaptureData = null;
                    pendingCaptureResultCode = Activity.RESULT_CANCELED;
                    moveTaskToBack(true);
                }
            } else {
                resolveScreenCaptureResult(false, "Screen-capture permission was denied.");
            }
            autoStartAfterCaptureGrant = false;
            return;
        }

        if (requestCode == REQUEST_SAVE_LOCATION && pendingSaveLocationResult != null) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                final int flags = data.getFlags() &
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    getContentResolver().takePersistableUriPermission(treeUri, flags);
                } catch (SecurityException ignored) {
                    // Some providers do not grant persistable permissions consistently.
                }
                StorageManager.persistTreeUri(this, treeUri);
                StorageManager.setSaveMode(this, StorageManager.SAVE_MODE_CUSTOM);
                pendingSaveLocationResult.success(RecordingController.getSettings(this));
            } else {
                Map<String, Object> settings = RecordingController.getSettings(this);
                settings.put("message", "Folder selection was cancelled.");
                pendingSaveLocationResult.success(settings);
            }
            pendingSaveLocationResult = null;
        }
    }

    private Map<String, Object> buildDisplayInfo() {
        Map<String, Object> info = new HashMap<>();
        DisplayMetrics metrics = new DisplayMetrics();
        Display display = getDefaultDisplayCompat();
        if (display != null) {
            display.getRealMetrics(metrics);
            info.put("width", metrics.widthPixels);
            info.put("height", metrics.heightPixels);
            info.put("densityDpi", metrics.densityDpi);
            info.put("refreshRate", display.getRefreshRate());
            info.put("rotation", display.getRotation());
        } else {
            metrics = getResources().getDisplayMetrics();
            info.put("width", metrics.widthPixels);
            info.put("height", metrics.heightPixels);
            info.put("densityDpi", metrics.densityDpi);
            info.put("refreshRate", 60.0);
            info.put("rotation", 0);
        }
        info.put("orientation", metrics.widthPixels >= metrics.heightPixels ? "landscape" : "portrait");
        info.put("preferredH264Encoder", findPreferredH264Encoder());
        return info;
    }

    private Display getDefaultDisplayCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return getDisplay();
        }
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        return windowManager == null ? null : windowManager.getDefaultDisplay();
    }

    private String findPreferredH264Encoder() {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
            if (!codecInfo.isEncoder()) {
                continue;
            }
            for (String type : codecInfo.getSupportedTypes()) {
                if ("video/avc".equalsIgnoreCase(type)) {
                    return codecInfo.getName();
                }
            }
        }
        return "Unavailable";
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
