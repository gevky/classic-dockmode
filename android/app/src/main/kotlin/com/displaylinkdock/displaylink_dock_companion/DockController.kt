package com.displaylinkdock.displaylink_dock_companion

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

data class DockOperationResult(
    val succeeded: Boolean,
    val message: String,
) {
    fun toMap(): Map<String, Any> = mapOf("succeeded" to succeeded, "message" to message)
}

data class TargetResolution(val width: Int, val height: Int)

class DockController(private val context: Context) {
    private val directBackend = DirectGrantBackend(context)
    private val store = DockSessionStore(context)
    private val displays = DisplayRepository(context)
    private val preferences = DockPreferences(context)

    fun status(): Map<String, Any?> {
        migrateConfirmedSession()
        val probe = directBackend.probe()
        val wmState = probe.query?.stdout?.let(WmSizeParser::parse)
            ?: WmSizeParser.parse(directBackend.execute(listOf("/system/bin/wm", "size")).stdout)
        val natural = displays.naturalDisplay()
        val logical = displays.logicalDisplay(wmState)
        val external = displays.preferredExternal()
        val config = preferences.read()
        val target = resolveTarget(config, external).getOrNull()
        val writeSettingsGranted = Settings.System.canWrite(context)
        return mapOf(
            "packageName" to context.packageName,
            "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            "androidVersion" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "presenterInstalled" to isPackageInstalled(PRESENTER_PACKAGE),
            "writeSecureSettingsGranted" to directBackend.hasSecureSettingsGrant(),
            "writeSettingsGranted" to writeSettingsGranted,
            "backendName" to probe.name,
            "backendReady" to (probe.ready && writeSettingsGranted),
            "backendDetail" to if (probe.ready && !writeSettingsGranted) {
                "wm size is available; Modify system settings access is still required"
            } else probe.detail,
            "naturalWidth" to natural.first,
            "naturalHeight" to natural.second,
            "naturalRefreshRate" to natural.third.toDouble(),
            "logicalWidth" to logical.first,
            "logicalHeight" to logical.second,
            "hasDisplayOverride" to (wmState?.overrideWidth != null),
            "externalDisplay" to external?.toMap(),
            "presentationDisplays" to displays.presentationDisplays().map { it.toMap() },
            "sessionPhase" to store.phase,
            "sessionActive" to store.isActive,
            "testActive" to store.isTestPending,
            "testDeadlineMillis" to store.testDeadlineMillis,
            "targetWidth" to store.targetLandscapeWidth,
            "targetHeight" to store.targetLandscapeHeight,
            "preferences" to config.toMap(),
            "selectedTargetWidth" to target?.width,
            "selectedTargetHeight" to target?.height,
            "selectedProfileConfirmed" to (
                target != null && preferences.isConfirmed(target.width, target.height)
            ),
            "frontendLabel" to config.frontendPackage?.let(::applicationLabel),
        )
    }

    fun probe(): Map<String, Any?> {
        val probe = directBackend.probe()
        return mapOf(
            "ready" to (probe.ready && Settings.System.canWrite(context)),
            "backend" to probe.name,
            "detail" to probe.detail,
            "exitCode" to probe.query?.exitCode,
            "stdout" to probe.query?.stdout,
            "stderr" to probe.query?.stderr,
        )
    }

    fun getPreferences(): Map<String, Any?> = preferences.read().toMap()

    fun updatePreferences(
        values: Map<String, Any?>,
        allowTemporaryMonitoring: Boolean = false,
    ): Map<String, Any?> {
        val config = preferences.update(values)
        if (values.containsKey("backgroundDetection")) {
            if (config.backgroundDetection) {
                DockService.start(context, scanNow = displays.preferredExternal() != null)
            } else if (!store.isSessionPresent) {
                if (allowTemporaryMonitoring) {
                    DockService.start(
                        context,
                        scanNow = displays.preferredExternal() != null,
                        allowTemporary = true,
                    )
                } else {
                    DockService.stop(context)
                }
            }
        }
        return config.toMap()
    }

