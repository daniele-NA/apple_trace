package com.crescenzi.appletrace.toolWindow

import com.crescenzi.appletrace.AppleTraceBundle
import com.crescenzi.appletrace.model.IosDevice
import com.crescenzi.appletrace.model.LogEntry
import com.crescenzi.appletrace.model.LogLevel
import com.crescenzi.appletrace.service.DeviceService
import com.crescenzi.appletrace.service.LogStreamService
import com.crescenzi.appletrace.service.StreamListener
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.SideBorder
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import java.util.concurrent.TimeUnit
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent

class AppleTracePanel(project: Project, parentDisposable: Disposable) {

    private val deviceService = service<DeviceService>()
    private val streamService = project.service<LogStreamService>()

    private val deviceModel = DefaultComboBoxModel<IosDevice>()
    private val deviceCombo = ComboBox(deviceModel).apply {
        renderer = SimpleListCellRenderer.create("(no devices)") { it.displayName }
        prototypeDisplayValue = null
    }

    private val predicateField = JBTextField(28).apply {
        emptyText.text = "NSPredicate, e.g. eventMessage CONTAINS \"||\""
        toolTipText = "Server-side filter for simulators. Pushed to `log stream --predicate`. " +
            "Empty = full firehose. Ignored on physical devices (idevicesyslog has no predicate support)."
    }
    private val searchField = JBTextField(28).apply { emptyText.text = "filter logs (process, subsystem, category, message)" }
    private val clearButton = JButton(AppleTraceBundle["toolWindow.clear"], AllIcons.Actions.GC)

    private val statusLabel = JBLabel(" ")

    private val consoleView: ConsoleView = TextConsoleBuilderFactory.getInstance()
        .createBuilder(project)
        .console
        .also { Disposer.register(parentDisposable, it) }

    /** UDID of the device the active stream is bound to. */
    private var activeDeviceUdid: String? = null

    /**
     * Predicate string the active stream was started with. Tracked so a
     * keystroke that doesn't actually change the predicate doesn't tear down
     * and re-start the underlying `log stream` process.
     */
    private var activePredicate: String = ""

    /**
     * In-memory ring buffer of every parsed entry. Re-rendering the console on
     * search changes reads from here, so each keystroke can show or hide
     * already-printed lines without losing them. Capped at [MAX_BUFFER] to keep
     * memory bounded under firehose streams.
     */
    private val entries = ArrayDeque<LogEntry>(MAX_BUFFER)
    private val entriesLock = Any()

    /**
     * Lines staged for the next EDT flush. Coalescing avoids printing on every
     * single line (iOS unified logging is firehose-y on busy apps).
     */
    private val pending = ArrayDeque<Pair<String, ConsoleViewContentType>>()
    private val pendingLock = Any()

    private val flushTimer = Timer(FLUSH_INTERVAL_MS) { flushPending() }.apply {
        isRepeats = true
        isCoalesce = true
    }

    /** One-shot debounce for search keystrokes — avoids re-rendering on every char. */
    private val searchDebounce = Timer(SEARCH_DEBOUNCE_MS) { rerenderConsole() }.apply {
        isRepeats = false
    }

    /**
     * One-shot debounce for predicate keystrokes. Restarting `log stream` is
     * heavier than re-rendering, so this debounce is longer — we want the user
     * to finish typing before we tear down the process.
     */
    private val predicateDebounce = Timer(PREDICATE_DEBOUNCE_MS) { restartStream() }.apply {
        isRepeats = false
    }

    @Volatile
    private var lastDevices: List<IosDevice> = emptyList()

    val component: JComponent = buildComponent()

