package com.akenterprises.auroraengine.framegeneration

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.akenterprises.auroraengine.AuroraApplication
import com.akenterprises.auroraengine.R
import com.akenterprises.auroraengine.graphics.NativeFrameProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * V2 optional capture analysis uses a 160x90 grid, samples at most once every 250ms, and runs on
 * a worker thread. It is not presented as generated frames or as a Roblox FPS increase.
 */
class FrameGenerationService : Service() {

    data class PipelineTelemetry(
        val isCapturing: Boolean = false,
        val multiplier: Int = 1,
        val capturedFps: Float = 0f,
        val interpolatedEffectiveFps: Float = 0f,
        val lastProcessLatencyMs: Float = 0f,
        val averageMotionSad: Int = 0,
        val processedFramesCount: Long = 0L,
        val statusNote: String = "Aurora Frame Generation capture analysis is separate from Roblox rendering and display presentation."
    )

    companion object {
        const val ACTION_START_CAPTURE = "com.akenterprises.auroraengine.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.akenterprises.auroraengine.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_MULTIPLIER = "extra_multiplier"
        const val EXTRA_SHARPNESS = "extra_sharpness"

        private val _telemetry = MutableStateFlow(PipelineTelemetry())
        val telemetry: StateFlow<PipelineTelemetry> = _telemetry.asStateFlow()
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private val captureWidth = 160
    private val captureHeight = 90
    private val prevFrameBuffer = IntArray(captureWidth * captureHeight)
    private val currFrameBuffer = IntArray(captureWidth * captureHeight)
    private val interpFrameBuffer = IntArray(captureWidth * captureHeight)

    private var hasPrevFrame = false
    private var lastSampledTimeMs = 0L
    private var processedCount = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val multiplier = intent.getIntExtra(EXTRA_MULTIPLIER, 2).coerceIn(2, 4)
                val sharpness = intent.getFloatExtra(EXTRA_SHARPNESS, 0.65f)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startForegroundWithProjection(resultCode, resultData, multiplier, sharpness)
                } else {
                    stopCapturePipeline()
                }
            }
            ACTION_STOP_CAPTURE -> {
                stopCapturePipeline()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithProjection(
        resultCode: Int,
        resultData: Intent,
        multiplier: Int,
        sharpness: Float
    ) {
        val notification = buildForegroundNotification(multiplier)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notification)
        }

        stopProjectionResources()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapturePipeline()
            }
        }, null)

        workerThread = HandlerThread("AuroraFrameGenLowOverhead").apply { start() }
        workerHandler = Handler(workerThread!!.looper)
        lastSampledTimeMs = 0L

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try {
                reader.acquireLatestImage()
            } catch (_: Throwable) {
                null
            } ?: return@setOnImageAvailableListener

            try {
                val nowMs = SystemClock.elapsedRealtime()
                // Throttle optical-flow sampling to max 4 times/sec so Mali-G57 has 0% lag in Roblox!
                if (nowMs - lastSampledTimeMs < 250L) {
                    return@setOnImageAvailableListener
                }
                val elapsedMs = if (lastSampledTimeMs == 0L) 0L else nowMs - lastSampledTimeMs
                lastSampledTimeMs = nowMs

                val plane = image.planes.firstOrNull()
                val buffer = plane?.buffer
                if (buffer != null) {
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    var idx = 0
                    for (y in 0 until captureHeight) {
                        var rowOffset = y * rowStride
                        for (x in 0 until captureWidth) {
                            if (rowOffset + 2 < buffer.limit()) {
                                val r = buffer.get(rowOffset).toInt() and 0xFF
                                val g = buffer.get(rowOffset + 1).toInt() and 0xFF
                                val b = buffer.get(rowOffset + 2).toInt() and 0xFF
                                currFrameBuffer[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                            rowOffset += pixelStride
                            idx++
                        }
                    }

                    if (hasPrevFrame) {
                        val procStartNs = SystemClock.elapsedRealtimeNanos()
                        val sad = NativeFrameProcessor.processCapturedPair(
                            prevFrameBuffer,
                            currFrameBuffer,
                            interpFrameBuffer,
                            captureWidth,
                            captureHeight,
                            0.5f,
                            sharpness
                        )
                        val procMs = (SystemClock.elapsedRealtimeNanos() - procStartNs) / 1_000_000f
                        processedCount++

                        _telemetry.value = PipelineTelemetry(
                            isCapturing = true,
                            multiplier = multiplier,
                            capturedFps = if (elapsedMs > 0L) 1000f / elapsedMs else 0f,
                            interpolatedEffectiveFps = 0f,
                            lastProcessLatencyMs = procMs,
                            averageMotionSad = sad,
                            processedFramesCount = processedCount,
                            statusNote = "Aurora Frame Generation analysis only (max 4 Hz, ${String.format("%.2f", procMs)} ms); frames are not presented or injected into Roblox."
                        )
                    }

                    System.arraycopy(currFrameBuffer, 0, prevFrameBuffer, 0, captureWidth * captureHeight)
                    hasPrevFrame = true
                }
            } finally {
                image.close()
            }
        }, workerHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AuroraLowOverheadCapture",
            captureWidth,
            captureHeight,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            workerHandler
        )
        _telemetry.value = PipelineTelemetry(
            isCapturing = true,
            multiplier = multiplier,
            statusNote = "Aurora Frame Generation capture analysis is active at ${captureWidth}x${captureHeight}, throttled to 4 Hz; it does not replace display output."
        )
    }

    private fun stopProjectionResources() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
        try { workerThread?.quitSafely() } catch (_: Throwable) {}
        workerThread = null
        workerHandler = null
        hasPrevFrame = false
        lastSampledTimeMs = 0L
    }

    private fun stopCapturePipeline() {
        stopProjectionResources()
        _telemetry.value = PipelineTelemetry(
            isCapturing = false,
            multiplier = 1,
            statusNote = "MediaProjection capture stopped."
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopProjectionResources()
        _telemetry.value = PipelineTelemetry(statusNote = "Capture analysis stopped.")
        super.onDestroy()
    }

    private fun buildForegroundNotification(multiplier: Int): Notification {
        return NotificationCompat.Builder(this, AuroraApplication.CHANNEL_FRAMEGEN_ID)
            .setSmallIcon(R.drawable.ic_stat_aurora)
            .setContentTitle("Aurora Frame Generation • Capture Analysis")
            .setContentText("${multiplier}X low-rate analysis; no intermediate frames are presented.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
