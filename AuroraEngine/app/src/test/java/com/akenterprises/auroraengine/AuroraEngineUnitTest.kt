package com.akenterprises.auroraengine

import com.akenterprises.auroraengine.core.model.FrameGenMultiplier
import com.akenterprises.auroraengine.core.model.PerformanceMode
import com.akenterprises.auroraengine.core.model.ShizukuStatus
import com.akenterprises.auroraengine.core.model.UpscalingMode
import org.junit.Assert.*
import org.junit.Test

class AuroraEngineUnitTest {

    @Test
    fun mandatoryShizukuGateBlocksWhenUnauthorized() {
        val disconnected = ShizukuStatus(isInstalled = true, isRunning = false, isAuthorized = false)
        assertFalse(disconnected.canExecutePrivileged)

        val unauthorized = ShizukuStatus(isInstalled = true, isRunning = true, isAuthorized = false)
        assertFalse(unauthorized.canExecutePrivileged)

        val authorized = ShizukuStatus(isInstalled = true, isRunning = true, isAuthorized = true, uid = 2000)
        assertTrue(authorized.canExecutePrivileged)
    }

    @Test
    fun performanceModesHaveDistinctVisualAccentsAndAnimationScales() {
        assertEquals(0xFF00C853, PerformanceMode.BATTERY.accentHex)
        assertEquals(0xFFFFC107, PerformanceMode.BALANCED.accentHex)
        assertEquals(0xFFFF1744, PerformanceMode.PERFORMANCE.accentHex)

        assertTrue(PerformanceMode.BALANCED.enableDecorativeUiAnimations)
        assertFalse(PerformanceMode.PERFORMANCE.enableDecorativeUiAnimations)
    }

    @Test
    fun nvidiaDlssExplicitlyMarkedUnsupportedOnMali() {
        val dlss = UpscalingMode.NVIDIA_DLSS_UNSUPPORTED
        assertTrue(dlss.badge.contains("Unsupported", ignoreCase = true))
    }

    @Test
    fun frameGenMultipliersCover2x3x4xExperimental() {
        val multipliers = FrameGenMultiplier.entries.map { it.multiplier }
        assertEquals(listOf(1, 2, 3, 4), multipliers)
    }
}
