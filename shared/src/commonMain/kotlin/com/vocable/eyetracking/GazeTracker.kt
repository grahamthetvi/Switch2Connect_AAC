package com.vocable.eyetracking

import com.vocable.eyetracking.calibration.GazeCalibration
import com.vocable.eyetracking.models.*
import com.vocable.eyetracking.smoothing.AdaptiveKalmanFilter2D
import com.vocable.eyetracking.smoothing.KalmanFilter2D
import com.vocable.platform.FaceLandmarkDetector
import com.vocable.platform.Logger
import com.vocable.platform.Storage

/**
 * Main gaze tracking coordinator for KMP.
 *
 * This class orchestrates the entire gaze tracking pipeline:
 * 1. Face landmark detection (via platform-specific detector)
 * 2. Gaze calculation (pure Kotlin math)
 * 3. Smoothing/filtering (Kalman filters)
 * 4. Calibration application
 * 5. Screen coordinate mapping
 *
 * All the heavy algorithmic work is in shared commonMain code,
 * only camera capture and MediaPipe bindings are platform-specific.
 */
class GazeTracker(
    private val faceLandmarkDetector: FaceLandmarkDetector,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val storage: Storage,
    private val logger: Logger? = null
) {
    // Gaze calculation
    private val gazeCalculator = IrisGazeCalculator()

    // Smoothing filters
    private val kalmanFilter = KalmanFilter2D()
    private val adaptiveKalmanFilter = AdaptiveKalmanFilter2D()

    // Calibration
    private val calibration = GazeCalibration(
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        logger = { message -> logger?.debug(message) }
    )

    // Configuration
    var smoothingMode: SmoothingMode = SmoothingMode.ADAPTIVE_KALMAN
    var eyeSelection: EyeSelection = EyeSelection.BOTH_EYES
    var trackingMethod: TrackingMethod = TrackingMethod.IRIS_2D

    // Eye landmark indices (MediaPipe Face Mesh)
    companion object {
        // Left eye
        const val LEFT_EYE_OUTER = 33
        const val LEFT_EYE_INNER = 133
        const val LEFT_IRIS_CENTER = 468
        const val LEFT_EYE_TOP = 159
        const val LEFT_EYE_BOTTOM = 145

        // Right eye
        const val RIGHT_EYE_OUTER = 362
        const val RIGHT_EYE_INNER = 263
        const val RIGHT_IRIS_CENTER = 473
        const val RIGHT_EYE_TOP = 386
        const val RIGHT_EYE_BOTTOM = 374
    }

    /**
     * Process a frame and estimate gaze.
     */
    suspend fun processFrame(): GazeResult? {
        val landmarkResult = faceLandmarkDetector.detectLandmarks() ?: return null

        val landmarks = landmarkResult.landmarks
        val frameWidth = landmarkResult.frameWidth.toFloat()
        val frameHeight = landmarkResult.frameHeight.toFloat()

        // Estimate head pose
        val (headYaw, headPitch, headRoll) = gazeCalculator.estimateHeadPose(landmarks)

        // Detect blinks
        val leftBlink = if (landmarks.size > LEFT_EYE_BOTTOM) {
            gazeCalculator.detectBlink(
                landmarks[LEFT_EYE_TOP],
                landmarks[LEFT_EYE_BOTTOM]
            )
        } else false

        val rightBlink = if (landmarks.size > RIGHT_EYE_BOTTOM) {
            gazeCalculator.detectBlink(
                landmarks[RIGHT_EYE_TOP],
                landmarks[RIGHT_EYE_BOTTOM]
            )
        } else false

        // Calculate iris positions
        var leftGaze: FloatArray? = null
        var leftIrisCenter: Pair<Float, Float>? = null
        var rightGaze: FloatArray? = null
        var rightIrisCenter: Pair<Float, Float>? = null

        val useLeftEye = eyeSelection == EyeSelection.BOTH_EYES || eyeSelection == EyeSelection.LEFT_EYE_ONLY
        if (useLeftEye && !leftBlink && landmarks.size > LEFT_IRIS_CENTER) {
            val (gaze, center) = gazeCalculator.calculateIrisPosition(
                outer = landmarks[LEFT_EYE_OUTER],
                inner = landmarks[LEFT_EYE_INNER],
                irisCenter = landmarks[LEFT_IRIS_CENTER],
                frameWidth = frameWidth,
                frameHeight = frameHeight
            )
            leftGaze = gaze
            leftIrisCenter = center
        }

        val useRightEye = eyeSelection == EyeSelection.BOTH_EYES || eyeSelection == EyeSelection.RIGHT_EYE_ONLY
        if (useRightEye && !rightBlink && landmarks.size > RIGHT_IRIS_CENTER) {
            val (gaze, center) = gazeCalculator.calculateIrisPosition(
                outer = landmarks[RIGHT_EYE_OUTER],
                inner = landmarks[RIGHT_EYE_INNER],
                irisCenter = landmarks[RIGHT_IRIS_CENTER],
                frameWidth = frameWidth,
                frameHeight = frameHeight
            )
            rightGaze = gaze
            rightIrisCenter = center
        }

        // Combine gaze based on eye selection
        val (combinedGaze, confidence) = when (eyeSelection) {
            EyeSelection.LEFT_EYE_ONLY -> {
                if (leftGaze != null) leftGaze to 1.0f else return null
            }
            EyeSelection.RIGHT_EYE_ONLY -> {
                if (rightGaze != null) rightGaze to 1.0f else return null
            }
            EyeSelection.BOTH_EYES -> {
                gazeCalculator.combineGaze(leftGaze, rightGaze)?.let {
                    it.first to it.second
                } ?: return null
            }
        }

        // Apply head pose compensation
        val (compensatedX, compensatedY) = gazeCalculator.applyHeadPoseCompensation(
            combinedGaze[0], combinedGaze[1], headYaw, headPitch
        )

        // Apply smoothing
        val (smoothedX, smoothedY) = applySmoothing(compensatedX, compensatedY)

        return GazeResult(
            gazeX = smoothedX,
            gazeY = smoothedY,
            leftIrisCenter = leftIrisCenter,
            rightIrisCenter = rightIrisCenter,
            confidence = confidence,
            leftBlink = leftBlink,
            rightBlink = rightBlink,
            headYaw = headYaw,
            headPitch = headPitch,
            headRoll = headRoll
        )
    }

    /**
     * Apply smoothing filter to gaze coordinates.
     */
    private fun applySmoothing(gazeX: Float, gazeY: Float): Pair<Float, Float> {
        return when (smoothingMode) {
            SmoothingMode.SIMPLE_LERP -> {
                // Simple lerp smoothing (implement later if needed)
                Pair(gazeX, gazeY)
            }
            SmoothingMode.KALMAN_FILTER -> {
                val filtered = kalmanFilter.update(gazeX, gazeY)
                Pair(filtered[0], filtered[1])
            }
            SmoothingMode.ADAPTIVE_KALMAN -> {
                val filtered = adaptiveKalmanFilter.update(gazeX, gazeY)
                Pair(filtered[0], filtered[1])
            }
            SmoothingMode.COMBINED -> {
                // Kalman + lerp
                val filtered = adaptiveKalmanFilter.update(gazeX, gazeY)
                Pair(filtered[0], filtered[1])
            }
        }
    }

    /**
     * Convert raw gaze to screen coordinates using calibration.
     */
    fun gazeToScreen(gazeResult: GazeResult): Pair<Int, Int> {
        return calibration.gazeToScreen(gazeResult.gazeX, gazeResult.gazeY)
    }

    /**
     * Get calibration manager for calibration operations.
     */
    fun getCalibration(): GazeCalibration = calibration

    /**
     * Get gaze calculator for adjusting parameters.
     */
    fun getGazeCalculator(): IrisGazeCalculator = gazeCalculator

    /**
     * Reset all filters and state.
     */
    fun reset() {
        kalmanFilter.reset()
        adaptiveKalmanFilter.reset()
    }

    /**
     * Save current calibration to storage.
     */
    fun saveCalibration(): Boolean {
        val data = calibration.getCalibrationData() ?: return false
        val mode = if (calibration.isPolynomialCalibration()) "polynomial" else "affine"
        return storage.saveCalibrationData(data, mode)
    }

    /**
     * Load calibration from storage.
     */
    fun loadCalibration(): Boolean {
        // Try polynomial first
        val polyData = storage.loadCalibrationData("polynomial")
        if (polyData != null) {
            return calibration.loadCalibrationData(polyData)
        }

        // Fallback to affine
        val affineData = storage.loadCalibrationData("affine")
        if (affineData != null) {
            return calibration.loadCalibrationData(affineData)
        }

        return false
    }
}
