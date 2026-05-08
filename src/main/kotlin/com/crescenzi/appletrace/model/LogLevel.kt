package com.crescenzi.appletrace.model

/**
 * Apple unified-logging level. Used internally to choose the console color
 * for each rendered line.
 */
enum class LogLevel(val displayName: String) {
    DEBUG("Debug"),
    INFO("Info"),
    DEFAULT("Default"),
    ERROR("Error"),
    FAULT("Fault");

    override fun toString(): String = displayName
}
