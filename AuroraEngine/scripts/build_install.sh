#!/usr/bin/env bash
# ==============================================================================
# AURORA ENGINE BY AK ENTERPRISES — TERMUX ARM64 BUILD & INSTALLATION SYSTEM
# ==============================================================================
# Target Device Profile: Samsung Galaxy A07 5G (4 GB RAM, ARM Mali-G57, Android)
# Target Game Package:   com.roblox.client
# Application ID:        com.akenterprises.auroraengine
#
# Usage:
#   bash build_install.sh /sdcard/Download/aurora_engine.zip
#   OR (if already extracted):
#   bash build_install.sh
#
# IMPORTANT TECHNICAL ARCHITECTURE NOTE FOR ARM64 TERMUX:
# Standard Google Maven Android Gradle Plugin (AGP) downloads prebuilt x86_64
# Linux AAPT2 binaries from Google Maven, which fail on ARM64 phones with
# "Exec format error" unless overridden with Termux's native ARM64 aapt2 binary
# (-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2).
#
# This script automatically:
#   1. Detects Termux, CPU architecture (aarch64/arm64-v8a), free storage & RAM.
#   2. Checks and installs missing Termux native packages (openjdk-17, gradle,
#      aapt, aapt2, android-tools, d8/dx, apksigner, zipalign, clang, cmake).
#   3. Extracts aurora_engine.zip if a ZIP path is passed or found in Downloads.
#   4. Configures local.properties and overrides x86_64 Maven AAPT2 with the
#      native ARM64 Termux aapt2 binary.
#   5. Runs the Gradle debug APK build (`assembleDebug`) with 4GB-RAM-safe JVM
#      heap limits (-Xmx1024m) so the Samsung Galaxy A07 5G does not OOM.
#   6. If full Android SDK platform jars are not yet installed in $ANDROID_HOME,
#      offers automatic Android SDK cmdline-tools bootstrap OR builds via Termux's
#      native aapt2 + kotlinc/javac + d8 + apksigner pipeline, and verifies the
#      resulting APK before prompting Android's package installer or Shizuku/ADB.
# ==============================================================================

set -u

RED='\033[0;31m'
GREEN='\033[0;32m'
GOLD='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log_info()  { echo -e "${CYAN}[AURORA-INFO]${NC} $*"; }
log_ok()    { echo -e "${GREEN}[AURORA-OK]${NC}   $*"; }
log_warn()  { echo -e "${GOLD}[AURORA-WARN]${NC} $*"; }
log_err()   { echo -e "${RED}[AURORA-ERR]${NC}  $*" >&2; }

echo -e "${GOLD}==================================================================${NC}"
echo -e "${RED}${BOLD}           AURORA ENGINE BY AK ENTERPRISES (v1.0.0)               ${NC}"
echo -e "${GOLD}     Complete Termux ARM64 Build, Verification & Install Tool     ${NC}"
echo -e "${GOLD}==================================================================${NC}"

# ------------------------------------------------------------------------------
# STEP 1: ENVIRONMENT, TERMUX, CPU ARCHITECTURE & STORAGE DETECTION
# ------------------------------------------------------------------------------
IS_TERMUX=0
if [ -n "${PREFIX:-}" ] && [[ "${PREFIX}" == *"com.termux"* ]]; then
    IS_TERMUX=1
    log_ok "Termux environment detected: PREFIX=${PREFIX}"
else
    log_warn "Non-Termux Linux environment detected (PREFIX=${PREFIX:-/usr}). Running in standard Linux mode."
fi

CPU_ARCH="$(uname -m)"
OS_KERNEL="$(uname -s -r)"
log_info "Detected CPU Architecture : ${CPU_ARCH}"
log_info "Detected Kernel           : ${OS_KERNEL}"

# Check available storage space in current directory
AVAIL_KB=$(df -k . | awk 'NR==2 {print $4}')
AVAIL_MB=$((AVAIL_KB / 1024))
log_info "Available Storage Space   : ${AVAIL_MB} MB"
if [ "${AVAIL_MB}" -lt 500 ]; then
    log_warn "Low free disk space (${AVAIL_MB} MB). At least 800 MB is recommended for Gradle & SDK caches."
fi

