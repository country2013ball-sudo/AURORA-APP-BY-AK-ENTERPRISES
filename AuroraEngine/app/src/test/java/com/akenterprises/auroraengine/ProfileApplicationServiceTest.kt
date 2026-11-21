package com.akenterprises.auroraengine

import com.akenterprises.auroraengine.core.model.PerformanceMode
import com.akenterprises.auroraengine.performance.DiagnosticLevel
import com.akenterprises.auroraengine.performance.ProfileApplicationService
import com.akenterprises.auroraengine.performance.ProfileCommandExecutor
import com.akenterprises.auroraengine.performance.ProfileSettingBackup
import com.akenterprises.auroraengine.performance.ProfileSettingRead
import com.akenterprises.auroraengine.performance.ProfileSettingsBackupStore
import com.akenterprises.auroraengine.performance.ProfileShellResult
import com.akenterprises.auroraengine.performance.ShizukuValidation
import com.akenterprises.auroraengine.performance.ProfileCheck
import com.akenterprises.auroraengine.performance.parseProfileSettingRead
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ProfileApplicationServiceTest {

    @Test
    fun allThreeModesUseTheSharedVerifiedTransaction() = runBlocking {
        val executor = FakeExecutor()
        val service = service(executor)
        val scales = linkedMapOf(
            PerformanceMode.BATTERY to "0.85",
            PerformanceMode.BALANCED to "0.7",
            PerformanceMode.PERFORMANCE to "0.5"
        )

        scales.forEach { (mode, expectedScale) ->
            val result = service.apply(mode, listOf(60f, 90f, 120f), null, "disable", false)
            assertTrue(result.summary, result.activated)
            assertEquals(expectedScale, executor.values["global:window_animation_scale"])
            assertEquals(DiagnosticLevel.PASS, when (mode) {
                PerformanceMode.BATTERY -> result.diagnostics.batteryProfile.level
                PerformanceMode.BALANCED -> result.diagnostics.balancedProfile.level
                PerformanceMode.PERFORMANCE -> result.diagnostics.performanceProfile.level
            })
        }
    }

    @Test
    fun unsetAndEmptySettingsAreBackedUpAndRestoredAsDistinctStates() = runBlocking {
        val executor = FakeExecutor()
        executor.values["global:window_animation_scale"] = ""
        val store = FakeBackupStore()
        val service = service(executor, store)

        val result = service.apply(PerformanceMode.BALANCED, emptyList(), null, "disable", false)
        assertTrue(result.activated)
        assertTrue(store.get("global", "transition_animation_scale")?.wasSet == false)
        assertEquals("", store.get("global", "window_animation_scale")?.originalValue)
        assertTrue(store.get("global", "window_animation_scale")?.wasSet == true)

        service.restoreAll()
        assertFalse(executor.values.containsKey("global:transition_animation_scale"))
        assertEquals("", executor.values["global:window_animation_scale"])
    }

    @Test
    fun nullLiteralIsAnUnsetValueAndReadFailuresKeepExitCodes() {
        assertFalse(parseProfileSettingRead("AURORA_UNSET", 0).wasSet)
        val empty = parseProfileSettingRead("AURORA_SET_BEGIN\n\nAURORA_SET_END", 0)
        assertTrue(empty.successful)
        assertTrue(empty.wasSet)
        assertEquals("", empty.value)

        val denied = parseProfileSettingRead("permission denied", 13, "Permission denied")
        assertFalse(denied.successful)
        assertEquals(13, denied.exitCode)
    }

    @Test
    fun unsupportedOptionalRefreshSettingDoesNotBlockAnimationProfile() = runBlocking {
        val executor = FakeExecutor().apply {
            readFailures["system:peak_refresh_rate"] = ProfileSettingRead(
                successful = false,
                exitCode = 1,
                detail = "Unknown setting"
            )
        }
        val result = service(executor).apply(
            PerformanceMode.PERFORMANCE,
            listOf(60f, 90f),
            null,
            "disable",
            false
        )

        assertTrue(result.activated)
        assertFalse(result.fullyApplied)
        assertEquals("0.5", executor.values["global:window_animation_scale"])
        assertEquals(DiagnosticLevel.PARTIAL, result.diagnostics.systemSettingsRead.level)
    }

    @Test
    fun requiredReadPermissionFailureOrTimeoutPreventsWrites() = runBlocking {
        listOf(
            ProfileSettingRead(false, exitCode = 13, detail = "Permission denied"),
            ProfileSettingRead(false, exitCode = -1, detail = "Command timed out")
        ).forEach { failure ->
            val executor = FakeExecutor().apply {
                readFailures["global:window_animation_scale"] = failure
            }
            val result = service(executor).apply(
                PerformanceMode.BATTERY,
                emptyList(),
                null,
                "disable",
                false
            )
            assertFalse(result.activated)
            assertTrue(executor.writeLog.isEmpty())
            assertEquals(failure.exitCode, result.diagnostics.batteryProfile.exitCode)
        }
    }

    @Test
    fun disconnectedShizukuFailsBeforeSettingsRead() = runBlocking {
        val executor = FakeExecutor().apply { connected = false }
        val result = service(executor).apply(
            PerformanceMode.BALANCED,
            emptyList(),
            null,
            "disable",
            false
        )
        assertFalse(result.activated)
        assertTrue(executor.readLog.isEmpty())
        assertEquals(DiagnosticLevel.FAIL, result.diagnostics.shizukuService.level)
    }

    @Test
    fun failedRequiredBackupPreventsAnyChange() = runBlocking {
        val executor = FakeExecutor()
        val store = FakeBackupStore().apply {
            failOn = "global:transition_animation_scale"
        }
        val result = service(executor, store).apply(
            PerformanceMode.BALANCED,
            emptyList(),
            null,
            "disable",
            false
        )
        assertFalse(result.activated)
        assertTrue(executor.writeLog.isEmpty())
        assertTrue(store.allVerified().isEmpty())
        assertEquals(DiagnosticLevel.FAIL, result.diagnostics.settingsBackup.level)
    }

    @Test
    fun requiredWriteFailureRollsBackEarlierWrites() = runBlocking {
        val executor = FakeExecutor().apply {
            values["global:window_animation_scale"] = "1.0"
            values["global:transition_animation_scale"] = "1.0"
            failWriteOnce["global:transition_animation_scale"] = true
        }
        val result = service(executor).apply(
            PerformanceMode.PERFORMANCE,
            emptyList(),
            null,
            "disable",
            false
        )
        assertFalse(result.activated)
        assertEquals("1.0", executor.values["global:window_animation_scale"])
        assertTrue(result.operations.any { it.name.startsWith("rollback global:window_animation_scale") })
    }

    @Test
    fun optionalCommandFailureYieldsPartialSuccess() = runBlocking {
        val executor = FakeExecutor().apply {
            optionalFailures["game_mode"] = ProfileShellResult(1, stderr = "not supported")
        }
        val result = service(executor).apply(
            PerformanceMode.BATTERY,
            emptyList(),
            null,
            "disable",
            false
        )
        assertTrue(result.activated)
        assertFalse(result.fullyApplied)
        assertEquals(DiagnosticLevel.PARTIAL, result.diagnostics.batteryProfile.level)
    }

    @Test
    fun repeatedSwitchesKeepInitialBackupUntilRestore() = runBlocking {
        val executor = FakeExecutor().apply {
            values["global:window_animation_scale"] = "1.0"
        }
        val store = FakeBackupStore()
        val service = service(executor, store)
        listOf(PerformanceMode.BATTERY, PerformanceMode.PERFORMANCE, PerformanceMode.BALANCED).forEach {
            assertTrue(service.apply(it, emptyList(), null, "disable", false).activated)
        }
        assertEquals("1.0", store.get("global", "window_animation_scale")?.originalValue)
        service.restoreAll()
        assertEquals("1.0", executor.values["global:window_animation_scale"])
    }

    @Test
    fun concurrentModeSwitchesAreSerialized() = runBlocking {
        val executor = FakeExecutor().apply { delayOnWrite = true }
        val service = service(executor)
        val first = async(Dispatchers.Default) {
            service.apply(PerformanceMode.BATTERY, emptyList(), null, "disable", false)
        }
        val second = async(Dispatchers.Default) {
            service.apply(PerformanceMode.PERFORMANCE, emptyList(), null, "disable", false)
        }
        assertTrue(first.await().activated)
        assertTrue(second.await().activated)
        assertFalse(executor.overlapDetected)
    }

    private fun service(
        executor: FakeExecutor,
        backups: FakeBackupStore = FakeBackupStore()
    ) = ProfileApplicationService(executor, backups, sdkInt = 34)

    private class FakeExecutor : ProfileCommandExecutor {
        var connected = true
        var delayOnWrite = false
        var overlapDetected = false
        private val inWrite = AtomicBoolean(false)
        val values = mutableMapOf<String, String>()
        val readFailures = mutableMapOf<String, ProfileSettingRead>()
        val failWriteOnce = mutableMapOf<String, Boolean>()
        val optionalFailures = mutableMapOf<String, ProfileShellResult>()
        val readLog = mutableListOf<String>()
        val writeLog = mutableListOf<String>()

        override suspend fun validateShizukuAndShell() = if (connected) {
            ShizukuValidation(
                ProfileCheck(DiagnosticLevel.PASS, "service"),
                ProfileCheck(DiagnosticLevel.PASS, "authorization"),
                ProfileCheck(DiagnosticLevel.PASS, "shell")
            )
        } else {
            ShizukuValidation(
                ProfileCheck(DiagnosticLevel.FAIL, "disconnected"),
                ProfileCheck(DiagnosticLevel.FAIL, "authorization unavailable"),
                ProfileCheck(DiagnosticLevel.FAIL, "shell unavailable")
            )
        }

        override suspend fun readSystemSetting(namespace: String, key: String): ProfileSettingRead {
            val name = "$namespace:$key"
            readLog += name
            readFailures[name]?.let { return it }
            return if (values.containsKey(name)) {
                ProfileSettingRead(true, wasSet = true, value = values[name], exitCode = 0)
            } else {
                ProfileSettingRead(true, wasSet = false, exitCode = 0)
            }
        }

        override suspend fun writeSystemSetting(
            namespace: String,
            key: String,
            value: String?
        ): ProfileShellResult {
            if (!inWrite.compareAndSet(false, true)) overlapDetected = true
            try {
                if (delayOnWrite) delay(2)
                val name = "$namespace:$key"
                writeLog += "$name=$value"
                if (failWriteOnce[name] == true) {
                    failWriteOnce[name] = false
                    return ProfileShellResult(13, stderr = "Permission denied")
                }
                if (value == null) values.remove(name) else values[name] = value
                return ProfileShellResult(0)
            } finally {
                inWrite.set(false)
            }
        }

        override suspend fun executeOptionalProfileCommand(
            commandId: String,
            argument: String
        ): ProfileShellResult = optionalFailures[commandId] ?: ProfileShellResult(0)
    }

    private class FakeBackupStore : ProfileSettingsBackupStore {
        private val values = linkedMapOf<Pair<String, String>, ProfileSettingBackup>()
        var failOn: String? = null

        override fun get(namespace: String, key: String) = values[namespace to key]

        override fun saveAndVerify(backup: ProfileSettingBackup): Boolean {
            if ("${backup.namespace}:${backup.key}" == failOn) return false
            values.putIfAbsent(backup.namespace to backup.key, backup.copy(backupVerified = true))
            return values[backup.namespace to backup.key]?.backupVerified == true
        }

        override fun allVerified() = values.values.filter { it.backupVerified }

        override fun remove(namespace: String, key: String) = values.remove(namespace to key) != null
    }
}
