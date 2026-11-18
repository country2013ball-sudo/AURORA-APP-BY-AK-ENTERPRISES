# AURORA ENGINE — Feature & Hardware Compatibility Matrix

**Primary Target Profile:** Samsung Galaxy A07 5G (4 GB RAM, ARM Mali-G57 GPU, One UI Core, Android)  
**Target Game:** Roblox (`com.roblox.client`)

## 1. Core Feature Status & Hardware Compatibility

| Feature Module | Implementation Status | Required Permission / API | Behavior on Samsung Galaxy A07 5G (4 GB RAM / Mali-G57) |
| :--- | :--- | :--- | :--- |
| **Mandatory Shizuku Authorization System** | **Fully Supported** | `moe.shizuku.privileged.api` (ADB UID 2000) | Blocks all privileged shell commands until Shizuku is running and user approves the permission dialog. Halts privileged work immediately if Shizuku disconnects. |
| **Three Performance Modes (Power-saving / Balanced / Pro Gamer)** | **Fully Supported** | Normal + Shizuku ADB for OS scales | User-switchable OxygenOS 16 lime and ColorOS 17 crimson themes; applies window/transition/animator scales (`0.85x` / `0.70x` / `0.5x`) plus Android 12+ `cmd game mode` (`3` / `1` / `2`) via Shizuku. Liquid Flow stays active in every mode; Pro Gamer uses faster local transitions. |
| **Touch Response (`Standard` / `Ultra touch`)** | **Supported (Hardware-Aware)** | Shizuku ADB (`min_refresh_rate` floor) | Locks minimum refresh rate floor to peak supported panel rate during gaming so touch dispatch aligns with high-refresh scanning. Does not fake kernel touch driver overclocking on locked bootloaders. |
| **Screen Refresh Rate Control (`60 / 90 / 120 Hz`)** | **Fully Supported** | `WindowManager.defaultDisplay.supportedModes` + Shizuku | Dynamically inspects the panel's actual supported refresh rates at runtime and requests `peak_refresh_rate` / `min_refresh_rate` via Shizuku only for rates supported by the physical display. |
| **Roblox Optimization Engine (`com.roblox.client`)** | **Fully Supported** | `<queries>` package visibility + Shizuku | Detects Roblox installation version and running PID, trims background memory competition before launch, applies Android GameMode intervention, launches Roblox, and rolls back all changes on session stop. Never modifies Roblox files or memory. |
| **Safe Gaming RAM Manager** | **Fully Supported** | `KILL_BACKGROUND_PROCESSES` + Shizuku `am send-trim-memory` | Displays real-time available/total RAM and low-memory threshold. Safely reclaims memory from cached third-party apps while strictly protecting SystemUI, calls/telephony, notifications, Termux, Shizuku, and Roblox. |
| **GPU & Renderer Capability Detection** | **Fully Supported** | `EGL14` / `GLES20` + `PackageManager` Vulkan features | Queries the real GPU renderer (`ARM Mali-G57`), vendor (`ARM`), OpenGL ES version (`OpenGL ES 3.2`), and hardware Vulkan support at runtime. Never forces an unsupported renderer. |
| **NVIDIA DLSS Detection** | **Explicitly Marked Unsupported** | Hardware capability check | Genuine NVIDIA DLSS requires proprietary NVIDIA Tensor Cores. Aurora Engine explicitly marks NVIDIA DLSS as **Hardware Unsupported** on ARM Mali-G57 and never misidentifies Mali hardware as NVIDIA. |
| **Contrast-Adaptive Sharpening (CAS) & Experimental AI Upscaling** | **Supported / Experimental** | C++17 JNI (`libaurora_native.so`) + Kotlin fallback | Provides edge-preserving Contrast-Adaptive Sharpening (`0%–100%` slider) on captured frames with automatic CPU fallback if native library is unavailable. |
| **Aurora Frame Generation (`OFF / 2X / 3X`)** | **Capture-analysis prototype; no output replacement** | Explicit `MediaProjection` consent + `FOREGROUND_SERVICE_MEDIA_PROJECTION` | An optional 160x90 capture-analysis pipeline calls the native interpolation/sharpening code at up to 4 Hz. It does not present intermediate frames over Roblox or claim a game FPS increase. |
| **Live Frame-Rate Telemetry** | **Supported with source labels** | `Choreographer.FrameCallback`; optional Shizuku `dumpsys gfxinfo` | Reports measured app UI callback cadence, and prefers recent Roblox completed-frame timestamps when Shizuku is authorized and Roblox is running. Unknown values are shown as `--`; display refresh rate is not reported as FPS. |
| **Liquid Control Centre & Floating Edge Panel** | **User-enabled app overlays** | `SYSTEM_ALERT_WINDOW`; `WRITE_SETTINGS` for brightness | Provides an app-owned glass-style quick-controls surface, settings shortcuts, brightness/volume controls, and a separate gaming panel. It cannot replace Android's protected notification shade or read/intercept notifications. |
| **Startup Branding & Liquid Flow** | **Supported in-app UI** | Jetpack Compose | Shows the AURORA ENGINE splash animation and selectable Fluid Motion / Fluid Fusion transitions. |

## 2. Important Technical Disclosures & Limitations

1. **MediaProjection Frame Generation vs. Internal Game Engine FPS:**
   - Android security isolates each application's rendering surface (`SurfaceFlinger`). An external app using `MediaProjection` **cannot inject synthesized frames back into Roblox's internal rendering swapchain** without root-level `SurfaceFlinger` hooks, nor does frame interpolation increase Roblox's server-side physics or simulation tick rate.
   - Aurora Frame Generation runs only as bounded capture analysis using MediaProjection and the native processor. The app does not inject into Roblox or the kernel, and does not display generated intermediate frames as if they were game output.
2. **Control Centre Overlay vs. Android System UI:**
   - Android does not grant ordinary apps authority to replace the notification shade or access notifications without a separate notification-listener grant. Aurora's optional panel is an app-owned overlay; its Wi-Fi, Bluetooth, DND, and refresh shortcuts open Android settings.
3. **System Optimization Safety:**
   - The apply action uses the selected Shizuku performance profile. Refresh-rate choices are limited to modes the device reports. It does not run broad `am kill-all` commands; background cleanup remains in the existing SafeRamManager path.
4. **Thermal Protection & Hardware Safety Limits:**
   - Aurora Engine monitors battery temperature (`BatteryManager.EXTRA_TEMPERATURE`) and Android thermal headroom (`PowerManager.currentThermalStatus`), displaying thermal warnings when temperatures rise. It **never** attempts to disable manufacturer thermal throttling or override hardware safety limits.
