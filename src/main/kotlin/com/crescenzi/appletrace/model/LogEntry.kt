package com.crescenzi.appletrace.model

data class LogEntry(
    val raw: String,
    val timestamp: String?,
    val process: String?,
    val subsystem: String?,
    val category: String?,
    val level: LogLevel,
    val message: String,
)
