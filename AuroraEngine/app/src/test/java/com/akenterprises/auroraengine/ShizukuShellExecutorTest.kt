package com.akenterprises.auroraengine

import com.akenterprises.auroraengine.shizuku.ShizukuShellExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShizukuShellExecutorTest {

    @Test
    fun successfulCommandCapturesOutputAndExitCode() {
        val process = FakeProcess("AURORA_SHIZUKU_OK", "", 0)
        process.complete()
        val result = execute(process)
        assertTrue(result.success)
        assertEquals(0, result.exitCode)
        assertEquals("AURORA_SHIZUKU_OK", result.stdout)
        assertEquals("", result.stderr)
        assertFalse(result.timedOut)
    }

    @Test
    fun nonZeroExitIsReportedWithoutBeingATimeout() {
        val process = FakeProcess("partial", "permission denied", 13)
        process.complete()
        val result = execute(process)
        assertFalse(result.success)
        assertEquals(13, result.exitCode)
        assertEquals("partial", result.stdout)
        assertEquals("permission denied", result.stderr)
        assertFalse(result.timedOut)
    }

    @Test
    fun processTimeoutHasNullExitCode() {
        val process = FakeProcess("late output", "", 0)
        val result = execute(process, timeoutSeconds = 1)
        assertFalse(result.success)
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertTrue(process.wasDestroyed)
    }

    @Test
    fun processCreationFailureHasNoFabricatedExitCode() {
        val executor = ShizukuShellExecutor(
            processFactory = { throw IllegalStateException("Shizuku binder unavailable") },
            executorFactory = { Executors.newFixedThreadPool(3) }
        )
        val result = executor.execute(listOf("id"), 2)
        assertFalse(result.success)
        assertNull(result.exitCode)
        assertTrue(result.errorMessage?.contains("binder unavailable") == true)
    }

    @Test
    fun emptyOutputIsCaptured() {
        val process = FakeProcess("", "", 0)
        process.complete()
        val result = execute(process)
        assertTrue(result.success)
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun largeStdoutAndStderrAreCapturedWithinBounds() {
        val stdout = "O".repeat(20_000)
        val stderr = "E".repeat(20_000)
        val process = FakeProcess(stdout, stderr, 0)
        process.complete()
        val result = execute(process, maxStdoutChars = 80_000)
        assertTrue(result.success)
        assertEquals(20_000, result.stdout.length)
        assertEquals(600, result.stderr.length)
    }

    @Test
    fun concurrentExecutionsAreSerialized() {
        val first = FakeProcess("first", "", 0)
        val second = FakeProcess("second", "", 0)
        first.complete()
        second.complete()
        // This test exercises the executor's synchronized method through a changing factory queue.
        val synchronizedFactory = java.util.concurrent.atomic.AtomicInteger()
        val queuedExecutor = ShizukuShellExecutor(
            processFactory = {
                if (synchronizedFactory.getAndIncrement() == 0) first else second
            },
            executorFactory = { Executors.newFixedThreadPool(3) }
        )
        val threads = (1..2).map {
            Thread {
                queuedExecutor.execute(listOf("id"), 2)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(first.exitValueCalled)
        assertTrue(second.exitValueCalled)
    }

    private fun execute(
        process: FakeProcess,
        timeoutSeconds: Long = 2,
        maxStdoutChars: Int = 600
    ): com.akenterprises.auroraengine.shizuku.ShellResult {
        val executor = ShizukuShellExecutor(
            processFactory = { process },
            executorFactory = { Executors.newFixedThreadPool(3) }
        )
        return executor.execute(listOf("echo", "AURORA_SHIZUKU_OK"), timeoutSeconds, maxStdoutChars)
    }

    private class FakeProcess(
        stdout: String,
        stderr: String,
        private val code: Int
    ) : Process() {
        private val completion = CountDownLatch(1)
        private val stdin = ByteArrayOutputStream()
        private val stdoutStream = ByteArrayInputStream(stdout.toByteArray())
        private val stderrStream = ByteArrayInputStream(stderr.toByteArray())
        var exitValueCalled = false
            private set
        var wasDestroyed = false
            private set

        fun complete() = completion.countDown()

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream

        override fun waitFor(): Int {
            completion.await()
            return code
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            completion.await(timeout, unit)

        override fun exitValue(): Int {
            if (completion.count == 1L) {
                throw IllegalArgumentException("process hasn't exited")
            }
            exitValueCalled = true
            return code
        }

        override fun destroy() {
            wasDestroyed = true
            // Do not release a timed-out process automatically.
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }
}
