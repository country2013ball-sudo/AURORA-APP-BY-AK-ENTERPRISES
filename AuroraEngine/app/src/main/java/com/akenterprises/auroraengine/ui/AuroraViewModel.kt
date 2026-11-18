package com.akenterprises.auroraengine.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.akenterprises.auroraengine.core.model.*
import com.akenterprises.auroraengine.core.prefs.AuroraPreferences
import com.akenterprises.auroraengine.framegeneration.FrameGenerationService
import com.akenterprises.auroraengine.graphics.NativeFrameProcessor
import com.akenterprises.auroraengine.monitoring.HardwareTelemetryMonitor
import com.akenterprises.auroraengine.performance.PerformanceModeController
import com.akenterprises.auroraengine.rammanager.SafeRamManager
import com.akenterprises.auroraengine.roblox.RobloxOptimizationEngine
import com.akenterprises.auroraengine.shizuku.ShizukuCommandEngine
import com.akenterprises.auroraengine.shizuku.ShizukuConnectionManager
import com.akenterprises.auroraengine.ui.overlay.EdgePanelOverlayService
import com.akenterprises.auroraengine.ui.overlay.LiquidControlShadeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AuroraUiState(
    val selectedTab: EdgeTab = EdgeTab.PERFORMANCE,
    val isEdgePanelExpanded: Boolean = true,
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
    val visualTheme: AuroraVisualTheme = AuroraVisualTheme.OXYGEN_OS_16,
    val liquidFlowType: LiquidFlowType = LiquidFlowType.FLUID_MOTION,
    val touchProfile: TouchProfile = TouchProfile.ULTRA_TOUCH,
    val upscalingMode: UpscalingMode = UpscalingMode.SUPPORTED_CAS,
    val frameGenMultiplier: FrameGenMultiplier = FrameGenMultiplier.OFF,
    val casSharpness: Float = 0.65f,
    val autoDetectRoblox: Boolean = true,
    val smoothAnimations: Boolean = true,
    val telemetryPolling: Boolean = true,
    val shizukuStatus: ShizukuStatus = ShizukuStatus(),
    val hardwareProfile: DeviceHardwareProfile? = null,
    val robloxStatus: RobloxSessionStatus = RobloxSessionStatus(
        isInstalled = false,
        versionName = "Checking...",
        isRunning = false,
        pid = null,
        sessionActive = false,
        appliedOptimizationsCount = 0,
        statusSummary = "Ready"
    ),
    val frameBenchmark: NativeFrameProcessor.FrameBenchmarkResult? = null,
    val lastRamCleanSummary: String? = null,
    val statusBannerMessage: String = "Aurora Engine v2.0 Smooth Edition Ready.",
    val overlayPermissionGranted: Boolean = false,
    val isOverlayServiceActive: Boolean = false,
    val canWriteSystemSettings: Boolean = false,
    val systemApplyProgress: Float? = null,
    val isControlShadeActive: Boolean = false
)

class AuroraViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val prefs = AuroraPreferences(appContext)
    val shizukuConnectionManager = ShizukuConnectionManager(appContext)
    val shizukuCommandEngine = ShizukuCommandEngine(shizukuConnectionManager)
    val telemetryMonitor = HardwareTelemetryMonitor(appContext)
    val safeRamManager = SafeRamManager(appContext, shizukuCommandEngine)
    val performanceModeController = PerformanceModeController(
        prefs, shizukuConnectionManager, shizukuCommandEngine
    )
    val robloxEngine = RobloxOptimizationEngine(
        appContext, shizukuCommandEngine,
        performanceModeController, safeRamManager
    )

    private val _uiState = MutableStateFlow(
        AuroraUiState(
            performanceMode = prefs.selectedMode,
            visualTheme = prefs.visualTheme,
            liquidFlowType = prefs.liquidFlowType,
            touchProfile = prefs.touchProfile,
            upscalingMode = prefs.upscalingMode,
            frameGenMultiplier = prefs.frameGenMultiplier,
            casSharpness = prefs.casSharpness,
            autoDetectRoblox = prefs.autoDetectRoblox,
            smoothAnimations = true,
            telemetryPolling = prefs.enableTelemetryPolling
        )
    )
    val uiState: StateFlow<AuroraUiState> = _uiState.asStateFlow()

    val commandHistory: StateFlow<List<CommandExecutionRecord>> = shizukuCommandEngine.executionHistory
    val frameGenTelemetry: StateFlow<FrameGenerationService.PipelineTelemetry> = FrameGenerationService.telemetry

    private var telemetryJob: Job? = null

    init {
        prefs.smoothPanelAnimations = true
        shizukuConnectionManager.registerListeners(
            onDisconnected = {
                viewModelScope.launch {
                    _uiState.update {
                        it.copy(
                            shizukuStatus = shizukuConnectionManager.status.value,
                            statusBannerMessage = "Shizuku disconnected. Privileged operations halted."
                        )
                    }
                }
            }
        )

        viewModelScope.launch(Dispatchers.IO) {
            val bench = withContext(Dispatchers.Default) {
                NativeFrameProcessor.runRealTimeCapabilityBenchmark()
            }
            _uiState.update { it.copy(frameBenchmark = bench) }
            refreshAllTelemetryNow(includePing = false)
            startTelemetryLoop()
        }
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(4000L)
                if (_uiState.value.telemetryPolling) {
                    refreshAllTelemetryNow(includePing = true)
                }
            }
        }
    }

    fun refreshAllTelemetry(includePing: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            refreshAllTelemetryNow(includePing)
        }
    }

    private suspend fun refreshAllTelemetryNow(includePing: Boolean) {
        val shizuku = shizukuConnectionManager.refreshStatus()
        val hw = telemetryMonitor.sampleDeviceProfile(includeNetworkPing = includePing)
        val roblox = robloxEngine.inspectRobloxStatus()
        val canOverlay = Settings.canDrawOverlays(appContext)
        _uiState.update {
            it.copy(
                shizukuStatus = shizuku,
                hardwareProfile = hw,
                robloxStatus = roblox,
                overlayPermissionGranted = canOverlay,
                isOverlayServiceActive = EdgePanelOverlayService.isOverlayRunning,
                isControlShadeActive = LiquidControlShadeService.isOverlayRunning,
                canWriteSystemSettings = Settings.System.canWrite(appContext)
            )
        }
    }

    fun toggleVisualTheme() {
        val next = if (_uiState.value.visualTheme == AuroraVisualTheme.OXYGEN_OS_16) {
            AuroraVisualTheme.COLOR_OS_17
        } else {
            AuroraVisualTheme.OXYGEN_OS_16
        }
        prefs.visualTheme = next
        _uiState.update { it.copy(visualTheme = next) }
    }

    fun selectLiquidFlowType(type: LiquidFlowType) {
        prefs.liquidFlowType = type
        _uiState.update { it.copy(liquidFlowType = type) }
    }

    fun applySystemTransformation() {
        if (_uiState.value.systemApplyProgress != null) return
        viewModelScope.launch {
            for (step in 1..25) {
                delay(100L)
                _uiState.update { it.copy(systemApplyProgress = step / 25f) }
            }
            val current = _uiState.value
            val supportedHz = current.hardwareProfile?.supportedRefreshRatesHz ?: listOf(60f, 90f, 120f)
            val report = withContext(Dispatchers.IO) {
                performanceModeController.applyMode(
                    mode = current.performanceMode,
                    touchProfile = current.touchProfile,
                    supportedRefreshRates = supportedHz
                )
            }
            _uiState.update {
                it.copy(
                    systemApplyProgress = null,
                    statusBannerMessage = report.summary
                )
            }
        }
    }

    fun selectTab(tab: EdgeTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun toggleEdgePanelExpanded() {
        _uiState.update { it.copy(isEdgePanelExpanded = !it.isEdgePanelExpanded) }
    }

    fun selectPerformanceMode(mode: PerformanceMode) {
        viewModelScope.launch(Dispatchers.IO) {
            val supportedHz = _uiState.value.hardwareProfile?.supportedRefreshRatesHz ?: listOf(60f, 90f, 120f)
            val report = performanceModeController.applyMode(
                mode = mode,
                touchProfile = _uiState.value.touchProfile,
                supportedRefreshRates = supportedHz
            )
            _uiState.update {
                it.copy(
                    performanceMode = mode,
                    smoothAnimations = mode.enableDecorativeUiAnimations,
                    statusBannerMessage = report.summary
                )
            }
        }
    }

    fun selectTouchProfile(profile: TouchProfile) {
        prefs.touchProfile = profile
        _uiState.update { it.copy(touchProfile = profile) }
        selectPerformanceMode(_uiState.value.performanceMode)
    }

    fun selectTargetRefreshRate(hz: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            val supportedHz = _uiState.value.hardwareProfile?.supportedRefreshRatesHz ?: listOf(60f, 90f, 120f)
            if (hz !in supportedHz || hz !in setOf(60f, 90f, 120f)) {
                _uiState.update {
                    it.copy(statusBannerMessage = "The selected ${hz.toInt()} Hz target is not supported by this display.")
                }
                return@launch
            }
            prefs.targetRefreshRateHz = hz
            val shizuku = shizukuConnectionManager.refreshStatus()
            if (shizuku.canExecutePrivileged) {
                val report = performanceModeController.applyMode(
                    mode = _uiState.value.performanceMode,
                    touchProfile = _uiState.value.touchProfile,
                    supportedRefreshRates = supportedHz,
                    targetRefreshRate = hz
                )
                _uiState.update {
                    it.copy(statusBannerMessage = report.summary)
                }
            } else {
                _uiState.update {
                    it.copy(statusBannerMessage = "Saved ${hz.toInt()} Hz preference. Authorize Shizuku to lock display refresh rate.")
                }
            }
        }
    }

    fun requestShizukuPermission() {
        val requested = shizukuConnectionManager.requestAuthorization()
        if (!requested) {
            _uiState.update {
                it.copy(
                    selectedTab = EdgeTab.SHIZUKU,
                    statusBannerMessage = shizukuConnectionManager.status.value.statusMessage
                )
            }
        }
    }

    fun runSafeRamBoost() {
        viewModelScope.launch(Dispatchers.IO) {
            val res = safeRamManager.performSafeMemoryOptimization(includeShizukuTrim = false)
            val hw = telemetryMonitor.sampleDeviceProfile()
            _uiState.update {
                it.copy(
                    hardwareProfile = hw,
                    lastRamCleanSummary = res.summary,
                    statusBannerMessage = res.summary
                )
            }
        }
    }

    fun startGaming(launchRoblox: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val shizuku = shizukuConnectionManager.refreshStatus()
            if (!shizuku.canExecutePrivileged) {
                _uiState.update {
                    it.copy(
                        selectedTab = EdgeTab.SHIZUKU,
                        statusBannerMessage = "Authorize Shizuku first before executing START GAMING optimizations."
                    )
                }
                return@launch
            }
            val supportedHz = _uiState.value.hardwareProfile?.supportedRefreshRatesHz ?: listOf(60f, 90f, 120f)
            val status = robloxEngine.startGamingSession(
                mode = _uiState.value.performanceMode,
                supportedRefreshRates = supportedHz,
                launchRobloxApp = launchRoblox
            )
            _uiState.update {
                it.copy(
                    robloxStatus = status,
                    isOverlayServiceActive = EdgePanelOverlayService.isOverlayRunning,
                    statusBannerMessage = status.statusSummary
                )
            }
        }
    }

    fun stopGamingAndRestore() {
        viewModelScope.launch(Dispatchers.IO) {
            stopMediaProjectionCapture()
            val status = robloxEngine.stopGamingSessionAndRestore()
            prefs.frameGenMultiplier = FrameGenMultiplier.OFF
            _uiState.update {
                it.copy(
                    robloxStatus = status,
                    frameGenMultiplier = FrameGenMultiplier.OFF,
                    statusBannerMessage = "Gaming session ended. All Shizuku settings restored to stock defaults."
                )
            }
        }
    }

    fun selectUpscalingMode(mode: UpscalingMode) {
        if (mode == UpscalingMode.NVIDIA_DLSS_UNSUPPORTED) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Hardware Notice: NVIDIA DLSS requires NVIDIA Tensor Cores. Use Supported CAS or Experimental AI Upscaling (Hardware GameManager Downscale)."
                )
            }
            return
        }
        prefs.upscalingMode = mode
        _uiState.update { it.copy(upscalingMode = mode) }
        // Immediately apply zero-copy hardware scaling if Shizuku is connected
        selectPerformanceMode(_uiState.value.performanceMode)
    }

    fun updateCasSharpness(value: Float) {
        prefs.casSharpness = value
        _uiState.update { it.copy(casSharpness = value) }
    }

    fun rerunGraphicsBenchmark() {
        viewModelScope.launch(Dispatchers.Default) {
            val bench = NativeFrameProcessor.runRealTimeCapabilityBenchmark()
            _uiState.update {
                it.copy(
                    frameBenchmark = bench,
                    statusBannerMessage = bench.recommendation
                )
            }
        }
    }

    /**
     * The experimental multiplier requests hardware refresh pacing and GameManager downscaling.
     * It does not start MediaProjection or claim to generate frames for the running game.
     */
    fun setFrameGenMultiplierSelection(mult: FrameGenMultiplier) {
        prefs.frameGenMultiplier = mult
        _uiState.update { it.copy(frameGenMultiplier = mult) }
        if (mult == FrameGenMultiplier.OFF) {
            stopMediaProjectionCapture()
            selectPerformanceMode(_uiState.value.performanceMode)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                val report = performanceModeController.applyFramePacing(mult)
                _uiState.update {
                    it.copy(statusBannerMessage = report?.summary ?: "Frame pacing is turned off.")
                }
            }
        }
    }

    fun startMediaProjectionCapture(resultCode: Int, data: Intent) {
        val mult = _uiState.value.frameGenMultiplier.multiplier.coerceAtLeast(2)
        val intent = Intent(appContext, FrameGenerationService::class.java).apply {
            action = FrameGenerationService.ACTION_START_CAPTURE
            putExtra(FrameGenerationService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FrameGenerationService.EXTRA_RESULT_DATA, data)
            putExtra(FrameGenerationService.EXTRA_MULTIPLIER, mult)
            putExtra(FrameGenerationService.EXTRA_SHARPNESS, _uiState.value.casSharpness)
        }
        appContext.startForegroundService(intent)
    }

    fun stopMediaProjectionCapture() {
        appContext.stopService(Intent(appContext, FrameGenerationService::class.java))
    }

    fun toggleFloatingOverlayService() {
        if (!Settings.canDrawOverlays(appContext)) {
            _uiState.update {
                it.copy(statusBannerMessage = "Tap 'Overlay Permission' first and enable 'Display over other apps' for Aurora Engine.")
            }
            return
        }
        val intent = Intent(appContext, EdgePanelOverlayService::class.java)
        if (EdgePanelOverlayService.isOverlayRunning) {
            appContext.stopService(intent)
            _uiState.update {
                it.copy(
                    isOverlayServiceActive = false,
                    statusBannerMessage = "In-Game Floating Mini Edge-Panel stopped."
                )
            }
        } else {
            appContext.startForegroundService(intent)
            _uiState.update {
                it.copy(
                    isOverlayServiceActive = true,
                    statusBannerMessage = "In-Game Floating Mini Edge-Panel active! Tap the 'AE' edge pill inside Roblox to open the mini panel without leaving your game."
                )
            }
        }
    }

    fun toggleLiquidControlShade() {
        if (!Settings.canDrawOverlays(appContext)) {
            _uiState.update {
                it.copy(statusBannerMessage = "Grant 'Display over other apps' permission before starting Liquid Control Centre.")
            }
            return
        }
        val intent = Intent(appContext, LiquidControlShadeService::class.java)
        if (LiquidControlShadeService.isOverlayRunning) {
            appContext.stopService(intent)
            _uiState.update { it.copy(isControlShadeActive = false) }
        } else {
            appContext.startForegroundService(intent)
            _uiState.update { it.copy(isControlShadeActive = true) }
        }
    }

    fun updateSettingFlags(
        autoDetectRoblox: Boolean = _uiState.value.autoDetectRoblox,
        smoothAnimations: Boolean = _uiState.value.smoothAnimations,
        telemetryPolling: Boolean = _uiState.value.telemetryPolling
    ) {
        prefs.autoDetectRoblox = autoDetectRoblox
        prefs.smoothPanelAnimations = smoothAnimations
        prefs.enableTelemetryPolling = telemetryPolling
        _uiState.update {
            it.copy(
                autoDetectRoblox = autoDetectRoblox,
                smoothAnimations = smoothAnimations,
                telemetryPolling = telemetryPolling
            )
        }
    }

    fun resetAllSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            stopMediaProjectionCapture()
            shizukuCommandEngine.rollbackAllReversibleChanges()
            prefs.resetAllSettings()
            _uiState.update {
                it.copy(
                    performanceMode = PerformanceMode.BALANCED,
                    visualTheme = AuroraVisualTheme.OXYGEN_OS_16,
                    liquidFlowType = LiquidFlowType.FLUID_MOTION,
                    touchProfile = TouchProfile.ULTRA_TOUCH,
                    upscalingMode = UpscalingMode.SUPPORTED_CAS,
                    frameGenMultiplier = FrameGenMultiplier.OFF,
                    casSharpness = 0.65f,
                    autoDetectRoblox = true,
                    smoothAnimations = true,
                    telemetryPolling = true,
                    statusBannerMessage = "All settings reset and Shizuku changes rolled back."
                )
            }
        }
    }

    override fun onCleared() {
        shizukuConnectionManager.unregisterListeners()
        telemetryJob?.cancel()
        super.onCleared()
    }
}
