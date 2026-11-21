package com.akenterprises.auroraengine.shizuku

import android.content.Context
import android.os.Build
import com.akenterprises.auroraengine.core.model.CommandExecutionRecord
import com.akenterprises.auroraengine.core.model.PerformanceMode
import com.akenterprises.auroraengine.performance.DiagnosticLevel
import com.akenterprises.auroraengine.performance.ProfileCheck
import com.akenterprises.auroraengine.performance.ProfileCommandExecutor
import com.akenterprises.auroraengine.performance.ProfileSettingRead
import com.akenterprises.auroraengine.performance.ProfileShellResult
import com.akenterprises.auroraengine.performance.ProfileApplicationService
import com.akenterprises.auroraengine.performance.SharedPreferencesProfileSettingsBackupStore
import com.akenterprises.auroraengine.performance.ShizukuValidation
import com.akenterprises.auroraengine.performance.parseProfileSettingRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Executes allowlisted Shizuku operations. Profile changes use the shared
 * [ProfileApplicationService] transaction for per-setting backup and verification.
 */
class ShizukuCommandEngine(
    private val connectionManager: ShizukuConnectionManager,
    context: Context
) : ProfileCommandExecutor {
    private val appContext = context.applicationContext
    private val shellExecutor = ShizukuShellExecutor(
        processFactory = ShizukuShellExecutor.shizukuFactory()
    )
    val profileApplicationService: ProfileApplicationService by lazy {
        ProfileApplicationService(
            executor = this,
            backups = SharedPreferencesProfileSettingsBackupStore(appContext),
            sdkInt = Build.VERSION.SDK_INT
        )
    }

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

    override suspend fun validateShizukuAndShell(): ShizukuValidation = withContext(Dispatchers.IO) {
            val status = connectionManager.refreshStatus()
            val serviceCheck = when {
                !status.isInstalled -> ProfileCheck(DiagnosticLevel.FAIL, "Shizuku is not installed.")
                !status.isRunning -> ProfileCheck(DiagnosticLevel.FAIL, "Shizuku Binder service is not running.")
                else -> ProfileCheck(DiagnosticLevel.PASS, "Shizuku Binder service is reachable.")
            }
            val authorizationCheck = if (!status.canExecutePrivileged) {
                ProfileCheck(DiagnosticLevel.FAIL, "Shizuku shell permission is not authorized.")
            } else {
                ProfileCheck(DiagnosticLevel.PASS, "Shizuku shell permission is granted.")
            }
            if (serviceCheck.level != DiagnosticLevel.PASS ||
                authorizationCheck.level != DiagnosticLevel.PASS
            ) {
                return@withContext ShizukuValidation(
                    serviceCheck,
                    authorizationCheck,
                    ProfileCheck(DiagnosticLevel.FAIL, "Shell probe not run.")
                )
            }

            val probe = runShizukuProcess(
                listOf("/system/bin/sh", "-c", "printf AURORA_PROFILE_SHELL_OK"),
                timeoutSec = 3L
            )
            val shellCheck = if (probe.first == 0 && probe.second == "AURORA_PROFILE_SHELL_OK") {
                ProfileCheck(DiagnosticLevel.PASS, "Authorized Shizuku shell executed the probe.", probe.first)
            } else {
                ProfileCheck(
                    DiagnosticLevel.FAIL,
                    probe.third.ifBlank { probe.second.ifBlank { "Shizuku shell probe failed." } },
                    probe.first
                )
            }
            ShizukuValidation(serviceCheck, authorizationCheck, shellCheck)
        }

        override suspend fun readSystemSetting(namespace: String, key: String): ProfileSettingRead =
            withContext(Dispatchers.IO) {
                if (!isValidSetting(namespace, key)) {
                    return@withContext ProfileSettingRead(
                        successful = false,
                        detail = "Unsupported settings namespace or key."
                    )
                }
                val script = """
                    value=`settings get $namespace $key 2>&1`
                    status=$?
                    if [ "${'$'}status" -ne 0 ]; then
                        printf 'AURORA_READ_ERROR:%s:%s\n' "${'$'}status" "${'$'}value"
                        exit "${'$'}status"
                    fi
                    if [ "${'$'}value" = "null" ]; then
                        printf 'AURORA_UNSET'
                    else
                        printf 'AURORA_SET_BEGIN\n%s\nAURORA_SET_END' "${'$'}value"
                    fi
                """.trimIndent()
                val result = runShizukuProcess(
                    listOf("/system/bin/sh", "-c", script),
                    timeoutSec = 3L
                )
                parseProfileSettingRead(result.second, result.first, result.third)
            }

        override suspend fun writeSystemSetting(
            namespace: String,
            key: String,
            value: String?
        ): ProfileShellResult = withContext(Dispatchers.IO) {
            if (!isValidSetting(namespace, key) ||
                (value != null && value.isNotEmpty() && !value.matches(Regex("""\d+(?:\.\d+)?""")))
            ) {
                return@withContext ProfileShellResult(-1, stderr = "Rejected invalid setting write.")
            }
            val command = if (value == null) {
                listOf("settings", "delete", namespace, key)
            } else {
                listOf("settings", "put", namespace, key, value)
            }
            val result = runShizukuProcess(command, timeoutSec = 4L)
            ProfileShellResult(result.first, result.second, result.third)
        }

        override suspend fun executeOptionalProfileCommand(
            commandId: String,
            argument: String
        ): ProfileShellResult = withContext(Dispatchers.IO) {
            val command = when (commandId) {
                "game_mode" -> {
                    val mode = argument.takeIf { it in setOf("1", "2", "3") }
                        ?: return@withContext ProfileShellResult(-1, stderr = "Unsupported GameManager mode.")
                    listOf("cmd", "game", "mode", mode, "com.roblox.client")
                }
                "hardware_downscale" -> {
                    val factor = argument.takeIf { it in setOf("disable", "0.9", "0.85", "0.8", "0.75", "0.7") }
                        ?: return@withContext ProfileShellResult(-1, stderr = "Unsupported GameManager downscale.")
                    listOf("cmd", "game", "downscale", factor, "com.roblox.client")
                }
                "fixed_performance_mode" -> {
                    val enabled = argument.takeIf { it == "true" || it == "false" }
                        ?: return@withContext ProfileShellResult(-1, stderr = "Invalid fixed-performance flag.")
                    listOf("cmd", "power", "set-fixed-performance-mode-enabled", enabled)
                }
                else -> return@withContext ProfileShellResult(-1, stderr = "Unsupported optional operation: $commandId")
            }
            val result = runShizukuProcess(command, timeoutSec = 4L)
            ProfileShellResult(result.first, result.second, result.third)
        }

    private fun isValidSetting(namespace: String, key: String): Boolean =
        namespace in setOf("global", "system") &&
            key.matches(Regex("""[a-zA-Z0-9_]+"""))

    /** Compatibility entry point routed through the shared verified profile transaction. */
    suspend fun executeBatchedGamingProfile(
        mode: PerformanceMode,
        targetHz: Float,
        lockMinRefresh: Boolean,
        downscaleFactor: String,
        enableFixedPerfHint: Boolean
    ): CommandExecutionRecord {
        val result = profileApplicationService.apply(
            mode = mode,
            supportedRefreshRates = listOf(targetHz),
            targetRefreshRate = targetHz,
            downscaleFactor = downscaleFactor,
            enableFixedPerformanceHint = enableFixedPerfHint
        )
        val operation = result.operations.lastOrNull { it.required && it.level != DiagnosticLevel.PASS }
        val record = CommandExecutionRecord(
            optimizationName = "${mode.title} Profile Transaction",
            purpose = "Per-setting backup, apply, verification, and rollback transaction.",
            command = operation?.name ?: "profile transaction",
            requiredPermission = "Shizuku ADB Shell (UID 2000)",
            supportedAndroidVersions = "Android 8.0+",
            exitCode = if (result.activated) 0 else operation?.exitCode ?: -1,
            stdout = result.summary,
            stderr = if (result.activated) {
                result.operations.filter { it.level != DiagnosticLevel.PASS }
                    .joinToString("; ") { "${it.name}: ${it.detail}" }
            } else {
                result.summary
            },
            rollbackCommand = "ProfileApplicationService.restoreAll()"
        )
        appendRecord(record)
        return record
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

    suspend fun rollbackAllReversibleChanges(): List<CommandExecutionRecord> {
        val operations = profileApplicationService.restoreAll()
        return operations.map { operation ->
            val record = CommandExecutionRecord(
                optimizationName = operation.name,
                purpose = "Global profile transaction restore.",
                command = operation.name,
                requiredPermission = "Shizuku ADB Shell (UID 2000)",
                supportedAndroidVersions = "Android 8.0+",
                exitCode = operation.exitCode ?: if (operation.level == DiagnosticLevel.PASS) 0 else -1,
                stdout = if (operation.level == DiagnosticLevel.PASS) operation.detail else "",
                stderr = if (operation.level == DiagnosticLevel.PASS) "" else operation.detail,
                rollbackCommand = null
            )
            appendRecord(record)
            record
        }.ifEmpty {
            listOf(
                CommandExecutionRecord(
                    optimizationName = "Profile Restore",
                    purpose = "No saved profile settings were available.",
                    command = "No-op",
                    requiredPermission = "Shizuku ADB Shell (UID 2000)",
                    supportedAndroidVersions = "Android 8.0+",
                    exitCode = 0,
                    stdout = "No saved profile settings.",
                    stderr = "",
                    rollbackCommand = null
                )
            )
        }
    }

    private fun runShizukuProcess(
        tokens: List<String>,
        timeoutSec: Long,
        maxOutputChars: Int = 600
    ): Triple<Int, String, String> {
        val result = shellExecutor.execute(
            tokens = tokens,
            timeoutSeconds = timeoutSec,
            maxStdoutChars = maxOutputChars
        )
        val exitCode = result.exitCode ?: if (result.timedOut) -1 else -1
        val stderr = result.stderr.ifBlank {
            result.errorMessage ?: if (result.timedOut) "Command timed out." else ""
        }
        return Triple(exitCode, result.stdout, stderr)
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
