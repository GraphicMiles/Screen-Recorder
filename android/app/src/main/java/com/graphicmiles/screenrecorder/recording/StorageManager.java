package com.graphicmiles.screenrecorder.recording;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StorageManager {
    public static final String SAVE_MODE_GALLERY = "gallery";
    public static final String SAVE_MODE_CUSTOM = "custom";

    private static final String PREFS_NAME = RecordingController.PREFS_NAME;
    private static final String KEY_SAVE_MODE = "save_mode";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String DEFAULT_FOLDER = Environment.DIRECTORY_MOVIES + "/Screen Recorder";

    private final Context context;

    public StorageManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public File createTempRecordingFile() throws IOException {
        File parent = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (parent == null) {
            parent = new File(context.getFilesDir(), "recordings");
        }
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not prepare temporary recording storage.");
        }
        cleanupStaleTempFiles(parent);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(parent, "pending_" + timestamp + ".mp4");
    }

    public Map<String, Object> finalizeRecording(File tempFile) throws IOException {
        if (tempFile == null || !tempFile.exists() || tempFile.length() <= 0) {
            throw new IOException("Temporary recording file is missing or empty.");
        }

        RecordingController.debug(context, "Finalizing recording. tempFile=" + tempFile.getAbsolutePath() + ", bytes=" + tempFile.length());
        String displayName = buildDisplayName();
        if (SAVE_MODE_CUSTOM.equals(getSaveMode(context))) {
            Uri treeUri = getTreeUri(context);
            if (treeUri != null) {
                RecordingController.debug(context, "Saving recording to SAF location");
                return saveToSaf(tempFile, treeUri, displayName);
            }
        }
        RecordingController.debug(context, "Saving recording to MediaStore gallery location");
        return saveToMediaStore(tempFile, displayName);
    }

    public void cleanupFailedRecording(File tempFile) {
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }

    public String getSaveModeLabel() {
        return SAVE_MODE_CUSTOM.equals(getSaveMode(context)) ? "Custom location" : "Device / Gallery";
    }

    public String getCustomLocationDescription() {
        Uri treeUri = getTreeUri(context);
        if (treeUri == null) {
            return "Not selected";
        }
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree != null && tree.getName() != null) {
            return tree.getName();
        }
        return treeUri.toString();
    }

    public List<Map<String, Object>> listSavedRecordings() {
        List<Map<String, Object>> results = new ArrayList<>();
        results.addAll(listMediaStoreRecordings());
        results.addAll(listSafRecordings());
        Collections.sort(results, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                long leftTime = numberValue(left.get("savedAtMs"));
                long rightTime = numberValue(right.get("savedAtMs"));
                return Long.compare(rightTime, leftTime);
            }
        });
        if (results.size() > 20) {
            return new ArrayList<>(results.subList(0, 20));
        }
        return results;
    }

    public static void persistTreeUri(Context context, Uri treeUri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TREE_URI, treeUri == null ? null : treeUri.toString())
                .apply();
    }

    public static Uri getTreeUri(Context context) {
        String value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TREE_URI, null);
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    public static String getSaveMode(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SAVE_MODE, SAVE_MODE_GALLERY);
    }

    public static void setSaveMode(Context context, String saveMode) {
        String normalized = SAVE_MODE_CUSTOM.equalsIgnoreCase(saveMode)
                ? SAVE_MODE_CUSTOM
                : SAVE_MODE_GALLERY;
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (SAVE_MODE_CUSTOM.equals(normalized) && getTreeUri(context) == null) {
            normalized = SAVE_MODE_GALLERY;
        }
        preferences.edit().putString(KEY_SAVE_MODE, normalized).apply();
    }

    private Map<String, Object> saveToMediaStore(File tempFile, String displayName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, DEFAULT_FOLDER);
        values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = resolver.insert(collection, values);
        if (itemUri == null) {
            throw new IOException("Could not create a MediaStore destination.");
        }

        try {
            copyFileToUri(tempFile, itemUri);
            ContentValues finalizeValues = new ContentValues();
            finalizeValues.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(itemUri, finalizeValues, null, null);
            Map<String, Object> payload = new HashMap<>();
            payload.put("uri", itemUri.toString());
            payload.put("displayName", displayName);
            payload.put("locationLabel", "Gallery");
            payload.put("savedAtMs", System.currentTimeMillis());
            payload.put("sizeBytes", tempFile.length());
            cleanupFailedRecording(tempFile);
            RecordingController.debug(context, "MediaStore save complete: " + itemUri);
            return payload;
        } catch (IOException error) {
            resolver.delete(itemUri, null, null);
            throw error;
        }
    }

    private Map<String, Object> saveToSaf(File tempFile, Uri treeUri, String displayName) throws IOException {
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.canWrite()) {
            throw new IOException("The chosen folder is no longer writable.");
        }
        DocumentFile file = tree.createFile("video/mp4", displayName);
        if (file == null || file.getUri() == null) {
            throw new IOException("Could not create the custom destination file.");
        }

        copyFileToUri(tempFile, file.getUri());
        Map<String, Object> payload = new HashMap<>();
        payload.put("uri", file.getUri().toString());
        payload.put("displayName", displayName);
        payload.put("locationLabel", "Custom");
        payload.put("savedAtMs", System.currentTimeMillis());
        payload.put("sizeBytes", tempFile.length());
        cleanupFailedRecording(tempFile);
        RecordingController.debug(context, "SAF save complete: " + file.getUri());
        return payload;
    }

    private List<Map<String, Object>> listMediaStoreRecordings() {
        List<Map<String, Object>> items = new ArrayList<>();
        String[] projection = new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH
        };

        Cursor cursor = context.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                new String[]{DEFAULT_FOLDER + "%"},
                MediaStore.Video.Media.DATE_ADDED + " DESC"
        );

        if (cursor == null) {
            return items;
        }

        try {
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                Uri uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                Map<String, Object> row = new HashMap<>();
                row.put("uri", uri.toString());
                row.put("displayName", cursor.getString(nameColumn));
                row.put("savedAtMs", cursor.getLong(dateColumn) * 1000L);
                row.put("sizeBytes", cursor.getLong(sizeColumn));
                row.put("locationLabel", "Gallery");
                items.add(row);
            }
        } finally {
            cursor.close();
        }
        return items;
    }

    private List<Map<String, Object>> listSafRecordings() {
        List<Map<String, Object>> items = new ArrayList<>();
        Uri treeUri = getTreeUri(context);
        if (treeUri == null) {
            return items;
        }

        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.exists() || !tree.isDirectory()) {
            return items;
        }

        for (DocumentFile file : tree.listFiles()) {
            if (!file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (name == null || !name.toLowerCase(Locale.US).endsWith(".mp4")) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("uri", file.getUri().toString());
            row.put("displayName", name);
            row.put("savedAtMs", file.lastModified());
            row.put("sizeBytes", file.length());
            row.put("locationLabel", "Custom");
            items.add(row);
        }
        return items;
    }

    private void copyFileToUri(File source, Uri destinationUri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream inputStream = new FileInputStream(source);
             OutputStream outputStream = resolver.openOutputStream(destinationUri, "w")) {
            if (outputStream == null) {
                throw new IOException("Could not open the destination file.");
            }
            byte[] buffer = new byte[32 * 1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        }
    }

    private String buildDisplayName() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date());
        return "ScreenRecording_" + timestamp + ".mp4";
    }

    private void cleanupStaleTempFiles(File parent) {
        File[] files = parent.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - (24L * 60L * 60L * 1000L);
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("pending_") && file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    private long numberValue(Object raw) {
        return raw instanceof Number ? ((Number) raw).longValue() : 0L;
    }
}
