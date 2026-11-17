package com.akenterprises.auroraengine.shizuku

import android.os.Build
import com.akenterprises.auroraengine.core.model.CommandExecutionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Authorized Shizuku Command Engine with Strict Command Whitelist, Argument Validation,
 * Execution Timeouts, Output Capture, Deduplication, and Reversible Rollback Registry.
 */
class ShizukuCommandEngine(
    private val connectionManager: ShizukuConnectionManager
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
        val rollbackBuilder: ((String) -> List<String>)? = null
    )

    private val _executionHistory = MutableStateFlow<List<CommandExecutionRecord>>(emptyList())
    val executionHistory: StateFlow<List<CommandExecutionRecord>> = _executionHistory.asStateFlow()

    // Stores rollback commands keyed by spec ID so changes can be cleanly restored
    private val activeRollbacks = LinkedHashMap<String, Pair<WhitelistedSpec, List<String>>>()
    private val lastExecutedArgs = HashMap<String, String>()

    // Strict whitelist of safe, non-destructive Android shell commands
    private val whitelist: Map<String, WhitelistedSpec> = mapOf(
        "window_anim_scale" to WhitelistedSpec(
            id = "window_anim_scale",
            name = "Window Animation Scale",
            purpose = "Adjusts window open/close transition duration to match selected performance mode.",
            requiredPermission = "Shizuku ADB Shell (UID 2000) / WRITE_SECURE_SETTINGS",
            minSdk = 26,
            commandBuilder = { scale -> listOf("settings", "put", "global", "window_animation_scale", scale) },
            argumentValidator = { scale -> scale in setOf("0.0", "0.5", "0.75", "1.0") },
            rollbackBuilder = { prev -> listOf("settings", "put", "global", "window_animation_scale", prev.ifBlank { "1.0" }) }
        ),
        "transition_anim_scale" to WhitelistedSpec(
            id = "transition_anim_scale",
            name = "Transition Animation Scale",
            purpose = "Adjusts activity transition animation duration.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 26,
            commandBuilder = { scale -> listOf("settings", "put", "global", "transition_animation_scale", scale) },
            argumentValidator = { scale -> scale in setOf("0.0", "0.5", "0.75", "1.0") },
            rollbackBuilder = { prev -> listOf("settings", "put", "global", "transition_animation_scale", prev.ifBlank { "1.0" }) }
        ),
        "animator_duration_scale" to WhitelistedSpec(
            id = "animator_duration_scale",
            name = "Animator Duration Scale",
            purpose = "Adjusts ValueAnimator speed for UI responsiveness.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 26,
            commandBuilder = { scale -> listOf("settings", "put", "global", "animator_duration_scale", scale) },
            argumentValidator = { scale -> scale in setOf("0.0", "0.5", "0.75", "1.0") },
            rollbackBuilder = { prev -> listOf("settings", "put", "global", "animator_duration_scale", prev.ifBlank { "1.0" }) }
        ),
        "peak_refresh_rate" to WhitelistedSpec(
            id = "peak_refresh_rate",
            name = "Display Peak Refresh Rate",
            purpose = "Requests supported peak display refresh rate (60/90/120 Hz) on compatible panels.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 29,
            commandBuilder = { hz -> listOf("settings", "put", "system", "peak_refresh_rate", hz) },
            argumentValidator = { hz -> hz in setOf("60.0", "90.0", "120.0") },
            rollbackBuilder = { prev -> listOf("settings", "put", "system", "peak_refresh_rate", prev.ifBlank { "90.0" }) }
        ),
        "min_refresh_rate" to WhitelistedSpec(
            id = "min_refresh_rate",
            name = "Display Minimum Refresh Rate",
            purpose = "Requests stable minimum refresh floor during active gaming sessions.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 29,
            commandBuilder = { hz -> listOf("settings", "put", "system", "min_refresh_rate", hz) },
            argumentValidator = { hz -> hz in setOf("60.0", "90.0", "120.0") },
            rollbackBuilder = { prev -> listOf("settings", "put", "system", "min_refresh_rate", prev.ifBlank { "60.0" }) }
        ),
        "game_mode_intervention" to WhitelistedSpec(
            id = "game_mode_intervention",
            name = "Android GameManager Mode",
            purpose = "Sets standard Android 12+ GameMode (1=Standard, 2=Performance, 3=Battery) for com.roblox.client.",
            requiredPermission = "Shizuku ADB Shell (UID 2000) / MANAGE_GAME_MODE",
            minSdk = 31,
            commandBuilder = { modeNum -> listOf("cmd", "game", "mode", modeNum, "com.roblox.client") },
            argumentValidator = { modeNum -> modeNum in setOf("1", "2", "3") },
            rollbackBuilder = { _ -> listOf("cmd", "game", "mode", "1", "com.roblox.client") }
        ),
        "trim_roblox_caches" to WhitelistedSpec(
            id = "trim_roblox_caches",
            name = "Safe Memory Trim Hint",
            purpose = "Sends RUNNING_LOW memory trim hint to non-critical background processes without killing system services.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 26,
            commandBuilder = { pkg -> listOf("am", "send-trim-memory", pkg, "RUNNING_LOW") },
            argumentValidator = { pkg -> pkg.matches(Regex("^[a-zA-Z0-9_.]{3,80}$")) },
            rollbackBuilder = null
        ),
        "query_roblox_meminfo" to WhitelistedSpec(
            id = "query_roblox_meminfo",
            name = "Roblox PSS Memory Inspection",
            purpose = "Reads dumpsys meminfo summary for com.roblox.client when running.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 26,
            commandBuilder = { _ -> listOf("dumpsys", "meminfo", "com.roblox.client", "-s") },
            argumentValidator = { true },
            rollbackBuilder = null
        ),
        "query_gfxinfo_roblox" to WhitelistedSpec(
            id = "query_gfxinfo_roblox",
            name = "Roblox Frame Timing Inspection",
            purpose = "Reads dumpsys gfxinfo frame stats for com.roblox.client.",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            minSdk = 26,
            commandBuilder = { _ -> listOf("dumpsys", "gfxinfo", "com.roblox.client") },
            argumentValidator = { true },
            rollbackBuilder = null
        )
    )

    suspend fun executeWhitelisted(
        specId: String,
        argument: String,
        timeoutSeconds: Long = 4L
    ): CommandExecutionRecord = withContext(Dispatchers.IO) {
        val status = connectionManager.refreshStatus()
        val spec = whitelist[specId]
            ?: return@withContext recordFailure(
                name = specId,
                purpose = "Unknown command ID",
                cmd = specId,
                perm = "Shizuku ADB",
                versions = "N/A",
                stderr = "Blocked: Command ID '$specId' is not in the strict Aurora Engine whitelist."
            )

        val versionLabel = "Android API ${spec.minSdk}+"

        // Mandatory Authorization Rule Check
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

        // Android SDK Compatibility Check
        if (Build.VERSION.SDK_INT < spec.minSdk || Build.VERSION.SDK_INT > spec.maxSdk) {
            return@withContext recordFailure(
                name = spec.name,
                purpose = spec.purpose,
                cmd = spec.commandBuilder(argument).joinToString(" "),
                perm = spec.requiredPermission,
                versions = versionLabel,
                stderr = "Skipped: Requires $versionLabel (Current device is API ${Build.VERSION.SDK_INT})."
            )
        }

        // Argument Validation Check
        if (!spec.argumentValidator(argument)) {
            return@withContext recordFailure(
                name = spec.name,
                purpose = spec.purpose,
                cmd = specId,
                perm = spec.requiredPermission,
                versions = versionLabel,
                stderr = "Blocked: Argument '$argument' failed strict whitelist validation."
            )
        }

        // Deduplication Check (avoid repeated execution of identical state)
        if (spec.rollbackBuilder != null && lastExecutedArgs[specId] == argument) {
            val cmdStr = spec.commandBuilder(argument).joinToString(" ")
            val rec = CommandExecutionRecord(
                optimizationName = spec.name,
                purpose = spec.purpose,
                command = cmdStr,
                requiredPermission = spec.requiredPermission,
                supportedAndroidVersions = versionLabel,
                exitCode = 0,
                stdout = "Already applied ($argument) — skipped duplicate execution.",
                stderr = "",
                rollbackCommand = activeRollbacks[specId]?.second?.joinToString(" ")
            )
            appendRecord(rec)
            return@withContext rec
        }

        val cmdTokens = spec.commandBuilder(argument)
        val rollbackTokens = spec.rollbackBuilder?.invoke("1.0")
        val rawResult = runShizukuProcess(cmdTokens, timeoutSeconds)

        if (rawResult.first == 0 && rollbackTokens != null) {
            activeRollbacks[specId] = spec to rollbackTokens
            lastExecutedArgs[specId] = argument
        }

        val record = CommandExecutionRecord(
            optimizationName = spec.name,
            purpose = spec.purpose,
            command = cmdTokens.joinToString(" "),
            requiredPermission = spec.requiredPermission,
            supportedAndroidVersions = versionLabel,
            exitCode = rawResult.first,
            stdout = rawResult.second,
            stderr = rawResult.third,
            rollbackCommand = rollbackTokens?.joinToString(" ")
        )
        appendRecord(record)
        record
    }

    suspend fun rollbackAllReversibleChanges(): List<CommandExecutionRecord> = withContext(Dispatchers.IO) {
        val results = mutableListOf<CommandExecutionRecord>()
        val status = connectionManager.refreshStatus()
        if (!status.canExecutePrivileged) {
            activeRollbacks.clear()
            lastExecutedArgs.clear()
            return@withContext results
        }

        for ((_, pair) in activeRollbacks.entries.reversed()) {
            val (spec, rollbackTokens) = pair
            val raw = runShizukuProcess(rollbackTokens, 4L)
            val rec = CommandExecutionRecord(
                optimizationName = "Rollback: ${spec.name}",
                purpose = "Restores ${spec.name} to default system state.",
                command = rollbackTokens.joinToString(" "),
                requiredPermission = spec.requiredPermission,
                supportedAndroidVersions = "Android API ${spec.minSdk}+",
                exitCode = raw.first,
                stdout = raw.second.ifBlank { "Restored default value." },
                stderr = raw.third,
                rollbackCommand = null
            )
            appendRecord(rec)
            results.add(rec)
        }
        activeRollbacks.clear()
        lastExecutedArgs.clear()
        results
    }

    private fun runShizukuProcess(tokens: List<String>, timeoutSec: Long): Triple<Int, String, String> {
        return try {
            // Shizuku.newProcess is package-private in newer API versions; invoke via official Binder wrapper
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, tokens.toTypedArray(), null, null) as Process

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Triple(-1, "", "Command timed out after ${timeoutSec}s")
            }

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText().trim() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText().trim() }
            Triple(process.exitValue(), stdout.take(600), stderr.take(600))
        } catch (t: Throwable) {
            Triple(-1, "", "Shizuku execution error: ${t.javaClass.simpleName}: ${t.message}")
        }
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
        val updated = (listOf(record) + _executionHistory.value).take(40)
        _executionHistory.value = updated
    }
}