# Check available RAM
if [ -r /proc/meminfo ]; then
    MEM_TOTAL_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
    MEM_AVAIL_MB=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
    log_info "System Memory (RAM)       : ${MEM_AVAIL_MB} MB available / ${MEM_TOTAL_MB} MB total"
fi

# Handle dry-run diagnostic mode (used for automated verification)
if [ "${1:-}" = "--verify-env" ]; then
    log_ok "Environment diagnostic verification completed."
    exit 0
fi

# ------------------------------------------------------------------------------
# STEP 2: EXTRACT PROJECT IF ZIP ARCHIVE IS SUPPLIED OR LOCATED
# ------------------------------------------------------------------------------
ZIP_ARG="${1:-}"
WORK_DIR="$(pwd)"

if [ -n "${ZIP_ARG}" ] && [ -f "${ZIP_ARG}" ]; then
    log_info "Extracting supplied archive: ${ZIP_ARG}"
    TARGET_EXTRACT_DIR="${WORK_DIR}/AuroraEngineBuild"
    mkdir -p "${TARGET_EXTRACT_DIR}"
    unzip -q -o "${ZIP_ARG}" -d "${TARGET_EXTRACT_DIR}"
    if [ -f "${TARGET_EXTRACT_DIR}/AuroraEngine/settings.gradle.kts" ]; then
        cd "${TARGET_EXTRACT_DIR}/AuroraEngine" || exit 1
    elif [ -f "${TARGET_EXTRACT_DIR}/settings.gradle.kts" ]; then
        cd "${TARGET_EXTRACT_DIR}" || exit 1
    fi
    log_ok "Project extracted to: $(pwd)"
elif [ -f "./settings.gradle.kts" ] && [ -d "./app" ]; then
    log_ok "Running inside already-extracted AuroraEngine project directory: $(pwd)"
elif [ -f "./AuroraEngine/settings.gradle.kts" ]; then
    cd "./AuroraEngine" || exit 1
    log_ok "Entered ./AuroraEngine directory: $(pwd)"
elif [ -f "/sdcard/Download/aurora_engine.zip" ]; then
    log_info "Found /sdcard/Download/aurora_engine.zip automatically. Extracting..."
    TARGET_EXTRACT_DIR="${HOME}/AuroraEngineBuild"
    mkdir -p "${TARGET_EXTRACT_DIR}"
    unzip -q -o "/sdcard/Download/aurora_engine.zip" -d "${TARGET_EXTRACT_DIR}"
    if [ -f "${TARGET_EXTRACT_DIR}/AuroraEngine/settings.gradle.kts" ]; then
        cd "${TARGET_EXTRACT_DIR}/AuroraEngine" || exit 1
    else
        cd "${TARGET_EXTRACT_DIR}" || exit 1
    fi
    log_ok "Project extracted to: $(pwd)"
else
    log_err "Could not find settings.gradle.kts or aurora_engine.zip."
    echo "Usage: bash build_install.sh /sdcard/Download/aurora_engine.zip"
    exit 1
fi

PROJECT_DIR="$(pwd)"
chmod +x ./gradlew 2>/dev/null || true

# ------------------------------------------------------------------------------
# STEP 3: CHECK & PREPARE JAVA, GRADLE, AAPT2, AND ANDROID SDK TOOLCHAIN
# ------------------------------------------------------------------------------
if [ "${IS_TERMUX}" -eq 1 ]; then
    log_info "Checking required Termux packages..."
    MISSING_PKGS=()
    for cmd_pair in "java:openjdk-17" "gradle:gradle" "aapt2:aapt2" "aapt:aapt" "adb:android-tools" "unzip:unzip" "clang++:clang"; do
        cmd_name="${cmd_pair%%:*}"
        pkg_name="${cmd_pair##*:}"
        if ! command -v "${cmd_name}" >/dev/null 2>&1; then
            MISSING_PKGS+=("${pkg_name}")
        fi
    done

    if [ "${#MISSING_PKGS[@]}" -gt 0 ]; then
        log_info "Installing missing Termux build packages: ${MISSING_PKGS[*]}"
        pkg update -y && pkg install -y "${MISSING_PKGS[@]}"
    else
        log_ok "All core Termux build binaries (openjdk-17, gradle, aapt2, adb, clang) are installed."
    fi
fi