    init {
        Disposer.register(parentDisposable) { streamService.stop() }

        clearButton.addActionListener {
            consoleView.clear()
            synchronized(entriesLock) { entries.clear() }
            synchronized(pendingLock) { pending.clear() }
        }
        deviceCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) restartStream()
        }
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                searchDebounce.restart()
            }
        })
        predicateField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                predicateDebounce.restart()
            }
        })

        flushTimer.start()
        Disposer.register(parentDisposable) { flushTimer.stop() }
        Disposer.register(parentDisposable) { searchDebounce.stop() }
        Disposer.register(parentDisposable) { predicateDebounce.stop() }

        startDevicePolling(parentDisposable)
    }

    private fun buildComponent(): JComponent {
        val root = JBPanel<JBPanel<*>>(BorderLayout())
        root.add(buildToolbar(), BorderLayout.NORTH)
        root.add(consoleView.component, BorderLayout.CENTER)
        val south = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
            add(statusLabel, BorderLayout.WEST)
        }
        root.add(south, BorderLayout.SOUTH)
        return root
    }

    private fun buildToolbar(): JComponent {
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JBLabel(AppleTraceBundle["toolWindow.deviceLabel"]))
            add(deviceCombo)
            add(separator())
            add(predicateField)
            add(separator())
            add(JBLabel(AppleTraceBundle["toolWindow.searchLabel"]))
            add(searchField)
            add(separator())
            add(clearButton)
        }
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = IdeBorderFactory.createBorder(SideBorder.BOTTOM)
            add(row, BorderLayout.CENTER)
        }
    }

    private fun separator(): JComponent =
        JBLabel("|").apply { foreground = UIUtil.getInactiveTextColor() }

    private fun startDevicePolling(parentDisposable: Disposable) {
        val executor = AppExecutorUtil.getAppScheduledExecutorService()
        val future = executor.scheduleWithFixedDelay({
            try {
                val devices = deviceService.listAll()
                if (devices == lastDevices) return@scheduleWithFixedDelay
                lastDevices = devices
                SwingUtilities.invokeLater { applyDevices(devices) }
            } catch (t: Throwable) {
                thisLogger().warn("Apple Trace device poll failed", t)
            }
        }, 0L, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        Disposer.register(parentDisposable) { future.cancel(false) }
    }

    private fun applyDevices(devices: List<IosDevice>) {
        val previous = deviceCombo.selectedItem as? IosDevice
        deviceModel.removeAllElements()
        devices.forEach(deviceModel::addElement)
        if (devices.isEmpty()) {
            streamService.stop()
            activeDeviceUdid = null
            activePredicate = ""
            statusLabel.text = AppleTraceBundle["toolWindow.noDevices"]
        } else {
            val toSelect = devices.firstOrNull { it.udid == previous?.udid } ?: devices.first()
            deviceCombo.selectedItem = toSelect
        }
    }

    private fun restartStream() {
        val device = deviceCombo.selectedItem as? IosDevice
        if (device == null) {
            streamService.stop()
            activeDeviceUdid = null
            activePredicate = ""
            statusLabel.text = AppleTraceBundle["toolWindow.noDevices"]
            return
        }
        val predicate = predicateField.text.trim()
        // Same device AND same predicate AND already streaming → no-op. Without
        // this guard a single device-list refresh would tear down `log stream`
        // and miss the entries that arrive during the restart window.
        if (device.udid == activeDeviceUdid &&
            predicate == activePredicate &&
            streamService.isStreaming
        ) return
        activeDeviceUdid = device.udid
        activePredicate = predicate
        statusLabel.text = AppleTraceBundle["toolWindow.streaming", device.displayName]

        streamService.start(device, predicate.takeIf { it.isNotEmpty() }, object : StreamListener {
            override fun onEntry(entry: LogEntry) {
                synchronized(entriesLock) {
                    entries.addLast(entry)
                    while (entries.size > MAX_BUFFER) entries.removeFirst()
                }
                if (matchesSearch(entry)) {
                    val (text, type) = format(entry)
                    synchronized(pendingLock) { pending.addLast(text to type) }
                }
            }

            override fun onError(message: String) {
                SwingUtilities.invokeLater {
                    consoleView.print("$message\n", ConsoleViewContentType.ERROR_OUTPUT)
                    statusLabel.text = AppleTraceBundle["toolWindow.error", message]
                }
            }

            override fun onTerminated() { /* always-on: nothing to reset here */ }
        })
    }

    /**
     * Re-prints the entire visible buffer through the current search filter.
     * Called on every search keystroke (debounced) so lines appear/disappear
     * live as the query narrows or widens.
     */
    private fun rerenderConsole() {
        val snapshot = synchronized(entriesLock) { entries.toList() }
        // Drop any pending lines staged under the previous query — they would
        // arrive after our clear() and produce duplicates.
        synchronized(pendingLock) { pending.clear() }
        consoleView.clear()
        for (entry in snapshot) {
            if (!matchesSearch(entry)) continue
            val (text, type) = format(entry)
            consoleView.print(text, type)
        }
    }

    private fun flushPending() {
        val snapshot = synchronized(pendingLock) {
            if (pending.isEmpty()) emptyList<Pair<String, ConsoleViewContentType>>()
            else ArrayList(pending).also { pending.clear() }
        }
        for ((text, type) in snapshot) consoleView.print(text, type)
    }

    private fun matchesSearch(entry: LogEntry): Boolean {
        val q = searchField.text
        if (q.isBlank()) return true
        if (entry.message.contains(q, ignoreCase = true)) return true
        if (entry.subsystem?.contains(q, ignoreCase = true) == true) return true
        if (entry.process?.contains(q, ignoreCase = true) == true) return true
        if (entry.category?.contains(q, ignoreCase = true) == true) return true
        return false
    }

    private fun format(entry: LogEntry): Pair<String, ConsoleViewContentType> {
        val ts = entry.timestamp ?: ""
        val proc = entry.process ?: "-"
        val sub = entry.subsystem?.let { ":$it" } ?: ""
        val cat = entry.category?.let { ":$it" } ?: ""
        val levelTag = "[${entry.level.displayName.first()}]"
        val text = buildString {
            if (ts.isNotEmpty()) { append(ts); append(' ') }
            append(levelTag); append(' ')
            append(proc).append(sub).append(cat).append(": ")
            append(entry.message).append('\n')
        }
        val type = when (entry.level) {
            LogLevel.DEBUG -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
            LogLevel.INFO -> ConsoleViewContentType.LOG_INFO_OUTPUT
            LogLevel.DEFAULT -> ConsoleViewContentType.LOG_VERBOSE_OUTPUT
            LogLevel.ERROR -> ConsoleViewContentType.LOG_ERROR_OUTPUT
            LogLevel.FAULT -> ConsoleViewContentType.LOG_ERROR_OUTPUT
        }
        return text to type
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 250
        const val POLL_INTERVAL_SECONDS = 3L
        // iOS unified-logging at `--level debug` produces thousands of lines per
        // second on a busy sim. With a small cap, narrowing the search and then
        // widening it again (e.g. `x → y → x`) loses the original matches because
        // they get evicted while the user is typing. 50k lines ≈ tens of seconds
        // of buffer even on chatty apps, while staying under ~25 MB of memory.
        const val MAX_BUFFER = 50_000
        // Short enough to feel live, long enough to skip work mid-typing.
        const val SEARCH_DEBOUNCE_MS = 120
        // Restarting `log stream` is a heavier operation than re-rendering the
        // buffer, so wait longer before reacting to predicate edits — let the
        // user finish typing.
        const val PREDICATE_DEBOUNCE_MS = 400
    }
}
