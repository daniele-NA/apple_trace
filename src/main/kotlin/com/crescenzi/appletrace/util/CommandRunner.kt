package com.crescenzi.appletrace.util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Thin helpers around IntelliJ's process API.
 */
object CommandRunner {

    /**
     * Runs a command synchronously, captures stdout, and returns it.
     * Returns null if the binary is missing or the exit code is non-zero.
     */
    fun runCapturing(command: List<String>, timeoutMs: Long = 10_000): String? {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                thisLogger().warn("Command timed out: ${command.joinToString(" ")}")
                return null
            }
            val output = process.inputStream.bufferedReader().readText()
            if (process.exitValue() != 0) {
                thisLogger().warn("Non-zero exit (${process.exitValue()}) for: ${command.joinToString(" ")}\n$output")
                return null
            }
            output
        } catch (t: Throwable) {
            thisLogger().warn("Failed to run: ${command.joinToString(" ")}", t)
            null
        }
    }

    /**
     * Returns true if `which <bin>` succeeds.
     */
    fun isOnPath(binary: String): Boolean {
        val out = runCapturing(listOf("/usr/bin/which", binary), 3_000) ?: return false
        return out.isNotBlank()
    }

    /**
     * Builds a long-running ProcessHandler for log streaming.
     * Caller must start, listen, and dispose it.
     */
    fun streamingHandler(command: List<String>): ProcessHandler {
        val cmdLine = GeneralCommandLine(command).withRedirectErrorStream(true)
        return OSProcessHandler(cmdLine)
    }
}
