package com.displaylinkdock.displaylink_dock_companion

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

data class ExternalDisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
    val isDisplayLinkLikely: Boolean,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "displayId" to displayId,
        "name" to name,
        "width" to width,
        "height" to height,
        "refreshRate" to refreshRate.toDouble(),
        "isDisplayLinkLikely" to isDisplayLinkLikely,
    )
}

class DisplayRepository(context: Context) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    fun presentationDisplays(): List<ExternalDisplayInfo> =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }
            .map { display ->
                val mode = display.mode
                ExternalDisplayInfo(
                    displayId = display.displayId,
                    name = display.name ?: "External display",
                    width = mode.physicalWidth,
                    height = mode.physicalHeight,
                    refreshRate = mode.refreshRate,
                    isDisplayLinkLikely = display.name?.contains("DisplayLink", ignoreCase = true) == true,
                )
            }

    fun preferredExternal(): ExternalDisplayInfo? {
        val displayList = presentationDisplays()
        return displayList.firstOrNull { it.isDisplayLinkLikely } ?: displayList.firstOrNull()
    }

    fun hasMatchingExternal(saved: SavedDeviceState): Boolean {
        val displayList = presentationDisplays()
        return displayList.any { display ->
            if (!saved.externalDisplayName.isNullOrBlank()) {
                display.name == saved.externalDisplayName
            } else {
                display.displayId == saved.externalDisplayId
            }
        }
    }

    fun naturalDisplay(): Triple<Int, Int, Float> {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        return Triple(mode?.physicalWidth ?: 0, mode?.physicalHeight ?: 0, mode?.refreshRate ?: 0f)
    }

    fun logicalDisplay(wmState: WmSizeState?): Pair<Int, Int> {
        if (wmState == null) return 0 to 0
        val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        return if (rotation == android.view.Surface.ROTATION_90 ||
            rotation == android.view.Surface.ROTATION_270
        ) {
            wmState.effectiveHeight to wmState.effectiveWidth
        } else {
            wmState.effectiveWidth to wmState.effectiveHeight
        }
    }
}
