package com.akenterprises.auroraengine.performance

import android.content.Context
import android.content.SharedPreferences
import com.akenterprises.auroraengine.core.model.PerformanceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

enum class DiagnosticLevel { PASS, PARTIAL, FAIL }

data class ProfileCheck(
    val level: DiagnosticLevel,
    val detail: String,
    val exitCode: Int? = null
)

data class ProfileDiagnostics(
    val shizukuService: ProfileCheck,
    val shizukuAuthorization: ProfileCheck,
    val shellExecution: ProfileCheck,
    val systemSettingsRead: ProfileCheck,
    val settingsBackup: ProfileCheck,
    val batteryProfile: ProfileCheck,
    val balancedProfile: ProfileCheck,
    val performanceProfile: ProfileCheck,
    val failedOperation: String? = null,
    val operations: List<ProfileOperation> = emptyList()
) {
    companion object {
        fun initial() = ProfileDiagnostics(
            shizukuService = ProfileCheck(DiagnosticLevel.FAIL, "Not checked"),
            shizukuAuthorization = ProfileCheck(DiagnosticLevel.FAIL, "Not checked"),
            shellExecution = ProfileCheck(DiagnosticLevel.FAIL, "Not checked"),
            systemSettingsRead = ProfileCheck(DiagnosticLevel.FAIL, "Not checked"),
            settingsBackup = ProfileCheck(DiagnosticLevel.FAIL, "Not checked"),
            batteryProfile = ProfileCheck(DiagnosticLevel.FAIL, "Not checked"),
            balancedProfile = ProfileCheck(DiagnosticLevel.FAIL, "Not checked"),
            performanceProfile = ProfileCheck(DiagnosticLevel.FAIL, "Not checked")
        )
    }
}

data class ProfileSettingRead(
    val successful: Boolean,
    val wasSet: Boolean = false,
    val value: String? = null,
    val exitCode: Int? = null,
    val detail: String = ""
)

data class ProfileShellResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = ""
) {
    val successful: Boolean get() = exitCode == 0
}

data class ProfileSettingBackup(
    val namespace: String,
    val key: String,
    val originalValue: String?,
    val wasSet: Boolean,
    val backupVerified: Boolean
)

data class ProfileSettingChange(
    val namespace: String,
    val key: String,
    val value: String,
    val required: Boolean
)

data class ProfileApplicationResult(
    val mode: PerformanceMode,
    val activated: Boolean,
    val fullyApplied: Boolean,
    val summary: String,
    val diagnostics: ProfileDiagnostics,
    val operations: List<ProfileOperation>
)

data class ProfileOperation(
    val name: String,
    val required: Boolean,
    val level: DiagnosticLevel,
    val exitCode: Int?,
    val detail: String
)

interface ProfileCommandExecutor {
    suspend fun validateShizukuAndShell(): ShizukuValidation
    suspend fun readSystemSetting(namespace: String, key: String): ProfileSettingRead
    suspend fun writeSystemSetting(namespace: String, key: String, value: String?): ProfileShellResult
    suspend fun executeOptionalProfileCommand(commandId: String, argument: String): ProfileShellResult
}

data class ShizukuValidation(
    val service: ProfileCheck,
    val authorization: ProfileCheck,
    val shell: ProfileCheck
) {
    val successful: Boolean
        get() = service.level == DiagnosticLevel.PASS &&
            authorization.level == DiagnosticLevel.PASS &&
            shell.level == DiagnosticLevel.PASS
}

interface ProfileSettingsBackupStore {
    fun get(namespace: String, key: String): ProfileSettingBackup?
    fun saveAndVerify(backup: ProfileSettingBackup): Boolean
    fun allVerified(): List<ProfileSettingBackup>
    fun remove(namespace: String, key: String): Boolean
}

