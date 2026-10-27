package com.displaylinkdock.displaylink_dock_companion

import android.content.Context

data class DockConfiguration(
    val automaticDocking: Boolean,
    val backgroundDetection: Boolean,
    val profile: String,
    val customWidth: Int,
    val customHeight: Int,
    val brightnessMode: String,
    val customBrightness: Int,
    val rotationMode: String,
    val launchEnabled: Boolean,
    val frontendPackage: String?,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "automaticDocking" to automaticDocking,
        "backgroundDetection" to backgroundDetection,
        "profile" to profile,
        "customWidth" to customWidth,
        "customHeight" to customHeight,
        "brightnessMode" to brightnessMode,
        "customBrightness" to customBrightness,
        "rotationMode" to rotationMode,
        "launchEnabled" to launchEnabled,
        "frontendPackage" to frontendPackage,
    )
}

class DockPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("dock_preferences", Context.MODE_PRIVATE)

    fun read(): DockConfiguration {
        val frontendPackage = prefs.getString(KEY_FRONTEND_PACKAGE, null)
        return DockConfiguration(
            // Automatic docking is the product's default behavior. There is no separate UI switch.
            automaticDocking = true,
            backgroundDetection = prefs.getBoolean(KEY_BACKGROUND_DETECTION, true),
            profile = prefs.getString(KEY_PROFILE, PROFILE_1080) ?: PROFILE_1080,
            customWidth = prefs.getInt(KEY_CUSTOM_WIDTH, 1600),
            customHeight = prefs.getInt(KEY_CUSTOM_HEIGHT, 900),
            brightnessMode = prefs.getString(KEY_BRIGHTNESS_MODE, BRIGHTNESS_MINIMUM)
                ?: BRIGHTNESS_MINIMUM,
            customBrightness = prefs.getInt(KEY_CUSTOM_BRIGHTNESS, 16).coerceIn(1, 255),
            rotationMode = prefs.getString(KEY_ROTATION_MODE, ROTATION_AUTO) ?: ROTATION_AUTO,
            // Choosing an app means it should launch; choosing None removes the package.
            launchEnabled = !frontendPackage.isNullOrBlank(),
            frontendPackage = frontendPackage,
        )
    }

    fun update(values: Map<String, Any?>): DockConfiguration {
        val editor = prefs.edit()
        (values["automaticDocking"] as? Boolean)?.let { editor.putBoolean(KEY_AUTOMATIC, it) }
        (values["backgroundDetection"] as? Boolean)?.let {
            editor.putBoolean(KEY_BACKGROUND_DETECTION, it)
        }
        (values["profile"] as? String)?.takeIf { it in PROFILES }?.let {
            editor.putString(KEY_PROFILE, it)
        }
        (values["customWidth"] as? Number)?.toInt()?.let { editor.putInt(KEY_CUSTOM_WIDTH, it) }
        (values["customHeight"] as? Number)?.toInt()?.let { editor.putInt(KEY_CUSTOM_HEIGHT, it) }
        (values["brightnessMode"] as? String)?.takeIf { it in BRIGHTNESS_MODES }?.let {
            editor.putString(KEY_BRIGHTNESS_MODE, it)
        }
        (values["customBrightness"] as? Number)?.toInt()?.coerceIn(1, 255)?.let {
            editor.putInt(KEY_CUSTOM_BRIGHTNESS, it)
        }
        (values["rotationMode"] as? String)?.takeIf { it in ROTATION_MODES }?.let {
            editor.putString(KEY_ROTATION_MODE, it)
        }
        (values["launchEnabled"] as? Boolean)?.let { editor.putBoolean(KEY_LAUNCH_ENABLED, it) }
        if (values.containsKey("frontendPackage")) {
            val packageName = values["frontendPackage"] as? String
            if (packageName.isNullOrBlank()) editor.remove(KEY_FRONTEND_PACKAGE)
            else editor.putString(KEY_FRONTEND_PACKAGE, packageName)
        }
        editor.commit()
        return read()
    }

    fun markConfirmed(width: Int, height: Int) {
        prefs.edit().putBoolean(confirmationKey(width, height), true).commit()
    }

    fun isConfirmed(width: Int, height: Int): Boolean =
        (width == 1920 && height == 1080) ||
            prefs.getBoolean(confirmationKey(width, height), false)

    private fun confirmationKey(width: Int, height: Int) = "confirmed_${width}x$height"

    companion object {
        const val PROFILE_1080 = "1080p"
        const val PROFILE_720 = "720p"
        const val PROFILE_MATCH = "match"
        const val PROFILE_CUSTOM = "custom"

        const val BRIGHTNESS_MINIMUM = "minimum"
        const val BRIGHTNESS_KEEP = "keep"
        const val BRIGHTNESS_CUSTOM = "custom"

        const val ROTATION_AUTO = "auto"
        const val ROTATION_90 = "90"
        const val ROTATION_270 = "270"

        private val PROFILES = setOf(PROFILE_1080, PROFILE_720, PROFILE_MATCH, PROFILE_CUSTOM)
        private val BRIGHTNESS_MODES = setOf(
            BRIGHTNESS_MINIMUM,
            BRIGHTNESS_KEEP,
            BRIGHTNESS_CUSTOM,
        )
        private val ROTATION_MODES = setOf(ROTATION_AUTO, ROTATION_90, ROTATION_270)

        private const val KEY_AUTOMATIC = "automatic_docking"
        private const val KEY_BACKGROUND_DETECTION = "background_detection"
        private const val KEY_PROFILE = "profile"
        private const val KEY_CUSTOM_WIDTH = "custom_width"
        private const val KEY_CUSTOM_HEIGHT = "custom_height"
        private const val KEY_BRIGHTNESS_MODE = "brightness_mode"
        private const val KEY_CUSTOM_BRIGHTNESS = "custom_brightness"
        private const val KEY_ROTATION_MODE = "rotation_mode"
        private const val KEY_LAUNCH_ENABLED = "launch_enabled"
        private const val KEY_FRONTEND_PACKAGE = "frontend_package"
    }
}
