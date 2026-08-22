package com.graphicmiles.cobble.recording;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.graphicmiles.cobble.MainActivity;

public class RecorderTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        RecordingController.State state = RecordingController.getCurrentState();
        if (state == RecordingController.State.RECORDING
                || state == RecordingController.State.STARTING
                || state == RecordingController.State.STOPPING
                || state == RecordingController.State.SAVING) {
            RecordingController.stopService(this, "Stopped from Quick Settings");
        } else {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setAction(MainActivity.ACTION_START_RECORDING_FLOW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        this,
                        200,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                startActivityAndCollapse(pendingIntent);
            } else {
                //noinspection deprecation
                startActivityAndCollapse(intent);
            }
        }
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        RecordingController.State state = RecordingController.getCurrentState();
        boolean active = state == RecordingController.State.RECORDING
                || state == RecordingController.State.STARTING
                || state == RecordingController.State.STOPPING
                || state == RecordingController.State.SAVING;
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(active ? "Stop recording" : "Start recording");
        tile.updateTile();
    }
}
