# Aurora Engine by AK Enterprises — Termux Build & Installation Guide

## Desktop / GitHub Codespaces Build

Use JDK 17, Gradle 8.5, and Android SDK Platform 34. From this directory run:

```sh
gradle assembleRelease assembleDebug
```

The optimized, debug-signed release APK is `app/build/outputs/apk/release/app-release.apk`.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The release APK is for
direct testing and is not signed for Play Store publishing.

**Application Name:** Aurora Engine  
**Developer Branding:** AK Enterprises  
**Package ID:** `com.akenterprises.auroraengine`  
**Primary Gaming Target:** Roblox (`com.roblox.client`)  
**Reference Hardware Profile:** Samsung Galaxy A07 5G (4 GB RAM, ARM Mali-G57 GPU, Android)

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
If Android's Phantom Process Killer interrupts heavy compilation on your 4 GB RAM phone, `aurora_engine.zip` includes a ready-to-run GitHub Actions workflow (`.github/workflows/build-aurora-apk.yml`):
1. Open Chrome on your phone and upload the extracted `AuroraEngine` project to a GitHub repository under your account (`mardhaakshith2013-byte`).
2. Open the **Actions** tab on GitHub (or open the repository in **GitHub Codespaces** in your mobile browser and run `./gradlew assembleDebug`).
3. Download the compiled `aurora-engine-debug-apk` artifact (`app-debug.apk`) directly to your phone and tap to install.

## 4. Connecting Shizuku & Optimizing Roblox After Installation

1. Install and open **Shizuku** (`moe.shizuku.privileged.api`) on your phone.
2. Start the Shizuku service using **Wireless Debugging** (Developer Options → Wireless Debugging → Pair device with pairing code in Shizuku).
3. Open **Aurora Engine** by AK Enterprises.
4. If Shizuku is not yet authorized, Aurora Engine displays the **Mandatory Shizuku Authorization** banner. Tap **Authorize** and approve the official Shizuku prompt.
5. Select your desired mode from the Edge Panel pill bar (**Power-saving / Battery**, **Balanced**, or **Pro Gamer / Performance**), choose **Standard** or **Ultra touch**, and tap **START GAMING** to optimize and launch Roblox (`com.roblox.client`).