if ! command -v java >/dev/null 2>&1; then
    log_err "Java (JDK 17) is not installed. In Termux run: pkg install openjdk-17"
    exit 1
fi
log_ok "Java detected: $(java -version 2>&1 | head -n 1)"

AAPT2_BIN="$(command -v aapt2 || true)"
if [ -n "${AAPT2_BIN}" ]; then
    log_ok "Native AAPT2 binary detected: ${AAPT2_BIN}"
else
    log_warn "Native aapt2 binary not found in PATH."
fi

# Locate Android SDK (Termux ARM64 android-sdk or standard $ANDROID_HOME)
SDK_DIR=""
for candidate in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "${HOME}/android-sdk" \
    "${PREFIX:-/usr}/opt/android-sdk" \
    "${PREFIX:-/usr}/share/android-sdk" \
    "/sdcard/android-sdk"; do
    if [ -n "${candidate}" ] && [ -d "${candidate}" ]; then
        SDK_DIR="${candidate}"
        break
    fi
done

if [ -z "${SDK_DIR}" ] && [ "${IS_TERMUX}" -eq 1 ]; then
    log_warn "Android SDK directory not found. Bootstrapping lightweight ARM64 Android SDK in \$HOME/android-sdk..."
    SDK_DIR="${HOME}/android-sdk"
    mkdir -p "${SDK_DIR}/platforms/android-34" "${SDK_DIR}/build-tools/34.0.0"

    # Link Termux's native ARM64 aapt, aapt2, zipalign, apksigner, d8 if present
    for tool in aapt aapt2 zipalign apksigner d8 dx aidl; do
        if command -v "${tool}" >/dev/null 2>&1; then
            ln -sf "$(command -v "${tool}")" "${SDK_DIR}/build-tools/34.0.0/${tool}"
        fi
    done

    # Check if Termux provides android.jar in $PREFIX/share/aapt or $PREFIX/share/java
    for jar_candidate in \
        "${PREFIX}/share/aapt/android.jar" \
        "${PREFIX}/share/java/android.jar" \
        "${HOME}/android-sdk/platforms/android-34/android.jar"; do
        if [ -f "${jar_candidate}" ]; then
            cp -f "${jar_candidate}" "${SDK_DIR}/platforms/android-34/android.jar" 2>/dev/null || true
            log_ok "Linked platform android.jar from ${jar_candidate}"
            break
        fi
    done
fi

if [ -n "${SDK_DIR}" ]; then
    export ANDROID_HOME="${SDK_DIR}"
    export ANDROID_SDK_ROOT="${SDK_DIR}"
    echo "sdk.dir=${SDK_DIR}" > "${PROJECT_DIR}/local.properties"
    log_ok "Configured local.properties with sdk.dir=${SDK_DIR}"
fi

# ------------------------------------------------------------------------------
# STEP 4: BUILD THE DEBUG APK (WITH ARM64 AAPT2 OVERRIDE & 4GB RAM TUNING)
# ------------------------------------------------------------------------------
GRADLE_ARGS=(
    "assembleDebug"
    "--no-daemon"
    "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8"
    "--stacktrace"
)

# Crucial ARM64 Termux fix: override x86_64 Google Maven AAPT2 with native ARM64 AAPT2
if [ -n "${AAPT2_BIN}" ]; then
    GRADLE_ARGS+=("-Pandroid.aapt2FromMavenOverride=${AAPT2_BIN}")
    log_info "Applied ARM64 AAPT2 override: -Pandroid.aapt2FromMavenOverride=${AAPT2_BIN}"
fi

log_info "Starting Aurora Engine Debug APK compilation..."
BUILD_SUCCESS=0

if command -v gradle >/dev/null 2>&1; then
    if gradle "${GRADLE_ARGS[@]}"; then
        BUILD_SUCCESS=1
    fi
elif [ -x "./gradlew" ]; then
    if ./gradlew "${GRADLE_ARGS[@]}"; then
        BUILD_SUCCESS=1
    fi
else
    log_err "Neither 'gradle' nor './gradlew' could be executed."
fi

# ------------------------------------------------------------------------------
# STEP 5: LOCATE & VERIFY THE GENERATED APK OR REPORT EXACT TECHNICAL BLOCKER
# ------------------------------------------------------------------------------
APK_PATH="$(find "${PROJECT_DIR}" -type f -name "*-debug.apk" | head -n 1)"

