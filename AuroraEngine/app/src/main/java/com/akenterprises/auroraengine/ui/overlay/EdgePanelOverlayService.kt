package com.akenterprises.auroraengine.ui.overlay

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.akenterprises.auroraengine.AuroraApplication
import com.akenterprises.auroraengine.MainActivity
import com.akenterprises.auroraengine.R
import com.akenterprises.auroraengine.core.model.PerformanceMode
import com.akenterprises.auroraengine.core.model.TouchProfile
import com.akenterprises.auroraengine.core.model.UpscalingMode
import com.akenterprises.auroraengine.core.prefs.AuroraPreferences
import com.akenterprises.auroraengine.performance.PerformanceModeController
import com.akenterprises.auroraengine.rammanager.SafeRamManager
import com.akenterprises.auroraengine.shizuku.ShizukuCommandEngine
import com.akenterprises.auroraengine.shizuku.ShizukuConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * V2 True In-Game Floating Mini Edge-Panel Overlay Service.
 *
 * CRITICAL FIX FOR USER:
 * - Tapping the left-edge `AE` trigger handle NO LONGER launches MainActivity or pulls you out of Roblox!
 * - Instead, it toggles an ultra-lightweight, hardware-accelerated In-Game Mini Edge-Panel
 *   (modeled after OxygenOS 16 OP Gaming Core & ColorOS 17 HyperBoost) directly on top of your game.
 * - Uses native Android Views (zero Jetpack Compose memory/JIT overhead in the overlay) so
 *   opening/closing the panel mid-game is 100% instant and stutter-free on 4 GB RAM devices.
 */
class EdgePanelOverlayService : Service() {

    companion object {
        private const val TAG = "AuroraEdgeOverlay"

        var isOverlayRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var prefs: AuroraPreferences
    private lateinit var shizukuConnectionManager: ShizukuConnectionManager
    private lateinit var shizukuCommandEngine: ShizukuCommandEngine
    private lateinit var performanceModeController: PerformanceModeController
    private lateinit var safeRamManager: SafeRamManager

    private var windowManager: WindowManager? = null
    private var collapsedHandleView: View? = null
    private var expandedPanelView: View? = null
    private var isPanelExpanded = false

    override fun onCreate() {
        super.onCreate()
        prefs = AuroraPreferences(applicationContext)
        shizukuConnectionManager = ShizukuConnectionManager(applicationContext)
        shizukuCommandEngine = ShizukuCommandEngine(shizukuConnectionManager)
        performanceModeController = PerformanceModeController(
            prefs, shizukuConnectionManager, shizukuCommandEngine
        )
        safeRamManager = SafeRamManager(applicationContext, shizukuCommandEngine)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1002, notification)
        }

