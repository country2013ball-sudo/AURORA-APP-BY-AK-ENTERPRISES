# AURORA-APP-BY-AK-ENTERPRISES

Aurora Engine Android app source is in [`AuroraEngine/`](./AuroraEngine/).

Build the debug APK from the project directory:

```sh
cd AuroraEngine
gradle assembleDebug
```

Use Gradle 8.5, JDK 17, and Android SDK Platform 34.
The APK is written to `AuroraEngine/app/build/outputs/apk/debug/app-debug.apk`.
GitHub Actions also builds and uploads the debug APK on pushes to `main` or
`master`.
