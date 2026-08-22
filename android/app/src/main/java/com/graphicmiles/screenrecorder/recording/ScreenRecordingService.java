package com.graphicmiles.screenrecorder.recording;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.graphicmiles.screenrecorder.MainActivity;
import com.graphicmiles.screenrecorder.R;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScreenRecordingService extends Service {
    public static final String ACTION_START = "com.graphicmiles.screenrecorder.action.START";
    public static final String ACTION_STOP = "com.graphicmiles.screenrecorder.action.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_STOP_REASON = "stop_reason";

    private static final String TAG = "ScreenRecorder";
    private static final String CHANNEL_ID = "screen_recording";
    private static final int NOTIFICATION_ID = 4001;

    private ExecutorService executor;
    private StorageManager storageManager;
    private MediaProjectionManager mediaProjectionManager;
    private DisplayManager displayManager;
    private Handler mainHandler;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder mediaRecorder;
    private Surface recorderSurface;
    private File tempFile;

    private boolean recorderStarted = false;
    private boolean stopInProgress = false;
    private int captureRotation = 0;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            RecordingController.debug(ScreenRecordingService.this, "MediaProjection callback onStop fired");
            if (!stopInProgress && executor != null) {
                executor.execute(() -> stopRecording("Screen-capture permission was revoked by Android."));
            }
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            Display display = getDefaultDisplayCompat();
            if (display == null) {
                return;
            }
            int newRotation = display.getRotation();
            if (newRotation != captureRotation
                    && !stopInProgress
                    && RecordingController.getCurrentState() == RecordingController.State.RECORDING
                    && executor != null) {
                RecordingController.debug(ScreenRecordingService.this,
                        "Display rotation changed from " + captureRotation + " to " + newRotation);
                executor.execute(() -> stopRecording(
                        "Recording stopped after device rotation to keep the MP4 orientation correct."
                ));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        storageManager = new StorageManager(this);
        mediaProjectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        mainHandler = new Handler(getMainLooper());
        createNotificationChannel();
        RecordingController.debug(this, "ScreenRecordingService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            RecordingController.debug(this, "Service started with null intent or action");
            return START_NOT_STICKY;
        }

        RecordingController.debug(this, "Service onStartCommand action=" + intent.getAction());
        if (ACTION_START.equals(intent.getAction())) {
            final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            final Intent resultData = getResultData(intent);
            executor.execute(() -> startRecording(resultCode, resultData));
        } else if (ACTION_STOP.equals(intent.getAction())) {
            final String reason = intent.getStringExtra(EXTRA_STOP_REASON);
            executor.execute(() -> stopRecording(reason == null ? "Stopping recording." : reason));
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        RecordingController.debug(this, "ScreenRecordingService destroyed");
        releaseResources(false);
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    private Intent getResultData(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private void startRecording(int resultCode, Intent resultData) {
        if (RecordingController.getCurrentState() == RecordingController.State.RECORDING
                || RecordingController.getCurrentState() == RecordingController.State.STARTING) {
            RecordingController.debug(this, "Ignoring duplicate start request");
            return;
        }

        RecordingController.updateState(this, RecordingController.State.STARTING, "Preparing recorder");

        try {
            startForegroundWithProjectionType(buildNotification("Preparing recorder", true));
            RecordingController.debug(this, "Foreground notification started for recording service");

            if (mediaProjectionManager == null || resultData == null || resultCode != Activity.RESULT_OK) {
                throw new IOException("Missing MediaProjection consent result.");
            }

            DisplayMetrics metrics = getRealDisplayMetrics();
            Display display = getDefaultDisplayCompat();
            if (display != null) {
                captureRotation = display.getRotation();
            }
            VideoConfig config = buildVideoConfig(metrics, display);
            RecordingController.debug(this,
                    "Display config width=" + config.width
                            + ", height=" + config.height
                            + ", density=" + config.densityDpi
                            + ", fps=" + config.frameRate
                            + ", bitrate=" + config.bitrate);

            tempFile = storageManager.createTempRecordingFile();
            RecordingController.debug(this, "Temp file: " + tempFile.getAbsolutePath());

            mediaRecorder = createMediaRecorder();
            configureRecorder(mediaRecorder, config, tempFile);
            recorderSurface = mediaRecorder.getSurface();
            RecordingController.debug(this, "MediaRecorder prepared");

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) {
                throw new IOException("Could not acquire a MediaProjection instance.");
            }
            mediaProjection.registerCallback(projectionCallback, mainHandler);
            RecordingController.debug(this, "MediaProjection acquired and callback registered");

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenRecorder",
                    config.width,
                    config.height,
                    config.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorderSurface,
                    null,
                    null
            );
            if (virtualDisplay == null) {
                throw new IOException("Could not create a virtual display.");
            }
            RecordingController.debug(this, "VirtualDisplay created");

            if (displayManager != null) {
                displayManager.registerDisplayListener(displayListener, mainHandler);
            }

            mediaRecorder.start();
            recorderStarted = true;
            stopInProgress = false;
            RecordingController.debug(this, "MediaRecorder.start() succeeded");
            RecordingController.reportStarted(this);
            RecorderTileService.requestTileRefresh(this);
            startForegroundWithProjectionType(buildNotification("Recording in progress", true));
        } catch (Exception error) {
            handleFailure("Could not start recording.", error);
        }
    }

    private void stopRecording(String reason) {
        if (stopInProgress) {
            RecordingController.debug(this, "Ignoring duplicate stop request");
            return;
        }
        stopInProgress = true;
        RecordingController.debug(this, "Stopping recording: " + reason);

        RecordingController.reportStopped(this, reason);
        RecordingController.updateState(this, RecordingController.State.STOPPING, reason);

        Exception stopError = null;
        try {
            if (mediaRecorder != null && recorderStarted) {
                mediaRecorder.stop();
                RecordingController.debug(this, "MediaRecorder.stop() succeeded");
            }
        } catch (Exception error) {
            stopError = error;
            RecordingController.debugError(this, "MediaRecorder.stop() failed", error);
        }
        recorderStarted = false;

        // Release encoder/display resources but keep the MediaProjection alive for now.
        // On Android 14+ stopping the projection revokes the "project_media" app-op that
        // the consent dialog granted, and any later
        // startForeground(FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) then throws a
        // SecurityException ("requires permissions ... project_media").
        releaseResources(false);

        if (stopError != null) {
            handleFailure("Recording could not be finalized.", stopError);
            return;
        }

        RecordingController.updateState(this, RecordingController.State.SAVING, "Saving video");
        try {
            // The service is already foreground; refresh the notification through
            // NotificationManager instead of startForeground() so the system does not
            // re-validate the mediaProjection foreground service type after the
            // projection has been released.
            updateNotification(buildNotification("Saving video", false));
            Map<String, Object> saved = storageManager.finalizeRecording(tempFile);
            String uriString = saved.get("uri") == null ? null : saved.get("uri").toString();
            String displayName = saved.get("displayName") == null ? "Recording" : saved.get("displayName").toString();
            RecordingController.debug(this, "Recording saved to URI: " + uriString);
            RecordingController.reportSaved(this,
                    uriString == null ? null : Uri.parse(uriString),
                    displayName);
            RecorderTileService.requestTileRefresh(this);
        } catch (Exception error) {
            releaseResources(true);
            handleSaveFailure(error);
            return;
        }

        // Saving is done and startForeground() will not be called again, so the
        // projection can finally be released.
        releaseResources(true);

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private MediaRecorder createMediaRecorder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new MediaRecorder(this);
        }
        return new MediaRecorder();
    }

    private void configureRecorder(MediaRecorder recorder, VideoConfig config, File outputFile) throws IOException {
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(outputFile.getAbsolutePath());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(config.width, config.height);
        recorder.setVideoEncodingBitRate(config.bitrate);
        recorder.setVideoFrameRate(config.frameRate);
        recorder.prepare();
    }

    private void handleSaveFailure(Exception error) {
        RecordingController.debugError(this, "Recording finished but save failed", error);
        storageManager.cleanupFailedRecording(tempFile);
        RecordingController.reportError(this, "Recording finished but could not be saved: " + error.getMessage());
        RecordingController.moveToIdle();
        RecorderTileService.requestTileRefresh(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void handleFailure(String message, Exception error) {
        stopInProgress = true;
        Log.e(TAG, message, error);
        RecordingController.debugError(this, message, error);
        storageManager.cleanupFailedRecording(tempFile);
        releaseResources(true);
        RecordingController.reportError(this, message + " " + error.getMessage());
        RecordingController.moveToIdle();
        RecorderTileService.requestTileRefresh(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseResources(boolean stopProjection) {
        try {
            if (displayManager != null) {
                displayManager.unregisterDisplayListener(displayListener);
            }
        } catch (Exception ignored) {
        }

        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
        } catch (Exception ignored) {
        }
        virtualDisplay = null;

        try {
            if (recorderSurface != null) {
                recorderSurface.release();
            }
        } catch (Exception ignored) {
        }
        recorderSurface = null;

        try {
            if (mediaRecorder != null) {
                mediaRecorder.reset();
            }
        } catch (Exception ignored) {
        }
        try {
            if (mediaRecorder != null) {
                mediaRecorder.release();
            }
        } catch (Exception ignored) {
        }
        mediaRecorder = null;

        try {
            if (mediaProjection != null) {
                mediaProjection.unregisterCallback(projectionCallback);
            }
        } catch (Exception ignored) {
        }
        try {
            if (mediaProjection != null && stopProjection) {
                mediaProjection.stop();
            }
        } catch (Exception ignored) {
        }
        mediaProjection = null;
    }

    private void startForegroundWithProjectionType(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /**
     * Updates the existing foreground notification without calling startForeground().
     * Safe to use after the MediaProjection has been stopped, because unlike
     * startForeground(FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) it does not re-check
     * the project_media app-op that Android revokes once the projection ends.
     */
    private void updateNotification(Notification notification) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String text, boolean includeStopAction) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                100,
                openIntent,
                pendingIntentFlags()
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Recorder")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_recorder)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPendingIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE);

        if (includeStopAction) {
            Intent stopIntent = new Intent(this, ScreenRecordingService.class);
            stopIntent.setAction(ACTION_STOP);
            stopIntent.putExtra(EXTRA_STOP_REASON, "Stopped by user");
            PendingIntent stopPendingIntent = PendingIntent.getService(
                    this,
                    101,
                    stopIntent,
                    pendingIntentFlags()
            );
            builder.addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent);
        }
        return builder.build();
    }

    private int pendingIntentFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Screen recording",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps screen recording alive while you use other apps.");
        manager.createNotificationChannel(channel);
    }

    private DisplayMetrics getRealDisplayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        Display display = getDefaultDisplayCompat();
        if (display != null) {
            display.getRealMetrics(metrics);
            return metrics;
        }
        return getResources().getDisplayMetrics();
    }

    private Display getDefaultDisplayCompat() {
        if (displayManager != null) {
            return displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        }
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        return windowManager == null ? null : windowManager.getDefaultDisplay();
    }

    private VideoConfig buildVideoConfig(DisplayMetrics metrics, Display display) {
        int width = even(metrics.widthPixels);
        int height = even(metrics.heightPixels);
        int densityDpi = metrics.densityDpi;
        int frameRate = 30;
        if (display != null) {
            frameRate = Math.max(24, Math.min(60, Math.round(display.getRefreshRate())));
        }
        String quality = RecordingController.getQualityPreset(this);
        double bitsPerPixel;
        if ("high".equals(quality)) {
            bitsPerPixel = 0.16d;
        } else if ("standard".equals(quality)) {
            bitsPerPixel = 0.10d;
        } else {
            bitsPerPixel = 0.13d;
        }
        int bitrate = (int) Math.max(4_000_000L,
                Math.min(20_000_000L, Math.round(width * height * frameRate * bitsPerPixel)));
        return new VideoConfig(width, height, densityDpi, frameRate, bitrate);
    }

    private int even(int value) {
        return value % 2 == 0 ? value : Math.max(2, value - 1);
    }

    private static final class VideoConfig {
        final int width;
        final int height;
        final int densityDpi;
        final int frameRate;
        final int bitrate;

        VideoConfig(int width, int height, int densityDpi, int frameRate, int bitrate) {
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.frameRate = frameRate;
            this.bitrate = bitrate;
        }
    }
}
