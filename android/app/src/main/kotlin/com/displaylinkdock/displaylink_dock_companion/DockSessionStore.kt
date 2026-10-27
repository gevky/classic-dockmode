package com.displaylinkdock.displaylink_dock_companion

import android.content.Context

data class SavedDeviceState(
    val overrideWidth: Int?,
    val overrideHeight: Int?,
    val accelerometerRotation: Int,
    val userRotation: Int,
    val screenBrightness: Int,
    val screenBrightnessMode: Int,
    val backend: String,
    val timestamp: Long,
    val externalDisplayId: Int?,
    val externalDisplayName: String?,
)

class DockSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("dock_session", Context.MODE_PRIVATE)

    val phase: String get() = prefs.getString(KEY_PHASE, PHASE_INACTIVE) ?: PHASE_INACTIVE
    val isSessionPresent: Boolean get() = phase != PHASE_INACTIVE
    val isTestPending: Boolean get() = phase == PHASE_TESTING
    val isActive: Boolean get() = phase == PHASE_ACTIVE
    val testDeadlineMillis: Long get() = prefs.getLong(KEY_TEST_DEADLINE, 0L)
    val targetLandscapeWidth: Int get() = prefs.getInt(KEY_TARGET_WIDTH, 0)
    val targetLandscapeHeight: Int get() = prefs.getInt(KEY_TARGET_HEIGHT, 0)

    fun savePending(
        state: SavedDeviceState,
        targetWidth: Int,
        targetHeight: Int,
        testDeadlineMillis: Long,
    ) {
        prefs.edit()
            .putString(KEY_PHASE, PHASE_PENDING)
            .putBoolean(KEY_HAD_OVERRIDE, state.overrideWidth != null && state.overrideHeight != null)
            .putInt(KEY_OVERRIDE_WIDTH, state.overrideWidth ?: 0)
            .putInt(KEY_OVERRIDE_HEIGHT, state.overrideHeight ?: 0)
            .putInt(KEY_ACCELEROMETER_ROTATION, state.accelerometerRotation)
            .putInt(KEY_USER_ROTATION, state.userRotation)
            .putInt(KEY_BRIGHTNESS, state.screenBrightness)
            .putInt(KEY_BRIGHTNESS_MODE, state.screenBrightnessMode)
            .putString(KEY_BACKEND, state.backend)
            .putLong(KEY_TIMESTAMP, state.timestamp)
            .putInt(KEY_EXTERNAL_ID, state.externalDisplayId ?: -1)
            .putString(KEY_EXTERNAL_NAME, state.externalDisplayName)
            .putInt(KEY_TARGET_WIDTH, targetWidth)
            .putInt(KEY_TARGET_HEIGHT, targetHeight)
            .putLong(KEY_TEST_DEADLINE, testDeadlineMillis)
            .commit()
    }

    fun markTesting() {
        prefs.edit().putString(KEY_PHASE, PHASE_TESTING).commit()
    }

    fun markActive() {
        prefs.edit()
            .putString(KEY_PHASE, PHASE_ACTIVE)
            .remove(KEY_TEST_DEADLINE)
            .commit()
    }

    fun readSavedState(): SavedDeviceState? {
        if (!isSessionPresent) return null
        val hadOverride = prefs.getBoolean(KEY_HAD_OVERRIDE, false)
        return SavedDeviceState(
            overrideWidth = if (hadOverride) prefs.getInt(KEY_OVERRIDE_WIDTH, 0) else null,
            overrideHeight = if (hadOverride) prefs.getInt(KEY_OVERRIDE_HEIGHT, 0) else null,
            accelerometerRotation = prefs.getInt(KEY_ACCELEROMETER_ROTATION, 1),
            userRotation = prefs.getInt(KEY_USER_ROTATION, 0),
            screenBrightness = prefs.getInt(KEY_BRIGHTNESS, 128),
            screenBrightnessMode = prefs.getInt(KEY_BRIGHTNESS_MODE, 1),
            backend = prefs.getString(KEY_BACKEND, "Direct") ?: "Direct",
            timestamp = prefs.getLong(KEY_TIMESTAMP, 0L),
            externalDisplayId = prefs.getInt(KEY_EXTERNAL_ID, -1).takeIf { it >= 0 },
            externalDisplayName = prefs.getString(KEY_EXTERNAL_NAME, null),
        )
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        const val PHASE_INACTIVE = "inactive"
        const val PHASE_PENDING = "pending"
        const val PHASE_TESTING = "testing"
        const val PHASE_ACTIVE = "active"

        private const val KEY_PHASE = "phase"
        private const val KEY_HAD_OVERRIDE = "had_override"
        private const val KEY_OVERRIDE_WIDTH = "override_width"
        private const val KEY_OVERRIDE_HEIGHT = "override_height"
        private const val KEY_ACCELEROMETER_ROTATION = "accelerometer_rotation"
        private const val KEY_USER_ROTATION = "user_rotation"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_BRIGHTNESS_MODE = "brightness_mode"
        private const val KEY_BACKEND = "backend"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_EXTERNAL_ID = "external_id"
        private const val KEY_EXTERNAL_NAME = "external_name"
        private const val KEY_TARGET_WIDTH = "target_width"
        private const val KEY_TARGET_HEIGHT = "target_height"
        private const val KEY_TEST_DEADLINE = "test_deadline"
    }
}