        if (collapsedHandleView == null) {
            showCollapsedEdgeHandle()
        }
        isOverlayRunning = true
        return START_STICKY
    }

    private fun overlayLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCollapsedEdgeHandle() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        removeExpandedPanel()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = 0
            y = -dp(90)
        }

        val modeColor = prefs.selectedMode.accentHex.toInt()
        val handle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(22), dp(10), dp(22))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    0f, 0f,
                    dp(18).toFloat(), dp(18).toFloat(),
                    dp(18).toFloat(), dp(18).toFloat(),
                    0f, 0f
                )
                setColor(Color.parseColor("#E612141C"))
                setStroke(dp(2), modeColor)
            }

            val label = TextView(context).apply {
                text = "AE"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FFD4AF37"))
            }
            addView(label)
        }

        var initialY = 0
        var touchStartY = 0f
        var moved = false

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    touchStartY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - touchStartY).toInt()
                    if (kotlin.math.abs(dy) > dp(8)) {
                        moved = true
                        params.y = initialY + dy
                        try {
                            windowManager?.updateViewLayout(handle, params)
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "Could not reposition the edge handle.", e)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // Expand Mini Edge-Panel IN-GAME without launching MainActivity!
                        showExpandedInGameMiniPanel()
                    }
                    true
                }
                else -> false
            }
        }

        collapsedHandleView = handle
        isPanelExpanded = false
        try {
            windowManager?.addView(handle, params)
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Could not attach the edge handle.", e)
            stopSelf()
        } catch (e: SecurityException) {
            Log.e(TAG, "Overlay permission was revoked before the edge handle was attached.", e)
            stopSelf()
        }
    }

    private fun removeCollapsedHandle() {
        collapsedHandleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "The edge handle was already detached.", e)
            }
        }
        collapsedHandleView = null
    }

    private fun removeExpandedPanel() {
        expandedPanelView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "The expanded panel was already detached.", e)
            }
        }
        expandedPanelView = null
    }

    /**
     * Builds and displays the OxygenOS 16 / ColorOS 17 Mini Edge-Panel directly on top of the
     * active game using fast native Views. Tapping outside or tapping 'Hide' collapses back to the handle.
     */
    private fun showExpandedInGameMiniPanel() {
        removeCollapsedHandle()
        removeExpandedPanel()

        val wm = windowManager ?: return
        val params = WindowManager.LayoutParams(
            dp(330),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = dp(10)
            y = 0
        }

        val currentMode = prefs.selectedMode
        val accentInt = currentMode.accentHex.toInt()
        val (availRamMb, totalRamMb) = readQuickRamMb()
        val battery = readQuickBattery()
        val batPct = battery?.first?.toString() ?: "--"
        val batTempC = battery?.second?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
        val shizukuOk = shizukuConnectionManager.refreshStatus().canExecutePrivileged

        // Outer horizontal container: Left Mini Rail + Right Mini Card (matches 3372.jpg reference)
        val rootRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    showCollapsedEdgeHandle()
                    true
                } else {
                    false
                }
            }
        }

        // 1. LEFT VERTICAL MINI RAIL PILL
        val leftRail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(14), dp(8), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor("#F212141B"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }

        val statusTextView = TextView(this).apply {
            text = if (shizukuOk) "Shizuku ADB Active • In-Game Overlay" else "Shizuku Locked • Tap App to Authorize"
            textSize = 10f
            setTextColor(if (shizukuOk) Color.parseColor("#00E676") else Color.parseColor("#FF8A80"))
        }

        fun addRailButton(symbol: String, label: String, active: Boolean, onClick: () -> Unit) {
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                if (active) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.parseColor("#26FFFFFF"))
                    }
                }
                setOnClickListener { onClick() }
            }
            btn.addView(TextView(this).apply {
                text = symbol
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (active) accentInt else Color.parseColor("#9AA4B2"))
            })
            btn.addView(TextView(this).apply {
                text = label
                textSize = 8.5f
                gravity = Gravity.CENTER
                setTextColor(if (active) accentInt else Color.parseColor("#9AA4B2"))
            })
            leftRail.addView(btn)
        }

        addRailButton("((•))", "Boost", true) {
            serviceScope.launch {
                val report = performanceModeController.applyMode(prefs.selectedMode)
                statusTextView.text = report.summary
                showExpandedInGameMiniPanel()
            }
        }
        addRailButton("▤", "RAM Purge", false) {
            serviceScope.launch {
                val res = safeRamManager.performSafeMemoryOptimization(includeShizukuTrim = false)
                statusTextView.text = "RAM Purged: +${res.reclaimedMb} MB (${res.afterAvailableMb} MB free)"
            }
        }
        addRailButton("⌂", "Full App", false) {
            showCollapsedEdgeHandle()
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launch)
        }
        addRailButton("✕", "Hide", false) {
            showCollapsedEdgeHandle()
        }

        rootRow.addView(leftRail, LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8)
        })

        // 2. RIGHT MAIN FLOATING MINI CARD
        val mainCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F212141B"))
                setStroke(dp(1), accentInt)
            }
        }

        // Top Header Row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = "AURORA HYPERBOOST MINI"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFD4AF37"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        headerRow.addView(TextView(this).apply {
            text = "Collapse ✕"
            textSize = 10f
            setTextColor(Color.parseColor("#A0AAB8"))
            setOnClickListener { showCollapsedEdgeHandle() }
        })
        mainCard.addView(headerRow)

        // Telemetry Triad Row (FPS Target | Battery & Temp | Free RAM)
        val triadRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }

        fun buildMetricBox(title: String, bigVal: String, subVal: String): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(context).apply {
                    text = title
                    textSize = 9.5f
                    setTextColor(Color.parseColor("#9AA4B2"))
                })
                addView(TextView(context).apply {
                    text = bigVal
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                })
                addView(TextView(context).apply {
                    text = subVal
                    textSize = 9f
                    setTextColor(accentInt)
                })
            }
        }

        val targetFpsStr = when (currentMode) {
            PerformanceMode.BATTERY -> "60"
            PerformanceMode.BALANCED -> "90"
            PerformanceMode.PERFORMANCE -> "120"
        }
        triadRow.addView(buildMetricBox("Refresh", targetFpsStr, "Hz Target"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        triadRow.addView(buildMetricBox("Battery", "$batPct%", "${batTempC}°C"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        triadRow.addView(buildMetricBox("Free RAM", "$availRamMb", "/ $totalRamMb MB"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        mainCard.addView(triadRow)

        // 3-Mode Selector Pill Row (Power-saving | Balanced | Pro Gamer)
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#1B1E28"))
            }
        }

        val modeSpecs = listOf(
            PerformanceMode.BATTERY to "Battery",
            PerformanceMode.BALANCED to "Balanced",
            PerformanceMode.PERFORMANCE to "Pro Gamer"
        )
        for ((modeItem, label) in modeSpecs) {
            val selected = modeItem == currentMode
            val pill = TextView(this).apply {
                text = label
                textSize = 10.5f
                gravity = Gravity.CENTER
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(dp(4), dp(7), dp(4), dp(7))
                setTextColor(
                    if (selected && modeItem != PerformanceMode.PERFORMANCE) Color.parseColor("#090A0F")
                    else if (selected) Color.WHITE
                    else Color.parseColor("#A0AAB8")
                )
                if (selected) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(15).toFloat()
                        setColor(modeItem.accentHex.toInt())
                    }
                }
                setOnClickListener {
                    serviceScope.launch {
                        val rep = performanceModeController.applyMode(modeItem)
                        statusTextView.text = rep.summary
                        showExpandedInGameMiniPanel() // Re-render accent instantly
                    }
                }
            }
            modeRow.addView(pill, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        mainCard.addView(modeRow)

        // Touch Response & Hardware Scaling Row
        val touchHeader = TextView(this).apply {
            text = "Touch & Hardware Scaling"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(4))
        }
        mainCard.addView(touchHeader)

        val quickActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val isUltraTouch = prefs.touchProfile == TouchProfile.ULTRA_TOUCH
        val touchBtn = TextView(this).apply {
            text = if (isUltraTouch) "Touch: Ultra ✓" else "Touch: Standard"
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setTextColor(if (isUltraTouch) accentInt else Color.parseColor("#D0D7E2"))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1B1E28"))
                setStroke(dp(1), if (isUltraTouch) accentInt else Color.parseColor("#2A2E3D"))
            }
            setOnClickListener {
                val next = if (isUltraTouch) TouchProfile.STANDARD else TouchProfile.ULTRA_TOUCH
                prefs.touchProfile = next
                serviceScope.launch {
                    performanceModeController.applyMode(prefs.selectedMode, next)
                    showExpandedInGameMiniPanel()
                }
            }
        }

        val isHwScaleActive = prefs.upscalingMode != UpscalingMode.NATIVE
        val hwScaleLabel = if (isHwScaleActive) {
            when (prefs.upscalingMode) {
                UpscalingMode.EXPERIMENTAL_AI -> "HW Scale: 0.75x ✓"
                UpscalingMode.SUPPORTED_CAS -> "HW Scale: mode-dependent ✓"
                else -> "HW Scale: active ✓"
            }
        } else {
            "HW Scale: 100%"
        }
        val scaleBtn = TextView(this).apply {
            text = hwScaleLabel
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setTextColor(if (isHwScaleActive) accentInt else Color.parseColor("#D0D7E2"))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1B1E28"))
                setStroke(dp(1), if (isHwScaleActive) accentInt else Color.parseColor("#2A2E3D"))
            }
            setOnClickListener {
                val nextScale = if (isHwScaleActive) UpscalingMode.NATIVE else UpscalingMode.EXPERIMENTAL_AI
                prefs.upscalingMode = nextScale
                serviceScope.launch {
                    val rep = performanceModeController.applyMode(prefs.selectedMode)
                    statusTextView.text = rep.summary
                    showExpandedInGameMiniPanel()
                }
            }
        }

        quickActionRow.addView(touchBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(6)
        })
        quickActionRow.addView(scaleBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        mainCard.addView(quickActionRow)

        // Status Footer inside Mini Card
        statusTextView.setPadding(0, dp(8), 0, 0)
        mainCard.addView(statusTextView)

        rootRow.addView(mainCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        expandedPanelView = rootRow
        isPanelExpanded = true
        try {
            wm.addView(rootRow, params)
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Could not attach the expanded panel.", e)
            showCollapsedEdgeHandle()
        } catch (e: SecurityException) {
            Log.e(TAG, "Overlay permission was revoked before the panel was attached.", e)
            stopSelf()
        }
    }

    private fun readQuickRamMb(): Pair<Long, Long> {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return (mi.availMem / (1024L * 1024L)) to (mi.totalMem / (1024L * 1024L))
    }

    private fun readQuickBattery(): Pair<Int, Float>? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        if (level < 0 || scale <= 0 || temp < 0) return null
        return ((level * 100) / scale) to (temp / 10f)
    }

    override fun onDestroy() {
        isOverlayRunning = false
        removeCollapsedHandle()
        removeExpandedPanel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, AuroraApplication.CHANNEL_OVERLAY_ID)
            .setSmallIcon(R.drawable.ic_stat_aurora)
            .setContentTitle("Aurora In-Game Mini Edge-Panel Active")
            .setContentText("Tap the left-edge AE pill in Roblox to open the mini gaming panel.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
