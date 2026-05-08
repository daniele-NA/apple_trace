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

    fun start(device: IosDevice, predicate: String?, listener: StreamListener) {
        stop()
        val command = buildCommand(device, predicate)
        if (command == null) {
            listener.onError("idevicesyslog not found. Install it with: brew install libimobiledevice")
            return
        }
        thisLogger().info("Apple Trace command: ${command.joinToString(" ")}")
        try {
            val handler = CommandRunner.streamingHandler(command)
            this.handler = handler
            this.currentDevice = device
            // Process output arrives in arbitrary chunks — a single NDJSON record
            // can be split across multiple `onTextAvailable` callbacks, and the
            // first half won't parse as JSON. Buffer the tail until a newline
            // arrives, then emit complete lines. With `withRedirectErrorStream`
            // the merged stream comes from a single reader thread, so no extra
            // synchronization is needed here.
            val partial = StringBuilder()
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val raw = event.text ?: return
                    partial.append(raw)
                    while (true) {
                        val nl = partial.indexOf('\n')
                        if (nl < 0) break
                        val line = partial.substring(0, nl)
                        partial.delete(0, nl + 1)
                        if (line.isBlank()) continue
                        val entry = parseLine(device.kind, line) ?: continue
                        listener.onEntry(entry)
                    }
                    // Defensive cap: if a producer stops emitting newlines (very
                    // unlikely with `log stream --style ndjson`), don't grow the
                    // buffer without bound — flush whatever we have as a single
                    // line so it's at least visible.
                    if (partial.length > MAX_PARTIAL_BUFFER) {
                        val line = partial.toString()
                        partial.setLength(0)
                        val entry = parseLine(device.kind, line) ?: return
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

    private fun buildCommand(device: IosDevice, predicate: String?): List<String>? {
        return when (device.kind) {
            DeviceKind.SIMULATOR -> buildSimulatorCommand(device, predicate)
            DeviceKind.PHYSICAL -> buildPhysicalCommand(device)
        }
    }

    private fun buildSimulatorCommand(device: IosDevice, predicate: String?): List<String> {
        // `--level debug` is critical: Apple's `log stream` defaults to showing
        // only entries at the "default" severity and above, which silently drops
        // every `info` and `debug` event. Flutter's `print()` / `debugPrint()`
        // and many framework messages land at those lower levels — without this
        // flag the console looks empty when an app is actually logging plenty.
        //
        // The optional `predicate` is forwarded to `--predicate`. It uses
        // NSPredicate syntax against unified-log fields (eventMessage, subsystem,
        // category, processImagePath, …). Pushing the filter into `log stream`
        // is dramatically cheaper than client-side filtering: a busy simulator
        // emits hundreds of lines per second and the in-memory buffer gets
        // evicted before the user can find their app's logs in the noise.
        // Common Flutter recipe: `eventMessage CONTAINS "||"` (or whatever
        // marker your AppLogger embeds in every line).
        val base = listOf(
            "xcrun", "simctl", "spawn", device.udid,
            "log", "stream",
            "--style", "ndjson",
            "--level", "debug",
        )
        return if (predicate.isNullOrBlank()) base else base + listOf("--predicate", predicate)
    }

    private fun buildPhysicalCommand(device: IosDevice): List<String>? {
        if (!CommandRunner.isOnPath("idevicesyslog")) return null
        // `idevicesyslog` does not understand NSPredicate; the predicate field
        // is simulator-only by design. Physical devices stay on the existing
        // unfiltered firehose and rely on client-side search.
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
        if (!trimmed.startsWith("{")) {
            // Should not happen with `--style ndjson`, but if simctl ever prints
            // a banner, warning, or any non-JSON line, surface it as a raw
            // DEFAULT entry rather than dropping it on the floor. Losing logs
            // silently is the worst possible failure mode for this tool.
            return LogEntry(
                raw = trimmed,
                timestamp = null,
                process = null,
                subsystem = null,
                category = null,
                level = LogLevel.DEFAULT,
                message = trimmed,
            )
        }
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
        private const val MAX_PARTIAL_BUFFER = 1_048_576 // 1 MiB; an unterminated chunk this big means something is very wrong upstream.
        private val SYSLOG_TS = Regex("""^\w{3}\s+\d+\s+\d{2}:\d{2}:\d{2}""")
        private val SYSLOG_PROCESS = Regex("""\s([^\s\[]+)\[\d+]""")
        private val SYSLOG_LEVEL = Regex("""<(Debug|Info|Notice|Default|Warning|Error|Fault)>""", RegexOption.IGNORE_CASE)
    }
}
