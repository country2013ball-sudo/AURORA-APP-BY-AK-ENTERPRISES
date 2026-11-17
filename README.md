# AURORA-APP-BY-AK-ENTERPRISES

Aurora Engine v2 Android app source is in [`AuroraEngine/`](./AuroraEngine/).

Build the optimized, debug-signed release APK and the debug APK from the project directory:

```sh
cd AuroraEngine
gradle assembleRelease assembleDebug
```

Use Gradle 8.5, JDK 17, and Android SDK Platform 34. The release APK is at
`AuroraEngine/app/build/outputs/apk/release/app-release.apk`; it is R8-optimized
and debug-signed for direct testing, not Play Store publishing. The debug APK is
at `AuroraEngine/app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds both APKs and uploads them on pushes to `main` or `master`.
