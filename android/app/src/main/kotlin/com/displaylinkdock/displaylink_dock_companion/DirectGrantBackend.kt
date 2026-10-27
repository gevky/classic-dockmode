package com.displaylinkdock.displaylink_dock_companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.util.concurrent.TimeUnit

class DirectGrantBackend(private val context: Context) : PrivilegeBackend {
    override val name: String = "Direct"

    fun hasSecureSettingsGrant(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    override fun probe(): BackendProbe {
        if (!hasSecureSettingsGrant()) {
            return BackendProbe(
                ready = false,
                name = name,
                detail = "WRITE_SECURE_SETTINGS is not granted",
            )
        }

        val result = execute(listOf("/system/bin/wm", "size"))
        val readable = result.succeeded && WmSizeParser.parse(result.stdout) != null
        return BackendProbe(
            ready = readable,
            name = name,
            detail = when {
                readable -> "ADB grant present and wm size is readable"
                result.stderr.isNotBlank() -> result.stderr.trim()
                else -> "wm size did not return a readable display size"
            },
            query = result,
        )
    }

    override fun execute(command: List<String>): ExecResult {
        if (command.isEmpty()) return ExecResult(-1, "", "Empty command")
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ExecResult(-1, "", "Command timed out")
            }
            ExecResult(
                exitCode = process.exitValue(),
                stdout = process.inputStream.bufferedReader().use { it.readText() },
                stderr = process.errorStream.bufferedReader().use { it.readText() },
            )
        } catch (error: IOException) {
            ExecResult(-1, "", error.message ?: error.javaClass.simpleName)
        } catch (error: SecurityException) {
            ExecResult(-1, "", error.message ?: "Permission denied")
        }
    }
}
