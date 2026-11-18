package com.akenterprises.auroraengine.ui.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.akenterprises.auroraengine.AuroraApplication
import com.akenterprises.auroraengine.MainActivity
import com.akenterprises.auroraengine.R
import com.akenterprises.auroraengine.monitoring.HardwareTelemetryMonitor
import com.akenterprises.auroraengine.core.model.PerformanceMode
import com.akenterprises.auroraengine.core.model.AuroraVisualTheme
import com.akenterprises.auroraengine.core.model.FrameGenMultiplier
import com.akenterprises.auroraengine.core.model.LiquidFlowType
import com.akenterprises.auroraengine.core.model.TouchProfile
import com.akenterprises.auroraengine.core.prefs.AuroraPreferences
import com.akenterprises.auroraengine.performance.PerformanceModeController
import com.akenterprises.auroraengine.rammanager.SafeRamManager
import com.akenterprises.auroraengine.roblox.RobloxOptimizationEngine
import com.akenterprises.auroraengine.shizuku.ShizukuCommandEngine
import com.akenterprises.auroraengine.shizuku.ShizukuConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var robloxEngine: RobloxOptimizationEngine
    private lateinit var telemetryMonitor: HardwareTelemetryMonitor

    private var windowManager: WindowManager? = null
    private var collapsedHandleView: View? = null
    private var expandedPanelView: View? = null
    private var isPanelExpanded = false
    private var fpsSamplingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AuroraPreferences(applicationContext)
        shizukuConnectionManager = ShizukuConnectionManager(applicationContext)
        shizukuCommandEngine = ShizukuCommandEngine(shizukuConnectionManager)
        performanceModeController = PerformanceModeController(
            prefs, shizukuConnectionManager, shizukuCommandEngine
        )
        safeRamManager = SafeRamManager(applicationContext, shizukuCommandEngine)
        robloxEngine = RobloxOptimizationEngine(
            applicationContext,
            shizukuCommandEngine,
            performanceModeController,
            safeRamManager
        )
        telemetryMonitor = HardwareTelemetryMonitor(applicationContext)
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

        val modeColor = prefs.visualTheme.accentHex.toInt()
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
        fpsSamplingJob?.cancel()
        fpsSamplingJob = null
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
            dp(360),
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
        val accentInt = prefs.visualTheme.accentHex.toInt()
        val battery = readQuickBattery()
        val batPct = battery?.first?.toString() ?: "--"
        val batTempC = battery?.second?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
        val shizukuOk = shizukuConnectionManager.refreshStatus().canExecutePrivileged

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
            text = if (shizukuOk) "SHIZUKU READY" else "SHIZUKU LOCKED"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (shizukuOk) Color.WHITE else Color.parseColor("#EF4444"))
            setOnClickListener {
                startActivity(Intent(this@EdgePanelOverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
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

        addRailButton("◉", "Performance", true) {
            serviceScope.launch {
                val report = performanceModeController.applyMode(prefs.selectedMode)
                statusTextView.text = report.summary
                showExpandedInGameMiniPanel()
            }
        }
        addRailButton("☷", "Tools", false) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow display over other apps to open Controls.", Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                val controlIntent = Intent(this, LiquidControlShadeService::class.java)
                if (LiquidControlShadeService.isOverlayRunning) {
                    stopService(controlIntent)
                } else {
                    startForegroundService(controlIntent)
                }
            }
        }
        addRailButton("AK", "Me", false) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        addRailButton("R", "Roblox", false) {
            val launchIntent = packageManager.getLaunchIntentForPackage("com.roblox.client")
            if (launchIntent == null) {
                Toast.makeText(this, "Roblox is not installed.", Toast.LENGTH_SHORT).show()
            } else {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        }

        rootRow.addView(leftRail, LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8)
        })

        val mainCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F212141B"))
                setStroke(dp(1), accentInt)
            }
        }
        val panelContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(panelContent)
        }
        val availableHeight = (resources.displayMetrics.heightPixels - dp(120)).coerceAtLeast(dp(360))
        mainCard.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                minOf(dp(620), availableHeight)
            )
        )

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val themeButton = TextView(this).apply {
            text = if (prefs.visualTheme == AuroraVisualTheme.OXYGEN_OS_16) "OP GAMING CORE" else "HYPERBOOST"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentInt)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0x26121212)
                setStroke(dp(1), accentInt)
            }
            setOnClickListener {
                prefs.visualTheme = if (prefs.visualTheme == AuroraVisualTheme.OXYGEN_OS_16) {
                    AuroraVisualTheme.COLOR_OS_17
                } else {
                    AuroraVisualTheme.OXYGEN_OS_16
                }
                showExpandedInGameMiniPanel()
            }
        }
        headerRow.addView(themeButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val shizukuBadge = TextView(this).apply {
            text = if (shizukuOk) "SHIZUKU READY" else "SHIZUKU LOCKED"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (shizukuOk) Color.WHITE else Color.parseColor("#FF6B6B"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                startActivity(Intent(this@EdgePanelOverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
        }
        headerRow.addView(shizukuBadge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        headerRow.addView(TextView(this).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(5), 0, dp(5))
            setOnClickListener { showCollapsedEdgeHandle() }
        })
        panelContent.addView(headerRow)

        val triadRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
        }

        fun buildMetricBox(title: String, bigVal: String, subVal: String): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(4), dp(2), dp(4))
                addView(TextView(context).apply {
                    text = title
                    textSize = 9f
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

        val fpsGauge = ArcMetricView(this, accentInt, "Frame rate", "FPS")
        val networkGauge = ArcMetricView(this, accentInt, "Network", "ms")
        triadRow.addView(fpsGauge, LinearLayout.LayoutParams(0, dp(88), 1f))
        val batteryLabel = if (batPct == "--") "--" else "$batPct%"
        triadRow.addView(buildMetricBox("Battery", batteryLabel, "${batTempC}°C"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        triadRow.addView(networkGauge, LinearLayout.LayoutParams(0, dp(88), 1f))
        panelContent.addView(triadRow)
        val fpsStatusView = TextView(this).apply {
            text = "Live Roblox FPS requires Shizuku and a running game."
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(0, 0, 0, dp(6))
        }
        panelContent.addView(fpsStatusView)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#1B1E28"))
            }
        }

        val modeSpecs = listOf(
            PerformanceMode.BATTERY to "Power-saving",
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
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#A0AAB8"))
                if (selected) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(15).toFloat()
                        setColor(accentInt)
                    }
                }
                setOnClickListener {
                    serviceScope.launch {
                        val rep = performanceModeController.applyMode(modeItem)
                        statusTextView.text = rep.summary
                        showExpandedInGameMiniPanel()
                    }
                }
            }
            modeRow.addView(pill, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        panelContent.addView(modeRow)

        fun addSectionTitle(title: String, subtitle: String? = null) {
            panelContent.addView(TextView(this).apply {
                text = title
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, dp(10), 0, dp(3))
            })
            if (subtitle != null) {
                panelContent.addView(TextView(this).apply {
                    text = subtitle
                    textSize = 9f
                    setTextColor(Color.parseColor("#9AA4B2"))
                    setPadding(0, 0, 0, dp(5))
                })
            }
        }

        fun addChoiceRow(
            labels: List<String>,
            selectedLabel: String?,
            enabledLabels: Set<String> = labels.toSet(),
            onSelected: (String) -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            labels.forEachIndexed { index, label ->
                val enabled = label in enabledLabels
                val selected = label == selectedLabel
                val choice = TextView(this).apply {
                    text = label
                    textSize = 9.5f
                    gravity = Gravity.CENTER
                    typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    setTextColor(
                        when {
                            !enabled -> Color.parseColor("#5A6270")
                            selected -> Color.WHITE
                            else -> Color.parseColor("#D0D7E2")
                        }
                    )
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (selected) accentInt else Color.parseColor("#1B1E28"))
                        setStroke(
                            dp(1),
                            if (selected) accentInt else Color.parseColor("#2A2E3D")
                        )
                    }
                    isEnabled = enabled
                    setOnClickListener { onSelected(label) }
                }
                row.addView(
                    choice,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index < labels.lastIndex) rightMargin = dp(5)
                    }
                )
            }
            panelContent.addView(row)
        }

        addSectionTitle(
            "Touch response",
            "May increase device power consumption and temperature."
        )
        addChoiceRow(
            labels = listOf("Standard", "Ultra touch"),
            selectedLabel = if (prefs.touchProfile == TouchProfile.STANDARD) "Standard" else "Ultra touch"
        ) { label ->
            val touchProfile = if (label == "Standard") TouchProfile.STANDARD else TouchProfile.ULTRA_TOUCH
            prefs.touchProfile = touchProfile
            serviceScope.launch {
                val report = performanceModeController.applyMode(prefs.selectedMode, touchProfile)
                statusTextView.text = report.summary
                showExpandedInGameMiniPanel()
            }
        }

        addSectionTitle("Liquid Flow Engine")
        addChoiceRow(
            labels = listOf("Fluid Motion", "Fluid Fusion"),
            selectedLabel = prefs.liquidFlowType.label
        ) { label ->
            prefs.liquidFlowType = if (label == "Fluid Motion") {
                LiquidFlowType.FLUID_MOTION
            } else {
                LiquidFlowType.FLUID_FUSION
            }
            showExpandedInGameMiniPanel()
        }

        addSectionTitle("Screen refresh rate")
        val supportedRates = supportedRefreshRatesHz()
        val supportedRateLabels = supportedRates
            .filter { it in listOf(60f, 90f, 120f) }
            .map { "${it.toInt()} Hz" }
            .toSet()
        addChoiceRow(
            labels = listOf("60 Hz", "90 Hz", "120 Hz"),
            selectedLabel = prefs.targetRefreshRateHz.takeIf { it in supportedRates }?.let { "${it.toInt()} Hz" },
            enabledLabels = supportedRateLabels
        ) { label ->
            val refreshRate = label.removeSuffix(" Hz").toFloat()
            prefs.targetRefreshRateHz = refreshRate
            serviceScope.launch {
                val report = performanceModeController.applyMode(
                    prefs.selectedMode,
                    prefs.touchProfile,
                    supportedRates,
                    refreshRate
                )
                statusTextView.text = report.summary
                showExpandedInGameMiniPanel()
            }
        }

        addSectionTitle("Aurora Frame Generation", "Capture analysis does not replace Roblox frames.")
        addChoiceRow(
            labels = listOf("OFF", "2X", "3X"),
            selectedLabel = prefs.frameGenMultiplier.label
        ) { label ->
            val multiplier = when (label) {
                "2X" -> FrameGenMultiplier.X2
                "3X" -> FrameGenMultiplier.X3
                else -> FrameGenMultiplier.OFF
            }
            prefs.frameGenMultiplier = multiplier
            serviceScope.launch {
                val report = if (multiplier == FrameGenMultiplier.OFF) {
                    performanceModeController.applyMode(prefs.selectedMode)
                } else {
                    performanceModeController.applyFramePacing(multiplier)
                }
                statusTextView.text = report?.summary ?: "Frame pacing is turned off."
                showExpandedInGameMiniPanel()
            }
        }

        val applyButton = TextView(this).apply {
            text = "CHANGE / APPLY (2-3s)"
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accentInt)
            }
            setOnClickListener {
                isEnabled = false
                text = "APPLYING..."
                serviceScope.launch {
                    delay(2_500L)
                    val targetRefreshRate = prefs.targetRefreshRateHz
                        .takeIf { it in supportedRates }
                    val report = performanceModeController.applyMode(
                        prefs.selectedMode,
                        prefs.touchProfile,
                        supportedRates,
                        targetRefreshRate
                    )
                    statusTextView.text = report.summary
                    isEnabled = true
                    text = "CHANGE / APPLY (2-3s)"
                }
            }
        }
        panelContent.addView(applyButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val startGamingButton = TextView(this).apply {
            text = "START GAMING"
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accentInt)
            }
            setOnClickListener {
                val launchIntent = packageManager.getLaunchIntentForPackage("com.roblox.client")
                if (launchIntent == null) {
                    statusTextView.text = "Roblox is not installed."
                } else {
                    serviceScope.launch {
                        val report = performanceModeController.applyMode(
                            prefs.selectedMode,
                            prefs.touchProfile,
                            supportedRates,
                            prefs.targetRefreshRateHz.takeIf { it in supportedRates }
                        )
                        statusTextView.text = report.summary
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                    }
                }
            }
        }
        panelContent.addView(startGamingButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        statusTextView.setPadding(0, dp(8), 0, 0)
        panelContent.addView(statusTextView)

        rootRow.addView(mainCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        expandedPanelView = rootRow
        isPanelExpanded = true
        try {
            wm.addView(rootRow, params)
            startRealtimeFpsSampling(fpsGauge, networkGauge, fpsStatusView)
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Could not attach the expanded panel.", e)
            showCollapsedEdgeHandle()
        } catch (e: SecurityException) {
            Log.e(TAG, "Overlay permission was revoked before the panel was attached.", e)
            stopSelf()
        }
    }

    private fun readQuickBattery(): Pair<Int, Float>? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        if (level < 0 || scale <= 0 || temp < 0) return null
        return ((level * 100) / scale) to (temp / 10f)
    }

    @Suppress("DEPRECATION")
    private fun supportedRefreshRatesHz(): List<Float> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return wm.defaultDisplay.supportedModes
            .map { it.refreshRate }
            .distinct()
            .sorted()
    }

    private fun startRealtimeFpsSampling(
        fpsGauge: ArcMetricView,
        networkGauge: ArcMetricView,
        statusView: TextView
    ) {
        fpsSamplingJob?.cancel()
        val panel = expandedPanelView ?: return
        fpsSamplingJob = serviceScope.launch {
            var sampleCount = 0
            var lastNetworkLatencyMs: Float? = null
            while (isActive && expandedPanelView === panel) {
                val sample = withContext(Dispatchers.IO) {
                    val networkLatencyMs = if (sampleCount % 4 == 0) {
                        telemetryMonitor.sampleNetworkLatencyMs()
                    } else {
                        null
                    }
                    if (!shizukuConnectionManager.refreshStatus().canExecutePrivileged) {
                        FpsSamplingResult(false, false, null, networkLatencyMs)
                    } else {
                        val robloxRunning = robloxEngine.inspectRobloxStatus().isRunning
                        val fps = if (robloxRunning) shizukuCommandEngine.sampleRobloxFrameRate() else null
                        FpsSamplingResult(true, robloxRunning, fps, networkLatencyMs)
                    }
                }
                if (sampleCount % 4 == 0) {
                    lastNetworkLatencyMs = sample.networkLatencyMs
                }
                fpsGauge.setMetric(
                    value = sample.fps?.toInt()?.toString() ?: "--",
                    progress = sample.fps?.div(240f)
                )
                networkGauge.setMetric(
                    value = lastNetworkLatencyMs?.let { String.format(Locale.US, "%.0f", it) } ?: "--",
                    progress = lastNetworkLatencyMs?.let { 1f - it / 120f }
                )
                statusView.text = when {
                    !sample.authorized -> "Authorize Shizuku to read live Roblox FPS."
                    !sample.robloxRunning -> "Open Roblox to display live FPS."
                    sample.fps == null -> "Roblox FPS is currently unavailable."
                    else -> "Live Roblox frame rate"
                }
                sampleCount += 1
                delay(1_000L)
            }
        }
    }

    private data class FpsSamplingResult(
        val authorized: Boolean,
        val robloxRunning: Boolean,
        val fps: Float?,
        val networkLatencyMs: Float?
    )

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

private class ArcMetricView(
    context: Context,
    private val accentColor: Int,
    private val title: String,
    private val unit: String
) : View(context) {
    private val density = resources.displayMetrics.density
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A0AAB8")
        textAlign = Paint.Align.CENTER
        textSize = 9f * density
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 19f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        textAlign = Paint.Align.CENTER
        textSize = 8f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private var metricValue = "--"
    private var progress: Float? = null

    fun setMetric(value: String, progress: Float?) {
        metricValue = value
        this.progress = progress?.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = arcPaint.strokeWidth
        val inset = stroke / 2f + 2f * density
        val bounds = RectF(inset, inset, width - inset, height - inset)
        val centerX = width / 2f
        val centerY = height / 2f

        arcPaint.color = Color.parseColor("#34343D")
        canvas.drawArc(bounds, 135f, 270f, false, arcPaint)

        progress?.let {
            arcPaint.color = accentColor
            canvas.drawArc(bounds, 135f, 270f * it, false, arcPaint)
        }

        canvas.drawText(title, centerX, centerY - 12f * density, labelPaint)
        canvas.drawText(metricValue, centerX, centerY + 6f * density, valuePaint)
        canvas.drawText(unit, centerX, centerY + 20f * density, unitPaint)
    }
}
