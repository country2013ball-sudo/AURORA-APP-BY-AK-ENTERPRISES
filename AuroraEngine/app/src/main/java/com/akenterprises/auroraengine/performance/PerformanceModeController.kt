package com.akenterprises.auroraengine.performance

import com.akenterprises.auroraengine.core.model.CommandExecutionRecord
import com.akenterprises.auroraengine.core.model.PerformanceMode
import com.akenterprises.auroraengine.core.model.TouchProfile
import com.akenterprises.auroraengine.core.prefs.AuroraPreferences
import com.akenterprises.auroraengine.shizuku.ShizukuCommandEngine
import com.akenterprises.auroraengine.shizuku.ShizukuConnectionManager

/**
 * Coordinates the Three Main Operating Modes:
 * - MODE 1: BATTERY MODE (Dark Green accents, conservative scheduling, 0.75x animations, 60Hz target)
 * - MODE 2: BALANCED MODE (Metallic Gold accents, polished 1.0x desktop-grade animations, 90Hz target)
 * - MODE 3: PERFORMANCE MODE (Dark Red accents, 0.5x fast transitions, decorative animations disabled, 120Hz target)
 */
class PerformanceModeController(
    private val prefs: AuroraPreferences,
    private val shizukuConnectionManager: ShizukuConnectionManager,
    private val shizukuCommandEngine: ShizukuCommandEngine
) {

    data class ModeApplicationReport(
        val mode: PerformanceMode,
        val shizukuAuthorized: Boolean,
        val records: List<CommandExecutionRecord>,
        val summary: String
    )

    suspend fun applyMode(
        mode: PerformanceMode,
        touchProfile: TouchProfile = prefs.touchProfile,
        supportedRefreshRates: List<Float> = listOf(60f, 90f, 120f)
    ): ModeApplicationReport {
        prefs.selectedMode = mode
        prefs.touchProfile = touchProfile
        prefs.smoothPanelAnimations = mode.enableDecorativeUiAnimations

        val shizukuStatus = shizukuConnectionManager.refreshStatus()
        if (!shizukuStatus.canExecutePrivileged) {
            return ModeApplicationReport(
                mode = mode,
                shizukuAuthorized = false,
                records = emptyList(),
                summary = "${mode.title} UI & local lifecycle profile active. Connect and authorize Shizuku to apply system-level animation scales, refresh rate hints, and Android GameMode interventions."
            )
        }

        val executed = mutableListOf<CommandExecutionRecord>()

        // 1. Apply window, transition, and animator scales
        val scaleStr = mode.windowAnimationScale.toString()
        executed += shizukuCommandEngine.executeWhitelisted("window_anim_scale", scaleStr)
        executed += shizukuCommandEngine.executeWhitelisted("transition_anim_scale", scaleStr)
        executed += shizukuCommandEngine.executeWhitelisted("animator_duration_scale", scaleStr)

        // 2. Apply Android 12+ GameMode intervention for Roblox (3=Battery, 1=Standard/Balanced, 2=Performance)
        val gameModeCode = when (mode) {
            PerformanceMode.BATTERY -> "3"
            PerformanceMode.BALANCED -> "1"
            PerformanceMode.PERFORMANCE -> "2"
        }
        executed += shizukuCommandEngine.executeWhitelisted("game_mode_intervention", gameModeCode)

        // 3. Apply safe display refresh rate bounds based on actual panel support
        val targetHz = when (mode) {
            PerformanceMode.BATTERY -> 60.0f
            PerformanceMode.BALANCED -> if (supportedRefreshRates.any { it >= 90f }) 90.0f else 60.0f
            PerformanceMode.PERFORMANCE -> when {
                supportedRefreshRates.any { it >= 120f } -> 120.0f
                supportedRefreshRates.any { it >= 90f } -> 90.0f
                else -> 60.0f
            }
        }
        val hzArg = String.format("%.1f", targetHz)
        executed += shizukuCommandEngine.executeWhitelisted("peak_refresh_rate", hzArg)
        if (mode == PerformanceMode.PERFORMANCE || touchProfile == TouchProfile.ULTRA_TOUCH) {
            executed += shizukuCommandEngine.executeWhitelisted("min_refresh_rate", hzArg)
        } else {
            executed += shizukuCommandEngine.executeWhitelisted("min_refresh_rate", "60.0")
        }

        val okCount = executed.count { it.isSuccess }
        return ModeApplicationReport(
            mode = mode,
            shizukuAuthorized = true,
            records = executed,
            summary = "${mode.title} applied: $okCount/${executed.size} Shizuku-authorized optimizations succeeded (Target ${targetHz.toInt()} Hz, Touch: ${touchProfile.label})."
        )
    }
}
