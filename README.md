# Screen Recorder

This repository now builds a lightweight native Android screen recorder focused on stability and small APK size.

## Included

- Native Android UI
- MediaProjection + VirtualDisplay + MediaCodec + MediaMuxer
- MP4 + H.264 recording
- MediaStore saving by default
- Optional SAF custom save folder
- Foreground service for continued recording
- Quick Settings tile fallback for reliable global control

## Important Android limitation

A truly invisible system-wide triple-tap detector is not available to ordinary Android apps without overlays or elevated privileges.

So this build uses:

- local triple-tap while the app screen is open
- Quick Settings tile for reliable global control
- foreground notification stop action while recording

## Output location

Default saved recordings go to:

- `Movies/Screen Recorder/`

## Build locally

```bash
./gradlew :app:assembleRelease
```

## GitHub Actions

The workflow builds a single installable release APK and uploads it as `screen-recorder-release-apk`.
