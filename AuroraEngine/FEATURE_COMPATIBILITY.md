# Aurora Engine by AK Enterprises — Feature & Hardware Compatibility Matrix

**Primary Target Profile:** Samsung Galaxy A07 5G (4 GB RAM, ARM Mali-G57 GPU, One UI Core, Android)  
**Target Game:** Roblox (`com.roblox.client`)

## 1. Core Feature Status & Hardware Compatibility

| Feature Module | Implementation Status | Required Permission / API | Behavior on Samsung Galaxy A07 5G (4 GB RAM / Mali-G57) |
| :--- | :--- | :--- | :--- |
| **Mandatory Shizuku Authorization System** | **Fully Supported** | `moe.shizuku.privileged.api` (ADB UID 2000) | Blocks all privileged shell commands until Shizuku is running and user approves the permission dialog. Halts privileged work immediately if Shizuku disconnects. |
| **Three Performance Modes (Battery / Balanced / Pro Gamer)** | **Fully Supported** | Normal + Shizuku ADB for OS scales | Dynamically switches theme accents (Green / Gold / Crimson), toggles decorative UI transitions, and applies window/transition/animator scales (`0.75x` / `1.0x` / `0.5x`) plus Android 12+ `cmd game mode` (`3` / `1` / `2`) via Shizuku. |
| **Touch Response (`Standard` / `Ultra touch`)** | **Supported (Hardware-Aware)** | Shizuku ADB (`min_refresh_rate` floor) | Locks minimum refresh rate floor to peak supported panel rate during gaming so touch dispatch aligns with high-refresh scanning. Does not fake kernel touch driver overclocking on locked bootloaders. |
| **Screen Refresh Rate Control (`60 / 90 / 120 Hz`)** | **Fully Supported** | `WindowManager.defaultDisplay.supportedModes` + Shizuku | Dynamically inspects the panel's actual supported refresh rates at runtime and requests `peak_refresh_rate` / `min_refresh_rate` via Shizuku only for rates supported by the physical display. |
| **Roblox Optimization Engine (`com.roblox.client`)** | **Fully Supported** | `<queries>` package visibility + Shizuku | Detects Roblox installation version and running PID, trims background memory competition before launch, applies Android GameMode intervention, launches Roblox, and rolls back all changes on session stop. Never modifies Roblox files or memory. |
| **Safe Gaming RAM Manager** | **Fully Supported** | `KILL_BACKGROUND_PROCESSES` + Shizuku `am send-trim-memory` | Displays real-time available/total RAM and low-memory threshold. Safely reclaims memory from cached third-party apps while strictly protecting SystemUI, calls/telephony, notifications, Termux, Shizuku, and Roblox. |
| **GPU & Renderer Capability Detection** | **Fully Supported** | `EGL14` / `GLES20` + `PackageManager` Vulkan features | Queries the real GPU renderer (`ARM Mali-G57`), vendor (`ARM`), OpenGL ES version (`OpenGL ES 3.2`), and hardware Vulkan support at runtime. Never forces an unsupported renderer. |
| **NVIDIA DLSS Detection** | **Explicitly Marked Unsupported** | Hardware capability check | Genuine NVIDIA DLSS requires proprietary NVIDIA Tensor Cores. Aurora Engine explicitly marks NVIDIA DLSS as **Hardware Unsupported** on ARM Mali-G57 and never misidentifies Mali hardware as NVIDIA. |
| **Contrast-Adaptive Sharpening (CAS) & Experimental AI Upscaling** | **Supported / Experimental** | C++17 JNI (`libaurora_native.so`) + Kotlin fallback | Provides edge-preserving Contrast-Adaptive Sharpening (`0%–100%` slider) on captured frames with automatic CPU fallback if native library is unavailable. |
| **LSFG-Inspired Multi-Frame Generation (`OFF / 2X / 3X / 4X`)** | **Experimental Prototype** | `MediaProjection` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Captures bounded `320x180` RGBA frames via `ImageReader` (2-frame ring buffer to prevent 4 GB RAM exhaustion) and runs C++17 8x8 block motion estimation + frame interpolation after benchmarking device frame-processing speed. |
| **Collapsible Edge-Panel UI & Optional Floating Handle** | **Fully Supported** | In-app Compose UI + optional `SYSTEM_ALERT_WINDOW` | Recreates the OxygenOS 16 OP Gaming Core / ColorOS 17 HyperBoost dual-column layout inside the app by default, and offers an optional left-edge floating handle when the user grants overlay permission. |

## 2. Important Technical Disclosures & Limitations

1. **MediaProjection Frame Generation vs. Internal Game Engine FPS:**
   - Android security isolates each application's rendering surface (`SurfaceFlinger`). An external app using `MediaProjection` **cannot inject synthesized frames back into Roblox's internal rendering swapchain** without root-level `SurfaceFlinger` hooks, nor does frame interpolation increase Roblox's server-side physics or simulation tick rate.
   - Aurora Engine's 2X/3X/4X Multi-Frame Generation module operates as a real, bounded **MediaProjection + C++17 Optical-Flow capture and interpolation pipeline** with live frame-budget benchmarking and telemetry, and clearly labels this architectural boundary in the UI.
2. **Thermal Protection & Hardware Safety Limits:**
   - Aurora Engine monitors battery temperature (`BatteryManager.EXTRA_TEMPERATURE`) and Android thermal headroom (`PowerManager.currentThermalStatus`), displaying thermal warnings when temperatures rise. It **never** attempts to disable manufacturer thermal throttling or override hardware safety limits.