class SharedPreferencesProfileSettingsBackupStore(context: Context) : ProfileSettingsBackupStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("aurora_system_restore", Context.MODE_PRIVATE)

    override fun get(namespace: String, key: String): ProfileSettingBackup? {
        migrateLegacySnapshotIfNeeded()
        val prefix = prefix(namespace, key)
        if (!preferences.contains("${prefix}verified")) return null
        val wasSet = preferences.getBoolean("${prefix}was_set", false)
        return ProfileSettingBackup(
            namespace = namespace,
            key = key,
            originalValue = if (wasSet) preferences.getString("${prefix}value", "") else null,
            wasSet = wasSet,
            backupVerified = preferences.getBoolean("${prefix}verified", false)
        )
    }

    override fun saveAndVerify(backup: ProfileSettingBackup): Boolean {
        val existing = readWithoutMigration(backup.namespace, backup.key)
        if (existing != null) return existing.backupVerified

        val prefix = prefix(backup.namespace, backup.key)
        val saved = preferences.edit()
            .putBoolean("${prefix}was_set", backup.wasSet)
            .putString("${prefix}value", backup.originalValue.orEmpty())
            .putBoolean("${prefix}verified", false)
            .commit()
        if (!saved) return false

        val unverified = readWithoutMigration(backup.namespace, backup.key) ?: return false
        if (unverified.wasSet != backup.wasSet || unverified.originalValue != backup.originalValue) {
            return false
        }
        if (!preferences.edit().putBoolean("${prefix}verified", true).commit()) return false
        return readWithoutMigration(backup.namespace, backup.key)?.backupVerified == true
    }

    override fun allVerified(): List<ProfileSettingBackup> {
        migrateLegacySnapshotIfNeeded()
        return preferences.all.keys
            .filter { it.startsWith("profile_backup.") && it.endsWith(".verified") }
            .mapNotNull { name ->
                val parts = name.removePrefix("profile_backup.").removeSuffix(".verified")
                    .split('.', limit = 2)
                if (parts.size != 2) null else get(parts[0], parts[1])
            }
            .filter { it.backupVerified }
    }

    override fun remove(namespace: String, key: String): Boolean {
        val prefix = prefix(namespace, key)
        return preferences.edit()
            .remove("${prefix}was_set")
            .remove("${prefix}value")
            .remove("${prefix}verified")
            .commit()
    }

    private fun readWithoutMigration(namespace: String, key: String): ProfileSettingBackup? {
        val prefix = prefix(namespace, key)
        if (!preferences.contains("${prefix}verified")) return null
        val wasSet = preferences.getBoolean("${prefix}was_set", false)
        return ProfileSettingBackup(
            namespace,
            key,
            if (wasSet) preferences.getString("${prefix}value", "") else null,
            wasSet,
            preferences.getBoolean("${prefix}verified", false)
        )
    }

    private fun migrateLegacySnapshotIfNeeded() {
        val legacy = preferences.getString(LEGACY_SNAPSHOT_KEY, null) ?: return
        val entries = parseLegacySettingsSnapshot(legacy) ?: return
        entries.forEach { backup ->
            if (readWithoutMigration(backup.namespace, backup.key) == null) {
                saveAndVerify(backup)
            }
        }
        if (entries.all { readWithoutMigration(it.namespace, it.key)?.backupVerified == true }) {
            preferences.edit().remove(LEGACY_SNAPSHOT_KEY).commit()
        }
    }

    private fun prefix(namespace: String, key: String) = "profile_backup.$namespace.$key."

    private companion object {
        const val LEGACY_SNAPSHOT_KEY = "profile_settings_snapshot"
    }
}

