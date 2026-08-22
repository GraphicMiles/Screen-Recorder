# Screen Recorder

A lightweight Android screen recorder with:

- Flutter / Dart UI
- Java native recording engine
- MediaProjection capture
- MediaRecorder-based MP4 recording for reliability
- MediaStore default saving
- optional SAF custom folder saving
- foreground service background recording
- Quick Settings tile fallback for global control

## Important Android limitation

A truly invisible system-wide triple-tap detector is not available to ordinary Android apps without overlays or elevated privileges.

So this build uses:

- local triple-tap while the app screen is open
- Quick Settings tile for reliable global control
- foreground notification stop action while recording

## Build locally

```bash
flutter pub get
flutter build apk --release --target-platform android-arm,android-arm64 --tree-shake-icons
```

## GitHub Actions

The workflow builds a single release APK artifact named `screen-recorder-release-apk`.
