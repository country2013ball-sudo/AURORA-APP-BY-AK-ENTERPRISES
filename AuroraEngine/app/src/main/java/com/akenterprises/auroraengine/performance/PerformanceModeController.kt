package com.akenterprises.auroraengine.performance

import com.akenterprises.auroraengine.core.model.CommandExecutionRecord
import com.akenterprises.auroraengine.core.model.FrameGenMultiplier
import com.akenterprises.auroraengine.core.model.PerformanceMode
import com.akenterprises.auroraengine.core.model.TouchProfile
import com.akenterprises.auroraengine.core.model.UpscalingMode
import com.akenterprises.auroraengine.core.prefs.AuroraPreferences
import com.akenterprises.auroraengine.shizuku.ShizukuCommandEngine
import com.akenterprises.auroraengine.shizuku.ShizukuConnectionManager

/**
 * V2 Performance Mode Controller.
 * Executes all Shizuku settings in a single batched shell command and maps UpscalingMode
 * to Android GameManager downscale requests (`0.85` / `0.75` / `disable`) without running
 * MediaProjection while Roblox is in the foreground.
 */
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
        val summary: String
    )

    suspend fun applyMode(
        mode: PerformanceMode,
        touchProfile: TouchProfile = prefs.touchProfile,
        supportedRefreshRates: List<Float> = listOf(60f, 90f, 120f),
        targetRefreshRate: Float? = null
    ): ModeApplicationReport {
        if (targetRefreshRate != null &&
            (targetRefreshRate !in supportedRefreshRates || targetRefreshRate !in setOf(60f, 90f, 120f))
        ) {
            return ModeApplicationReport(
                mode = mode,
                shizukuAuthorized = false,
                records = emptyList(),
                summary = "The selected ${targetRefreshRate.toInt()} Hz target is not supported by this display."
            )
        }

        prefs.selectedMode = mode
        prefs.touchProfile = touchProfile
        prefs.smoothPanelAnimations = mode.enableDecorativeUiAnimations

        val shizukuStatus = shizukuConnectionManager.refreshStatus()
        if (!shizukuStatus.canExecutePrivileged) {
            return ModeApplicationReport(
                mode = mode,
                shizukuAuthorized = false,
                records = emptyList(),
                summary = "${mode.title} profile selected. Authorize Shizuku to apply its animation scales, supported refresh target, and GameManager settings."
            )
        }

        val defaultTargetHz = when (mode) {
            PerformanceMode.BATTERY -> 60.0f
            PerformanceMode.BALANCED -> if (supportedRefreshRates.any { it >= 90f }) 90.0f else 60.0f
            PerformanceMode.PERFORMANCE -> when {
                supportedRefreshRates.any { it >= 120f } -> 120.0f
                supportedRefreshRates.any { it >= 90f } -> 90.0f
                else -> 60.0f
            }
        }
        val targetHz = targetRefreshRate ?: defaultTargetHz

        val downscaleFactor = when (prefs.upscalingMode) {
            UpscalingMode.NATIVE -> "disable"
            UpscalingMode.SUPPORTED_CAS -> if (mode == PerformanceMode.PERFORMANCE) "0.85" else "disable"
            UpscalingMode.EXPERIMENTAL_AI -> "0.75"
            UpscalingMode.NVIDIA_DLSS_UNSUPPORTED -> "disable"
        }

        val lockFloor = (mode == PerformanceMode.PERFORMANCE || touchProfile == TouchProfile.ULTRA_TOUCH)
        val rec = shizukuCommandEngine.executeBatchedGamingProfile(
            mode = mode,
            targetHz = targetHz,
            lockMinRefresh = lockFloor,
            downscaleFactor = downscaleFactor,
            enableFixedPerfHint = (mode == PerformanceMode.PERFORMANCE)
        )

        val summary = if (rec.isSuccess) {
            "${mode.title} active (${targetHz.toInt()} Hz lock, Touch: ${touchProfile.label}, Hardware Scale: $downscaleFactor)."
        } else {
            "${mode.title} selected, but one or more Shizuku commands failed: ${rec.stderr.ifBlank { rec.stdout }}"
        }
        return ModeApplicationReport(
            mode = mode,
            shizukuAuthorized = true,
            records = listOf(rec),
            summary = summary
        )
    }

    suspend fun applyFramePacing(
        multiplier: FrameGenMultiplier,
        mode: PerformanceMode = prefs.selectedMode,
        supportedRefreshRates: List<Float> = listOf(60f, 90f, 120f)
    ): ModeApplicationReport? {
        val downscaleFactor = framePacingDownscale(multiplier) ?: return null
        val targetHz = highestSupportedRefreshRate(supportedRefreshRates)
            ?: return ModeApplicationReport(
                mode = mode,
                shizukuAuthorized = false,
                records = emptyList(),
                summary = "Frame pacing was not applied because the display reports no supported 60, 90, or 120 Hz mode."
            )
        val status = shizukuConnectionManager.refreshStatus()
        if (!status.canExecutePrivileged) {
            return ModeApplicationReport(
                mode = mode,
                shizukuAuthorized = false,
                records = emptyList(),
                summary = "Authorize Shizuku to apply the ${multiplier.label} hardware pacing profile."
            )
        }

        val record = shizukuCommandEngine.executeBatchedGamingProfile(
            mode = mode,
            targetHz = targetHz,
            lockMinRefresh = true,
            downscaleFactor = downscaleFactor,
            enableFixedPerfHint = true
        )
        val summary = if (record.isSuccess) {
            "${multiplier.label} selected: requested the supported ${targetHz.toInt()} Hz mode and ${downscaleFactor}x game downscale. This is hardware pacing, not generated Roblox frames."
        } else {
            "Hardware pacing could not be fully applied: ${record.stderr.ifBlank { record.stdout }}"
        }
        return ModeApplicationReport(
            mode = mode,
            shizukuAuthorized = true,
            records = listOf(record),
            summary = summary
        )
    }
}

internal fun highestSupportedRefreshRate(supportedRefreshRates: List<Float>): Float? =
    supportedRefreshRates.filter { it in setOf(60f, 90f, 120f) }.maxOrNull()
