package com.akenterprises.auroraengine.shizuku

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.akenterprises.auroraengine.core.model.CommandExecutionRecord
import com.akenterprises.auroraengine.core.model.PerformanceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val SETTINGS_SNAPSHOT_KEY = "profile_settings_snapshot"
private val RESTORABLE_SETTINGS = listOf(
    "global:window_animation_scale",
    "global:transition_animation_scale",
    "global:animator_duration_scale",
    "system:peak_refresh_rate",
    "system:min_refresh_rate"
)

/**
 * V2 Ultra-Fast Batched Shizuku Command Engine.
 *
 * Key V2 Performance Improvements over V1:
 * 1. Executes entire mode transitions in ONE single `/system/bin/sh -c` binder process instead
 *    of spawning 6-10 separate processes (eliminates startup & mode-switch lag completely).
 * 2. Integrates Android hardware optimizations for Roblox (`com.roblox.client`):
 *    - Android 12+ GameManager intervention & hardware surface downscaling (`cmd game downscale`)
 *    - Fixed performance power hint (`cmd power set-fixed-performance-mode-enabled`)
 *    - Display peak & floor refresh rate lock (`120.0` / `90.0` / `60.0`)
 *    - Batched animation and refresh-rate settings
 */
class ShizukuCommandEngine(
    private val connectionManager: ShizukuConnectionManager,
    context: Context
) {

    data class WhitelistedSpec(
        val id: String,
        val name: String,
        val purpose: String,
        val requiredPermission: String,
        val minSdk: Int,
        val maxSdk: Int = 99,
        val commandBuilder: (String) -> List<String>,
        val argumentValidator: (String) -> Boolean,
        val rollbackCommandStr: String? = null
    )

    private val _executionHistory = MutableStateFlow<List<CommandExecutionRecord>>(emptyList())
    val executionHistory: StateFlow<List<CommandExecutionRecord>> = _executionHistory.asStateFlow()

    private val restorePrefs: SharedPreferences =
        context.getSharedPreferences("aurora_system_restore", Context.MODE_PRIVATE)

    private val settingsToRestore = RESTORABLE_SETTINGS

    private val whitelist: Map<String, WhitelistedSpec> = mapOf(
        "window_anim_scale" to WhitelistedSpec(
            id = "window_anim_scale",
            name = "Window Animation Scale",
            purpose = "Adjusts window open/close transition duration.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 26,
            commandBuilder = { scale -> listOf("settings", "put", "global", "window_animation_scale", scale) },
            argumentValidator = { scale -> scale in setOf("0.0", "0.5", "0.7", "0.75", "0.85", "1.0") },
            rollbackCommandStr = "settings put global window_animation_scale 1.0"
        ),
        "transition_anim_scale" to WhitelistedSpec(
            id = "transition_anim_scale",
            name = "Transition Animation Scale",
            purpose = "Adjusts activity transition animation duration.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 26,
            commandBuilder = { scale -> listOf("settings", "put", "global", "transition_animation_scale", scale) },
            argumentValidator = { scale -> scale in setOf("0.0", "0.5", "0.7", "0.75", "0.85", "1.0") },
            rollbackCommandStr = "settings put global transition_animation_scale 1.0"
        ),
        "animator_duration_scale" to WhitelistedSpec(
            id = "animator_duration_scale",
            name = "Animator Duration Scale",
            purpose = "Adjusts ValueAnimator speed for instant UI response.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 26,
            commandBuilder = { scale -> listOf("settings", "put", "global", "animator_duration_scale", scale) },
            argumentValidator = { scale -> scale in setOf("0.0", "0.5", "0.7", "0.75", "0.85", "1.0") },
            rollbackCommandStr = "settings put global animator_duration_scale 1.0"
        ),
        "peak_refresh_rate" to WhitelistedSpec(
            id = "peak_refresh_rate",
            name = "Display Peak Refresh Rate",
            purpose = "Locks peak display refresh rate (60/90/120 Hz).",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 29,
            commandBuilder = { hz -> listOf("settings", "put", "system", "peak_refresh_rate", hz) },
            argumentValidator = { hz -> hz in setOf("60.0", "90.0", "120.0") },
            rollbackCommandStr = "settings put system peak_refresh_rate 90.0"
        ),
        "min_refresh_rate" to WhitelistedSpec(
            id = "min_refresh_rate",
            name = "Display Minimum Refresh Rate",
            purpose = "Locks minimum refresh floor to eliminate VRR frame-rate drops during Roblox.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 29,
            commandBuilder = { hz -> listOf("settings", "put", "system", "min_refresh_rate", hz) },
            argumentValidator = { hz -> hz in setOf("60.0", "90.0", "120.0") },
            rollbackCommandStr = "settings put system min_refresh_rate 60.0"
        ),
        "game_mode_intervention" to WhitelistedSpec(
            id = "game_mode_intervention",
            name = "Android GameManager Mode",
            purpose = "Sets Android 12+ GameMode (1=Standard, 2=Performance, 3=Battery) for com.roblox.client.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 31,
            commandBuilder = { modeNum -> listOf("cmd", "game", "mode", modeNum, "com.roblox.client") },
            argumentValidator = { modeNum -> modeNum in setOf("1", "2", "3") },
            rollbackCommandStr = "cmd game mode 1 com.roblox.client"
        ),
        "hardware_downscale" to WhitelistedSpec(
            id = "hardware_downscale",
            name = "Hardware SurfaceFlinger / GameManager Downscale",
            purpose = "Uses Android's zero-copy hardware scaler (0.75 / 0.85 / disable) to boost Mali-G57 FPS in Roblox without MediaProjection lag.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 31,
            commandBuilder = { factor -> listOf("cmd", "game", "downscale", factor, "com.roblox.client") },
            argumentValidator = { factor -> factor in setOf("disable", "0.9", "0.85", "0.8", "0.75", "0.7") },
            rollbackCommandStr = "cmd game downscale disable com.roblox.client"
        ),
        "fixed_performance_mode" to WhitelistedSpec(
            id = "fixed_performance_mode",
            name = "Fixed Performance Power Hint",
            purpose = "Enables Android PowerManager sustained performance frequency hint.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 29,
            commandBuilder = { state -> listOf("cmd", "power", "set-fixed-performance-mode-enabled", state) },
            argumentValidator = { state -> state in setOf("true", "false") },
            rollbackCommandStr = "cmd power set-fixed-performance-mode-enabled false"
        ),
        "trim_roblox_caches" to WhitelistedSpec(
            id = "trim_roblox_caches",
            name = "Safe Memory Trim Hint",
            purpose = "Sends RUNNING_LOW memory trim hint to background user package.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 26,
            commandBuilder = { pkg -> listOf("am", "send-trim-memory", pkg, "RUNNING_LOW") },
            argumentValidator = { pkg -> pkg.matches(Regex("^[a-zA-Z0-9_.]{3,80}$")) }
        )
    )

    /**
     * Executes an entire Performance Mode + Hardware Frame Pacing & Upscaling profile in a
     * SINGLE batched `/system/bin/sh -c` call so there is ZERO binder/process spawn lag!
     */
    suspend fun executeBatchedGamingProfile(
        mode: PerformanceMode,
        targetHz: Float,
        lockMinRefresh: Boolean,
        downscaleFactor: String,
        enableFixedPerfHint: Boolean
    ): CommandExecutionRecord = withContext(Dispatchers.IO) {
        val status = connectionManager.refreshStatus()
        if (!status.canExecutePrivileged) {
            return@withContext recordFailure(
                name = "Batched ${mode.title} Profile",
                purpose = "Applies all Shizuku gaming optimizations in a single non-blocking shell call.",
                cmd = "sh -c <batched_profile>",
                perm = "Shizuku ADB (UID 2000)",
                versions = "Android 8.0+",
                stderr = "Blocked: Authorize Shizuku first."
            )
        }

        if (targetHz !in setOf(60f, 90f, 120f)) {
            return@withContext recordFailure(
                name = "Batched ${mode.title} Profile",
                purpose = "Applies a validated Android gaming profile in one Shizuku shell process.",
                cmd = "sh -c <batched_profile>",
                perm = "Shizuku ADB (UID 2000)",
                versions = "Android 8.0+",
                stderr = "Blocked: Unsupported refresh-rate target '$targetHz'."
            )
        }

        val scale = mode.windowAnimationScale.toString()
        val hzStr = String.format(Locale.US, "%.1f", targetHz)
        val minHzStr = if (lockMinRefresh) hzStr else "60.0"
        val gameModeNum = when (mode) {
            PerformanceMode.BATTERY -> "3"
            PerformanceMode.BALANCED -> "1"
            PerformanceMode.PERFORMANCE -> "2"
        }
        val fixedPerf = if (enableFixedPerfHint && mode == PerformanceMode.PERFORMANCE) "true" else "false"
        if (downscaleFactor !in setOf("disable", "0.9", "0.85", "0.8", "0.75", "0.7")) {
            return@withContext recordFailure(
                name = "Batched ${mode.title} Profile",
                purpose = "Applies a validated Android gaming profile in one Shizuku shell process.",
                cmd = "sh -c <batched_profile>",
                perm = "Shizuku ADB (UID 2000)",
                versions = "Android 8.0+",
                stderr = "Blocked: Unsupported hardware downscale value '$downscaleFactor'."
            )
        }
        val safeDownscale = downscaleFactor

        if (!captureSettingsSnapshotIfNeeded()) {
            return@withContext recordFailure(
                name = "Batched ${mode.title} Profile",
                purpose = "Saves the current animation and refresh-rate settings so they can be restored later.",
                cmd = "settings get <profile_settings>",
                perm = "Shizuku ADB (UID 2000)",
                versions = "Android 8.0+",
                stderr = "The current system settings could not be read and saved; no profile changes were applied."
            )
        }

        val commands = mutableListOf(
            "settings put global window_animation_scale $scale",
            "settings put global transition_animation_scale $scale",
            "settings put global animator_duration_scale $scale",
            "settings put system peak_refresh_rate $hzStr",
            "settings put system min_refresh_rate $minHzStr"
        )
        if (Build.VERSION.SDK_INT >= 31) {
            commands += "cmd game mode $gameModeNum com.roblox.client"
            commands += "cmd game downscale $safeDownscale com.roblox.client"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            commands += "cmd power set-fixed-performance-mode-enabled $fixedPerf"
        }

        val batchedCmd = buildString {
            append("failures=0\n")
            append("run_cmd() { \"\$@\" >/dev/null 2>&1 || { printf 'FAILED: %s\\n' \"\$*\"; failures=1; }; }\n")
            commands.forEach { append("run_cmd $it\n") }
            append("if [ \"\$failures\" -eq 0 ]; then printf 'BATCH_OK\\n'; fi\n")
            append("exit \"\$failures\"")
        }
        val raw = runShizukuProcess(listOf("/system/bin/sh", "-c", batchedCmd), 8L)

        val rec = CommandExecutionRecord(
            optimizationName = "Batched ${mode.title} Profile",
            purpose = "Single-shot execution of Animation Scales ($scale), Refresh Lock ($hzStr Hz), GameManager Mode ($gameModeNum), Hardware Downscale ($safeDownscale), and Fixed Performance Hint ($fixedPerf).",
            command = batchedCmd,
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            supportedAndroidVersions = "Android 8.0–16+",
            exitCode = raw.first,
            stdout = raw.second,
            stderr = raw.third,
            rollbackCommand = "settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings put global animator_duration_scale 1.0; settings put system min_refresh_rate 60.0; cmd game mode 1 com.roblox.client; cmd game downscale disable com.roblox.client; cmd power set-fixed-performance-mode-enabled false"
        )
        appendRecord(rec)
        rec
    }

    suspend fun executeWhitelisted(
        specId: String,
        argument: String,
        timeoutSeconds: Long = 3L
    ): CommandExecutionRecord = withContext(Dispatchers.IO) {
        val status = connectionManager.refreshStatus()
        val spec = whitelist[specId]
            ?: return@withContext recordFailure(
                name = specId,
                purpose = "Unknown command ID",
                cmd = specId,
                perm = "Shizuku ADB",
                versions = "N/A",
                stderr = "Blocked: Command ID '$specId' is not in whitelist."
            )

        val versionLabel = "Android API ${spec.minSdk}+"
        if (!status.canExecutePrivileged) {
            return@withContext recordFailure(
                name = spec.name,
                purpose = spec.purpose,
                cmd = spec.commandBuilder(argument).joinToString(" "),
                perm = spec.requiredPermission,
                versions = versionLabel,
                stderr = "Blocked: Mandatory Shizuku authorization is not active."
            )
        }

        if (!spec.argumentValidator(argument)) {
            return@withContext recordFailure(
                name = spec.name,
                purpose = spec.purpose,
                cmd = specId,
                perm = spec.requiredPermission,
                versions = versionLabel,
                stderr = "Blocked: Argument '$argument' failed validation."
            )
        }

        val cmdTokens = spec.commandBuilder(argument)
        val rawResult = runShizukuProcess(cmdTokens, timeoutSeconds)

        val record = CommandExecutionRecord(
            optimizationName = spec.name,
            purpose = spec.purpose,
            command = cmdTokens.joinToString(" "),
            requiredPermission = spec.requiredPermission,
            supportedAndroidVersions = versionLabel,
            exitCode = rawResult.first,
            stdout = rawResult.second,
            stderr = rawResult.third,
            rollbackCommand = spec.rollbackCommandStr
        )
        appendRecord(record)
        record
    }

    suspend fun sampleRobloxFrameRate(): Float? = withContext(Dispatchers.IO) {
        if (!connectionManager.refreshStatus().canExecutePrivileged) return@withContext null
        val result = runShizukuProcess(
            listOf("/system/bin/dumpsys", "gfxinfo", "com.roblox.client", "framestats"),
            timeoutSec = 3L,
            maxOutputChars = 80_000
        )
        if (result.first != 0) return@withContext null
        parseGfxinfoFrameRate(result.second)
    }

    suspend fun rollbackAllReversibleChanges(): List<CommandExecutionRecord> = withContext(Dispatchers.IO) {
        val status = connectionManager.refreshStatus()
        if (!status.canExecutePrivileged) {
            return@withContext listOf(
                recordFailure(
                    name = "Full System Rollback",
                    purpose = "Restores Aurora Engine system settings to their documented defaults.",
                    cmd = "sh -c <rollback_profile>",
                    perm = "Shizuku ADB (UID 2000)",
                    versions = "Android 8.0+",
                    stderr = "Rollback was not run because Shizuku authorization is not active."
                )
            )
        }
        val hasSavedSnapshot = restorePrefs.contains(SETTINGS_SNAPSHOT_KEY)
        val savedSettings = loadSettingsSnapshot()
        if (hasSavedSnapshot && savedSettings == null) {
            return@withContext listOf(
                recordFailure(
                    name = "Full System Rollback",
                    purpose = "Restores the saved animation and refresh-rate settings.",
                    cmd = "sh -c <rollback_profile>",
                    perm = "Shizuku ADB Shell (UID 2000)",
                    versions = "Android 8.0+",
                    stderr = "The saved settings snapshot is invalid; no system values were changed."
                )
            )
        }
        val commands = if (savedSettings != null) {
            savedSettings.map { (setting, value) ->
                val (namespace, key) = setting.split(':', limit = 2)
                if (value == "null") {
                    "settings delete $namespace $key"
                } else {
                    "settings put $namespace $key $value"
                }
            }.toMutableList()
        } else {
            mutableListOf(
                "settings put global window_animation_scale 1.0",
                "settings put global transition_animation_scale 1.0",
                "settings put global animator_duration_scale 1.0",
                "settings put system min_refresh_rate 60.0",
                "settings put system peak_refresh_rate 90.0"
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            commands += "cmd game mode 1 com.roblox.client"
            commands += "cmd game downscale disable com.roblox.client"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            commands += "cmd power set-fixed-performance-mode-enabled false"
        }
        val restoreCmd = buildString {
            append("failures=0\n")
            append("run_cmd() { \"\$@\" >/dev/null 2>&1 || { printf 'FAILED: %s\\n' \"\$*\"; failures=1; }; }\n")
            commands.forEach { append("run_cmd $it\n") }
            append("if [ \"\$failures\" -eq 0 ]; then printf 'ALL_RESTORED\\n'; fi\n")
            append("exit \"\$failures\"")
        }

        val raw = runShizukuProcess(listOf("/system/bin/sh", "-c", restoreCmd), 8L)
        val rec = CommandExecutionRecord(
            optimizationName = "Full System Rollback",
            purpose = if (savedSettings != null) {
                "Restores saved animation and refresh-rate settings, plus Aurora GameManager and power overrides."
            } else {
                "No saved settings snapshot was available; restores legacy fallback defaults and Aurora GameManager and power overrides."
            },
            command = restoreCmd,
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            supportedAndroidVersions = "Android 8.0+",
            exitCode = raw.first,
            stdout = raw.second,
            stderr = raw.third,
            rollbackCommand = null
        )
        appendRecord(rec)
        if (raw.first == 0 && raw.second.contains("ALL_RESTORED")) {
            restorePrefs.edit().remove(SETTINGS_SNAPSHOT_KEY).commit()
        }
        listOf(rec)
    }

    private fun captureSettingsSnapshotIfNeeded(): Boolean {
        if (restorePrefs.contains(SETTINGS_SNAPSHOT_KEY)) return loadSettingsSnapshot() != null

        val script = buildString {
            append("set -e\n")
            settingsToRestore.forEach { setting ->
                val (namespace, key) = setting.split(':', limit = 2)
                append("value=\$(settings get $namespace $key)\n")
                append("printf '%s\\t%s\\t%s\\n' '$namespace' '$key' \"\$value\"\n")
            }
        }
        val result = runShizukuProcess(
            listOf("/system/bin/sh", "-c", script),
            timeoutSec = 5L
        )
        if (result.first != 0) return false
        val snapshot = parseSettingsSnapshot(result.second) ?: return false
        if (snapshot.keys != settingsToRestore.toSet()) return false
        val serialized = snapshot.entries.joinToString("\n") { (key, value) -> "$key\t$value" }
        return restorePrefs.edit().putString(SETTINGS_SNAPSHOT_KEY, serialized).commit()
    }

    private fun loadSettingsSnapshot(): Map<String, String>? {
        val serialized = restorePrefs.getString(SETTINGS_SNAPSHOT_KEY, null) ?: return null
        return parsePersistedSettingsSnapshot(serialized)
    }

    private fun runShizukuProcess(
        tokens: List<String>,
        timeoutSec: Long,
        maxOutputChars: Int = 600
    ): Triple<Int, String, String> {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, tokens.toTypedArray(), null, null) as Process
            val stdoutFuture = CompletableFuture.supplyAsync {
                readBoundedStream(process.inputStream, maxOutputChars, keepLast = maxOutputChars > 600)
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                readBoundedStream(process.errorStream, 600, keepLast = false)
            }

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                return Triple(-1, "", "Command timed out after ${timeoutSec}s")
            }

            val stdout = stdoutFuture.get(1L, TimeUnit.SECONDS).trim()
            val stderr = stderrFuture.get(1L, TimeUnit.SECONDS).trim()
            Triple(process.exitValue(), stdout, stderr)
        } catch (t: Throwable) {
            Triple(-1, "", "Shizuku execution error: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun readBoundedStream(
        stream: java.io.InputStream,
        maxChars: Int,
        keepLast: Boolean
    ): String {
        val result = StringBuilder(maxChars.coerceAtMost(4096))
        val buffer = CharArray(2048)
        InputStreamReader(stream).use { reader ->
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (keepLast) {
                    result.append(buffer, 0, count)
                    if (result.length > maxChars) {
                        result.delete(0, result.length - maxChars)
                    }
                } else if (result.length < maxChars) {
                    result.append(buffer, 0, minOf(count, maxChars - result.length))
                }
            }
        }
        return result.toString()
    }

    private fun recordFailure(
        name: String,
        purpose: String,
        cmd: String,
        perm: String,
        versions: String,
        stderr: String
    ): CommandExecutionRecord {
        val rec = CommandExecutionRecord(
            optimizationName = name,
            purpose = purpose,
            command = cmd,
            requiredPermission = perm,
            supportedAndroidVersions = versions,
            exitCode = -1,
            stdout = "",
            stderr = stderr,
            rollbackCommand = null
        )
        appendRecord(rec)
        return rec
    }

    private fun appendRecord(record: CommandExecutionRecord) {
        val updated = (listOf(record) + _executionHistory.value).take(30)
        _executionHistory.value = updated
    }
}

