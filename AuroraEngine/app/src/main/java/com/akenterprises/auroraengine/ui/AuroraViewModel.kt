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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AuroraUiState(
    val selectedTab: EdgeTab = EdgeTab.PERFORMANCE,
    val isEdgePanelExpanded: Boolean = true,
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
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
        statusSummary = "Inspecting Roblox installation..."
    ),
    val frameBenchmark: NativeFrameProcessor.FrameBenchmarkResult? = null,
    val lastRamCleanSummary: String? = null,
    val statusBannerMessage: String = "Aurora Engine Ready • Select a mode or connect Shizuku.",
    val overlayPermissionGranted: Boolean = false,
    val isOverlayServiceActive: Boolean = false
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
        appContext, shizukuConnectionManager, shizukuCommandEngine,
        performanceModeController, safeRamManager
    )

    private val _uiState = MutableStateFlow(
        AuroraUiState(
            performanceMode = prefs.selectedMode,
            touchProfile = prefs.touchProfile,
            upscalingMode = prefs.upscalingMode,
            frameGenMultiplier = prefs.frameGenMultiplier,
            casSharpness = prefs.casSharpness,
            autoDetectRoblox = prefs.autoDetectRoblox,
            smoothAnimations = prefs.smoothPanelAnimations,
            telemetryPolling = prefs.enableTelemetryPolling
        )
    )
    val uiState: StateFlow<AuroraUiState> = _uiState.asStateFlow()

    val commandHistory: StateFlow<List<CommandExecutionRecord>> = shizukuCommandEngine.executionHistory
    val frameGenTelemetry: StateFlow<FrameGenerationService.PipelineTelemetry> = FrameGenerationService.telemetry

    private var telemetryJob: Job? = null

    init {
        shizukuConnectionManager.registerListeners(
            onDisconnected = {
                viewModelScope.launch {
                    _uiState.update {
                        it.copy(
                            shizukuStatus = shizukuConnectionManager.status.value,
                            statusBannerMessage = "Shizuku disconnected. Privileged operations halted immediately."
                        )
                    }
                }
            }
        )

        viewModelScope.launch {
            shizukuConnectionManager.status.collect { status ->
                _uiState.update { it.copy(shizukuStatus = status) }
            }
        }

        // Run initial hardware benchmark and start periodic telemetry loop
        viewModelScope.launch {
            val bench = NativeFrameProcessor.runRealTimeCapabilityBenchmark()
            _uiState.update { it.copy(frameBenchmark = bench) }
            refreshAllTelemetry(includePing = true)
            startTelemetryLoop()
        }
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            var tick = 0
            while (true) {
                if (_uiState.value.telemetryPolling) {
                    refreshAllTelemetry(includePing = (tick % 4 == 0))
                    tick++
                }
                delay(2500L)
            }
        }
    }

    fun refreshAllTelemetry(includePing: Boolean = false) {
        viewModelScope.launch {
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
                    isOverlayServiceActive = EdgePanelOverlayService.isOverlayRunning
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
        viewModelScope.launch {
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
            refreshAllTelemetry()
        }
    }

    fun selectTouchProfile(profile: TouchProfile) {
        prefs.touchProfile = profile
        _uiState.update { it.copy(touchProfile = profile) }
        selectPerformanceMode(_uiState.value.performanceMode)
    }

    fun selectTargetRefreshRate(hz: Float) {
        viewModelScope.launch {
            prefs.targetRefreshRateHz = hz
            val shizuku = shizukuConnectionManager.refreshStatus()
            if (shizuku.canExecutePrivileged) {
                val arg = String.format("%.1f", hz)
                val rec = shizukuCommandEngine.executeWhitelisted("peak_refresh_rate", arg)
                _uiState.update {
                    it.copy(
                        statusBannerMessage = if (rec.isSuccess) {
                            "Requested ${hz.toInt()} Hz peak display refresh rate via Shizuku."
                        } else {
                            "Refresh rate request note: ${rec.stderr}"
                        }
                    )
                }
                refreshAllTelemetry()
            } else {
                _uiState.update {
                    it.copy(statusBannerMessage = "Saved ${hz.toInt()} Hz preference. Authorize Shizuku to apply system peak refresh rate.")
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
        viewModelScope.launch {
            val shizukuOk = shizukuConnectionManager.refreshStatus().canExecutePrivileged
            val res = safeRamManager.performSafeMemoryOptimization(includeShizukuTrim = shizukuOk)
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
        viewModelScope.launch {
            val shizuku = shizukuConnectionManager.refreshStatus()
            if (!shizuku.canExecutePrivileged) {
                _uiState.update {
                    it.copy(
                        selectedTab = EdgeTab.SHIZUKU,
                        statusBannerMessage = "Mandatory Rule: Connect & authorize Shizuku before executing privileged START GAMING optimizations."
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
                    statusBannerMessage = status.statusSummary
                )
            }
        }
    }

    fun stopGamingAndRestore() {
        viewModelScope.launch {
            stopMediaProjectionCapture()
            val status = robloxEngine.stopGamingSessionAndRestore()
            _uiState.update {
                it.copy(
                    robloxStatus = status,
                    frameGenMultiplier = FrameGenMultiplier.OFF,
                    statusBannerMessage = "Gaming session ended. All reversible Shizuku settings restored."
                )
            }
        }
    }

    fun selectUpscalingMode(mode: UpscalingMode) {
        if (mode == UpscalingMode.NVIDIA_DLSS_UNSUPPORTED) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Hardware Notice: NVIDIA DLSS requires NVIDIA Tensor Cores and is unsupported on ARM Mali GPUs. Use Supported CAS or Experimental AI Upscaling."
                )
            }
            return
        }
        prefs.upscalingMode = mode
        _uiState.update {
            it.copy(
                upscalingMode = mode,
                statusBannerMessage = "Upscaling mode set to: ${mode.label}"
            )
        }
    }

    fun updateCasSharpness(value: Float) {
        prefs.casSharpness = value
        _uiState.update { it.copy(casSharpness = value) }
    }

    fun rerunGraphicsBenchmark() {
        viewModelScope.launch {
            val bench = NativeFrameProcessor.runRealTimeCapabilityBenchmark()
            _uiState.update {
                it.copy(
                    frameBenchmark = bench,
                    statusBannerMessage = bench.recommendation
                )
            }
        }
    }

    fun setFrameGenMultiplierSelection(mult: FrameGenMultiplier) {
        prefs.frameGenMultiplier = mult
        _uiState.update { it.copy(frameGenMultiplier = mult) }
        if (mult == FrameGenMultiplier.OFF) {
            stopMediaProjectionCapture()
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
        val intent = Intent(appContext, FrameGenerationService::class.java).apply {
            action = FrameGenerationService.ACTION_STOP_CAPTURE
        }
        appContext.startService(intent)
    }

    fun toggleFloatingOverlayService() {
        if (!Settings.canDrawOverlays(appContext)) {
            _uiState.update {
                it.copy(statusBannerMessage = "Grant 'Display over other apps' permission first to enable the floating Edge Handle.")
            }
            return
        }
        val intent = Intent(appContext, EdgePanelOverlayService::class.java)
        if (EdgePanelOverlayService.isOverlayRunning) {
            appContext.stopService(intent)
            _uiState.update {
                it.copy(
                    isOverlayServiceActive = false,
                    statusBannerMessage = "Floating Edge Panel handle stopped."
                )
            }
        } else {
            appContext.startForegroundService(intent)
            _uiState.update {
                it.copy(
                    isOverlayServiceActive = true,
                    statusBannerMessage = "Floating Edge Panel handle started on left screen edge."
                )
            }
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
        viewModelScope.launch {
            stopMediaProjectionCapture()
            shizukuCommandEngine.rollbackAllReversibleChanges()
            prefs.resetAllSettings()
            _uiState.update {
                it.copy(
                    performanceMode = PerformanceMode.BALANCED,
                    touchProfile = TouchProfile.ULTRA_TOUCH,
                    upscalingMode = UpscalingMode.SUPPORTED_CAS,
                    frameGenMultiplier = FrameGenMultiplier.OFF,
                    casSharpness = 0.65f,
                    autoDetectRoblox = true,
                    smoothAnimations = true,
                    telemetryPolling = true,
                    statusBannerMessage = "All Aurora Engine preferences and reversible Shizuku settings have been reset to defaults."
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
