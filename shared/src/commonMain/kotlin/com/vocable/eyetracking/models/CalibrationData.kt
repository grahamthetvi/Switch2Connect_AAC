package com.vocable.eyetracking.models

import com.vocable.eyetracking.calibration.CalibrationMode

/**
 * Calibration data that can be saved/loaded.
 * Supports both affine and polynomial modes.
 */
data class CalibrationData(
    val transformX: FloatArray,  // Coefficients for screen_x
    val transformY: FloatArray,  // Coefficients for screen_y
    val screenWidth: Int,
    val screenHeight: Int,
    val calibrationError: Float,
    val mode: CalibrationMode = CalibrationMode.AFFINE  // Default for backward compatibility
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as CalibrationData

        if (!transformX.contentEquals(other.transformX)) return false
        if (!transformY.contentEquals(other.transformY)) return false
        if (screenWidth != other.screenWidth) return false
        if (screenHeight != other.screenHeight) return false
        if (mode != other.mode) return false

        return true
    }

    override fun hashCode(): Int {
        var result = transformX.contentHashCode()
        result = 31 * result + transformY.contentHashCode()
        result = 31 * result + screenWidth
        result = 31 * result + screenHeight
        result = 31 * result + mode.hashCode()
        return result
    }
}

/**
 * A single calibration point with screen position and collected gaze samples.
 */
data class CalibrationPoint(
    val screenX: Int,
    val screenY: Int,
    val gazeSamples: MutableList<FloatArray> = mutableListOf()
)
