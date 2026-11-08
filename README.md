# Sparta Remixer 3000

A free and open source Android video editor for Sparta remixes.

- **Import** videos and audio from anywhere on your device
- **Free timeline** with three tracks, drag to move, handles to trim, pinch to zoom
- **BPM** typed in or tapped, with a beat grid and optional snapping
- **Pitch** per clip in semitones, sampler style (audio and video speed change together)
- **Invert** video colors per clip
- **Auto cut**: slice a clip on the BPM grid, or trim silence
- **Export** H.264/AAC MP4 at 480p, 720p or 1080p through Android `MediaCodec`

No network permission, no analytics, no ads. The app needs Android 8.1 or newer.

## Build

JDK 17 and the Android SDK are required.

```
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. GitHub Actions also builds it on every
push and runs the real import and export on an emulator with `connectedDebugAndroidTest`.

## Dependencies

The app itself only uses the Android framework and the Kotlin standard library (Apache-2.0).
JUnit and AndroidX Test are used by the tests and do not ship in the APK.

## License

GPL-3.0-only. See [LICENSE](LICENSE).
