package com.akenterprises.auroraengine.performance

import com.akenterprises.auroraengine.core.model.CommandExecutionRecord
import com.akenterprises.auroraengine.core.model.FrameGenMultiplier
import com.akenterprises.auroraengine.core.model.PerformanceMode
import com.akenterprises.auroraengine.core.model.TouchProfile
import com.akenterprises.auroraengine.core.model.UpscalingMode
import com.akenterprises.auroraengine.core.prefs.AuroraPreferences
import com.akenterprises.auroraengine.shizuku.ShizukuCommandEngine
import com.akenterprises.auroraengine.shizuku.ShizukuConnectionManager

class PerformanceModeController(
    private val prefs: AuroraPreferences,
    private val shizukuConnectionManager: ShizukuConnectionManager,
    private val shizukuCommandEngine: ShizukuCommandEngine
) {
    companion object {
        fun framePacingDownscale(multiplier: FrameGenMultiplier): String? = multiplier.hardwareDownscale
    }

    data class ModeApplicationReport(
        val mode: PerformanceMode,
        val shizukuAuthorized: Boolean,
        val records: List<CommandExecutionRecord>,
        val summary: String,
        val activated: Boolean = false,
        val diagnostics: ProfileDiagnostics = ProfileDiagnostics.initial(),
        val operations: List<ProfileOperation> = emptyList()
    )

    suspend fun applyMode(
        mode: PerformanceMode,
        touchProfile: TouchProfile = prefs.touchProfile,
        supportedRefreshRates: List<Float> = emptyList(),
        targetRefreshRate: Float? = null,
        downscaleFactor: String? = null,
        enableFixedPerformanceHint: Boolean = mode == PerformanceMode.PERFORMANCE
    ): ModeApplicationReport {
        if (targetRefreshRate != null && targetRefreshRate !in supportedRefreshRates) {
            return ModeApplicationReport(
                mode = mode,
                shizukuAuthorized = false,
                records = emptyList(),
                summary = "The selected ${targetRefreshRate.toInt()} Hz target is not supported by this display."
            )
        }

        val selectedDownscale = downscaleFactor ?: when (prefs.upscalingMode) {
            UpscalingMode.NATIVE -> "disable"
            UpscalingMode.SUPPORTED_CAS -> if (mode == PerformanceMode.PERFORMANCE) "0.85" else "disable"
            UpscalingMode.EXPERIMENTAL_AI -> "0.75"
            UpscalingMode.NVIDIA_DLSS_UNSUPPORTED -> "disable"
        }
        val applied = shizukuCommandEngine.profileApplicationService.apply(
            mode = mode,
            supportedRefreshRates = supportedRefreshRates,
            targetRefreshRate = targetRefreshRate,
            downscaleFactor = selectedDownscale,
            enableFixedPerformanceHint = enableFixedPerformanceHint && mode == PerformanceMode.PERFORMANCE
        )

        if (applied.activated) {
            prefs.selectedMode = mode
            prefs.touchProfile = touchProfile
            prefs.smoothPanelAnimations = mode.enableDecorativeUiAnimations
        }

        val records = applied.operations.map { operation ->
            CommandExecutionRecord(
                optimizationName = operation.name,
                purpose = if (operation.required) "Required profile operation." else "Optional profile operation.",
                command = operation.name,
                requiredPermission = "Shizuku ADB Shell (UID 2000)",
                supportedAndroidVersions = "Android 8.0+",
                exitCode = operation.exitCode ?: if (operation.level == DiagnosticLevel.PASS) 0 else -1,
                stdout = if (operation.level == DiagnosticLevel.PASS) operation.detail else "",
                stderr = if (operation.level == DiagnosticLevel.PASS) "" else operation.detail,
                rollbackCommand = if (operation.name.startsWith("rollback")) null else "ProfileApplicationService.restoreAll()"
            )
        }
        return ModeApplicationReport(
            mode = mode,
            shizukuAuthorized = applied.diagnostics.shizukuAuthorization.level == DiagnosticLevel.PASS,
            records = records,
            summary = applied.summary,
            activated = applied.activated,
            diagnostics = applied.diagnostics,
            operations = applied.operations
        )
    }

    suspend fun applyFramePacing(
        multiplier: FrameGenMultiplier,
        mode: PerformanceMode = prefs.selectedMode,
        supportedRefreshRates: List<Float> = emptyList()
    ): ModeApplicationReport? {
        val downscaleFactor = framePacingDownscale(multiplier) ?: return null
        val targetHz = highestSupportedRefreshRate(supportedRefreshRates)
        if (targetHz == null) {
            return ModeApplicationReport(
                mode = mode,
                shizukuAuthorized = shizukuConnectionManager.refreshStatus().canExecutePrivileged,
                records = emptyList(),
                summary = "Frame pacing was not applied because no supported display refresh mode is available."
            )
        }
        return applyMode(
            mode = mode,
            supportedRefreshRates = supportedRefreshRates,
            targetRefreshRate = targetHz,
            downscaleFactor = downscaleFactor,
            enableFixedPerformanceHint = true
        )
    }
}

internal fun highestSupportedRefreshRate(supportedRefreshRates: List<Float>): Float? =
    supportedRefreshRates.filter { it.isFinite() && it >= 60f }.maxOrNull()
