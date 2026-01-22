package com.vocable.eyetracking

import com.vocable.eyetracking.models.LandmarkPoint
import kotlin.math.*

/**
 * Pure gaze calculation logic extracted from MediaPipe Iris Gaze Tracker.
 *
 * This class contains all the mathematical operations for gaze estimation,
 * separated from platform-specific MediaPipe integration.
 *
 * Key features:
 * - Head pose estimation from facial landmarks
 * - Head pose compensation for gaze accuracy
 * - Iris position calculation with sensitivity and offset
 * - Blink detection using eye aspect ratio
 */
class IrisGazeCalculator(
    var sensitivityX: Float = 2.5f,
    var sensitivityY: Float = 3.0f,
    var offsetX: Float = 0.0f,
    var offsetY: Float = 0.3f,
    var headPoseCompensationEnabled: Boolean = true,
    var headYawCompensation: Float = 0.3f,
    var headPitchCompensation: Float = 0.25f
) {
    companion object {
        // Blink detection threshold (eye aspect ratio)
        const val BLINK_THRESHOLD = 0.015f

        // Landmark indices
        const val NOSE_TIP = 1
        const val CHIN = 152
        const val LEFT_EYE_CORNER = 33
        const val RIGHT_EYE_CORNER = 263
    }

    /**
     * Estimate head pose (yaw, pitch, roll) from face landmarks.
     *
     * Uses key facial landmarks to approximate head orientation.
     *
     * @param landmarks List of normalized landmark points (0-1 range)
     * @return Triple of (yaw, pitch, roll) in degrees
     */
    fun estimateHeadPose(landmarks: List<LandmarkPoint>): Triple<Float, Float, Float> {
        if (landmarks.size <= maxOf(NOSE_TIP, CHIN, LEFT_EYE_CORNER, RIGHT_EYE_CORNER)) {
            return Triple(0f, 0f, 0f)
        }

        try {
            // Get key landmarks for head pose estimation
            val noseTip = landmarks[NOSE_TIP]
            val chin = landmarks[CHIN]
            val leftEye = landmarks[LEFT_EYE_CORNER]
            val rightEye = landmarks[RIGHT_EYE_CORNER]

            // Calculate yaw (horizontal rotation) from eye positions
            val eyeMidX = (leftEye.x + rightEye.x) / 2f
            val noseTipX = noseTip.x
            // If nose is to the left of eye midpoint, head is turned left (negative yaw)
            // Scale to approximate degrees (-30 to +30 typical range)
            val yaw = (noseTipX - eyeMidX) * 100f

            // Calculate pitch (vertical rotation) from nose-chin relationship
            val noseY = noseTip.y
            val chinY = chin.y
            val eyeMidY = (leftEye.y + rightEye.y) / 2f
            // If nose is higher relative to chin, head is tilted up (positive pitch)
            val noseToEyeY = noseY - eyeMidY
            val noseToChinY = chinY - noseY
            // Ratio changes with head pitch
            val expectedRatio = 0.6f  // typical frontal ratio
            val actualRatio = if (noseToChinY > 0.01f) noseToEyeY / noseToChinY else expectedRatio
            val pitch = (actualRatio - expectedRatio) * 150f

            // Calculate roll (tilt) from eye angle
            val eyeDeltaY = rightEye.y - leftEye.y
            val eyeDeltaX = rightEye.x - leftEye.x
            val roll = if (eyeDeltaX > 0.01f) {
                atan2(eyeDeltaY, eyeDeltaX) * (180f / PI.toFloat())
            } else {
                0f
            }

            return Triple(
                yaw.coerceIn(-45f, 45f),
                pitch.coerceIn(-45f, 45f),
                roll.coerceIn(-45f, 45f)
            )
        } catch (e: Exception) {
            return Triple(0f, 0f, 0f)
        }
    }

    /**
     * Apply head pose compensation to raw gaze coordinates.
     *
     * When the head is turned, the iris appears to shift within the eye socket
     * even when looking at the same point. This compensation corrects for that.
     *
     * @param gazeX Raw gaze X coordinate
     * @param gazeY Raw gaze Y coordinate
     * @param headYaw Head yaw in degrees
     * @param headPitch Head pitch in degrees
     * @return Compensated (x, y) gaze coordinates
     */
    fun applyHeadPoseCompensation(
        gazeX: Float,
        gazeY: Float,
        headYaw: Float,
        headPitch: Float
    ): Pair<Float, Float> {
        if (!headPoseCompensationEnabled) {
            return Pair(gazeX, gazeY)
        }

        // Convert head angles to compensation offsets
        // When head turns right (positive yaw), gaze appears to shift left
        // We need to add a correction in the opposite direction
        val yawCorrection = -headYaw / 45f * headYawCompensation
        val pitchCorrection = -headPitch / 45f * headPitchCompensation

        val compensatedX = (gazeX + yawCorrection).coerceIn(-1f, 1f)
        val compensatedY = (gazeY + pitchCorrection).coerceIn(-1f, 1f)

        return Pair(compensatedX, compensatedY)
    }

    /**
     * Calculate normalized iris position within the eye.
     *
     * @param outer Outer eye corner landmark
     * @param inner Inner eye corner landmark
     * @param irisCenter Iris center landmark
     * @param frameWidth Frame width in pixels
     * @param frameHeight Frame height in pixels
     * @return Pair of (gaze vector [x, y], iris center [x, y]) or (null, null) if failed
     */
    fun calculateIrisPosition(
        outer: LandmarkPoint,
        inner: LandmarkPoint,
        irisCenter: LandmarkPoint,
        frameWidth: Float,
        frameHeight: Float
    ): Pair<FloatArray?, Pair<Float, Float>?> {
        try {
            // Convert normalized coordinates to pixel coordinates
            val outerPx = floatArrayOf(outer.x * frameWidth, outer.y * frameHeight)
            val innerPx = floatArrayOf(inner.x * frameWidth, inner.y * frameHeight)

            // Calculate eye width
            val eyeWidth = sqrt(
                (innerPx[0] - outerPx[0]) * (innerPx[0] - outerPx[0]) +
                (innerPx[1] - outerPx[1]) * (innerPx[1] - outerPx[1])
            )

            if (eyeWidth < 1f) {
                return Pair(null, null)
            }

            val eyeCenter = floatArrayOf(
                (outerPx[0] + innerPx[0]) / 2f,
                (outerPx[1] + innerPx[1]) / 2f
            )

            // Get iris center position in pixels
            val irisPx = floatArrayOf(
                irisCenter.x * frameWidth,
                irisCenter.y * frameHeight
            )

            // Calculate normalized gaze position (-1 to 1 range)
            // Apply sensitivity multipliers to reduce required eye movement
            var gazeX = ((irisPx[0] - eyeCenter[0]) / (eyeWidth / 2f)) * sensitivityX
            var gazeY = ((irisPx[1] - eyeCenter[1]) / (eyeWidth / 4f)) * sensitivityY

            // Apply offset correction (helps with camera angle / head position bias)
            gazeX += offsetX
            gazeY += offsetY

            // Clamp to valid range
            gazeX = gazeX.coerceIn(-1f, 1f)
            gazeY = gazeY.coerceIn(-1f, 1f)

            return Pair(floatArrayOf(gazeX, gazeY), Pair(irisPx[0], irisPx[1]))
        } catch (e: Exception) {
            return Pair(null, null)
        }
    }

    /**
     * Detect if an eye is blinking based on eye aspect ratio.
     *
     * @param eyeTop Top eyelid landmark
     * @param eyeBottom Bottom eyelid landmark
     * @return true if eye is blinking
     */
    fun detectBlink(eyeTop: LandmarkPoint, eyeBottom: LandmarkPoint): Boolean {
        return try {
            val eyeHeight = abs(eyeBottom.y - eyeTop.y)
            eyeHeight < BLINK_THRESHOLD
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Combine gaze from left and right eyes based on availability.
     *
     * @param leftGaze Left eye gaze vector (or null if not available)
     * @param rightGaze Right eye gaze vector (or null if not available)
     * @return Pair of (combined gaze [x, y], confidence)
     */
    fun combineGaze(
        leftGaze: FloatArray?,
        rightGaze: FloatArray?
    ): Pair<FloatArray, Float>? {
        return when {
            leftGaze != null && rightGaze != null -> {
                // Average both eyes
                val avgX = (leftGaze[0] + rightGaze[0]) / 2f
                val avgY = (leftGaze[1] + rightGaze[1]) / 2f
                Pair(floatArrayOf(avgX, avgY), 1.0f)
            }
            leftGaze != null -> Pair(leftGaze, 0.7f)
            rightGaze != null -> Pair(rightGaze, 0.7f)
            else -> null
        }
    }

    /**
     * Set sensitivity for gaze-to-cursor mapping.
     *
     * @param x Horizontal sensitivity (higher = less eye movement needed)
     * @param y Vertical sensitivity (higher = less eye movement needed)
     */
    fun setSensitivity(x: Float? = null, y: Float? = null) {
        x?.let { sensitivityX = it.coerceIn(0.5f, 5.0f) }
        y?.let { sensitivityY = it.coerceIn(0.5f, 5.0f) }
    }

    /**
     * Set offset for gaze correction.
     *
     * @param x Horizontal offset (-1 to 1, positive = shift right)
     * @param y Vertical offset (-1 to 1, positive = shift down)
     */
    fun setOffset(x: Float? = null, y: Float? = null) {
        x?.let { offsetX = it.coerceIn(-1.0f, 1.0f) }
        y?.let { offsetY = it.coerceIn(-1.0f, 1.0f) }
    }
}
