# Cobble

Cobble is a lightweight Android screen recorder focused on stability and small size.

## What this build includes

- Minimal native Android UI
- Native Java recording engine
- MediaProjection + VirtualDisplay + MediaCodec + MediaMuxer
- MP4 + H.264 output
- MediaStore saving by default
- Optional SAF custom folder saving
- Foreground service for background-safe recording
- Quick Settings tile fallback for reliable global control

## Why this version is native-first

The earlier Flutter-based packaging made the APK much larger and introduced avoidable complexity for a utility app. To keep the app lightweight and reduce crash risk, this repo now builds a small native Android implementation while preserving the important recording features.

## Important Android limitation

A truly invisible system-wide triple-tap detector is not available to ordinary Android apps without overlays or elevated privileges.

So this build uses:

- local triple-tap while the Cobble screen is open
- Quick Settings tile for reliable global start/stop fallback
- notification stop action during recording

## Storage

Default save location:

- `Movies/Screen Recorder/`

Optional:

- a user-selected SAF folder for future recordings

## Build locally

```bash
cd android
./gradlew :app:assembleRelease
```

## GitHub Actions

The workflow builds a single installable release APK and uploads it as `cobble-release-apk`.
