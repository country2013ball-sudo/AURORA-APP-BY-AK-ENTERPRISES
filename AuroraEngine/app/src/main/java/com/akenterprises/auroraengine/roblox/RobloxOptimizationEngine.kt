package com.akenterprises.auroraengine.roblox

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.akenterprises.auroraengine.core.model.PerformanceMode
import com.akenterprises.auroraengine.core.model.RobloxSessionStatus
import com.akenterprises.auroraengine.performance.PerformanceModeController
import com.akenterprises.auroraengine.rammanager.SafeRamManager
import com.akenterprises.auroraengine.shizuku.ShizukuCommandEngine
import com.akenterprises.auroraengine.ui.overlay.EdgePanelOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * V2 Roblox Optimization Engine.
 * Avoids periodic `dumpsys meminfo` process spawning during UI polling.
 * Automatically starts the In-Game Floating Mini Edge-Panel when launching Roblox if overlay
 * permission is granted!
 */
class RobloxOptimizationEngine(
    private val context: Context,
    private val shizukuCommandEngine: ShizukuCommandEngine,
    private val performanceModeController: PerformanceModeController,
    private val safeRamManager: SafeRamManager
) {

    companion object {
        const val ROBLOX_PACKAGE = "com.roblox.client"
    }

    private var activeSession = false
    private var appliedCount = 0
    private var cachedInstalled: Boolean? = null
    private var cachedVersion: String = "Not Installed"
    private var sessionNotice: String? = null

    suspend fun inspectRobloxStatus(forcePackageCheck: Boolean = false): RobloxSessionStatus =
        withContext(Dispatchers.IO) {
            if (cachedInstalled == null || forcePackageCheck) {
                val pm = context.packageManager
                try {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(ROBLOX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(ROBLOX_PACKAGE, 0)
                    }
                    cachedInstalled = true
                    cachedVersion = info.versionName ?: "Installed"
                } catch (_: PackageManager.NameNotFoundException) {
                    cachedInstalled = false
                    cachedVersion = "Not Installed"
                }
            }

            val installed = cachedInstalled ?: false
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProc = am.runningAppProcesses?.firstOrNull {
                it.processName == ROBLOX_PACKAGE || it.pkgList?.contains(ROBLOX_PACKAGE) == true
            }
            val pid = runningProc?.pid
            val isRunning = pid != null

            val statusSummary = when {
                !installed -> "Roblox ($ROBLOX_PACKAGE) is not installed."
                activeSession && isRunning -> "Gaming session active • Roblox running (PID ${pid ?: "Active"})."
                activeSession -> "Gaming profile armed • Roblox is not currently running."
                isRunning -> "Roblox running (PID ${pid ?: "Active"})."
                else -> "Roblox v$cachedVersion installed and ready."
            }
            val summary = listOfNotNull(statusSummary, sessionNotice).joinToString(" ")

            RobloxSessionStatus(
                isInstalled = installed,
                versionName = cachedVersion,
                isRunning = isRunning,
                pid = pid,
                sessionActive = activeSession,
                appliedOptimizationsCount = appliedCount,
                statusSummary = summary
            )
        }

    suspend fun startGamingSession(
        mode: PerformanceMode,
        supportedRefreshRates: List<Float>,
        launchRobloxApp: Boolean
    ): RobloxSessionStatus {
        // 1. Safe local memory cleanup before the verified Shizuku profile transaction.
        safeRamManager.performSafeMemoryOptimization(includeShizukuTrim = false)

        // 2. Single batched Shizuku profile execution
        val modeReport = performanceModeController.applyMode(
            mode = mode,
            supportedRefreshRates = supportedRefreshRates
        )

        activeSession = modeReport.activated
        appliedCount = modeReport.records.count { it.isSuccess }
        sessionNotice = modeReport.summary
        if (!modeReport.activated) {
            return inspectRobloxStatus()
        }

        // 3. Auto-launch In-Game Floating Mini Edge-Panel if overlay permission is granted
        if (android.provider.Settings.canDrawOverlays(context) && !EdgePanelOverlayService.isOverlayRunning) {
            try {
                context.startForegroundService(Intent(context, EdgePanelOverlayService::class.java))
            } catch (e: IllegalStateException) {
                sessionNotice = "The overlay could not start because Android disallowed a background foreground-service start: ${e.message}"
            } catch (e: SecurityException) {
                sessionNotice = "The overlay could not start: ${e.message}"
            }
        }

        // 4. Launch Roblox
        val currentStatus = inspectRobloxStatus(forcePackageCheck = true)
        if (launchRobloxApp && currentStatus.isInstalled) {
            if (!launchRoblox()) {
                sessionNotice = "The gaming profile is active, but Android could not launch Roblox."
            }
        }

        return inspectRobloxStatus()
    }

    suspend fun stopGamingSessionAndRestore(): RobloxSessionStatus {
        activeSession = false
        appliedCount = 0
        val rollbackRecords = shizukuCommandEngine.rollbackAllReversibleChanges()
        val noSettingsBackup = rollbackRecords.any {
            it.optimizationName == "Restore saved profile settings"
        }
        val failures = rollbackRecords.filterNot {
            it.isSuccess || it.optimizationName == "Restore saved profile settings"
        }
        sessionNotice = when {
            failures.isNotEmpty() ->
                failures.joinToString(prefix = "Rollback incomplete: ") { it.stderr.ifBlank { it.stdout } }
            noSettingsBackup ->
                "No verified profile settings backup was available to restore; optional game and power overrides were reset where supported."
            else ->
                "Saved animation and refresh-rate settings restored; game and power overrides returned to defaults."
        }
        return inspectRobloxStatus()
    }

    fun launchRoblox(): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(ROBLOX_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