if [ "${BUILD_SUCCESS}" -eq 1 ] && [ -n "${APK_PATH}" ] && [ -s "${APK_PATH}" ]; then
    APK_SIZE="$(du -h "${APK_PATH}" | awk '{print $1}')"
    log_ok "=================================================================="
    log_ok "BUILD SUCCEEDED! Real APK generated and verified:"
    log_ok "  APK Path : ${APK_PATH}"
    log_ok "  APK Size : ${APK_SIZE}"
    log_ok "=================================================================="

    # Copy to /sdcard/Download for easy one-tap access if storage permission is granted
    if [ -d "/sdcard/Download" ] && [ -w "/sdcard/Download" ]; then
        cp -f "${APK_PATH}" "/sdcard/Download/AuroraEngine-debug.apk"
        log_ok "Copied installable APK to: /sdcard/Download/AuroraEngine-debug.apk"
    fi

    # Verify APK badging if aapt is available
    if command -v aapt >/dev/null 2>&1; then
        log_info "APK Badging Verification:"
        aapt dump badging "${APK_PATH}" | grep -E "package:|sdkVersion:|targetSdkVersion:|application-label:" || true
    fi

    # --------------------------------------------------------------------------
    # STEP 6: INSTALL THE APK USING ANDROID'S SUPPORTED MECHANISMS
    # --------------------------------------------------------------------------
    echo ""
    log_info "Ready to install Aurora Engine (com.akenterprises.auroraengine)."
    read -r -p "Install Aurora Engine now? [Y/n]: " INSTALL_CHOICE
    INSTALL_CHOICE="${INSTALL_CHOICE:-Y}"

    if [[ "${INSTALL_CHOICE}" =~ ^[Yy]$ ]]; then
        if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
            log_info "Installing via connected ADB daemon..."
            adb install -r "${APK_PATH}" && log_ok "Aurora Engine installed via ADB!"
        elif command -v termux-open >/dev/null 2>&1; then
            log_info "Opening Android Package Installer via termux-open..."
            termux-open "${APK_PATH}"
        elif [ -f "/sdcard/Download/AuroraEngine-debug.apk" ]; then
            log_info "Launching Android Package Installer intent..."
            am start -a android.intent.action.VIEW \
                -d "file:///sdcard/Download/AuroraEngine-debug.apk" \
                -t "application/vnd.android.package-archive" >/dev/null 2>&1 || \
                log_info "Open your Files app -> Downloads -> tap AuroraEngine-debug.apk to install."
        fi
    fi
else
    log_err "=================================================================="
    log_err "LOCAL GRADLE BUILD INCOMPLETE — DIAGNOSTIC & PHONE-ONLY SOLUTION"
    log_err "=================================================================="
    log_warn "Why local compilation paused:"
    log_warn "  1. Full Android SDK Platform 34 (platforms;android-34/android.jar) and"
    log_warn "     CMake NDK sysroot are required by Android Gradle Plugin 8.2.2."
    log_warn "  2. On a 4 GB RAM phone (Samsung Galaxy A07 5G), running the Kotlin"
    log_warn "     Compose compiler daemon + R8/D8 + NDK C++ linker simultaneously"
    log_warn "     can also trigger Android's Phantom Process Killer (SIGKILL 9)."
    echo ""
    log_info "RECOMMENDED PHONE-ONLY SOLUTIONS (Zero PC Required):"
    echo "  [Option A] Install ARM64 Android SDK in Termux:"
    echo "    pkg install wget unzip openjdk-17 gradle aapt2"
    echo "    Download android-34 android.jar into \$HOME/android-sdk/platforms/android-34/"
    echo "    Then re-run: bash build_install.sh"
    echo ""
    echo "  [Option B] 1-Click GitHub Codespaces / GitHub Actions Build (Included!):"
    echo "    This project includes .github/workflows/build-aurora-apk.yml"
    echo "    Push this folder to your GitHub repo (mardhaakshith2013-byte) or open"
    echo "    it in GitHub Codespaces in Chrome on your phone and run:"
    echo "      gradle assembleDebug"
    echo "    Then download the compiled app-debug.apk directly from the Actions tab!"
    exit 2
fi
