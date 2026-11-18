# AURORA ENGINE — Termux Build & Installation Guide

## Desktop / GitHub Codespaces Build

Use JDK 17, Gradle 8.5, and Android SDK Platform 34. From this directory run:

```sh
gradle assembleRelease assembleDebug
```

The optimized, debug-signed release APK is `app/build/outputs/apk/release/app-release.apk`.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The release APK is for
direct testing and is not signed for Play Store publishing.

- **Application name:** AURORA ENGINE
- **Developer:** AK by AK Enterprises
- **Package ID:** `com.akenterprises.auroraengine`
- **Primary gaming target:** Roblox (`com.roblox.client`)
- **Reference hardware profile:** Samsung Galaxy A07 5G (4 GB RAM, ARM Mali-G57 GPU, Android)

## What the App Contains

Aurora Engine is an Android companion app for configuring supported device settings and monitoring a Roblox gaming session. It does not modify Roblox files or inject code into the game.

- **Performance controls:** Power-saving, Balanced, and Pro Gamer profiles, plus supported refresh-rate and touch-response selections.
- **Shizuku integration:** User-authorized privileged commands for supported system changes and Roblox session optimization.
- **Safe memory tools:** Displays memory telemetry and uses the existing guarded cleanup path rather than indiscriminately killing apps.
- **Live telemetry:** Battery and network estimates in the edge panel, plus Roblox frame statistics there when Shizuku is authorized and Roblox is running. FPS is not shown in the main app and is not fabricated when unavailable.
- **Graphics utilities:** Native Contrast-Adaptive Sharpening and an optional MediaProjection capture-analysis prototype. Capture analysis does not replace Roblox's rendered output or promise higher game FPS.
- **App-owned overlays:** Optional floating controls and a liquid-style controls panel. These do not replace Android's notification shade.
- **Interface and branding:** AURORA ENGINE branding, a red-and-white main interface, and Fluid Motion / Fluid Fusion UI animation choices.

Some features require explicit Android permissions, Shizuku authorization, compatible hardware, or user approval for screen capture. System options are limited by the device and Android version.

## Target Game

The primary supported target is **Roblox for Android**, package `com.roblox.client`. Aurora Engine is designed to launch and optimize Roblox sessions using supported Android APIs; it does not change the game's code, assets, or rendering pipeline.

## 1. Quick-Start Phone-Only Workflow (No Nano Required)

You do **not** need to type or paste any code into `nano`. Everything is pre-packaged inside `aurora_engine.zip` and automated via `build_install.sh`.

### Step 1: Download the Deliverables
Download `aurora_engine.zip` and `build_install.sh` to your phone's `Download` folder (`/sdcard/Download/`).

### Step 2: Open Termux and Grant Storage Access
Open Termux on your Android phone and run once (if not already granted):
```bash
termux-setup-storage
```

### Step 3: Run the Automated Build & Install Script
Run a single command pointing to the downloaded ZIP file:
```bash
bash /sdcard/Download/build_install.sh /sdcard/Download/aurora_engine.zip
```

**Alternative (If you already extracted `aurora_engine.zip`):**
```bash
cd /sdcard/Download/AuroraEngine
bash build_install.sh
```

## 2. What `build_install.sh` Does Automatically

1. **Detects Environment & Hardware:** Verifies Termux (`$PREFIX`), ARM64 CPU architecture (`aarch64`), free disk space, and available RAM on your 4 GB device.
2. **Checks & Installs Dependencies:** Automatically checks for `openjdk-17`, `gradle`, `aapt`, `aapt2`, `android-tools` (`adb`), `clang`, and `unzip`, installing any missing packages via `pkg install -y`.
3. **Applies ARM64 AAPT2 Override:** Google's default Maven repository ships `x86_64` `aapt2` binaries that fail on ARM64 phones with `Exec format error`. `build_install.sh` automatically passes `-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2` so Gradle uses Termux's native ARM64 `aapt2`.
4. **Applies 4 GB RAM Safe Heap Limits:** Configures `-Xmx1024m` so the Gradle build does not trigger Android's low-memory killer on 4 GB RAM phones.
5. **Locates & Installs the APK:** Copies the compiled `app-debug.apk` to `/sdcard/Download/AuroraEngine-debug.apk` and launches the Android Package Installer (or installs via ADB if connected).

## 3. ARM64 Termux Technical Blocker & Cloud Build Alternative

### Why Native On-Device Gradle Builds Can Hit OS Limits on Android 14/15/16
On unrooted 4 GB RAM phones running Android 14+, two platform constraints can affect full Jetpack Compose + NDK builds inside Termux:
1. **Android SDK Platform 34 (`android.jar`) & NDK CMake Sysroot:** Official Google `sdkmanager` command-line binaries target desktop `x86_64` Linux. If `platforms/android-34/android.jar` is not yet present in `$HOME/android-sdk`, `build_install.sh` sets up the directory structure and links Termux's native `aapt2` and `android.jar` (from `aapt`).
2. **Android Phantom Process Killer (`SIGKILL 9`):** Android limits background child processes and RAM consumption for terminal apps.

### Phone-Only Cloud Build Alternative (GitHub Codespaces / GitHub Actions — Zero PC Required)
If Android's Phantom Process Killer interrupts heavy compilation on your 4 GB RAM phone, the repository workflow (`.github/workflows/build-aurora-apk.yml`) builds APKs on pushes to `main`/`master`, pull requests to those branches, or when started manually. Tag a commit as `v*` to build a release APK and attach it to a GitHub Release with `.github/workflows/publish-aurora-release.yml`:
1. Push this project to a GitHub repository where you can run Actions, or use the existing repository if you already have access.
2. Open the **Actions** tab on GitHub (or open the repository in **GitHub Codespaces** in your mobile browser and run `./gradlew assembleDebug`).
3. Open the completed workflow run, download the `aurora-engine-debug-apk` artifact, extract `app-debug.apk`, and tap it to install. The run also provides an `aurora-engine-release-apk` artifact.

## 4. Connecting Shizuku & Optimizing Roblox After Installation

1. Install and open **Shizuku** (`moe.shizuku.privileged.api`) on your phone.
2. Start the Shizuku service using **Wireless Debugging** (Developer Options → Wireless Debugging → Pair device with pairing code in Shizuku).
3. Open **AURORA ENGINE** (AK by AK Enterprises).
4. If Shizuku is not yet authorized, Aurora Engine displays the **Mandatory Shizuku Authorization** banner. Tap **Authorize** and approve the official Shizuku prompt.
5. Select your desired mode from the Edge Panel pill bar (**Power-saving / Battery**, **Balanced**, or **Pro Gamer / Performance**), choose **Standard** or **Ultra touch**, and tap **START GAMING** to optimize and launch Roblox (`com.roblox.client`).
