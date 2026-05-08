package com.crescenzi.appletrace.model

enum class DeviceKind { SIMULATOR, PHYSICAL }

data class IosDevice(
    val udid: String,
    val name: String,
    val osVersion: String,
    val kind: DeviceKind,
    val booted: Boolean,
) {
    val displayName: String
        get() {
            val tag = when (kind) {
                DeviceKind.SIMULATOR -> "Simulator"
                DeviceKind.PHYSICAL -> "Device"
            }
            val os = if (osVersion.isNotBlank()) " — iOS $osVersion" else ""
            return "$name [$tag]$os"
        }

    override fun toString(): String = displayName
}
