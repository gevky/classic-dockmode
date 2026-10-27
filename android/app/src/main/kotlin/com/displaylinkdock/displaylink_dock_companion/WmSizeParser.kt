package com.displaylinkdock.displaylink_dock_companion

data class WmSizeState(
    val physicalWidth: Int,
    val physicalHeight: Int,
    val overrideWidth: Int?,
    val overrideHeight: Int?,
) {
    val effectiveWidth: Int get() = overrideWidth ?: physicalWidth
    val effectiveHeight: Int get() = overrideHeight ?: physicalHeight
}

object WmSizeParser {
    private val physical = Regex("Physical size:\\s*(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
    private val override = Regex("Override size:\\s*(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
    private val simple = Regex("^(?:Physical size:\\s*)?(\\d+)x(\\d+)$", RegexOption.IGNORE_CASE)

    fun parse(output: String): WmSizeState? {
        val physicalMatch = physical.find(output)
            ?: simple.find(output.trim())
            ?: return null
        val overrideMatch = override.find(output)
        return WmSizeState(
            physicalWidth = physicalMatch.groupValues[1].toInt(),
            physicalHeight = physicalMatch.groupValues[2].toInt(),
            overrideWidth = overrideMatch?.groupValues?.get(1)?.toInt(),
            overrideHeight = overrideMatch?.groupValues?.get(2)?.toInt(),
        )
    }
}
