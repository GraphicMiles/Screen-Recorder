package com.graphicmiles.cobble;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.graphicmiles.cobble.recording.RecordingController;
import com.graphicmiles.cobble.recording.StorageManager;

public class SettingsActivity extends AppCompatActivity {
    private static final int REQUEST_SAVE_LOCATION = 702;

    private RadioGroup qualityGroup;
    private RadioGroup saveModeGroup;
    private TextView customFolderText;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Settings");
        }

        qualityGroup = findViewById(R.id.qualityGroup);
        saveModeGroup = findViewById(R.id.saveModeGroup);
        customFolderText = findViewById(R.id.customFolderText);
        Button chooseFolderButton = findViewById(R.id.chooseFolderButton);

        chooseFolderButton.setOnClickListener(view -> chooseFolder());

        qualityGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.qualityHigh) {
                RecordingController.setQualityPreset(this, "high");
            } else if (checkedId == R.id.qualityStandard) {
                RecordingController.setQualityPreset(this, "standard");
            } else {
                RecordingController.setQualityPreset(this, "automatic");
            }
        });

        saveModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.saveModeCustom) {
                if (StorageManager.getTreeUri(this) == null) {
                    chooseFolder();
                } else {
                    StorageManager.setSaveMode(this, StorageManager.SAVE_MODE_CUSTOM);
                    updateUi();
                }
            } else {
                StorageManager.setSaveMode(this, StorageManager.SAVE_MODE_GALLERY);
                updateUi();
            }
        });

        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SAVE_LOCATION && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri treeUri = data.getData();
            int flags = data.getFlags() &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(treeUri, flags);
            } catch (SecurityException ignored) {
            }
            StorageManager.persistTreeUri(this, treeUri);
            StorageManager.setSaveMode(this, StorageManager.SAVE_MODE_CUSTOM);
            updateUi();
        }
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_SAVE_LOCATION);
    }

    private void updateUi() {
        String quality = RecordingController.getQualityPreset(this);
        if ("high".equals(quality)) {
            qualityGroup.check(R.id.qualityHigh);
        } else if ("standard".equals(quality)) {
            qualityGroup.check(R.id.qualityStandard);
        } else {
            qualityGroup.check(R.id.qualityAutomatic);
        }

        if (StorageManager.SAVE_MODE_CUSTOM.equals(StorageManager.getSaveMode(this))) {
            saveModeGroup.check(R.id.saveModeCustom);
        } else {
            saveModeGroup.check(R.id.saveModeGallery);
        }

        customFolderText.setText("Custom folder: " + new StorageManager(this).getCustomLocationDescription());
    }
}
