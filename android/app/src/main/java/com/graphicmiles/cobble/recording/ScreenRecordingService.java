package com.graphicmiles.cobble.recording;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.graphicmiles.cobble.MainActivity;
import com.graphicmiles.cobble.R;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ScreenRecordingService extends Service {
    public static final String ACTION_START = "com.graphicmiles.cobble.action.START";
    public static final String ACTION_STOP = "com.graphicmiles.cobble.action.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_STOP_REASON = "stop_reason";

    private static final String TAG = "CobbleRecorder";
    private static final String CHANNEL_ID = "screen_recording";
    private static final int NOTIFICATION_ID = 4001;

    private final Object muxerLock = new Object();
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private ExecutorService executor;
    private StorageManager storageManager;
    private MediaProjectionManager mediaProjectionManager;
    private DisplayManager displayManager;
    private Handler mainHandler;
    private HandlerThread codecThread;
    private Handler codecHandler;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec videoEncoder;
    private Surface inputSurface;
    private MediaMuxer mediaMuxer;
    private File tempFile;

    private boolean muxerStarted = false;
    private int videoTrackIndex = -1;
    private CountDownLatch encoderFinishedLatch;
    private volatile boolean stopInProgress = false;
    private int captureRotation = 0;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            if (!stopInProgress) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        stopRecording("Screen-capture permission was revoked by Android.");
                    }
                });
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
            if (newRotation != captureRotation && !stopInProgress
                    && RecordingController.getCurrentState() == RecordingController.State.RECORDING) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        stopRecording("Recording stopped after device rotation to keep the MP4 orientation correct.");
                    }
                });
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

        final String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            final Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    startRecording(resultCode, resultData);
                }
            });
        } else if (ACTION_STOP.equals(action)) {
            final String reason = intent.getStringExtra(EXTRA_STOP_REASON);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    stopRecording(reason == null ? "Stopping recording." : reason);
                }
            });
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

    private void startRecording(int resultCode, Intent resultData) {
        if (RecordingController.getCurrentState() == RecordingController.State.RECORDING
                || RecordingController.getCurrentState() == RecordingController.State.STARTING) {
            return;
        }

        RecordingController.updateState(this, RecordingController.State.STARTING, "Preparing recorder");
        startForeground(NOTIFICATION_ID, buildNotification("Preparing recorder", true));

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
            mediaMuxer = new MediaMuxer(tempFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            codecThread = new HandlerThread("CobbleEncoderCallbacks");
            codecThread.start();
            codecHandler = new Handler(codecThread.getLooper());

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                    config.width,
                    config.height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoderFinishedLatch = new CountDownLatch(1);
            videoEncoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec codec, int index) {
                    // Surface input: nothing to do here.
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                    ByteBuffer encodedData = codec.getOutputBuffer(index);
                    if (encodedData == null) {
                        codec.releaseOutputBuffer(index, false);
                        return;
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0) {
                        synchronized (muxerLock) {
                            if (muxerStarted && mediaMuxer != null) {
                                encodedData.position(info.offset);
                                encodedData.limit(info.offset + info.size);
                                mediaMuxer.writeSampleData(videoTrackIndex, encodedData, info);
                            }
                        }
                    }
                    boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(index, false);
                    if (endOfStream && encoderFinishedLatch != null) {
                        encoderFinishedLatch.countDown();
                    }
                }

                @Override
                public void onError(MediaCodec codec, MediaCodec.CodecException error) {
                    handleFailure("Video encoder failed.", error);
                }

                @Override
                public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                    synchronized (muxerLock) {
                        if (muxerStarted) {
                            return;
                        }
                        videoTrackIndex = mediaMuxer.addTrack(format);
                        mediaMuxer.start();
                        muxerStarted = true;
                    }
                }
            }, codecHandler);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = videoEncoder.createInputSurface();
            videoEncoder.start();

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) {
                throw new IOException("Could not acquire a MediaProjection instance.");
            }
            mediaProjection.registerCallback(projectionCallback, mainHandler);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "CobbleRecorder",
                    config.width,
                    config.height,
                    config.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface,
                    null,
                    null
            );
            if (virtualDisplay == null) {
                throw new IOException("Could not create a virtual display.");
            }

            if (displayManager != null) {
                displayManager.registerDisplayListener(displayListener, mainHandler);
            }

            stopInProgress = false;
            RecordingController.reportStarted(this);
            startForeground(NOTIFICATION_ID, buildNotification("Recording in progress", true));
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

        try {
            if (videoEncoder != null) {
                videoEncoder.signalEndOfInputStream();
            }
            if (encoderFinishedLatch != null) {
                encoderFinishedLatch.await(5, TimeUnit.SECONDS);
            }
        } catch (Exception error) {
            Log.w(TAG, "Timed out waiting for encoder shutdown", error);
        }

        releaseResources(true);
        RecordingController.updateState(this, RecordingController.State.SAVING, "Saving video");
        startForeground(NOTIFICATION_ID, buildNotification("Saving video", true));

        try {
            Map<String, Object> saved = storageManager.finalizeRecording(tempFile);
            String uriString = saved.get("uri") == null ? null : saved.get("uri").toString();
            String displayName = saved.get("displayName") == null ? "Recording" : saved.get("displayName").toString();
            RecordingController.reportSaved(this, uriString == null ? null : android.net.Uri.parse(uriString), displayName);
        } catch (Exception error) {
            handleSaveFailure(error);
            return;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void handleSaveFailure(Exception error) {
        storageManager.cleanupFailedRecording(tempFile);
        RecordingController.reportError(this, "Recording finished but could not be saved: " + error.getMessage());
        RecordingController.moveToIdle();
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
            if (inputSurface != null) {
                inputSurface.release();
            }
        } catch (Exception ignored) {
        }
        inputSurface = null;

        try {
            if (videoEncoder != null) {
                videoEncoder.stop();
            }
        } catch (Exception ignored) {
        }
        try {
            if (videoEncoder != null) {
                videoEncoder.release();
            }
        } catch (Exception ignored) {
        }
        videoEncoder = null;

        synchronized (muxerLock) {
            try {
                if (mediaMuxer != null && muxerStarted) {
                    mediaMuxer.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                if (mediaMuxer != null) {
                    mediaMuxer.release();
                }
            } catch (Exception ignored) {
            }
            mediaMuxer = null;
            muxerStarted = false;
            videoTrackIndex = -1;
        }

        if (codecThread != null) {
            codecThread.quitSafely();
            codecThread = null;
            codecHandler = null;
        }

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
                .setContentTitle("Cobble Recorder")
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
            builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Stop",
                    stopPendingIntent
            );
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
        channel.setDescription("Keeps Cobble recordings alive while you use other apps.");
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
