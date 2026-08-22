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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

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
            return;
        }

        RecordingController.updateState(this, RecordingController.State.STARTING, "Preparing recorder");
        startForegroundWithProjectionType(buildNotification("Preparing recorder", true));

        try {
            if (mediaProjectionManager == null || resultData == null || resultCode != Activity.RESULT_OK) {
                throw new IOException("Missing MediaProjection consent result.");
            }

            DisplayMetrics metrics = getRealDisplayMetrics();
            Display display = getDefaultDisplayCompat();
            if (display != null) {
                captureRotation = display.getRotation();
            }
            VideoConfig config = buildVideoConfig(metrics, display);

            tempFile = storageManager.createTempRecordingFile();
            mediaRecorder = createMediaRecorder();
            configureRecorder(mediaRecorder, config, tempFile);
            recorderSurface = mediaRecorder.getSurface();

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) {
                throw new IOException("Could not acquire a MediaProjection instance.");
            }
            mediaProjection.registerCallback(projectionCallback, mainHandler);

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

            if (displayManager != null) {
                displayManager.registerDisplayListener(displayListener, mainHandler);
            }

            mediaRecorder.start();
            recorderStarted = true;
            stopInProgress = false;
            RecordingController.reportStarted(this);
            RecorderTileService.requestTileRefresh(this);
            startForegroundWithProjectionType(buildNotification("Recording in progress", true));
        } catch (Exception error) {
            handleFailure("Could not start recording.", error);
        }
    }

    private void stopRecording(String reason) {
        if (stopInProgress) {
            return;
        }
        stopInProgress = true;

        RecordingController.reportStopped(this, reason);
        RecordingController.updateState(this, RecordingController.State.STOPPING, reason);

        Exception stopError = null;
        try {
            if (mediaRecorder != null && recorderStarted) {
                mediaRecorder.stop();
            }
        } catch (Exception error) {
            stopError = error;
        }
        recorderStarted = false;

        releaseResources(true);

        if (stopError != null) {
            handleFailure("Recording could not be finalized.", stopError);
            return;
        }

        RecordingController.updateState(this, RecordingController.State.SAVING, "Saving video");
        startForegroundWithProjectionType(buildNotification("Saving video", true));

        try {
            Map<String, Object> saved = storageManager.finalizeRecording(tempFile);
            String uriString = saved.get("uri") == null ? null : saved.get("uri").toString();
            String displayName = saved.get("displayName") == null ? "Recording" : saved.get("displayName").toString();
            RecordingController.reportSaved(this,
                    uriString == null ? null : Uri.parse(uriString),
                    displayName);
            RecorderTileService.requestTileRefresh(this);
        } catch (Exception error) {
            handleSaveFailure(error);
            return;
        }

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
