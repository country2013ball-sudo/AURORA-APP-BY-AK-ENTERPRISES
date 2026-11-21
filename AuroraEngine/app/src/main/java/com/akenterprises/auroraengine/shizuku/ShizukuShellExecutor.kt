package com.akenterprises.auroraengine.shizuku

import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class ShellResult(
    val success: Boolean,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val errorMessage: String? = null
) {
    companion object {
        fun creationFailure(message: String) = ShellResult(
            success = false,
            exitCode = null,
            stdout = "",
            stderr = "",
            errorMessage = message
        )

        fun timeout(timeoutSeconds: Long) = ShellResult(
            success = false,
            exitCode = null,
            stdout = "",
            stderr = "",
            timedOut = true,
            errorMessage = "Command timed out after ${timeoutSeconds}s"
        )

        fun interrupted(message: String) = ShellResult(
            success = false,
            exitCode = null,
            stdout = "",
            stderr = "",
            errorMessage = message
        )
    }
}

fun interface ShizukuProcessFactory {
    fun start(tokens: List<String>): Process
}

class ShizukuShellExecutor(
    private val processFactory: ShizukuProcessFactory,
    private val executorFactory: () -> ExecutorService = {
        Executors.newFixedThreadPool(3)
    }
) {
    @Synchronized
    fun execute(
        tokens: List<String>,
        timeoutSeconds: Long,
        maxStdoutChars: Int = 600
    ): ShellResult {
        val executor = executorFactory()
        var process: Process? = null
        var stdoutFuture: Future<String>? = null
        var stderrFuture: Future<String>? = null
        var waitFuture: Future<Int>? = null
        try {
            process = processFactory.start(tokens)
            val startedProcess = process

            stdoutFuture = executor.submit<String> {
                readStream(startedProcess.inputStream, maxStdoutChars, keepLast = maxStdoutChars > 600)
            }
            stderrFuture = executor.submit<String> {
                readStream(startedProcess.errorStream, 600, keepLast = false)
            }
            waitFuture = executor.submit<Int> {
                startedProcess.waitFor()
                startedProcess.exitValue()
            }

            val exitCode = try {
                waitFuture.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                startedProcess.destroy()
                if (!startedProcess.waitFor(500, TimeUnit.MILLISECONDS)) {
                    startedProcess.destroyForcibly()
                }
                closeStreams(startedProcess)
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                return ShellResult.timeout(timeoutSeconds)
            }

            val stdout = getStreamFuture(stdoutFuture, timeoutSeconds)
            val stderr = getStreamFuture(stderrFuture, timeoutSeconds)
            return ShellResult(
                success = exitCode == 0,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process?.destroy()
            closeStreams(process)
            return ShellResult.interrupted("Shizuku execution interrupted: ${e.message ?: "interrupted"}")
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            process?.destroy()
            closeStreams(process)
            return ShellResult.creationFailure(
                "Shizuku process execution failed: ${cause?.message ?: cause?.javaClass?.simpleName ?: e.message}"
            )
        } catch (e: Throwable) {
            process?.destroy()
            closeStreams(process)
            return ShellResult.creationFailure(
                "Shizuku process creation failed: ${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            closeStreams(process)
            executor.shutdownNow()
        }
    }

    private fun getStreamFuture(future: Future<String>?, timeoutSeconds: Long): String {
        if (future == null) return ""
        return try {
            future.get(timeoutSeconds.coerceAtMost(2L), TimeUnit.SECONDS).trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun closeStreams(process: Process?) {
        try {
            process?.inputStream?.close()
        } catch (_: Throwable) {
        }
        try {
            process?.errorStream?.close()
        } catch (_: Throwable) {
        }
    }

    private fun readStream(stream: InputStream, maxChars: Int, keepLast: Boolean): String {
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

    companion object {
        fun shizukuFactory(): ShizukuProcessFactory = ShizukuProcessFactory { tokens ->
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, tokens.toTypedArray(), null, null) as Process
        }
    }
}
