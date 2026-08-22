# Keep Flutter runtime integration safe while still allowing normal shrinking.
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }

# Keep manifest-referenced Android components.
-keep class com.graphicmiles.cobble.MainActivity { *; }
-keep class com.graphicmiles.cobble.recording.ScreenRecordingService { *; }
-keep class com.graphicmiles.cobble.recording.RecorderTileService { *; }
