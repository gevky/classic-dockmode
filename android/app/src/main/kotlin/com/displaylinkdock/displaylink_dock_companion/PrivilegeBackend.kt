package com.displaylinkdock.displaylink_dock_companion

data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val succeeded: Boolean get() = exitCode == 0
}

data class BackendProbe(
    val ready: Boolean,
    val name: String,
    val detail: String,
    val query: ExecResult? = null,
)

interface PrivilegeBackend {
    val name: String
    fun probe(): BackendProbe
    fun execute(command: List<String>): ExecResult
}
