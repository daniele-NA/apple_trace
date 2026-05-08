package com.crescenzi.appletrace.service

import com.crescenzi.appletrace.model.DeviceKind
import com.crescenzi.appletrace.model.IosDevice
import com.crescenzi.appletrace.model.LogEntry
import com.crescenzi.appletrace.model.LogLevel
import com.crescenzi.appletrace.util.CommandRunner
import com.crescenzi.appletrace.util.JsonExtract
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

interface StreamListener {
    fun onEntry(entry: LogEntry)
    fun onError(message: String)
    fun onTerminated()
}

@Service(Service.Level.PROJECT)
class LogStreamService(private val project: Project) : Disposable {

    private var handler: ProcessHandler? = null
    private var currentDevice: IosDevice? = null

    val isStreaming: Boolean get() = handler?.let { !it.isProcessTerminated } == true

    fun streamingDevice(): IosDevice? = currentDevice

    fun start(device: IosDevice, listener: StreamListener) {
        stop()
        val command = buildCommand(device)
        if (command == null) {
            listener.onError("idevicesyslog not found. Install it with: brew install libimobiledevice")
            return
        }
        thisLogger().info("Apple Trace command: ${command.joinToString(" ")}")
        try {
            val handler = CommandRunner.streamingHandler(command)
            this.handler = handler
            this.currentDevice = device
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val raw = event.text ?: return
                    for (line in raw.split('\n')) {
                        if (line.isBlank()) continue
                        val entry = parseLine(device.kind, line) ?: continue
                        listener.onEntry(entry)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    listener.onTerminated()
                    this@LogStreamService.handler = null
                    this@LogStreamService.currentDevice = null
                }
            })
            handler.startNotify()
        } catch (t: Throwable) {
            thisLogger().warn("Failed to start log stream", t)
            listener.onError(t.message ?: t.toString())
        }
    }

    fun stop() {
        val h = handler ?: return
        try {
            h.destroyProcess()
        } catch (_: Throwable) { /* ignore */ }
        handler = null
        currentDevice = null
    }

    private fun buildCommand(device: IosDevice): List<String>? {
        return when (device.kind) {
            DeviceKind.SIMULATOR -> buildSimulatorCommand(device)
            DeviceKind.PHYSICAL -> buildPhysicalCommand(device)
        }
    }

    private fun buildSimulatorCommand(device: IosDevice): List<String> {
        // `--level debug` is critical: Apple's `log stream` defaults to showing
        // only entries at the "default" severity and above, which silently drops
        // every `info` and `debug` event. Flutter's `print()` / `debugPrint()`
        // and many framework messages land at those lower levels — without this
        // flag the console looks empty when an app is actually logging plenty.
        return listOf(
            "xcrun", "simctl", "spawn", device.udid,
            "log", "stream",
            "--style", "ndjson",
            "--level", "debug",
        )
    }

    private fun buildPhysicalCommand(device: IosDevice): List<String>? {
        if (!CommandRunner.isOnPath("idevicesyslog")) return null
        return listOf("idevicesyslog", "-u", device.udid)
    }

    private fun parseLine(kind: DeviceKind, line: String): LogEntry? {
        return when (kind) {
            DeviceKind.SIMULATOR -> parseNdjsonLine(line)
            DeviceKind.PHYSICAL -> parseSyslogLine(line)
        }
    }

    private fun parseNdjsonLine(line: String): LogEntry? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        val message = JsonExtract.string(trimmed, "eventMessage").orEmpty()
        val subsystem = JsonExtract.string(trimmed, "subsystem")
        val category = JsonExtract.string(trimmed, "category")
        val processPath = JsonExtract.string(trimmed, "processImagePath")
        val processName = processPath?.substringAfterLast('/')
        val messageType = JsonExtract.string(trimmed, "messageType")
        val timestamp = JsonExtract.string(trimmed, "timestamp")
        val level = mapLevel(messageType)
        return LogEntry(
            raw = trimmed,
            timestamp = timestamp,
            process = processName,
            subsystem = subsystem,
            category = category,
            level = level,
            message = message,
        )
    }

    private fun parseSyslogLine(line: String): LogEntry {
        val ts = SYSLOG_TS.find(line)?.value
        val proc = SYSLOG_PROCESS.find(line)?.groupValues?.getOrNull(1)
        val levelMatch = SYSLOG_LEVEL.find(line)
        val level = mapLevel(levelMatch?.groupValues?.getOrNull(1))
        val msgStart = levelMatch?.range?.endInclusive?.plus(1)
            ?: line.indexOf("]:").takeIf { it >= 0 }?.plus(2)
            ?: 0
        val message = line.substring(msgStart.coerceAtMost(line.length)).trimStart(':', ' ')
        return LogEntry(
            raw = line,
            timestamp = ts,
            process = proc,
            subsystem = null,
            category = null,
            level = level,
            message = message,
        )
    }

    private fun mapLevel(messageType: String?): LogLevel = when (messageType?.lowercase()) {
        "debug" -> LogLevel.DEBUG
        "info" -> LogLevel.INFO
        "default", "notice" -> LogLevel.DEFAULT
        "error" -> LogLevel.ERROR
        "fault" -> LogLevel.FAULT
        else -> LogLevel.DEFAULT
    }

    override fun dispose() {
        stop()
    }

    init {
        Disposer.register(project, this)
    }

    companion object {
        private val SYSLOG_TS = Regex("""^\w{3}\s+\d+\s+\d{2}:\d{2}:\d{2}""")
        private val SYSLOG_PROCESS = Regex("""\s([^\s\[]+)\[\d+]""")
        private val SYSLOG_LEVEL = Regex("""<(Debug|Info|Notice|Default|Warning|Error|Fault)>""", RegexOption.IGNORE_CASE)
    }
}
