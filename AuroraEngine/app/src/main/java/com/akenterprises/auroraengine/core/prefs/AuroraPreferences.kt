package com.akenterprises.auroraengine.core.prefs

import android.content.Context
import android.content.SharedPreferences
import com.akenterprises.auroraengine.core.model.FrameGenMultiplier
import com.akenterprises.auroraengine.core.model.AuroraVisualTheme
import com.akenterprises.auroraengine.core.model.LiquidFlowType
import com.akenterprises.auroraengine.core.model.PerformanceMode
import com.akenterprises.auroraengine.core.model.TouchProfile
import com.akenterprises.auroraengine.core.model.UpscalingMode

class AuroraPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("aurora_engine_prefs", Context.MODE_PRIVATE)

    var selectedMode: PerformanceMode
        get() {
            val id = prefs.getString("selected_mode", PerformanceMode.BALANCED.id)
            return PerformanceMode.entries.find { it.id == id } ?: PerformanceMode.BALANCED
        }
        set(value) = prefs.edit().putString("selected_mode", value.id).apply()

    var touchProfile: TouchProfile
        get() {
            val name = prefs.getString("touch_profile", TouchProfile.ULTRA_TOUCH.name)
            return TouchProfile.entries.find { it.name == name } ?: TouchProfile.ULTRA_TOUCH
        }
        set(value) = prefs.edit().putString("touch_profile", value.name).apply()

    var upscalingMode: UpscalingMode
        get() {
            val name = prefs.getString("upscaling_mode", UpscalingMode.SUPPORTED_CAS.name)
            return UpscalingMode.entries.find { it.name == name } ?: UpscalingMode.SUPPORTED_CAS
        }
        set(value) = prefs.edit().putString("upscaling_mode", value.name).apply()

    var frameGenMultiplier: FrameGenMultiplier
        get() {
            val mult = prefs.getInt("framegen_mult", 1)
            return FrameGenMultiplier.entries.find { it.multiplier == mult } ?: FrameGenMultiplier.OFF
        }
        set(value) = prefs.edit().putInt("framegen_mult", value.multiplier).apply()

    var visualTheme: AuroraVisualTheme
        get() = AuroraVisualTheme.entries.find {
            it.name == prefs.getString("visual_theme", AuroraVisualTheme.OXYGEN_OS_16.name)
        } ?: AuroraVisualTheme.OXYGEN_OS_16
        set(value) = prefs.edit().putString("visual_theme", value.name).apply()

    var liquidFlowType: LiquidFlowType
        get() = LiquidFlowType.entries.find {
            it.name == prefs.getString("liquid_flow_type", LiquidFlowType.FLUID_MOTION.name)
        } ?: LiquidFlowType.FLUID_MOTION
        set(value) = prefs.edit().putString("liquid_flow_type", value.name).apply()

    var autoDetectRoblox: Boolean
        get() = prefs.getBoolean("auto_detect_roblox", true)
        set(value) = prefs.edit().putBoolean("auto_detect_roblox", value).apply()

    var smoothPanelAnimations: Boolean
        get() = prefs.getBoolean("smooth_panel_animations", true)
        set(value) = prefs.edit().putBoolean("smooth_panel_animations", value).apply()

    var enableTelemetryPolling: Boolean
        get() = prefs.getBoolean("enable_telemetry_polling", true)
        set(value) = prefs.edit().putBoolean("enable_telemetry_polling", value).apply()

    var casSharpness: Float
        get() = prefs.getFloat("cas_sharpness", 0.65f)
        set(value) = prefs.edit().putFloat("cas_sharpness", value.coerceIn(0f, 1f)).apply()

    var targetRefreshRateHz: Float
        get() = prefs.getFloat("target_refresh_hz", 120f)
        set(value) = prefs.edit().putFloat("target_refresh_hz", value).apply()

    fun resetAllSettings() {
        prefs.edit().clear().commit()
    }
}