class ProfileApplicationService(
    private val executor: ProfileCommandExecutor,
    private val backups: ProfileSettingsBackupStore,
    private val sdkInt: Int
) {
    suspend fun apply(
        mode: PerformanceMode,
        supportedRefreshRates: List<Float>,
        targetRefreshRate: Float?,
        downscaleFactor: String,
        enableFixedPerformanceHint: Boolean
    ): ProfileApplicationResult = PROCESS_TRANSACTION_MUTEX.withLock {
        val result = applyUnderLock(mode, supportedRefreshRates, targetRefreshRate, downscaleFactor, enableFixedPerformanceHint)
        val mergedDiagnostics = mergeDiagnostics(
            lastDiagnostics.value,
            result.diagnostics.copy(operations = result.operations.takeLast(20)),
            mode
        )
        mutableDiagnostics.value = mergedDiagnostics
        result.copy(diagnostics = mergedDiagnostics)
    }

    suspend fun restoreAll(): List<ProfileOperation> = PROCESS_TRANSACTION_MUTEX.withLock {
        restoreUnderLock()
    }

    private suspend fun applyUnderLock(
        mode: PerformanceMode,
        supportedRefreshRates: List<Float>,
        targetRefreshRate: Float?,
        downscaleFactor: String,
        enableFixedPerformanceHint: Boolean
    ): ProfileApplicationResult {
        val operations = mutableListOf<ProfileOperation>()
        val validation = executor.validateShizukuAndShell()
        val diagnostics = ProfileDiagnostics.initial().copy(
            shizukuService = validation.service,
            shizukuAuthorization = validation.authorization,
            shellExecution = validation.shell
        )
        if (!validation.successful) {
            val failedCheck = listOf(validation.service, validation.authorization, validation.shell)
                .first { it.level != DiagnosticLevel.PASS }
            return failedResult(
                mode,
                operations,
                diagnostics.copy(failedOperation = "Shizuku validation (${failedCheck.detail})"),
                "Shizuku validation failed: ${failedCheck.detail}"
            )
        }

        val plannedChanges = buildSettingChanges(mode, supportedRefreshRates, targetRefreshRate)
        val readable = mutableListOf<Pair<ProfileSettingChange, ProfileSettingRead>>()
        var anyReadSkipped = false
        for (change in plannedChanges) {
            val read = executor.readSystemSetting(change.namespace, change.key)
            if (!read.successful) {
                operations += ProfileOperation(
                    "${change.namespace}.${change.key} read",
                    change.required,
                    if (change.required) DiagnosticLevel.FAIL else DiagnosticLevel.PARTIAL,
                    read.exitCode,
                    read.detail.ifBlank { "Setting could not be read." }
                )
                if (change.required) {
                    return failedResult(
                        mode,
                        operations,
                        diagnostics.copy(
                            systemSettingsRead = ProfileCheck(DiagnosticLevel.FAIL, read.detail, read.exitCode),
                            settingsBackup = ProfileCheck(DiagnosticLevel.FAIL, "Backup not attempted.")
                        ),
                        "Required setting read failed: ${change.namespace}.${change.key}: ${read.detail}"
                    )
                }
                anyReadSkipped = true
                continue
            }
            operations += ProfileOperation(
                "${change.namespace}.${change.key} read",
                change.required,
                DiagnosticLevel.PASS,
                read.exitCode,
                if (read.wasSet) "Original value read." else "Setting is unset; unset state recorded."
            )
            readable += change to read
        }

        val ready = mutableListOf<PreparedSetting>()
        var anyBackupSkipped = false
        val newlyCreatedBackups = mutableListOf<Pair<String, String>>()
        for ((change, read) in readable) {
            val existingBackup = backups.get(change.namespace, change.key)
            val backup = existingBackup ?: ProfileSettingBackup(
                change.namespace,
                change.key,
                read.value,
                read.wasSet,
                backupVerified = false
            )
            val backupIsValid = if (existingBackup != null) {
                existingBackup.backupVerified
            } else {
                backups.saveAndVerify(backup.copy(backupVerified = true)).also { saved ->
                    if (saved) newlyCreatedBackups.add(change.namespace to change.key)
                }
            }
            if (!backupIsValid) {
                val detail = "Original value could not be saved and read back; setting was not changed."
                operations += ProfileOperation(
                    "${change.namespace}.${change.key} backup",
                    change.required,
                    if (change.required) DiagnosticLevel.FAIL else DiagnosticLevel.PARTIAL,
                    null,
                    detail
                )
                val attemptedBackup = backups.get(change.namespace, change.key)
                if (existingBackup == null && attemptedBackup != null &&
                    !backups.remove(change.namespace, change.key)
                ) {
                    operations += ProfileOperation(
                        "${change.namespace}.${change.key} backup cleanup",
                        required = false,
                        level = DiagnosticLevel.PARTIAL,
                        exitCode = null,
                        detail = "Unverified backup could not be removed."
                    )
                }
                if (change.required) {
                    for ((namespace, key) in newlyCreatedBackups.asReversed()) {
                        val removed = backups.remove(namespace, key)
                        operations += ProfileOperation(
                            "$namespace.$key backup cleanup",
                            required = false,
                            level = if (removed) DiagnosticLevel.PASS else DiagnosticLevel.FAIL,
                            exitCode = null,
                            detail = if (removed) "Unused transaction backup removed."
                            else "Unused transaction backup could not be removed."
                        )
                    }
                    return failedResult(
                        mode,
                        operations,
                        diagnostics.copy(
                            systemSettingsRead = ProfileCheck(DiagnosticLevel.PASS, "Required values were read."),
                            settingsBackup = ProfileCheck(DiagnosticLevel.FAIL, detail)
                        ),
                        "Required settings backup failed for ${change.namespace}.${change.key}; no setting was changed."
                    )
                }
                anyBackupSkipped = true
                continue
            }
            operations += ProfileOperation(
                "${change.namespace}.${change.key} backup",
                change.required,
                DiagnosticLevel.PASS,
                null,
                "Structured backup saved and verified."
            )
            ready += PreparedSetting(change, read.wasSet, read.value)
        }

        var readLevel = if (anyReadSkipped) DiagnosticLevel.PARTIAL else DiagnosticLevel.PASS
        val backupLevel = if (anyBackupSkipped) DiagnosticLevel.PARTIAL else DiagnosticLevel.PASS
        val applied = mutableListOf<PreparedSetting>()
        var optionalFailure = anyReadSkipped || anyBackupSkipped
        for (setting in ready) {
            val write = executor.writeSystemSetting(
                setting.change.namespace,
                setting.change.key,
                setting.change.value
            )
            if (!write.successful) {
                operations += ProfileOperation(
                    "${setting.change.namespace}.${setting.change.key} apply",
                    setting.change.required,
                    if (setting.change.required) DiagnosticLevel.FAIL else DiagnosticLevel.PARTIAL,
                    write.exitCode,
                    write.stderr.ifBlank { write.stdout.ifBlank { "Command failed." } }
                )
                if (setting.change.required) {
                    val rollback = rollbackTransaction(applied + setting)
                    operations += rollback
                    return failedResult(
                        mode,
                        operations,
                        diagnostics.copy(
                            systemSettingsRead = ProfileCheck(readLevel, "Per-setting read completed."),
                            settingsBackup = ProfileCheck(backupLevel, "Required backups verified."),
                            batteryProfile = profileCheck(mode, DiagnosticLevel.FAIL, write),
                            balancedProfile = profileCheck(mode, DiagnosticLevel.FAIL, write),
                            performanceProfile = profileCheck(mode, DiagnosticLevel.FAIL, write),
                            failedOperation = "${setting.change.namespace}.${setting.change.key} write (exit ${write.exitCode})"
                        ),
                        "Required operation failed: ${setting.change.namespace}.${setting.change.key} (exit ${write.exitCode}); transaction was rolled back."
                    )
                }
                operations += restoreOne(setting)
                optionalFailure = true
                continue
            }

            val verify = executor.readSystemSetting(setting.change.namespace, setting.change.key)
            val verified = verify.successful && verify.wasSet &&
                verify.value == setting.change.value
            if (!verified) {
                if (!verify.successful) readLevel = DiagnosticLevel.PARTIAL
                val restore = restoreOne(setting)
                val detail = if (!verify.successful) {
                    "Verification read failed: ${verify.detail}; rollback ${restore.detail}"
                } else {
                    "Expected ${setting.change.value}; got ${if (verify.wasSet) verify.value else "unset"}; rollback ${restore.detail}"
                }
                operations += ProfileOperation(
                    "${setting.change.namespace}.${setting.change.key} verify",
                    setting.change.required,
                    if (setting.change.required) DiagnosticLevel.FAIL else DiagnosticLevel.PARTIAL,
                    verify.exitCode,
                    detail
                )
                if (setting.change.required) {
                    operations += restore
                    val rollback = rollbackTransaction(applied)
                    operations += rollback
                    return failedResult(
                        mode,
                        operations,
                        diagnostics.copy(
                            systemSettingsRead = ProfileCheck(readLevel, "Per-setting read completed."),
                            settingsBackup = ProfileCheck(backupLevel, "Required backups verified."),
                            failedOperation = "${setting.change.namespace}.${setting.change.key} verify (exit ${verify.exitCode})"
                        ),
                        "Required setting verification failed: ${setting.change.namespace}.${setting.change.key}; transaction was rolled back."
                    )
                }
                operations += restore
                optionalFailure = true
                continue
            }
            operations += ProfileOperation(
                "${setting.change.namespace}.${setting.change.key} apply",
                setting.change.required,
                DiagnosticLevel.PASS,
                write.exitCode,
                "Applied and verified."
            )
            applied += setting
        }

        val optionalCommands = buildOptionalCommands(mode, downscaleFactor, enableFixedPerformanceHint)
        for ((commandId, argument, description) in optionalCommands) {
            val result = executor.executeOptionalProfileCommand(commandId, argument)
            operations += ProfileOperation(
                description,
                required = false,
                level = if (result.successful) DiagnosticLevel.PASS else DiagnosticLevel.PARTIAL,
                exitCode = result.exitCode,
                detail = if (result.successful) {
                    result.stdout.ifBlank { "Optional command applied." }
                } else {
                    result.stderr.ifBlank { result.stdout.ifBlank { "Optional operation unsupported or unavailable." } }
                }
            )
            if (!result.successful) optionalFailure = true
        }

        val diagnosticsWithProfile = diagnostics.copy(
            systemSettingsRead = ProfileCheck(
                readLevel,
                if (anyReadSkipped) "Some optional settings could not be read and were skipped." else "Required and supported settings read."
            ),
            settingsBackup = ProfileCheck(
                backupLevel,
                if (anyBackupSkipped) "Some optional settings were skipped because their backups could not be verified." else "All applicable backups verified."
            ),
            batteryProfile = profileCheck(mode, if (mode == PerformanceMode.BATTERY) resultLevel(optionalFailure) else DiagnosticLevel.FAIL, null),
            balancedProfile = profileCheck(mode, if (mode == PerformanceMode.BALANCED) resultLevel(optionalFailure) else DiagnosticLevel.FAIL, null),
            performanceProfile = profileCheck(mode, if (mode == PerformanceMode.PERFORMANCE) resultLevel(optionalFailure) else DiagnosticLevel.FAIL, null)
        )
        return ProfileApplicationResult(
            mode = mode,
            activated = true,
            fullyApplied = !optionalFailure,
            summary = if (optionalFailure) {
                "${mode.title} profile applied with optional operations skipped or partially applied. Review profile diagnostics."
            } else {
                "${mode.title} profile applied and required settings were verified."
            },
            diagnostics = diagnosticsWithProfile,
            operations = operations
        )
    }

    private suspend fun restoreUnderLock(): List<ProfileOperation> {
        val validation = executor.validateShizukuAndShell()
        if (!validation.successful) {
            return listOf(
                ProfileOperation(
                    "Shizuku restore validation",
                    required = true,
                    level = DiagnosticLevel.FAIL,
                    exitCode = validation.shell.exitCode,
                    detail = listOf(validation.service, validation.authorization, validation.shell)
                        .first { it.level != DiagnosticLevel.PASS }.detail
                )
            )
        }
        val operations = mutableListOf<ProfileOperation>()
        val savedBackups = backups.allVerified()
        for (backup in savedBackups) {
            val result = executor.writeSystemSetting(
                backup.namespace,
                backup.key,
                if (backup.wasSet) backup.originalValue else null
            )
            val verification = if (result.successful) {
                executor.readSystemSetting(backup.namespace, backup.key)
            } else {
                ProfileSettingRead(false, exitCode = result.exitCode, detail = result.stderr)
            }
            val restored = result.successful && verification.successful &&
                verification.wasSet == backup.wasSet &&
                verification.value == backup.originalValue
            operations += ProfileOperation(
                "restore ${backup.namespace}.${backup.key}",
                required = false,
                level = if (restored) DiagnosticLevel.PASS else DiagnosticLevel.FAIL,
                exitCode = if (result.successful) verification.exitCode else result.exitCode,
                detail = if (restored) "Original ${if (backup.wasSet) "value" else "unset state"} restored and verified."
                else result.stderr.ifBlank { verification.detail.ifBlank { "Restore verification failed." } }
            )
            if (restored) backups.remove(backup.namespace, backup.key)
        }
        if (savedBackups.isEmpty()) {
            operations += ProfileOperation(
                "Restore saved profile settings",
                required = false,
                level = DiagnosticLevel.PARTIAL,
                exitCode = null,
                detail = "No verified profile settings backup was available."
            )
        }
        for ((commandId, argument, description) in listOf(
            Triple("game_mode", "1", "Restore Android GameManager mode"),
            Triple("hardware_downscale", "disable", "Disable Aurora game downscale"),
            Triple("fixed_performance_mode", "false", "Disable fixed performance hint")
        )) {
            val result = executor.executeOptionalProfileCommand(commandId, argument)
            operations += ProfileOperation(
                description,
                required = false,
                level = if (result.successful) DiagnosticLevel.PASS else DiagnosticLevel.PARTIAL,
                exitCode = result.exitCode,
                detail = if (result.successful) {
                    result.stdout.ifBlank { "Optional override reset." }
                } else {
                    result.stderr.ifBlank { result.stdout.ifBlank { "Optional restore unavailable." } }
                }
            )
        }
        return operations
    }

    private suspend fun rollbackTransaction(changed: List<PreparedSetting>): List<ProfileOperation> {
        return changed.asReversed().map { setting ->
            val result = executor.writeSystemSetting(
                setting.change.namespace,
                setting.change.key,
                if (setting.originallySet) setting.originalValue else null
            )
            val read = if (result.successful) {
                executor.readSystemSetting(setting.change.namespace, setting.change.key)
            } else {
                ProfileSettingRead(false, exitCode = result.exitCode, detail = result.stderr)
            }
            val successful = result.successful && read.successful &&
                read.wasSet == setting.originallySet &&
                read.value == setting.originalValue
            ProfileOperation(
                "rollback ${setting.change.namespace}.${setting.change.key}",
                required = true,
                level = if (successful) DiagnosticLevel.PASS else DiagnosticLevel.FAIL,
                exitCode = if (result.successful) read.exitCode else result.exitCode,
                detail = if (successful) "Transaction value restored and verified."
                else result.stderr.ifBlank { read.detail.ifBlank { "Transaction rollback verification failed." } }
            )
        }
    }

    private suspend fun restoreOne(setting: PreparedSetting): ProfileOperation {
        val result = executor.writeSystemSetting(
            setting.change.namespace,
            setting.change.key,
            if (setting.originallySet) setting.originalValue else null
        )
        val read = if (result.successful) {
            executor.readSystemSetting(setting.change.namespace, setting.change.key)
        } else {
            ProfileSettingRead(false, exitCode = result.exitCode, detail = result.stderr)
        }
        val success = result.successful && read.successful &&
            read.wasSet == setting.originallySet && read.value == setting.originalValue
        return ProfileOperation(
            "rollback ${setting.change.namespace}.${setting.change.key}",
            setting.change.required,
            if (success) DiagnosticLevel.PASS else DiagnosticLevel.FAIL,
            if (result.successful) read.exitCode else result.exitCode,
            if (success) "Transaction value restored and verified."
            else result.stderr.ifBlank { read.detail.ifBlank { "Rollback verification failed." } }
        )
    }

    private fun buildSettingChanges(
        mode: PerformanceMode,
        supportedRefreshRates: List<Float>,
        targetRefreshRate: Float?
    ): List<ProfileSettingChange> {
        val scale = mode.windowAnimationScale.toString()
        val changes = mutableListOf(
            ProfileSettingChange("global", "window_animation_scale", scale, required = true),
            ProfileSettingChange("global", "transition_animation_scale", scale, required = true),
            ProfileSettingChange("global", "animator_duration_scale", scale, required = true)
        )
        if (sdkInt >= 29) {
            val target = targetRefreshRate?.takeIf { it in supportedRefreshRates }
                ?: selectSupportedRefreshRate(mode, supportedRefreshRates)
            if (target != null) {
                val lockFloor = mode == PerformanceMode.PERFORMANCE
                changes += ProfileSettingChange(
                    "system",
                    "peak_refresh_rate",
                    String.format(Locale.US, "%.1f", target),
                    required = false
                )
                changes += ProfileSettingChange(
                    "system",
                    "min_refresh_rate",
                    String.format(
                        Locale.US,
                        "%.2f",
                        if (lockFloor) target
                        else selectSupportedRefreshRate(PerformanceMode.BATTERY, supportedRefreshRates) ?: target
                    ),
                    required = false
                )
            }
        }
        return changes
    }

    private fun buildOptionalCommands(
        mode: PerformanceMode,
        downscaleFactor: String,
        enableFixedPerformanceHint: Boolean
    ): List<Triple<String, String, String>> = buildList {
        if (sdkInt >= 31) {
            add(Triple("game_mode", when (mode) {
                PerformanceMode.BATTERY -> "3"
                PerformanceMode.BALANCED -> "1"
                PerformanceMode.PERFORMANCE -> "2"
            }, "Android GameManager mode"))
            add(Triple("hardware_downscale", downscaleFactor, "Roblox hardware downscale"))
        }
        if (sdkInt >= 29) {
            add(Triple(
                "fixed_performance_mode",
                if (enableFixedPerformanceHint && mode == PerformanceMode.PERFORMANCE) "true" else "false",
                "Fixed performance hint"
            ))
        }
    }

    private fun failedResult(
        mode: PerformanceMode,
        operations: List<ProfileOperation>,
        diagnostics: ProfileDiagnostics,
        summary: String
    ): ProfileApplicationResult {
        val failedOperation = operations.firstOrNull {
            it.level == DiagnosticLevel.FAIL && !it.name.startsWith("rollback")
        } ?: operations.firstOrNull { it.level == DiagnosticLevel.FAIL }
        val failedOperationName = failedOperation?.name ?: diagnostics.failedOperation
        val modeFailure = ProfileCheck(
            DiagnosticLevel.FAIL,
            summary,
            failedOperation?.exitCode
        )
        val updatedDiagnostics = when (mode) {
            PerformanceMode.BATTERY -> diagnostics.copy(batteryProfile = modeFailure)
            PerformanceMode.BALANCED -> diagnostics.copy(balancedProfile = modeFailure)
            PerformanceMode.PERFORMANCE -> diagnostics.copy(performanceProfile = modeFailure)
        }.copy(
            failedOperation = failedOperationName,
            operations = operations.takeLast(20)
        )
        return ProfileApplicationResult(
            mode = mode,
            activated = false,
            fullyApplied = false,
            summary = summary,
            diagnostics = updatedDiagnostics,
            operations = operations
        )
    }

    private fun profileCheck(mode: PerformanceMode, level: DiagnosticLevel, result: ProfileShellResult?) =
        ProfileCheck(
            level,
            result?.stderr?.ifBlank { result.stdout } ?: if (level == DiagnosticLevel.PASS) "Applied." else "Not applied.",
            result?.exitCode
        )

    private fun resultLevel(partial: Boolean) =
        if (partial) DiagnosticLevel.PARTIAL else DiagnosticLevel.PASS

    private data class PreparedSetting(
        val change: ProfileSettingChange,
        val originallySet: Boolean,
        val originalValue: String?
    )

    companion object {
        private val PROCESS_TRANSACTION_MUTEX = Mutex()
        private val mutableDiagnostics = MutableStateFlow(ProfileDiagnostics.initial())
        val lastDiagnostics: StateFlow<ProfileDiagnostics> = mutableDiagnostics.asStateFlow()

        fun mergeDiagnostics(
            previous: ProfileDiagnostics,
            current: ProfileDiagnostics,
            mode: PerformanceMode
        ): ProfileDiagnostics {
            return current.copy(
                batteryProfile = if (mode == PerformanceMode.BATTERY) current.batteryProfile else previous.batteryProfile,
                balancedProfile = if (mode == PerformanceMode.BALANCED) current.balancedProfile else previous.balancedProfile,
                performanceProfile = if (mode == PerformanceMode.PERFORMANCE) current.performanceProfile else previous.performanceProfile
            )
        }
    }
}

