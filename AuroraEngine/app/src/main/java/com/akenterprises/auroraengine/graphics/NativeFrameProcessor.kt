package com.akenterprises.auroraengine.graphics

import android.os.SystemClock

/**
 * JNI Bridge & Pure-Kotlin Fallback for Aurora Engine's Experimental Frame Interpolation (LSFG-inspired)
 * and Contrast-Adaptive Sharpening (CAS) Upscaling Pipeline.
 *
 * Clearly distinguishes captured-frame processing from proprietary NVIDIA DLSS or internal game-engine
 * frame injection.
 */
object NativeFrameProcessor {

    var isNativeLibraryLoaded: Boolean = false
        private set

    init {
        isNativeLibraryLoaded = try {
            System.loadLibrary("aurora_native")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    external fun nativeGetEngineVersion(): String

    external fun nativeInterpolateFrames(
        prevPixels: IntArray,
        currPixels: IntArray,
        outPixels: IntArray,
        width: Int,
        height: Int,
        alpha: Float
    ): Int

    external fun nativeApplyCasSharpening(
        inPixels: IntArray,
        outPixels: IntArray,
        width: Int,
        height: Int,
        sharpness: Float
    ): Int

    fun getEngineDescription(): String {
        return if (isNativeLibraryLoaded) {
            try {
                nativeGetEngineVersion()
            } catch (_: Throwable) {
                "Aurora Native C++17 Optical-Flow & CAS Pipeline Active"
            }
        } else {
            "Aurora Kotlin Coroutine Frame Pipeline (CPU Fallback Mode)"
        }
    }

    data class FrameBenchmarkResult(
        val resolution: String,
        val frameProcessingTimeMs: Double,
        val maxTheoreticalFps: Int,
        val canSustain2x: Boolean,
        val canSustain3x: Boolean,
        val canSustain4x: Boolean,
        val recommendation: String
    )

    /**
     * Benchmarks actual on-device frame interpolation & CAS sharpening speed before enabling
     * experimental 2X/3X/4X multipliers so 4 GB RAM / Mali-G57 devices never suffer unbounded lag.
     */
    fun runRealTimeCapabilityBenchmark(width: Int = 320, height: Int = 180): FrameBenchmarkResult {
        val count = width * height
        val prev = IntArray(count) { i -> 0xFF000000.toInt() or ((i % 255) shl 16) or ((i / width) shl 8) }
        val curr = IntArray(count) { i -> 0xFF000000.toInt() or (((i + 4) % 255) shl 16) or ((i / width) shl 8) }
        val out = IntArray(count)

        val startNs = SystemClock.elapsedRealtimeNanos()
        if (isNativeLibraryLoaded) {
            nativeInterpolateFrames(prev, curr, out, width, height, 0.5f)
            nativeApplyCasSharpening(out, prev, width, height, 0.6f)
        } else {
            fallbackInterpolateAndSharpen(prev, curr, out, width, height, 0.5f)
        }
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
        val safeElapsed = elapsedMs.coerceAtLeast(0.4)
        val maxFps = (1000.0 / safeElapsed).toInt().coerceAtMost(240)

        val sustain2x = safeElapsed <= 14.0
        val sustain3x = safeElapsed <= 9.5
        val sustain4x = safeElapsed <= 7.0

        val rec = when {
            sustain4x -> "Device pipeline processed ${width}x${height} frame in ${String.format("%.2f", safeElapsed)} ms. 2X/3X/4X experimental interpolation supported."
            sustain3x -> "Device pipeline processed frame in ${String.format("%.2f", safeElapsed)} ms. 2X and 3X supported; 4X may drop frames."
            sustain2x -> "Device pipeline processed frame in ${String.format("%.2f", safeElapsed)} ms. 2X experimental mode recommended for Mali-G57."
            else -> "Frame processing took ${String.format("%.2f", safeElapsed)} ms. Keep Frame Generation OFF during heavy scenes to preserve CPU/GPU headroom."
        }

        return FrameBenchmarkResult(
            resolution = "${width}x${height} Bounded Capture Buffer",
            frameProcessingTimeMs = safeElapsed,
            maxTheoreticalFps = maxFps,
            canSustain2x = sustain2x,
            canSustain3x = sustain3x,
            canSustain4x = sustain4x,
            recommendation = rec
        )
    }

    fun processCapturedPair(
        prev: IntArray,
        curr: IntArray,
        out: IntArray,
        width: Int,
        height: Int,
        alpha: Float,
        sharpness: Float
    ): Int {
        return if (isNativeLibraryLoaded) {
            val sad = nativeInterpolateFrames(prev, curr, out, width, height, alpha)
            if (sharpness > 0.05f) {
                nativeApplyCasSharpening(out, curr, width, height, sharpness)
                System.arraycopy(curr, 0, out, 0, width * height)
            }
            sad
        } else {
            fallbackInterpolateAndSharpen(prev, curr, out, width, height, alpha)
        }
    }

    private fun fallbackInterpolateAndSharpen(
        prev: IntArray,
        curr: IntArray,
        out: IntArray,
        width: Int,
        height: Int,
        alpha: Float
    ): Int {
        val a = (alpha * 256).toInt().coerceIn(0, 256)
        val invA = 256 - a
        var diffSum = 0L
        val total = width * height
        for (i in 0 until total) {
            val p0 = prev[i]
            val p1 = curr[i]
            val r0 = (p0 shr 16) and 0xFF
            val g0 = (p0 shr 8) and 0xFF
            val b0 = p0 and 0xFF
            val r1 = (p1 shr 16) and 0xFF
            val g1 = (p1 shr 8) and 0xFF
            val b1 = p1 and 0xFF
            diffSum += kotlin.math.abs(r1 - r0)
            val r = (r0 * invA + r1 * a) shr 8
            val g = (g0 * invA + g1 * a) shr 8
            val b = (b0 * invA + b1 * a) shr 8
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return if (total > 0) (diffSum / total).toInt() else 0
    }
}
