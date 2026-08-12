package com.mineserve.mobile.data

/** A non-destructive runtime and active-server health check. */
enum class DiagnosticStatus { Pass, Warning, Failed, NotApplicable, Running }

data class DiagnosticCheck(
    val id: String,
    val title: String,
    val detail: String,
    val status: DiagnosticStatus,
    val repairable: Boolean = false
)

data class DiagnosticReport(
    val checks: List<DiagnosticCheck> = emptyList(),
    val generatedAtMs: Long = 0L,
    val isRunning: Boolean = false
) {
    val issueCount: Int
        get() = checks.count { it.status == DiagnosticStatus.Warning || it.status == DiagnosticStatus.Failed }
}

data class ServerResourceStats(
    val processMemoryMb: Long? = null,
    val availableBytes: Long? = null,
    val directoryBytes: Long? = null,
    val javaAvailable: Boolean = false,
    val sampledAtMs: Long = 0L
)
