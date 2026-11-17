package com.akenterprises.auroraengine.core.model

enum class PerformanceMode(
    val id: String,
    val title: String,
    val badgeLabel: String,
    val subtitle: String,
    val accentHex: Long,
    val containerHex: Long,
    val windowAnimationScale: Float,
    val transitionAnimationScale: Float,
    val animatorDurationScale: Float,
    val enableDecorativeUiAnimations: Boolean
) {
    BATTERY(
        id = "battery",
        title = "Battery",
        badgeLabel = "Power-Saving",
        subtitle = "Smooth everyday usage, reduced background work, and conservative GPU scheduling.",
        accentHex = 0xFF00C853,
        containerHex = 0xFF0D3B1E,
        windowAnimationScale = 0.75f,
        transitionAnimationScale = 0.75f,
        animatorDurationScale = 0.75f,
        enableDecorativeUiAnimations = true
    ),
    BALANCED(
        id = "balanced",
        title = "Balanced",
        badgeLabel = "Balanced • Gold",
        subtitle = "Polished desktop-grade transitions paired with Roblox-aware resource scheduling.",
        accentHex = 0xFFFFC107,
        containerHex = 0xFF3E2F05,
        windowAnimationScale = 1.0f,
        transitionAnimationScale = 1.0f,
        animatorDurationScale = 1.0f,
        enableDecorativeUiAnimations = true
    ),
    PERFORMANCE(
        id = "performance",
        title = "Pro Gamer",
        badgeLabel = "Performance • Crimson",
        subtitle = "Maximum supported gaming responsiveness, reduced UI overhead, and active Roblox monitoring.",
        accentHex = 0xFFFF1744,
        containerHex = 0xFF420808,
        windowAnimationScale = 0.5f,
        transitionAnimationScale = 0.5f,
        animatorDurationScale = 0.5f,
        enableDecorativeUiAnimations = false
    )
}

enum class TouchProfile(val label: String, val description: String) {
    STANDARD("Standard", "Default Android touch sampling and pointer responsiveness."),
    ULTRA_TOUCH("Ultra touch", "Prioritizes input dispatch latency and pointer responsiveness where supported.")
}

enum class UpscalingMode(
    val label: String,
    val badge: String,
    val description: String
) {
    NATIVE(
        label = "Native Resolution",
        badge = "100% Native",
        description = "Renders at native display resolution with zero spatial scaling."
    ),
    SUPPORTED_CAS(
        label = "Supported Upscaling (CAS)",
        badge = "GPU/NEON Supported",
        description = "Applies Contrast-Adaptive Sharpening to preserve edge clarity without proprietary hardware."
    ),
    EXPERIMENTAL_AI(
        label = "Experimental AI Upscaling",
        badge = "Experimental",
        description = "Edge-directed spatial reconstruction + CAS sharpening on captured frames."
    ),
    NVIDIA_DLSS_UNSUPPORTED(
        label = "NVIDIA DLSS (Hardware Unsupported)",
        badge = "Unsupported on Mali",
        description = "Genuine NVIDIA DLSS requires proprietary NVIDIA Tensor Cores and is unavailable on ARM Mali GPUs."
    )
}

enum class FrameGenMultiplier(
    val multiplier: Int,
    val label: String,
    val targetFrameBudgetMs: Double,
    val isExperimental: Boolean
) {
    OFF(1, "OFF", 0.0, false),
    X2(2, "2X Experimental", 14.0, true),
    X3(3, "3X Experimental", 9.5, true),
    X4(4, "4X Experimental", 7.0, true)
}

enum class EdgeTab(val title: String) {
    HOME("Home"),
    PERFORMANCE("Performance"),
    GRAPHICS("Graphics"),
    RAM("RAM Manager"),
    SHIZUKU("Shizuku"),
    SETTINGS("Settings")
}

data class ShizukuStatus(
    val isInstalled: Boolean = false,
    val isRunning: Boolean = false,
    val isAuthorized: Boolean = false,
    val uid: Int = -1,
    val version: Int = -1,
    val statusMessage: String = "Checking Shizuku service..."
) {
    val canExecutePrivileged: Boolean
        get() = isInstalled && isRunning && isAuthorized
}

data class DeviceHardwareProfile(
    val manufacturer: String,
    val model: String,
    val deviceName: String,
    val socHardware: String,
    val androidRelease: String,
    val sdkInt: Int,
    val cpuAbi: String,
    val cpuCores: Int,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val lowMemoryThresholdMb: Long,
    val isLowMemory: Boolean,
    val gpuRenderer: String,
    val gpuVendor: String,
    val glEsVersion: String,
    val isVulkanSupported: Boolean,
    val vulkanVersionLabel: String,
    val currentRefreshRateHz: Float,
    val supportedRefreshRatesHz: List<Float>,
    val batteryPercent: Int,
    val batteryTempCelsius: Float,
    val thermalStatusLabel: String,
    val networkLatencyEstimateMs: Float
)

data class RobloxSessionStatus(
    val isInstalled: Boolean,
    val versionName: String,
    val isRunning: Boolean,
    val pid: Int?,
    val sessionActive: Boolean,
    val appliedOptimizationsCount: Int,
    val statusSummary: String
)

data class CommandExecutionRecord(
    val optimizationName: String,
    val purpose: String,
    val command: String,
    val requiredPermission: String,
    val supportedAndroidVersions: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val rollbackCommand: String?,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isSuccess: Boolean get() = exitCode == 0
}
