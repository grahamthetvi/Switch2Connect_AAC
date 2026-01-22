package com.vocable.eyetracking.models

/**
 * Result of gaze estimation containing normalized gaze coordinates.
 *
 * @param gazeX Horizontal gaze position (-1 = left, +1 = right)
 * @param gazeY Vertical gaze position (-1 = up, +1 = down)
 * @param leftIrisCenter Pixel coordinates of left iris center
 * @param rightIrisCenter Pixel coordinates of right iris center
 * @param confidence Detection confidence (0-1)
 * @param leftBlink Whether left eye is blinking
 * @param rightBlink Whether right eye is blinking
 * @param headYaw Head rotation around vertical axis (degrees, positive = right)
 * @param headPitch Head rotation around horizontal axis (degrees, positive = up)
 * @param headRoll Head rotation around forward axis (degrees, positive = tilt right)
 */
data class GazeResult(
    val gazeX: Float,
    val gazeY: Float,
    val leftIrisCenter: Pair<Float, Float>? = null,
    val rightIrisCenter: Pair<Float, Float>? = null,
    val confidence: Float = 1.0f,
    val leftBlink: Boolean = false,
    val rightBlink: Boolean = false,
    val headYaw: Float = 0f,
    val headPitch: Float = 0f,
    val headRoll: Float = 0f
)

/**
 * Eye selection - which eye(s) to use for tracking.
 */
enum class EyeSelection {
    LEFT_EYE_ONLY,
    RIGHT_EYE_ONLY,
    BOTH_EYES
}

/**
 * Tracking method - which algorithm to use for gaze tracking.
 */
enum class TrackingMethod {
    IRIS_2D,      // MediaPipe 2D iris-based tracking
    EYEBALL_3D    // MediaPipe 3D eyeball model tracking
}

/**
 * Smoothing mode for gaze filtering.
 */
enum class SmoothingMode {
    SIMPLE_LERP,      // Simple linear interpolation
    KALMAN_FILTER,    // Standard Kalman filter
    ADAPTIVE_KALMAN,  // Adaptive Kalman with velocity-based noise
    COMBINED          // Combined Kalman + Lerp
}

/**
 * Landmark data representing a single face/eye landmark point.
 * Platform-agnostic representation of MediaPipe NormalizedLandmark.
 */
data class LandmarkPoint(
    val x: Float,  // Normalized X coordinate (0-1)
    val y: Float,  // Normalized Y coordinate (0-1)
    val z: Float = 0f  // Optional Z depth
)
