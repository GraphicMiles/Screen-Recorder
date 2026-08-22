# Cobble

Cobble is a lightweight Android screen recorder with:

- a very small Flutter UI for status + settings
- a native Java recording pipeline
- MediaProjection + MediaCodec + MediaMuxer MP4 recording
- MediaStore saving by default
- optional SAF custom save location
- a foreground service for reliable background recording
- GitHub Actions APK builds

## Important Android limitation

A truly invisible, system-wide **triple-tap anywhere on the screen** trigger is **not available under normal Android app permissions**.

Android does not expose raw global tap events to ordinary apps without one of the following:

- a visible or transparent overlay that intercepts touches
- root/system privileges
- accessibility-style workarounds that still do not provide reliable raw global triple-tap detection

Because this project must respect Android's security model and must not place a permanent overlay into the captured video, this implementation does **not** fake or bypass that restriction.

### What this build does instead

- **Local triple-tap** works while Cobble is open.
- **Quick Settings tile** is the closest reliable global start/stop control.
- **Foreground notification stop action** provides a dependable stop fallback while recording.

The actual recording engine is kept independent from the gesture mechanism so another trigger can be added later if Android ever allows a clean system-level option.

## Android 14+ note

Android 14 tightened MediaProjection behavior. This build requests screen-capture consent for each recording session instead of trying to silently reuse stale tokens.

## Architecture

```text
Flutter / Dart
  ├── Minimal status UI
  ├── Settings UI
  ├── Local triple-tap detector
  └── MethodChannel / EventChannel
            │
            ▼
Java Native Layer
  ├── RecordingController
  ├── ScreenRecordingService
  │     ├── MediaProjection
  │     ├── VirtualDisplay
  │     ├── MediaCodec (H.264)
  │     └── MediaMuxer (MP4)
  ├── TripleTapController
  ├── RecorderTileService
  └── StorageManager
        ├── MediaStore
        └── SAF
```

## What is implemented

- MP4 + H.264 recording
- dynamic display sizing and refresh-rate-aware target FPS
- automatic / high / standard quality presets
- MediaStore saving into:
  - `Movies/Screen Recorder/`
- optional SAF custom folder saving
- native resource cleanup on stop/failure
- recent recordings list
- Quick Settings tile fallback for global control
- GitHub Actions APK artifact build

## Known trade-offs

- No floating overlay is used.
- No watermark is added.
- No cloud processing is used.
- Audio is intentionally left off in this first cut to keep the implementation small and reliable.
- If the device rotates during recording, the service stops gracefully to preserve a valid MP4 and correct proportions.

## Build locally

```bash
flutter pub get
flutter build apk --debug
```

## GitHub Actions

The workflow in `.github/workflows/android-apk.yml` builds a debug APK and uploads it as an artifact on every push to `main`.
