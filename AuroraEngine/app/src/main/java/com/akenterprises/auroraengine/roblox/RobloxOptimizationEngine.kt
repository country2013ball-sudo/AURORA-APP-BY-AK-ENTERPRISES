package com.akenterprises.auroraengine.roblox

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.akenterprises.auroraengine.core.model.PerformanceMode
import com.akenterprises.auroraengine.core.model.RobloxSessionStatus
import com.akenterprises.auroraengine.performance.PerformanceModeController
import com.akenterprises.auroraengine.rammanager.SafeRamManager
import com.akenterprises.auroraengine.shizuku.ShizukuCommandEngine
import com.akenterprises.auroraengine.shizuku.ShizukuConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated Optimization Module for Roblox (com.roblox.client).
 *
 * Strictly adheres to safety and anti-cheat rules:
 * - Never modifies Roblox APK or internal game files.
 * - Never injects code or modifies Roblox process memory.
 * - Never bypasses Roblox security or anti-cheat.
 * - Operates exclusively through standard Android package visibility, safe memory management,
 *   and Shizuku-authorized Android OS settings.
 */
class RobloxOptimizationEngine(
    private val context: Context,
    private val shizukuConnectionManager: ShizukuConnectionManager,
    private val shizukuCommandEngine: ShizukuCommandEngine,
    private val performanceModeController: PerformanceModeController,
    private val safeRamManager: SafeRamManager
) {

    companion object {
        const val ROBLOX_PACKAGE = "com.roblox.client"
    }

    private var activeSession = false
    private var appliedCount = 0

    suspend fun inspectRobloxStatus(): RobloxSessionStatus = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        var installed = false
        var versionName = "Not Installed"

        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(ROBLOX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ROBLOX_PACKAGE, 0)
            }
            installed = true
            versionName = info.versionName ?: "Installed"
        } catch (_: PackageManager.NameNotFoundException) {
            installed = false
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProc = am.runningAppProcesses?.firstOrNull {
            it.processName == ROBLOX_PACKAGE || it.pkgList?.contains(ROBLOX_PACKAGE) == true
        }
        var pid = runningProc?.pid
        var isRunning = pid != null

        // If Shizuku is authorized, we can also check via dumpsys meminfo if standard API visibility is restricted
        if (!isRunning && installed && shizukuConnectionManager.status.value.canExecutePrivileged) {
            val memCheck = shizukuCommandEngine.executeWhitelisted("query_roblox_meminfo", "")
            if (memCheck.isSuccess && memCheck.stdout.contains("pid", ignoreCase = true)) {
                isRunning = true
                val match = Regex("pid\\s+(\\d+)", RegexOption.IGNORE_CASE).find(memCheck.stdout)
                pid = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        }

        val summary = when {
            !installed -> "Roblox ($ROBLOX_PACKAGE) is not installed on this device."
            activeSession && isRunning -> "Active Gaming Session • Roblox running (PID ${pid ?: "Active"}) with $appliedCount optimizations."
            activeSession -> "Session Armed ($appliedCount optimizations ready) • Ready to launch Roblox."
            isRunning -> "Roblox detected running in background (PID ${pid ?: "Active"})."
            else -> "Roblox v$versionName installed and ready for optimization."
        }

        RobloxSessionStatus(
            isInstalled = installed,
            versionName = versionName,
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
        val shizukuOk = shizukuConnectionManager.refreshStatus().canExecutePrivileged

        // Step 1: Safe RAM cleanup before launching Roblox
        safeRamManager.performSafeMemoryOptimization(includeShizukuTrim = shizukuOk)

        // Step 2: Apply performance mode settings (animations, refresh rate, GameManager mode)
        val modeReport = performanceModeController.applyMode(
            mode = mode,
            supportedRefreshRates = supportedRefreshRates
        )

        activeSession = true
        appliedCount = if (shizukuOk) modeReport.records.count { it.isSuccess } + 1 else 1

        // Step 3: Launch Roblox if installed and requested by user
        val currentStatus = inspectRobloxStatus()
        if (launchRobloxApp && currentStatus.isInstalled) {
            launchRoblox()
        }

        return inspectRobloxStatus()
    }

    suspend fun stopGamingSessionAndRestore(): RobloxSessionStatus {
        activeSession = false
        appliedCount = 0
        shizukuCommandEngine.rollbackAllReversibleChanges()
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
        } catch (_: Throwable) {
            false
        }
    }
}
