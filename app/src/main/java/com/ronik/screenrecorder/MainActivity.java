package com.ronik.screenrecorder;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.ronik.screenrecorder.recording.RecorderTileService;
import com.ronik.screenrecorder.recording.RecordingController;
import com.ronik.screenrecorder.recording.StorageManager;
import com.ronik.screenrecorder.recording.TripleTapController;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    public static final String ACTION_START_RECORDING_FLOW =
            "com.ronik.screenrecorder.action.START_RECORDING_FLOW";

    private static final int REQUEST_SCREEN_CAPTURE = 701;
    private static final int REQUEST_POST_NOTIFICATIONS = 703;

    private final TripleTapController tripleTapController = new TripleTapController();
    private final List<Map<String, Object>> recordings = new ArrayList<>();

    private MediaProjectionManager mediaProjectionManager;
    private TextView statusText;
    private TextView statusMessageText;
    private TextView errorText;
    private TextView settingsSummaryText;
    private Button toggleButton;
    private ListView recordingsListView;

    private boolean startAfterNotificationPermission = false;
    private boolean moveTaskToBackAfterStart = false;
    private boolean autoStartIntentPending = false;

    private final BroadcastReceiver recordingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(RecordingController.EXTRA_MESSAGE);
            refreshUi();
            RecorderTileService.requestTileRefresh(context);
            if (message != null && !message.isEmpty()) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mediaProjectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        statusText = findViewById(R.id.statusText);
        statusMessageText = findViewById(R.id.statusMessageText);
        errorText = findViewById(R.id.errorText);
        settingsSummaryText = findViewById(R.id.settingsSummaryText);
        toggleButton = findViewById(R.id.toggleButton);
        Button settingsButton = findViewById(R.id.settingsButton);
        recordingsListView = findViewById(R.id.recordingsListView);
        TextView emptyView = findViewById(android.R.id.empty);
        recordingsListView.setEmptyView(emptyView);

        toggleButton.setOnClickListener(view -> toggleRecording(false));
        settingsButton.setOnClickListener(view ->
                startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        recordingsListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (position >= 0 && position < recordings.size()) {
                    openRecording(recordings.get(position));
                }
            }
        });

        handleLaunchIntent(getIntent());
        refreshUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(RecordingController.ACTION_RECORDING_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(recordingReceiver, filter);
        }
        refreshUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(recordingReceiver);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        refreshUi();
        if (autoStartIntentPending) {
            autoStartIntentPending = false;
            requestScreenCapture(true);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (tripleTapController.registerTap(System.currentTimeMillis())) {
                toggleRecording(false);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS && startAfterNotificationPermission) {
            startAfterNotificationPermission = false;
            launchScreenCaptureIntent();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                RecordingController.startService(this, resultCode, data);
                Toast.makeText(this, "Recording started.", Toast.LENGTH_SHORT).show();
                if (moveTaskToBackAfterStart) {
                    moveTaskToBack(true);
                }
            } else {
                Toast.makeText(this, "Screen-capture permission was denied.", Toast.LENGTH_SHORT).show();
            }
            moveTaskToBackAfterStart = false;
            refreshUi();
        }
    }

    private void handleLaunchIntent(Intent intent) {
        autoStartIntentPending = intent != null
                && ACTION_START_RECORDING_FLOW.equals(intent.getAction())
                && RecordingController.getCurrentState() == RecordingController.State.IDLE;
    }

    private void toggleRecording(boolean backgroundAfterStart) {
        RecordingController.State state = RecordingController.getCurrentState();
        boolean active = state == RecordingController.State.RECORDING
                || state == RecordingController.State.STARTING
                || state == RecordingController.State.STOPPING
                || state == RecordingController.State.SAVING;
        if (active) {
            RecordingController.stopService(this, "Stopped by user");
            refreshUi();
        } else {
            requestScreenCapture(backgroundAfterStart);
        }
    }

    private void requestScreenCapture(boolean backgroundAfterStart) {
        moveTaskToBackAfterStart = backgroundAfterStart;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            startAfterNotificationPermission = true;
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
            Toast.makeText(this, "Screen capture is not available on this device.", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
    }

    private void refreshUi() {
        RecordingController.State state = RecordingController.getCurrentState();
        boolean active = state == RecordingController.State.RECORDING
                || state == RecordingController.State.STARTING
                || state == RecordingController.State.STOPPING
                || state == RecordingController.State.SAVING;

        statusText.setText(String.format(Locale.US, "● %s", RecordingController.getStateLabel(state)));
        statusText.setTextColor(ContextCompat.getColor(
                this,
                active ? R.color.status_recording : R.color.status_ready
        ));

        statusMessageText.setText(active
                ? "Local triple-tap here, Quick Settings, or the recording notification can stop the recording."
                : "Triple-tap here to start while this screen is open. Quick Settings remains the reliable global fallback.");

        String lastError = RecordingController.getLastError();
        if (lastError != null && !lastError.isEmpty()) {
            errorText.setText(lastError);
            errorText.setVisibility(android.view.View.VISIBLE);
        } else {
            errorText.setVisibility(android.view.View.GONE);
        }

        toggleButton.setText(active ? "Stop recording" : "Start recording");
        toggleButton.setEnabled(state != RecordingController.State.STARTING
                && state != RecordingController.State.STOPPING
                && state != RecordingController.State.SAVING);

        StorageManager storageManager = new StorageManager(this);
        settingsSummaryText.setText(String.format(
                Locale.US,
                "Quality: %s\nSave location: %s\nCustom folder: %s",
                RecordingController.getQualityLabel(this),
                storageManager.getSaveModeLabel(),
                storageManager.getCustomLocationDescription()
        ));

        recordings.clear();
        recordings.addAll(storageManager.listSavedRecordings());

        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, Object> item : recordings) {
            Map<String, String> row = new HashMap<>();
            row.put("line1", String.valueOf(item.get("displayName")));
            row.put(
                    "line2",
                    String.format(
                            Locale.US,
                            "%s • %s • %s",
                            String.valueOf(item.get("locationLabel")),
                            formatTimestamp(item.get("savedAtMs")),
                            formatBytes(item.get("sizeBytes"))
                    )
            );
            rows.add(row);
        }

        recordingsListView.setAdapter(new SimpleAdapter(
                this,
                rows,
                R.layout.recording_list_item,
                new String[]{"line1", "line2"},
                new int[]{R.id.text1, R.id.text2}
        ));
    }

    private void openRecording(Map<String, Object> item) {
        Object rawUri = item.get("uri");
        if (rawUri == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(rawUri.toString()), "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "No video player is available for MP4 playback.", Toast.LENGTH_SHORT).show();
        }
    }

    private String formatTimestamp(Object raw) {
        if (!(raw instanceof Number)) {
            return "Unknown time";
        }
        Date date = new Date(((Number) raw).longValue());
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(date);
    }

    private String formatBytes(Object raw) {
        if (!(raw instanceof Number)) {
            return "size unknown";
        }
        double value = ((Number) raw).doubleValue();
        String[] units = new String[]{"B", "KB", "MB", "GB"};
        int index = 0;
        while (value >= 1024d && index < units.length - 1) {
            value /= 1024d;
            index += 1;
        }
        return String.format(Locale.US, value >= 100 || index == 0 ? "%.0f %s" : "%.1f %s", value, units[index]);
    }
}
