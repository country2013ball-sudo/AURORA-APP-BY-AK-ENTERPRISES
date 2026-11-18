package com.akenterprises.auroraengine

import com.akenterprises.auroraengine.core.model.FrameGenMultiplier
import com.akenterprises.auroraengine.core.model.PerformanceMode
import com.akenterprises.auroraengine.core.model.ShizukuStatus
import com.akenterprises.auroraengine.core.model.UpscalingMode
import com.akenterprises.auroraengine.shizuku.parseGfxinfoFrameRate
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
        assertEquals(0xFF84CC16, PerformanceMode.BATTERY.accentHex)
        assertEquals(0xFF84CC16, PerformanceMode.BALANCED.accentHex)
        assertEquals(0xFFEF4444, PerformanceMode.PERFORMANCE.accentHex)

        assertEquals(0.85f, PerformanceMode.BATTERY.windowAnimationScale)
        assertEquals(0.70f, PerformanceMode.BALANCED.windowAnimationScale)
        assertEquals(0.50f, PerformanceMode.PERFORMANCE.windowAnimationScale)
        assertTrue(PerformanceMode.BALANCED.enableDecorativeUiAnimations)
        assertTrue(PerformanceMode.PERFORMANCE.enableDecorativeUiAnimations)
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

    @Test
    fun experimentalPacingMapsMultipliersToSupportedDownscaleRequests() {
        assertEquals("0.85", FrameGenMultiplier.X2.hardwareDownscale)
        assertEquals("0.8", FrameGenMultiplier.X3.hardwareDownscale)
        assertEquals("0.75", FrameGenMultiplier.X4.hardwareDownscale)
        assertNull(FrameGenMultiplier.OFF.hardwareDownscale)
    }

    @Test
    fun gfxinfoFrameRateUsesCompletedFrameTimestampsAndRejectsMissingData() {
        val sample = """
            FLAGS,INTENDED_VSYNC,VSYNC,FRAME_COMPLETED
            0,1,1,1000000000
            0,2,2,1016666667
            0,3,3,1033333334
        """.trimIndent()

        assertEquals(60f, requireNotNull(parseGfxinfoFrameRate(sample)), 0.1f)
        assertNull(parseGfxinfoFrameRate("no frame stats available"))
    }
}
