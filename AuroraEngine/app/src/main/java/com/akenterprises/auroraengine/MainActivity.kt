package com.akenterprises.auroraengine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akenterprises.auroraengine.core.model.*
import com.akenterprises.auroraengine.framegeneration.FrameGenerationService
import com.akenterprises.auroraengine.graphics.NativeFrameProcessor
import com.akenterprises.auroraengine.ui.AuroraUiState
import com.akenterprises.auroraengine.ui.AuroraViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AuroraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsState()
            val commandHistory by viewModel.commandHistory.collectAsState()
            val frameTelemetry by viewModel.frameGenTelemetry.collectAsState()
            val context = LocalContext.current

            val projectionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    viewModel.startMediaProjectionCapture(result.resultCode, result.data!!)
                } else {
                    viewModel.setFrameGenMultiplierSelection(FrameGenMultiplier.OFF)
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(state.performanceMode.accentHex),
                    background = Color(0xFF090A0F),
                    surface = Color(0xFF12141C)
                )
            ) {
                AuroraEdgePanelRoot(
                    state = state,
                    commandHistory = commandHistory,
                    frameTelemetry = frameTelemetry,
                    onSelectTab = viewModel::selectTab,
                    onTogglePanel = viewModel::toggleEdgePanelExpanded,
                    onSelectMode = viewModel::selectPerformanceMode,
                    onSelectTouch = viewModel::selectTouchProfile,
                    onSelectRefreshHz = viewModel::selectTargetRefreshRate,
                    onRequestShizuku = viewModel::requestShizukuPermission,
                    onRefreshTelemetry = { viewModel.refreshAllTelemetry(includePing = true) },
                    onStartGaming = viewModel::startGaming,
                    onStopGaming = viewModel::stopGamingAndRestore,
                    onRunRamBoost = viewModel::runSafeRamBoost,
                    onSelectUpscaling = viewModel::selectUpscalingMode,
                    onUpdateSharpness = viewModel::updateCasSharpness,
                    onRerunBenchmark = viewModel::rerunGraphicsBenchmark,
                    onSelectFrameGen = { mult ->
                        viewModel.setFrameGenMultiplierSelection(mult)
                        if (mult != FrameGenMultiplier.OFF) {
                            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            projectionLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    },
                    onStopFrameGen = {
                        viewModel.setFrameGenMultiplierSelection(FrameGenMultiplier.OFF)
                    },
                    onRequestOverlayPermission = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    onToggleOverlayService = viewModel::toggleFloatingOverlayService,
                    onUpdateFlags = viewModel::updateSettingFlags,
                    onResetAll = viewModel::resetAllSettings
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAllTelemetry()
    }
}

@Composable
fun AuroraEdgePanelRoot(
    state: AuroraUiState,
    commandHistory: List<CommandExecutionRecord>,
    frameTelemetry: FrameGenerationService.PipelineTelemetry,
    onSelectTab: (EdgeTab) -> Unit,
    onTogglePanel: () -> Unit,
    onSelectMode: (PerformanceMode) -> Unit,
    onSelectTouch: (TouchProfile) -> Unit,
    onSelectRefreshHz: (Float) -> Unit,
    onRequestShizuku: () -> Unit,
    onRefreshTelemetry: () -> Unit,
    onStartGaming: (Boolean) -> Unit,
    onStopGaming: () -> Unit,
    onRunRamBoost: () -> Unit,
    onSelectUpscaling: (UpscalingMode) -> Unit,
    onUpdateSharpness: (Float) -> Unit,
    onRerunBenchmark: () -> Unit,
    onSelectFrameGen: (FrameGenMultiplier) -> Unit,
    onStopFrameGen: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onToggleOverlayService: () -> Unit,
    onUpdateFlags: (Boolean, Boolean, Boolean) -> Unit,
    onResetAll: () -> Unit
) {
    val accentColor = Color(state.performanceMode.accentHex)
    val goldColor = Color(0xFFD4AF37)
    val crimsonColor = Color(0xFFD50000)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF090A0F),
                        Color(0xFF11131C),
                        Color(0xFF090A0F)
                    )
                )
            )
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Branding Bar: AK ENTERPRISES (Golden) + AURORA ENGINE (Deep Crimson)
            TopBrandingHeader(
                shizukuStatus = state.shizukuStatus,
                mode = state.performanceMode,
                isExpanded = state.isEdgePanelExpanded,
                onToggleExpand = onTogglePanel,
                onClickShizukuBadge = { onSelectTab(EdgeTab.SHIZUKU) }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Mandatory Shizuku Authorization Gate Banner if not yet authorized
            if (!state.shizukuStatus.canExecutePrivileged) {
                MandatoryShizukuGateBanner(
                    status = state.shizukuStatus,
                    onAuthorizeClick = onRequestShizuku,
                    onOpenShizukuTab = { onSelectTab(EdgeTab.SHIZUKU) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Main Edge Panel Dual-Column Layout (Recreating OxygenOS 16 OP Gaming Core & ColorOS 17 HyperBoost)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // LEFT COLLAPSIBLE VERTICAL PILL NAVIGATION RAIL
                EdgeNavigationRailPill(
                    selectedTab = state.selectedTab,
                    accentColor = accentColor,
                    goldColor = goldColor,
                    shizukuAuthorized = state.shizukuStatus.canExecutePrivileged,
                    onSelectTab = onSelectTab
                )

                // RIGHT MAIN FLOATING GAME PANEL CARD
                val animDuration = if (state.smoothAnimations && state.performanceMode.enableDecorativeUiAnimations) 260 else 0
                AnimatedVisibility(
                    visible = state.isEdgePanelExpanded,
                    enter = fadeIn(tween(animDuration, easing = FastOutSlowInEasing)) +
                        expandHorizontally(tween(animDuration, easing = FastOutSlowInEasing)),
                    exit = fadeOut(tween(animDuration)) + shrinkHorizontally(tween(animDuration)),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(26.dp))
                            .background(Color(0xFF12141B))
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        accentColor.copy(alpha = 0.55f),
                                        Color(0xFF252936),
                                        goldColor.copy(alpha = 0.25f)
                                    )
                                ),
                                shape = RoundedCornerShape(26.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            // Panel Top Subtitle (OP Gaming Core / HyperBoost style header)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (state.performanceMode == PerformanceMode.PERFORMANCE) {
                                        "AURORA HYPERBOOST CORE"
                                    } else {
                                        "AE GAMING CORE • ${state.performanceMode.badgeLabel.uppercase()}"
                                    },
                                    color = Color(0xFFA0AAB8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.2.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // TELEMETRY TRIAD GAUGES (Frame Rate • Battery • Network/RAM)
                            TelemetryTriadHeader(
                                mode = state.performanceMode,
                                hw = state.hardwareProfile,
                                frameTelemetry = frameTelemetry,
                                accentColor = accentColor
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // 3-MODE SEGMENTED PILL BAR (Power-saving / Balanced / Pro Gamer)
                            ThreeModeSelectorBar(
                                selectedMode = state.performanceMode,
                                onSelectMode = onSelectMode
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Dynamic Tab Content
                            when (state.selectedTab) {
                                EdgeTab.PERFORMANCE -> PerformancePanelContent(
                                    state = state,
                                    accentColor = accentColor,
                                    onSelectTouch = onSelectTouch,
                                    onSelectRefreshHz = onSelectRefreshHz,
                                    onStartGaming = onStartGaming,
                                    onStopGaming = onStopGaming
                                )
                                EdgeTab.HOME -> HomeDashboardContent(
                                    state = state,
                                    accentColor = accentColor,
                                    onStartGaming = onStartGaming,
                                    onStopGaming = onStopGaming,
                                    onRefreshTelemetry = onRefreshTelemetry
                                )
                                EdgeTab.GRAPHICS -> GraphicsTechnologyContent(
                                    state = state,
                                    frameTelemetry = frameTelemetry,
                                    accentColor = accentColor,
                                    onSelectUpscaling = onSelectUpscaling,
                                    onUpdateSharpness = onUpdateSharpness,
                                    onRerunBenchmark = onRerunBenchmark,
                                    onSelectFrameGen = onSelectFrameGen,
                                    onStopFrameGen = onStopFrameGen
                                )
                                EdgeTab.RAM -> RamManagerContent(
                                    state = state,
                                    accentColor = accentColor,
                                    onRunRamBoost = onRunRamBoost
                                )
                                EdgeTab.SHIZUKU -> ShizukuCenterContent(
                                    state = state,
                                    commandHistory = commandHistory,
                                    accentColor = accentColor,
                                    onRequestShizuku = onRequestShizuku,
                                    onRefresh = onRefreshTelemetry,
                                    onRollbackAll = onStopGaming
                                )
                                EdgeTab.SETTINGS -> SettingsPanelContent(
                                    state = state,
                                    accentColor = accentColor,
                                    onSelectMode = onSelectMode,
                                    onUpdateFlags = onUpdateFlags,
                                    onRequestOverlayPermission = onRequestOverlayPermission,
                                    onToggleOverlayService = onToggleOverlayService,
                                    onResetAll = onResetAll
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Live Status Footer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF141722))
                    .border(1.dp, Color(0xFF252A3A), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = state.statusBannerMessage,
                    color = Color(0xFFD5DCE6),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TopBrandingHeader(
    shizukuStatus: ShizukuStatus,
    mode: PerformanceMode,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onClickShizukuBadge: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF12141C))
            .border(1.dp, Color(0xFF2A2E3D), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Original Aurora Crest Emblem
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1B1014))
                    .border(1.5.dp, Color(0xFFD4AF37), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "AE",
                    color = Color(0xFFFF1744),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "AK ENTERPRISES",
                    color = Color(0xFFD4AF37),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
                Text(
                    text = "AURORA ENGINE",
                    color = Color(0xFFE53935),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val badgeBg = if (shizukuStatus.canExecutePrivileged) Color(0xFF0D3B1E) else Color(0xFF3E1212)
            val badgeFg = if (shizukuStatus.canExecutePrivileged) Color(0xFF00E676) else Color(0xFFFF5252)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(badgeBg)
                    .border(1.dp, badgeFg.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .clickable { onClickShizukuBadge() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (shizukuStatus.canExecutePrivileged) "SHIZUKU READY" else "SHIZUKU LOCKED",
                    color = badgeFg,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1D2130))
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (isExpanded) "Hide" else "Panel",
                    color = Color(0xFFD4AF37),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun MandatoryShizukuGateBanner(
    status: ShizukuStatus,
    onAuthorizeClick: () -> Unit,
    onOpenShizukuTab: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF281115))
            .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "MANDATORY SHIZUKU AUTHORIZATION REQUIRED",
                color = Color(0xFFFF8A80),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = status.statusMessage,
                color = Color(0xFFE0E6ED),
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = {
                if (status.isInstalled && status.isRunning) onAuthorizeClick() else onOpenShizukuTab()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = if (status.isRunning) "Authorize" else "Setup",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EdgeNavigationRailPill(
    selectedTab: EdgeTab,
    accentColor: Color,
    goldColor: Color,
    shizukuAuthorized: Boolean,
    onSelectTab: (EdgeTab) -> Unit
) {
    Column(
        modifier = Modifier
            .width(68.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(34.dp))
            .background(Color(0xFF12141B))
            .border(1.dp, Color(0xFF262A38), RoundedCornerShape(34.dp))
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            EdgeTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val itemColor = if (selected) accentColor else Color(0xFF8A94A6)
                val glyph = when (tab) {
                    EdgeTab.PERFORMANCE -> "((•))"
                    EdgeTab.HOME -> "⌂"
                    EdgeTab.GRAPHICS -> "❖"
                    EdgeTab.RAM -> "▤"
                    EdgeTab.SHIZUKU -> "⚡"
                    EdgeTab.SETTINGS -> "⚙"
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) accentColor.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 8.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = glyph,
                        color = itemColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tab.title,
                        color = itemColor,
                        fontSize = 9.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Bottom Core Hexagon Indicator Badge (matches reference image green/red hexagon badge)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xFF1A1E2B))
                .border(
                    width = 1.5.dp,
                    color = if (shizukuAuthorized) accentColor else goldColor,
                    shape = CircleShape
                )
                .clickable { onSelectTab(EdgeTab.HOME) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⬢",
                color = if (shizukuAuthorized) accentColor else goldColor,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
private fun TelemetryTriadHeader(
    mode: PerformanceMode,
    hw: DeviceHardwareProfile?,
    frameTelemetry: FrameGenerationService.PipelineTelemetry,
    accentColor: Color
) {
    val displayFps = when {
        frameTelemetry.isCapturing && frameTelemetry.interpolatedEffectiveFps > 0f ->
            frameTelemetry.interpolatedEffectiveFps.toInt()
        hw != null -> hw.currentRefreshRateHz.toInt()
        else -> 60
    }
    val batteryPct = hw?.batteryPercent ?: 86
    val netMs = hw?.networkLatencyEstimateMs ?: 10.9f
    val isProRed = mode == PerformanceMode.PERFORMANCE

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isProRed) {
            // ColorOS 17 HyperBoost layout: Battery (Left) | Frame rate (Center Large) | Network (Right)
            CompactSideMetric(
                title = "Battery",
                value = "$batteryPct",
                unit = "%",
                accentColor = accentColor
            )
            CenterAngularFpsGauge(
                fps = displayFps,
                accentColor = accentColor
            )
            CompactSideMetric(
                title = "Network",
                value = String.format("%.0f", netMs),
                unit = "ms",
                accentColor = accentColor
            )
        } else {
            // OxygenOS 16 OP Gaming Core layout: Circular Frame rate (Left) | Battery (Center) | Circular Network (Right)
            CircularTelemetryGauge(
                title = "Frame rate",
                valueText = "$displayFps",
                unitText = "fps",
                progress = (displayFps / 120f).coerceIn(0.15f, 1f),
                accentColor = accentColor
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Battery", color = Color(0xFF9AA4B2), fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$batteryPct%",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${String.format("%.1f", hw?.batteryTempCelsius ?: 35.0f)}°C",
                    color = Color(0xFF8A94A6),
                    fontSize = 10.sp
                )
            }
            CircularTelemetryGauge(
                title = "Network",
                valueText = String.format("%.1f", netMs),
                unitText = "ms",
                progress = (1f - (netMs / 120f)).coerceIn(0.2f, 0.95f),
                accentColor = accentColor
            )
        }
    }
}

@Composable
private fun CircularTelemetryGauge(
    title: String,
    valueText: String,
    unitText: String,
    progress: Float,
    accentColor: Color
) {
    Box(
        modifier = Modifier.size(104.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 6.dp.toPx()
            val arcSize = Size(size.width - strokeWidth * 2, size.height - strokeWidth * 2)
            val topLeft = Offset(strokeWidth, strokeWidth)

            drawArc(
                color = Color(0xFF222834),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = accentColor,
                startAngle = 135f,
                sweepAngle = 270f * progress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, color = Color(0xFFA0AAB8), fontSize = 10.sp)
            Text(
                text = valueText,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(text = unitText, color = Color(0xFF8A94A6), fontSize = 10.sp)
        }
    }
}

@Composable
private fun CenterAngularFpsGauge(
    fps: Int,
    accentColor: Color
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1A1014))
            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Frame rate", color = Color(0xFFB0B8C4), fontSize = 11.sp)
        Text(
            text = "$fps",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "FPS",
            color = accentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CompactSideMetric(
    title: String,
    value: String,
    unit: String,
    accentColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title, color = Color(0xFF9AA4B2), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(text = unit, color = accentColor, fontSize = 10.sp)
    }
}

@Composable
private fun ThreeModeSelectorBar(
    selectedMode: PerformanceMode,
    onSelectMode: (PerformanceMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF1B1E28))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        PerformanceMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            val pillColor = if (selected) Color(mode.accentHex) else Color.Transparent
            val textColor = when {
                selected && mode != PerformanceMode.PERFORMANCE -> Color(0xFF090A0F)
                selected -> Color.White
                else -> Color(0xFFA0AAB8)
            }
            val label = when (mode) {
                PerformanceMode.BATTERY -> "Power-saving"
                PerformanceMode.BALANCED -> "Balanced"
                PerformanceMode.PERFORMANCE -> "Pro Gamer"
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(pillColor)
                    .clickable { onSelectMode(mode) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PerformancePanelContent(
    state: AuroraUiState,
    accentColor: Color,
    onSelectTouch: (TouchProfile) -> Unit,
    onSelectRefreshHz: (Float) -> Unit,
    onStartGaming: (Boolean) -> Unit,
    onStopGaming: () -> Unit
) {
    // Touch Response Section (matches OxygenOS 16 / ColorOS 17 reference image)
    Text(
        text = "Touch response",
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = "May increase the device's power consumption and temperature.",
        color = Color(0xFF7D8796),
        fontSize = 11.sp
    )
    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TouchProfile.entries.forEach { profile ->
            val active = state.touchProfile == profile
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) accentColor.copy(alpha = 0.14f) else Color(0xFF1B1E28))
                    .border(
                        width = if (active) 1.5.dp else 1.dp,
                        color = if (active) accentColor else Color(0xFF282D3C),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onSelectTouch(profile) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = profile.label,
                    color = if (active) accentColor else Color(0xFFD0D7E2),
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Screen Refresh Rate Section
    Text(
        text = "Screen refresh rate",
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = "Dynamically detected hardware panel modes (Current: ${state.hardwareProfile?.currentRefreshRateHz?.toInt() ?: 60} Hz)",
        color = Color(0xFF7D8796),
        fontSize = 11.sp
    )
    Spacer(modifier = Modifier.height(8.dp))

    val rates = state.hardwareProfile?.supportedRefreshRatesHz ?: listOf(60f, 90f, 120f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(60f, 90f, 120f).forEach { hz ->
            val isSupported = rates.any { kotlin.math.abs(it - hz) < 2f }
            val isCurrent = kotlin.math.abs((state.hardwareProfile?.currentRefreshRateHz ?: 60f) - hz) < 2f
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isCurrent) accentColor.copy(alpha = 0.16f) else Color(0xFF1B1E28))
                    .border(
                        width = 1.dp,
                        color = if (isCurrent) accentColor else Color(0xFF282D3C),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable(enabled = isSupported) { onSelectRefreshHz(hz) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${hz.toInt()} Hz",
                        color = if (isSupported) Color.White else Color(0xFF5A6270),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isSupported) "Supported" else "Panel Limit",
                        color = if (isSupported) accentColor else Color(0xFF5A6270),
                        fontSize = 9.sp
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Active Mode Profile Summary Card
    InfoCard(
        title = "${state.performanceMode.title} Mode Profile",
        subtitle = state.performanceMode.subtitle,
        accentColor = accentColor
    ) {
        Text(
            text = "• Window/Transition Animation Scale: ${state.performanceMode.windowAnimationScale}x\n" +
                "• Decorative UI Transitions: ${if (state.performanceMode.enableDecorativeUiAnimations) "Enabled (Smooth Desktop-Style)" else "Disabled for Zero UI Overhead"}\n" +
                "• Thermal Safety Protection: Always Preserved (${state.hardwareProfile?.thermalStatusLabel ?: "Normal"})",
            color = Color(0xFFCFD6E0),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Primary START GAMING Action Button
    StartGamingButtonBlock(
        robloxStatus = state.robloxStatus,
        shizukuAuthorized = state.shizukuStatus.canExecutePrivileged,
        accentColor = accentColor,
        onStartGaming = onStartGaming,
        onStopGaming = onStopGaming
    )
}

@Composable
private fun HomeDashboardContent(
    state: AuroraUiState,
    accentColor: Color,
    onStartGaming: (Boolean) -> Unit,
    onStopGaming: () -> Unit,
    onRefreshTelemetry: () -> Unit
) {
    val hw = state.hardwareProfile

    StartGamingButtonBlock(
        robloxStatus = state.robloxStatus,
        shizukuAuthorized = state.shizukuStatus.canExecutePrivileged,
        accentColor = accentColor,
        onStartGaming = onStartGaming,
        onStopGaming = onStopGaming
    )

    Spacer(modifier = Modifier.height(12.dp))

    InfoCard(
        title = "Roblox Target Status (com.roblox.client)",
        subtitle = state.robloxStatus.statusSummary,
        accentColor = accentColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Installed: ${if (state.robloxStatus.isInstalled) "Yes (${state.robloxStatus.versionName})" else "No"}",
                color = Color(0xFFD0D7E2),
                fontSize = 11.sp
            )
            Text(
                text = "Running: ${if (state.robloxStatus.isRunning) "PID ${state.robloxStatus.pid ?: "Active"}" else "Idle"}",
                color = accentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    InfoCard(
        title = "Runtime Hardware & GPU Capabilities",
        subtitle = "${hw?.manufacturer ?: "Samsung"} ${hw?.model ?: "Galaxy A07 5G"} (${hw?.androidRelease ?: "Android"})",
        accentColor = accentColor
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("• SoC / ABI: ${hw?.socHardware ?: "MediaTek Dimensity"} (${hw?.cpuAbi ?: "arm64-v8a"}, ${hw?.cpuCores ?: 8} cores)", color = Color(0xFFCFD6E0), fontSize = 11.sp)
            Text("• GPU Renderer: ${hw?.gpuRenderer ?: "ARM Mali-G57"} (${hw?.gpuVendor ?: "ARM"})", color = Color(0xFFCFD6E0), fontSize = 11.sp)
            Text("• OpenGL ES: ${hw?.glEsVersion ?: "OpenGL ES 3.2"}", color = Color(0xFFCFD6E0), fontSize = 11.sp)
            Text("• Vulkan API: ${hw?.vulkanVersionLabel ?: "Vulkan 1.1 / 1.3 Supported"}", color = Color(0xFFCFD6E0), fontSize = 11.sp)
            Text("• System RAM: ${hw?.availableRamMb ?: 1850} MB free / ${hw?.totalRamMb ?: 4096} MB total", color = Color(0xFFCFD6E0), fontSize = 11.sp)
            Text("• Thermal & Battery: ${hw?.thermalStatusLabel ?: "Nominal"} • ${hw?.batteryPercent ?: 86}%", color = Color(0xFFCFD6E0), fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRefreshTelemetry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Refresh Live Hardware Telemetry", color = accentColor, fontSize = 11.sp)
        }
    }
}

@Composable
private fun GraphicsTechnologyContent(
    state: AuroraUiState,
    frameTelemetry: FrameGenerationService.PipelineTelemetry,
    accentColor: Color,
    onSelectUpscaling: (UpscalingMode) -> Unit,
    onUpdateSharpness: (Float) -> Unit,
    onRerunBenchmark: () -> Unit,
    onSelectFrameGen: (FrameGenMultiplier) -> Unit,
    onStopFrameGen: () -> Unit
) {
    InfoCard(
        title = "Graphics Renderer & Hardware Verification",
        subtitle = NativeFrameProcessor.getEngineDescription(),
        accentColor = accentColor
    ) {
        Text(
            text = "Detected GPU: ${state.hardwareProfile?.gpuRenderer ?: "ARM Mali-G57"}\n" +
                "Important Disclosure: NVIDIA DLSS is proprietary and requires NVIDIA Tensor Cores. " +
                "This ARM Mali GPU is never falsely identified as NVIDIA hardware. Aurora Engine provides " +
                "compatibility-aware Contrast-Adaptive Sharpening (CAS) and experimental Optical-Flow interpolation.",
            color = Color(0xFFCFD6E0),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text("A. AI & Spatial Upscaling Module", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))

    UpscalingMode.entries.forEach { mode ->
        val selected = state.upscalingMode == mode
        val unsupported = mode == UpscalingMode.NVIDIA_DLSS_UNSUPPORTED
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) accentColor.copy(alpha = 0.15f) else Color(0xFF1B1E28))
                .border(
                    1.dp,
                    if (selected) accentColor else if (unsupported) Color(0xFF4A2525) else Color(0xFF282D3C),
                    RoundedCornerShape(12.dp)
                )
                .clickable { onSelectUpscaling(mode) }
                .padding(10.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = mode.label,
                        color = if (unsupported) Color(0xFFFF8A80) else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = mode.badge,
                        color = if (unsupported) Color(0xFFFF5252) else accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = mode.description, color = Color(0xFF9AA4B2), fontSize = 10.sp)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "CAS Edge Sharpness: ${(state.casSharpness * 100).toInt()}%",
        color = Color(0xFFD0D7E2),
        fontSize = 11.sp
    )
    Slider(
        value = state.casSharpness,
        onValueChange = onUpdateSharpness,
        colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text("B. LSFG-Inspired Multi-Frame Generation (Experimental)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Text(
        text = "Uses Android MediaProjection + bounded ImageReader buffers + C++17 block optical flow. " +
            "Does not inject frames into Roblox's internal engine or alter server simulation FPS.",
        color = Color(0xFF8A94A6),
        fontSize = 10.sp
    )
    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FrameGenMultiplier.entries.forEach { mult ->
            val selected = state.frameGenMultiplier == mult
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) accentColor else Color(0xFF1B1E28))
                    .clickable { onSelectFrameGen(mult) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (mult == FrameGenMultiplier.OFF) "OFF" else "${mult.multiplier}X Exp",
                    color = if (selected) Color(0xFF090A0F) else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    state.frameBenchmark?.let { bench ->
        InfoCard(
            title = "Real-Time Frame Processing Feasibility Check",
            subtitle = bench.resolution,
            accentColor = accentColor
        ) {
            Text(
                text = "• Measured Frame Latency: ${String.format("%.2f", bench.frameProcessingTimeMs)} ms/frame (~${bench.maxTheoreticalFps} fps ceiling)\n" +
                    "• 2X Feasible: ${if (bench.canSustain2x) "Yes" else "Limited"} | 3X: ${if (bench.canSustain3x) "Yes" else "Limited"} | 4X: ${if (bench.canSustain4x) "Yes" else "Heavy"}\n" +
                    "• Pipeline Status: ${frameTelemetry.statusNote}",
                color = Color(0xFFCFD6E0),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRerunBenchmark, modifier = Modifier.weight(1f)) {
                    Text("Re-Test Speed", color = accentColor, fontSize = 11.sp)
                }
                if (frameTelemetry.isCapturing) {
                    Button(
                        onClick = onStopFrameGen,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop Capture", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RamManagerContent(
    state: AuroraUiState,
    accentColor: Color,
    onRunRamBoost: () -> Unit
) {
    val hw = state.hardwareProfile
    val total = (hw?.totalRamMb ?: 4096L).coerceAtLeast(1L)
    val avail = hw?.availableRamMb ?: 1800L
    val used = (total - avail).coerceAtLeast(0L)
    val usedRatio = (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    InfoCard(
        title = "Safe Gaming Memory Architecture",
        subtitle = "Available: $avail MB / $total MB (${(usedRatio * 100).toInt()}% in use)",
        accentColor = accentColor
    ) {
        LinearProgressIndicator(
            progress = usedRatio,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = accentColor,
            trackColor = Color(0xFF262B3A)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "• Low-Memory Threshold: ${hw?.lowMemoryThresholdMb ?: 450} MB (Pressure: ${if (hw?.isLowMemory == true) "HIGH" else "Normal"})\n" +
                "• Roblox Process State: ${if (state.robloxStatus.isRunning) "Running (PID ${state.robloxStatus.pid ?: "Active"})" else "Not Running"}\n" +
                "• Safety Policy: Never kills Android SystemUI, telephony/calls, notifications, or Shizuku.",
            color = Color(0xFFCFD6E0),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        state.lastRamCleanSummary?.let { summary ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = summary, color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onRunRamBoost,
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "REDUCE BACKGROUND MEMORY COMPETITION",
                color = if (state.performanceMode == PerformanceMode.PERFORMANCE) Color.White else Color(0xFF090A0F),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ShizukuCenterContent(
    state: AuroraUiState,
    commandHistory: List<CommandExecutionRecord>,
    accentColor: Color,
    onRequestShizuku: () -> Unit,
    onRefresh: () -> Unit,
    onRollbackAll: () -> Unit
) {
    val s = state.shizukuStatus
    InfoCard(
        title = "Mandatory Shizuku Authorization System",
        subtitle = s.statusMessage,
        accentColor = accentColor
    ) {
        Text(
            text = "• Installed: ${if (s.isInstalled) "Yes" else "No (moe.shizuku.privileged.api)"}\n" +
                "• Service Running: ${if (s.isRunning) "Yes (v${s.version})" else "No"}\n" +
                "• Permission Granted: ${if (s.isAuthorized) "Authorized (UID ${s.uid})" else "Not Authorized"}\n" +
                "• Root Required: No (Uses official rootless ADB Binder API)",
            color = Color(0xFFCFD6E0),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRequestShizuku,
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (s.canExecutePrivileged) "Re-Verify Binder" else "Request Authorization",
                    color = if (state.performanceMode == PerformanceMode.PERFORMANCE) Color.White else Color(0xFF090A0F),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                Text("Refresh Status", color = accentColor, fontSize = 11.sp)
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Whitelisted Command Audit Log", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        TextButton(onClick = onRollbackAll) {
            Text("Rollback All", color = Color(0xFFFF5252), fontSize = 11.sp)
        }
    }

    if (commandHistory.isEmpty()) {
        Text(
            text = "No privileged commands executed yet. Authorize Shizuku and select a performance mode or tap START GAMING.",
            color = Color(0xFF8A94A6),
            fontSize = 11.sp
        )
    } else {
        commandHistory.take(8).forEach { rec ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1D28))
                    .border(
                        1.dp,
                        if (rec.isSuccess) Color(0xFF1E4620) else Color(0xFF4A1C1C),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(8.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(rec.optimizationName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (rec.isSuccess) "EXIT 0" else "BLOCKED/ERR",
                            color = if (rec.isSuccess) Color(0xFF00E676) else Color(0xFFFF5252),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("Cmd: ${rec.command}", color = Color(0xFFD4AF37), fontSize = 10.sp)
                    Text("Purpose: ${rec.purpose}", color = Color(0xFF9AA4B2), fontSize = 10.sp)
                    if (rec.stdout.isNotBlank()) {
                        Text("Out: ${rec.stdout}", color = Color(0xFFB0BEC5), fontSize = 10.sp)
                    }
                    if (rec.stderr.isNotBlank()) {
                        Text("Note: ${rec.stderr}", color = Color(0xFFFF8A80), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanelContent(
    state: AuroraUiState,
    accentColor: Color,
    onSelectMode: (PerformanceMode) -> Unit,
    onUpdateFlags: (Boolean, Boolean, Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onToggleOverlayService: () -> Unit,
    onResetAll: () -> Unit
) {
    InfoCard(
        title = "Edge Panel & Customization Settings",
        subtitle = "All switches reflect genuine runtime behavior.",
        accentColor = accentColor
    ) {
        SettingToggleRow(
            title = "Automatic Roblox Detection",
            subtitle = "Checks com.roblox.client installation and process state during telemetry refresh.",
            checked = state.autoDetectRoblox,
            accentColor = accentColor,
            onCheckedChange = { onUpdateFlags(it, state.smoothAnimations, state.telemetryPolling) }
        )
        Divider(color = Color(0xFF262A38), modifier = Modifier.padding(vertical = 6.dp))
        SettingToggleRow(
            title = "Smooth Desktop-Grade Panel Transitions",
            subtitle = "Automatically disabled in Pro Gamer Performance Mode to eliminate UI overhead.",
            checked = state.smoothAnimations && state.performanceMode.enableDecorativeUiAnimations,
            accentColor = accentColor,
            onCheckedChange = { onUpdateFlags(state.autoDetectRoblox, it, state.telemetryPolling) }
        )
        Divider(color = Color(0xFF262A38), modifier = Modifier.padding(vertical = 6.dp))
        SettingToggleRow(
            title = "Real-Time Hardware Telemetry Polling",
            subtitle = "Polls RAM, battery temperature, and refresh rate every 2.5 seconds.",
            checked = state.telemetryPolling,
            accentColor = accentColor,
            onCheckedChange = { onUpdateFlags(state.autoDetectRoblox, state.smoothAnimations, it) }
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    InfoCard(
        title = "Floating Overlay Handle (Optional)",
        subtitle = if (state.overlayPermissionGranted) {
            "Overlay permission granted. You can toggle the floating left-edge handle."
        } else {
            "Requires explicit Android 'Display over other apps' permission."
        },
        accentColor = accentColor
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onRequestOverlayPermission,
                modifier = Modifier.weight(1f)
            ) {
                Text("Overlay Permission", color = accentColor, fontSize = 11.sp)
            }
            Button(
                onClick = onToggleOverlayService,
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (state.isOverlayServiceActive) "Stop Handle" else "Start Handle",
                    color = if (state.performanceMode == PerformanceMode.PERFORMANCE) Color.White else Color(0xFF090A0F),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Button(
        onClick = onResetAll,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E1212)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "RESET ALL AURORA ENGINE SETTINGS & ROLLBACK",
            color = Color(0xFFFF8A80),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StartGamingButtonBlock(
    robloxStatus: RobloxSessionStatus,
    shizukuAuthorized: Boolean,
    accentColor: Color,
    onStartGaming: (Boolean) -> Unit,
    onStopGaming: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF191C26))
            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Button(
            onClick = { onStartGaming(true) },
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = if (shizukuAuthorized) "START GAMING (OPTIMIZE & LAUNCH ROBLOX)" else "START GAMING (REQUIRES SHIZUKU)",
                color = if (accentColor == Color(0xFFFF1744)) Color.White else Color(0xFF090A0F),
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        if (robloxStatus.sessionActive) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onStopGaming,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("STOP SESSION & RESTORE SYSTEM DEFAULTS", color = Color(0xFFFF5252), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    subtitle: String,
    accentColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1D28))
            .border(1.dp, Color(0xFF282D3C), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(text = subtitle, color = accentColor, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accentColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = Color(0xFF8A94A6), fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = accentColor, checkedTrackColor = accentColor.copy(alpha = 0.4f))
        )
    }
}
