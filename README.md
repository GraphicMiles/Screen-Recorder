# Screen Recorder — OPTIC

A lightweight Android screen recorder with the **Optic** design system:
a viewfinder instrument — tick dial with a one-revolution-per-minute sweep,
camera shutter control, corner metadata, hairline rules on near-black.

- **Flutter / Dart UI** (Optic system: flat, hairline, tabular numerals)
- **Java native recording engine** (MediaProjection + VirtualDisplay + MediaRecorder MP4)
- Safe-area insets of at least 3rem (48dp) from top and bottom device edges
- MediaStore default saving to `Movies/Screen Recorder/`
- Optional SAF custom folder saving
- Foreground service (`mediaProjection` type) for background-safe recording
- Quick Settings tile + notification stop action for reliable global control
- Persistent debug log (Setup → Debug log)

## Design rules

- Flat: no shadows, no gradients, no glass.
- Hairlines divide; whitespace groups.
- One dominant tabular numeral per screen.
- Red = recording only. Amber = caution only.
- Logo: the monogramic viewfinder — two hands framing a negative-space screen.

## Size budget (< 30 MB)

CI builds with `--split-per-abi --tree-shake-icons`; the `arm64-v8a` APK is the
install target and stays well under 30 MB. No image assets: the logo, dial and
icons are vectors / custom paint.

## Build locally

```bash
flutter pub get
flutter build apk --release --split-per-abi --tree-shake-icons
```

## GitHub Actions

The workflow uploads `optic-release-apks` (one APK per ABI) and prints sizes.