    fun listLaunchableApplications(): List<Map<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return activities
            .filter { it.activityInfo.packageName != context.packageName }
            .associateBy { it.activityInfo.packageName }
            .values
            .map { info ->
                val label = context.packageManager
                    .getApplicationLabel(info.activityInfo.applicationInfo)
                    .toString()
                    .takeUnless { it == info.activityInfo.packageName }
                    ?: info.loadLabel(context.packageManager).toString()
                    .takeUnless { it == info.activityInfo.packageName }
                    ?: humanizePackageName(info.activityInfo.packageName)
                mapOf(
                    "packageName" to info.activityInfo.packageName,
                    "label" to label,
                )
            }
            .sortedBy { it["label"]?.lowercase() }
    }

    fun testSelectedOutput(): DockOperationResult = synchronized(SESSION_LOCK) {
        if (store.isSessionPresent) {
            return@synchronized DockOperationResult(
                false,
                "Restore the current dock session before starting another test",
            )
        }
        val external = displays.preferredExternal()
            ?: return@synchronized DockOperationResult(false, "Connect DisplayLink Presenter first")
        val config = preferences.read()
        val targetResult = resolveTarget(config, external)
        val target = targetResult.getOrElse {
            return@synchronized DockOperationResult(false, it.message ?: "Invalid output profile")
        }
        applySession(target, config, external, testing = true)
    }

    fun testOutput(landscapeWidth: Int, landscapeHeight: Int): DockOperationResult =
        synchronized(SESSION_LOCK) {
            if (store.isSessionPresent) {
                return@synchronized DockOperationResult(
                    false,
                    "Restore the current dock session before starting another test",
                )
            }
            val validation = validateTarget(landscapeWidth, landscapeHeight)
            if (validation != null) return@synchronized DockOperationResult(false, validation)
            val external = displays.preferredExternal()
                ?: return@synchronized DockOperationResult(false, "Connect DisplayLink Presenter first")
            applySession(
                TargetResolution(landscapeWidth, landscapeHeight),
                preferences.read(),
                external,
                testing = true,
            )
        }

    fun startDockMode(automatic: Boolean = false): DockOperationResult = synchronized(SESSION_LOCK) {
        if (store.isActive) return@synchronized DockOperationResult(true, "Dock Mode is already active")
        if (store.isSessionPresent) {
            return@synchronized DockOperationResult(false, "An output test is already running")
        }
        val config = preferences.read()
        if (automatic && !config.automaticDocking) {
            return@synchronized DockOperationResult(false, "Automatic Dock Mode is disabled")
        }
        val external = displays.preferredExternal()
            ?: return@synchronized DockOperationResult(false, "No DisplayLink presentation display found")
        val targetResult = resolveTarget(config, external)
        val target = targetResult.getOrElse {
            return@synchronized DockOperationResult(false, it.message ?: "Invalid output profile")
        }
        if (!preferences.isConfirmed(target.width, target.height)) {
            return@synchronized DockOperationResult(
                false,
                "Test and keep ${target.width}×${target.height} once before starting it automatically",
            )
        }
        applySession(target, config, external, testing = false)
    }

    fun autoStartIfReady(): DockOperationResult = startDockMode(automatic = true)

    fun externalDisplayConnected(): Boolean = displays.preferredExternal() != null

    fun keepTest(): DockOperationResult = synchronized(SESSION_LOCK) {
        if (!store.isTestPending) {
            return@synchronized DockOperationResult(false, "No output test is running")
        }
        preferences.markConfirmed(store.targetLandscapeWidth, store.targetLandscapeHeight)
        TestTimeoutReceiver.cancel(context)
        store.markActive()
        DockService.start(context, scanNow = false)
        val launchResult = launchConfiguredFrontend(preferences.read())
        if (launchResult == null) {
            DockOperationResult(true, "Output confirmed. Automatic disconnect recovery is active.")
        } else {
            DockOperationResult(true, "Output confirmed. $launchResult")
        }
    }

    fun restore(): DockOperationResult = synchronized(SESSION_LOCK) {
        TestTimeoutReceiver.cancel(context)
        val state = store.readSavedState()
        if (state == null) {
            val reset = directBackend.execute(listOf("/system/bin/wm", "size", "reset"))
            maintainMonitorAfterRestore()
            return@synchronized if (reset.succeeded) {
                DockOperationResult(true, "Display override reset. No saved dock session was present.")
            } else {
                DockOperationResult(false, "No saved session; wm size reset failed: ${errorText(reset)}")
            }
        }

        val errors = mutableListOf<String>()
        val sizeResult = if (state.overrideWidth != null && state.overrideHeight != null) {
            directBackend.execute(
                listOf("/system/bin/wm", "size", "${state.overrideWidth}x${state.overrideHeight}"),
            )
        } else {
            directBackend.execute(listOf("/system/bin/wm", "size", "reset"))
        }
        if (!sizeResult.succeeded) errors += "display size: ${errorText(sizeResult)}"
        if (!putSystemInt(Settings.System.USER_ROTATION, state.userRotation)) errors += "user rotation"
        if (!putSystemInt(Settings.System.ACCELEROMETER_ROTATION, state.accelerometerRotation)) {
            errors += "automatic rotation"
        }
        if (!putSystemInt(Settings.System.SCREEN_BRIGHTNESS, state.screenBrightness)) {
            errors += "brightness"
        }
        if (!putSystemInt(Settings.System.SCREEN_BRIGHTNESS_MODE, state.screenBrightnessMode)) {
            errors += "brightness mode"
        }

        if (errors.isEmpty()) {
            store.clear()
            maintainMonitorAfterRestore()
            return@synchronized DockOperationResult(true, "Original handheld settings restored")
        }
        DockOperationResult(
            false,
            "Restore incomplete (${errors.joinToString()}). Saved recovery state was retained.",
        )
    }

    fun recoverAndStartMonitoring(): DockOperationResult? = recover(allowTemporary = true)

    private fun recover(allowTemporary: Boolean): DockOperationResult? {
        val state = store.readSavedState()
        if (state != null) {
            if (!store.isActive || !displays.hasMatchingExternal(state)) return restore()
            migrateConfirmedSession()
            return try {
                DockService.start(context, scanNow = false)
                null
            } catch (error: RuntimeException) {
                DockOperationResult(
                    false,
                    "Dock session is active, but monitoring could not restart: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
        if (preferences.read().backgroundDetection || allowTemporary) {
            DockService.start(
                context,
                scanNow = displays.preferredExternal() != null,
                allowTemporary = allowTemporary,
            )
        }
        return null
    }

    fun restoreStaleSessionIfNeeded(): DockOperationResult? = recover(allowTemporary = false)

    fun restoreIfTestPending(): DockOperationResult? = if (store.isTestPending) restore() else null

    fun stopIdleMonitoringIfDisabled() {
        if (!preferences.read().backgroundDetection) {
            if (store.isSessionPresent) DockService.disableTemporaryMonitoring(context)
            else DockService.stop(context)
        }
    }

    fun openWriteSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun applySession(
        target: TargetResolution,
        config: DockConfiguration,
        external: ExternalDisplayInfo,
        testing: Boolean,
    ): DockOperationResult {
        validateTarget(target.width, target.height)?.let {
            return DockOperationResult(false, it)
        }
        val probe = directBackend.probe()
        if (!probe.ready) return DockOperationResult(false, probe.detail)
        if (!Settings.System.canWrite(context)) {
            return DockOperationResult(false, "Grant Modify system settings access first")
        }

        val currentSize = WmSizeParser.parse(
            directBackend.execute(listOf("/system/bin/wm", "size")).stdout,
        ) ?: return DockOperationResult(false, "Could not read the current display size")
        val state = SavedDeviceState(
            overrideWidth = currentSize.overrideWidth,
            overrideHeight = currentSize.overrideHeight,
            accelerometerRotation = Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                1,
            ),
            userRotation = Settings.System.getInt(
                context.contentResolver,
                Settings.System.USER_ROTATION,
                0,
            ),
            screenBrightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128,
            ),
            screenBrightnessMode = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            ),
            backend = directBackend.name,
            timestamp = System.currentTimeMillis(),
            externalDisplayId = external.displayId,
            externalDisplayName = external.name,
        )
        val deadline = if (testing) System.currentTimeMillis() + TEST_DURATION_MILLIS else 0L
        store.savePending(state, target.width, target.height, deadline)

        val natural = displays.naturalDisplay()
        val naturalIsPortrait = natural.first < natural.second
        val wmWidth = if (naturalIsPortrait) target.height else target.width
        val wmHeight = if (naturalIsPortrait) target.width else target.height
        val desiredRotation = when (config.rotationMode) {
            DockPreferences.ROTATION_90 -> 1
            DockPreferences.ROTATION_270 -> 3
            else -> if (naturalIsPortrait) 1 else 0
        }

        val applySize = directBackend.execute(
            listOf("/system/bin/wm", "size", "${wmWidth}x${wmHeight}"),
        )
        if (!applySize.succeeded) return rollbackFailure("Resolution failed: ${errorText(applySize)}")
        if (!putSystemInt(Settings.System.ACCELEROMETER_ROTATION, 0)) {
            return rollbackFailure("Could not disable automatic rotation")
        }
        if (!putSystemInt(Settings.System.USER_ROTATION, desiredRotation)) {
            return rollbackFailure("Could not set landscape rotation")
        }
        when (config.brightnessMode) {
            DockPreferences.BRIGHTNESS_MINIMUM -> {
                if (!putSystemInt(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) ||
                    !putSystemInt(Settings.System.SCREEN_BRIGHTNESS, 1)
                ) return rollbackFailure("Could not reduce handheld brightness")
            }
            DockPreferences.BRIGHTNESS_CUSTOM -> {
                if (!putSystemInt(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) ||
                    !putSystemInt(Settings.System.SCREEN_BRIGHTNESS, config.customBrightness)
                ) return rollbackFailure("Could not set handheld brightness")
            }
        }

        val verified = WmSizeParser.parse(
            directBackend.execute(listOf("/system/bin/wm", "size")).stdout,
        )
        if (verified?.overrideWidth != wmWidth || verified.overrideHeight != wmHeight) {
            return rollbackFailure("Android rejected or clamped the requested resolution")
        }
        val requestedRatio = target.width.toDouble() / target.height
        val appliedRatio = verified.overrideHeight!!.toDouble() / verified.overrideWidth!!
        val naturalAppliedRatio = verified.overrideWidth.toDouble() / verified.overrideHeight
        val ratioMatches = if (naturalIsPortrait) {
            kotlin.math.abs(appliedRatio - requestedRatio) < 0.02
        } else {
            kotlin.math.abs(naturalAppliedRatio - requestedRatio) < 0.02
        }
        if (!ratioMatches) return rollbackFailure("The applied display aspect ratio is incorrect")

        if (testing) {
            store.markTesting()
            TestTimeoutReceiver.schedule(context, TEST_DURATION_MILLIS)
        } else {
            store.markActive()
        }
        try {
            DockService.start(context, scanNow = false)
        } catch (error: RuntimeException) {
            return rollbackFailure(
                "Could not start dock monitoring: ${error.message ?: error.javaClass.simpleName}",
            )
        }
        if (!testing) launchConfiguredFrontend(config)
        return if (testing) {
            DockOperationResult(
                true,
                "Testing ${target.width}×${target.height}. Keep it within 15 seconds or it will revert.",
            )
        } else {
            DockOperationResult(true, "Dock Mode started at ${target.width}×${target.height}")
        }
    }

    private fun resolveTarget(
        config: DockConfiguration,
        external: ExternalDisplayInfo?,
    ): Result<TargetResolution> {
        val target = when (config.profile) {
            DockPreferences.PROFILE_720 -> TargetResolution(1280, 720)
            DockPreferences.PROFILE_MATCH -> {
                if (external == null) return Result.failure(IllegalStateException("Connect a display first"))
                if (external.width >= external.height) {
                    TargetResolution(external.width, external.height)
                } else {
                    TargetResolution(external.height, external.width)
                }
            }
            DockPreferences.PROFILE_CUSTOM -> TargetResolution(config.customWidth, config.customHeight)
            else -> TargetResolution(1920, 1080)
        }
        val validation = validateTarget(target.width, target.height)
        return if (validation == null) Result.success(target)
        else Result.failure(IllegalArgumentException(validation))
    }

    private fun validateTarget(width: Int, height: Int): String? = when {
        width < 640 || height < 360 -> "Minimum output resolution is 640×360"
        width > 3840 || height > 2160 -> "Maximum output resolution is 3840×2160"
        width <= height -> "Dock output must be landscape"
        else -> null
    }

    private fun launchConfiguredFrontend(config: DockConfiguration): String? {
        if (!config.launchEnabled || config.frontendPackage.isNullOrBlank()) return null
        val intent = context.packageManager.getLaunchIntentForPackage(config.frontendPackage)
            ?: return "The selected frontend is no longer launchable."
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val pendingIntent = PendingIntent.getActivity(
                context,
                config.frontendPackage.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode =
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                pendingIntent.send(
                    context,
                    0,
                    null,
                    null,
                    null,
                    null,
                    options.toBundle(),
                )
            } else {
                pendingIntent.send()
            }
            "Opened ${applicationLabel(config.frontendPackage) ?: config.frontendPackage}."
        } catch (error: Exception) {
            "Could not open the selected frontend: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun applicationLabel(packageName: String): String? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, 0)
        }
        context.packageManager.getApplicationLabel(info).toString()
            .takeUnless { it == packageName }
            ?: humanizePackageName(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun humanizePackageName(packageName: String): String = packageName
        .substringAfterLast('.')
        .replace('_', ' ')
        .replace('-', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun migrateConfirmedSession() {
        if (store.isActive && store.targetLandscapeWidth > 0 && store.targetLandscapeHeight > 0) {
            preferences.markConfirmed(store.targetLandscapeWidth, store.targetLandscapeHeight)
        }
    }

    private fun maintainMonitorAfterRestore() {
        if (!preferences.read().backgroundDetection) {
            DockService.stop(context)
            return
        }
        try {
            DockService.start(context, scanNow = false)
        } catch (_: RuntimeException) {
            // Recovery still succeeded; monitoring restarts on the next app open or boot.
        }
    }

    private fun rollbackFailure(message: String): DockOperationResult {
        val rollback = restore()
        return DockOperationResult(
            false,
            if (rollback.succeeded) "$message. Previous settings were restored." else "$message. ${rollback.message}",
        )
    }

    private fun putSystemInt(name: String, value: Int): Boolean = try {
        Settings.System.putInt(context.contentResolver, name, value)
    } catch (_: SecurityException) {
        false
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun errorText(result: ExecResult): String =
        result.stderr.trim().ifBlank { result.stdout.trim().ifBlank { "exit ${result.exitCode}" } }

    companion object {
        const val PRESENTER_PACKAGE = "com.displaylink.presenter"
        const val TEST_DURATION_MILLIS = 15_000L
        private val SESSION_LOCK = Any()
    }
}