internal fun parseProfileSettingRead(output: String, exitCode: Int, stderr: String = ""): ProfileSettingRead {
    if (exitCode != 0) {
        return ProfileSettingRead(
            successful = false,
            exitCode = exitCode,
            detail = stderr.ifBlank { output.ifBlank { "settings get exited with $exitCode." } }
        )
    }
    if (output == "AURORA_UNSET") {
        return ProfileSettingRead(true, wasSet = false, exitCode = exitCode, detail = "Unset.")
    }
    val start = "AURORA_SET_BEGIN\n"
    val end = "\nAURORA_SET_END"
    if (!output.startsWith(start) || !output.endsWith(end)) {
        return ProfileSettingRead(
            successful = false,
            exitCode = exitCode,
            detail = stderr.ifBlank { "Unexpected settings get response." }
        )
    }
    val value = output.substring(start.length, output.length - end.length)
    if (value.isNotEmpty() && !value.matches(Regex("""\d+(?:\.\d+)?"""))) {
        return ProfileSettingRead(
            successful = false,
            exitCode = exitCode,
            detail = stderr.ifBlank { "Setting returned a non-numeric or permission-error response." }
        )
    }
    return ProfileSettingRead(true, wasSet = true, value = value, exitCode = exitCode)
}

internal fun parseLegacySettingsSnapshot(serialized: String): List<ProfileSettingBackup>? {
    val lines = serialized.lineSequence().filter { it.isNotEmpty() }.toList()
    if (lines.isEmpty()) return null
    val records = lines.mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val setting = line.substring(0, tab)
        val separator = setting.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        val namespace = setting.substring(0, separator)
        val key = setting.substring(separator + 1)
        val value = line.substring(tab + 1)
        if (namespace !in setOf("global", "system") ||
            !key.matches(Regex("""[a-zA-Z0-9_]+""")) ||
            (value.isNotEmpty() && value != "null" && !value.matches(Regex("""\d+(?:\.\d+)?""")))
        ) return@mapNotNull null
        ProfileSettingBackup(
            namespace = namespace,
            key = key,
            originalValue = value.takeUnless { it == "null" },
            wasSet = value != "null",
            backupVerified = true
        )
    }
    return records.takeIf { it.size == lines.size }
}

internal fun selectSupportedRefreshRate(mode: PerformanceMode, refreshRates: List<Float>): Float? {
    val supported = refreshRates.filter { it.isFinite() && it >= 60f }
    if (supported.isEmpty()) return null
    return when (mode) {
        PerformanceMode.BATTERY -> supported.minOrNull()
        PerformanceMode.BALANCED -> supported.minByOrNull { kotlin.math.abs(it - 90f) }
        PerformanceMode.PERFORMANCE -> supported.maxOrNull()
    }
}
