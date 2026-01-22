package com.vocable.platform

import com.vocable.eyetracking.models.LandmarkPoint

/**
 * Result from face landmark detection.
 */
data class FaceLandmarkResult(
    val landmarks: List<LandmarkPoint>,
    val frameWidth: Int,
    val frameHeight: Int,
    val timestamp: Long = 0L
)

/**
 * Platform-agnostic face landmark detector interface.
 * Wraps MediaPipe FaceLandmarker on both Android and iOS.
 */
interface FaceLandmarkDetector {
    /**
     * Initialize the detector with the model file.
     * @param useGpu Whether to use GPU acceleration
     * @return true if initialization was successful
     */
    fun initialize(useGpu: Boolean = false): Boolean

    /**
     * Detect face landmarks in an image frame.
     * Platform implementations will convert platform-specific image formats
     * (Android: Bitmap, iOS: CVPixelBuffer) to the detector's expected format.
     *
     * @return FaceLandmarkResult if face detected, null otherwise
     */
    suspend fun detectLandmarks(): FaceLandmarkResult?

    /**
     * Check if the detector is initialized and ready.
     */
    fun isReady(): Boolean

    /**
     * Check if GPU acceleration is being used.
     */
    fun isUsingGpu(): Boolean

    /**
     * Release resources.
     */
    fun close()
}

/**
 * Expect declaration for platform-specific face landmark detector.
 * Implemented by:
 * - Android: MediaPipeFaceLandmarkDetector (uses MediaPipe Android SDK)
 * - iOS: MediaPipeFaceLandmarkDetector (uses MediaPipe iOS SDK)
 */
expect class PlatformFaceLandmarkDetector() : FaceLandmarkDetector
