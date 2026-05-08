package com.crescenzi.appletrace.service

import com.crescenzi.appletrace.model.DeviceKind
import com.crescenzi.appletrace.model.IosDevice
import com.crescenzi.appletrace.util.CommandRunner
import com.intellij.openapi.components.Service

/**
 * Discovers iOS devices available on the host (booted Simulators + connected
 * physical devices). All discovery is best-effort: missing tools return an empty
 * subset rather than failing the whole call.
 */
@Service
class DeviceService {

    /**
     * Returns only currently usable devices: booted simulators and connected
     * physical devices. Shutdown simulators and paired-but-disconnected iPhones
     * are excluded — there is nothing useful to stream from them.
     */
    fun listAll(): List<IosDevice> {
        val all = mutableListOf<IosDevice>()
        all += listSimulators()
        all += listPhysical()
        return all.sortedWith(
            compareByDescending<IosDevice> { it.kind == DeviceKind.PHYSICAL }
                .thenBy { it.name }
        )
    }

    private fun listSimulators(): List<IosDevice> {
        // `simctl list devices booted` only emits sims in the Booted state, so
        // we don't have to filter ourselves and we never see Shutdown ones.
        val output = CommandRunner.runCapturing(listOf("xcrun", "simctl", "list", "devices", "booted"))
            ?: return emptyList()
        val result = mutableListOf<IosDevice>()
        var currentRuntime = ""
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trimEnd()
            val rt = RUNTIME_HEADER.matchEntire(line.trim())
            if (rt != null) {
                currentRuntime = rt.groupValues[1].trim()
                continue
            }
            // Skip non-iOS runtimes (watchOS / tvOS / visionOS).
            if (!currentRuntime.startsWith("iOS", ignoreCase = true)) continue
            val m = DEVICE_LINE.matchEntire(line.trim()) ?: continue
            val name = m.groupValues[1].trim()
            val udid = m.groupValues[2]
            val state = m.groupValues[3].trim()
            // Defensive: even with `booted` filter, ignore anything that isn't.
            if (!state.equals("Booted", ignoreCase = true)) continue
            val osVersion = currentRuntime.removePrefix("iOS").trim()
            result += IosDevice(
                udid = udid,
                name = name,
                osVersion = osVersion,
                kind = DeviceKind.SIMULATOR,
                booted = true,
            )
        }
        return result
    }

    private fun listPhysical(): List<IosDevice> {
        val viaDevicectl = listPhysicalViaDevicectl()
        if (viaDevicectl.isNotEmpty()) return viaDevicectl
        return listPhysicalViaIdevice()
    }

    private fun listPhysicalViaDevicectl(): List<IosDevice> {
        // `xcrun devicectl list devices` produces a fixed-width table.
        val output = CommandRunner.runCapturing(listOf("xcrun", "devicectl", "list", "devices")) ?: return emptyList()
        val lines = output.lines()
        val headerIdx = lines.indexOfFirst { it.contains("Identifier", ignoreCase = true) && it.contains("State", ignoreCase = true) }
        if (headerIdx < 0) return emptyList()
        val header = lines[headerIdx]
        // Compute column starts by header titles.
        val nameStart = 0
        val identifierStart = header.indexOf("Identifier")
        val stateStart = header.indexOf("State")
        val modelStart = header.indexOf("Model")
        if (identifierStart < 0 || stateStart < 0) return emptyList()

        val devices = mutableListOf<IosDevice>()
        for (i in (headerIdx + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (line.startsWith("=") || line.startsWith("-")) continue
            if (line.length < stateStart) continue
            val name = line.substring(nameStart, identifierStart).trim()
            val identifier = line.substring(identifierStart, stateStart).trim()
            val state = if (modelStart in 0..line.length) line.substring(stateStart, modelStart).trim() else line.substring(stateStart).trim()
            if (identifier.isBlank()) continue
            // devicectl reports both paired-but-disconnected and connected; surface only connected.
            if (!state.contains("connected", ignoreCase = true)) continue
            devices += IosDevice(
                udid = identifier,
                name = name.ifBlank { "iPhone" },
                osVersion = "",
                kind = DeviceKind.PHYSICAL,
                booted = true,
            )
        }
        return devices
    }

    private fun listPhysicalViaIdevice(): List<IosDevice> {
        if (!CommandRunner.isOnPath("idevice_id")) return emptyList()
        val ids = CommandRunner.runCapturing(listOf("idevice_id", "-l")) ?: return emptyList()
        val devices = mutableListOf<IosDevice>()
        for (udid in ids.lines().map { it.trim() }.filter { it.isNotBlank() }) {
            val name = CommandRunner.runCapturing(listOf("ideviceinfo", "-u", udid, "-k", "DeviceName"))?.trim()
                ?: udid
            val osVersion = CommandRunner.runCapturing(listOf("ideviceinfo", "-u", udid, "-k", "ProductVersion"))?.trim().orEmpty()
            devices += IosDevice(
                udid = udid,
                name = name,
                osVersion = osVersion,
                kind = DeviceKind.PHYSICAL,
                booted = true,
            )
        }
        return devices
    }

    companion object {
        // Matches lines like: "-- iOS 17.2 --" or "-- watchOS 10.2 --".
        private val RUNTIME_HEADER = Regex("""--\s*(.+?)\s*--""")

        // Matches lines like: "    iPhone 15 (UUID) (Booted)" possibly preceded by whitespace.
        private val DEVICE_LINE = Regex("""(.+?)\s+\(([0-9A-Fa-f-]{8,})\)\s+\(([^)]+)\)""")
    }
}