internal fun parseSettingsSnapshot(output: String): Map<String, String>? {
    val snapshot = output.lineSequence().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) return@mapNotNull null
        "${parts[0]}:${parts[1]}" to parts[2].trim()
    }.toMap()
    if (snapshot.keys != RESTORABLE_SETTINGS.toSet()) return null
    if (snapshot.values.any { it != "null" && !it.matches(Regex("""\d+(?:\.\d+)?""")) }) {
        return null
    }
    return snapshot
}

internal fun parsePersistedSettingsSnapshot(serialized: String): Map<String, String>? {
    val snapshot = serialized.lineSequence().mapNotNull { line ->
        val separator = line.indexOf('\t')
        if (separator <= 0) return@mapNotNull null
        line.substring(0, separator) to line.substring(separator + 1)
    }.toMap()
    if (snapshot.keys != RESTORABLE_SETTINGS.toSet()) return null
    if (snapshot.values.any { it != "null" && !it.matches(Regex("""\d+(?:\.\d+)?""")) }) {
        return null
    }
    return snapshot
}

internal fun parseGfxinfoFrameRate(output: String): Float? {
    val lines = output.lineSequence().toList()
    val headerIndex = lines.indexOfFirst { it.contains("FRAME_COMPLETED") }
    if (headerIndex < 0) return null
    val completedIndex = lines[headerIndex].split(',').indexOfFirst {
        it.trim() == "FRAME_COMPLETED"
    }
    if (completedIndex < 0) return null

    val timestamps = lines.drop(headerIndex + 1).mapNotNull { line ->
        line.split(',')
            .getOrNull(completedIndex)
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }.takeLast(120)
    val latest = timestamps.lastOrNull() ?: return null
    val recent = timestamps.filter { it >= latest - 1_000_000_000L }
    val first = recent.firstOrNull() ?: return null
    val spanNs = latest - first
    if (recent.size < 2 || spanNs <= 0L) return null
    return (recent.size - 1) * 1_000_000_000f / spanNs
}
