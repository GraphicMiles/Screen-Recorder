# Screen Recorder

A lightweight Android screen recorder combining the best of two lineages:

- **Flutter / Dart UI** (from the original Screen-Recorder app)
- **Java native recording engine** (MediaProjection + VirtualDisplay + MediaRecorder MP4)
- Storage hardening and lifecycle fixes merged in from **Cobble**

## Features

- Flutter / Dart UI with status card, settings, saved-recordings list and debug panel
- Java native recording engine (MediaRecorder-based MP4 output for reliability)
- MediaProjection capture with per-session Android consent
- MediaStore default saving to `Movies/Screen Recorder/`
- Optional SAF custom folder saving
- Foreground service (`mediaProjection` type) for background-safe recording
- Quick Settings tile fallback for reliable global control
- Notification stop action while recording
- Local triple-tap to start while the app screen is open

## Important Android limitation

A truly invisible system-wide triple-tap detector is not available to ordinary Android apps without overlays or elevated privileges.

So this build uses:

- local triple-tap while the app screen is open
- Quick Settings tile for reliable global control
- foreground notification stop action while recording

## Merge notes (Cobble → Screen-Recorder)

- Kept the Flutter/Dart UI + Java engine architecture (the "dart and java" goal).
- Ported Cobble's storage hardening: exact `RELATIVE_PATH` listing query, trailing-slash
  default folder, version-guarded `IS_PENDING` MediaStore handling.
- Fixed a crash present in both lineages: `stopRecording()` used to call
  `startForeground(FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` *after* `MediaProjection.stop()`.
  On Android 14+ stopping the projection revokes the `project_media` app-op, so the
  `startForeground` call threw `SecurityException` and the finished recording was deleted
  instead of saved. The service now updates the "Saving" notification via
  `NotificationManager.notify()` and releases the projection only after the file is saved.

## Build locally

```bash
flutter pub get
flutter build apk --release --target-platform android-arm,android-arm64 --tree-shake-icons
```

## GitHub Actions

The workflow builds a single release APK artifact named `screen-recorder-release-apk`.
