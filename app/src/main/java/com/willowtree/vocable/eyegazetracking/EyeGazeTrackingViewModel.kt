package com.willowtree.vocable.eyegazetracking

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.DisplayMetrics
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import com.willowtree.vocable.R
import com.willowtree.vocable.utils.IEyeGazePermissions
import com.willowtree.vocable.utils.VocableSharedPreferences
import com.willowtree.vocable.utils.isEyeGazeEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Smoothing mode for eye gaze tracking.
 */
enum class SmoothingMode {
    /**
     * Simple linear interpolation (lerp) - like head tracking.
     * Fast response, can be jittery at high sensitivity.
     */
    SIMPLE_LERP,

    /**
     * Kalman filter - predictive model with velocity tracking.
     * Smoother output, better noise rejection, slightly more lag.
     */
    KALMAN_FILTER,

    /**
     * Adaptive Kalman filter - velocity-aware noise parameters.
     * Lower noise during saccades (responsive), higher during fixation (smooth).
     */
    ADAPTIVE_KALMAN,

    /**
     * Combined: Kalman filter on gaze, then lerp on screen coordinates.
     * Best of both worlds - smooth and responsive.
     */
    COMBINED
}

/**
 * Eye tracking method.
 */
enum class EyeTrackingMethod {
    /**
     * 2D Eye Tracking: Uses iris position relative to eye corners.
     * Simpler, faster, works well for frontal gaze.
     */
    TRACKING_2D,

    /**
     * 3D Eye Tracking: Uses 3D eyeball model for gaze ray calculation.
     * More accurate, better at different head poses, slightly more computation.
     */
    TRACKING_3D
}

/**
 * Source of cursor recenter action (for UI feedback).
 */
enum class RecenterSource {
    /** User double-blinked to recenter */
    DOUBLE_BLINK,
    /** User pressed the recenter button */
    MANUAL_BUTTON,
    /** Auto-recentered from looking straight ahead */
    AUTO_STRAIGHT_GAZE
}

/**
 * ViewModel that processes MediaPipe Iris gaze results.
 *
 * Supports multiple smoothing modes:
 * - SIMPLE_LERP: Like head tracking, fast and responsive
 * - KALMAN_FILTER: Predictive smoothing, better noise rejection
 * - COMBINED: Kalman on gaze + lerp on screen for best results
 */
class EyeGazeTrackingViewModel(
    eyeGazePermissions: IEyeGazePermissions,
) : ViewModel(), LifecycleObserver, KoinComponent {

    companion object {
        private const val FACE_DETECTION_TIMEOUT = 1000L

        // Kalman filter parameters
        private const val PROCESS_NOISE = 1e-4f      // Lower = smoother but more lag
        private const val MEASUREMENT_NOISE = 1e-2f  // Higher = trust predictions more

        // Adaptive Kalman filter parameters
        private const val LOW_VELOCITY_THRESHOLD = 0.02f   // Below this = dwelling
        private const val HIGH_VELOCITY_THRESHOLD = 0.15f  // Above this = rapid movement
        private const val DWELL_MEASUREMENT_MULTIPLIER = 3.0f   // Smoother during dwell
        private const val RAPID_MEASUREMENT_MULTIPLIER = 0.3f   // Responsive during movement

        // Gaze out-of-bounds detection (auto-hide cursor when looking far away)
        private const val GAZE_OUT_OF_BOUNDS_THRESHOLD = 1.2f  // Hide if |gaze| > this (normal range ~±0.5)
        private const val OUT_OF_BOUNDS_TIMEOUT_MS = 500L      // Must be out of bounds for this long

        // Gaze amplification levels (for users with limited eye movement range)
        val AMPLIFICATION_LEVELS = floatArrayOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

        // Double-blink detection parameters
        private const val DOUBLE_BLINK_WINDOW_MS = 600L      // Max time between two blinks for double-blink
        private const val BLINK_MIN_DURATION_MS = 50L        // Minimum blink duration to count
        private const val BLINK_COOLDOWN_MS = 300L           // Cooldown after recenter before detecting again

        // Auto-recenter parameters (when looking straight ahead)
        private const val CENTER_GAZE_THRESHOLD = 0.08f      // Gaze must be within ±0.08 to be "centered"
        private const val AUTO_RECENTER_DURATION_MS = 1500L  // Must look straight for 1.5 seconds
    }

    val eyeGazeEnabledLd = eyeGazePermissions.permissionState.asLiveData().map { it.isEyeGazeEnabled() }

    private val viewModelJob = SupervisorJob()
    private val backgroundScope = CoroutineScope(viewModelJob + Dispatchers.IO)

    private val sharedPrefs: VocableSharedPreferences by inject()
    private var sensitivity = VocableSharedPreferences.DEFAULT_SENSITIVITY
    private var eyeGazeEnabled = false

    private val sharedPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                VocableSharedPreferences.KEY_SENSITIVITY -> {
                    sensitivity = sharedPrefs.getSensitivity()
                }
                VocableSharedPreferences.KEY_EYE_GAZE_ENABLED -> {
                    eyeGazeEnabled = sharedPrefs.getEyeGazeEnabled()
                }
                VocableSharedPreferences.KEY_EYE_SELECTION -> {
                    val selectionString = sharedPrefs.getEyeSelection()
                    eyeSelection = EyeSelection.fromString(selectionString)
                    liveEyeSelection.postValue(eyeSelection)
                }
                VocableSharedPreferences.KEY_GAZE_AMPLIFICATION -> {
                    gazeAmplification = sharedPrefs.getGazeAmplification()
                    liveGazeAmplification.postValue(gazeAmplification)
                }
            }
        }

    private var gazeProcessingJob: Job? = null

    // Screen pointer location (final output)
    private val livePointerLocation = MutableLiveData<Pair<Float, Float>>()
    val pointerLocation: LiveData<Pair<Float, Float>> = livePointerLocation

    // Raw gaze data for calibration (exposed so calibration can collect samples)
    private val liveRawGaze = MutableLiveData<Pair<Float, Float>>()
    val rawGaze: LiveData<Pair<Float, Float>> = liveRawGaze

    // Current smoothing mode (exposed for UI/debugging)
    private val liveSmoothingMode = MutableLiveData(SmoothingMode.SIMPLE_LERP)
    val smoothingModeLd: LiveData<SmoothingMode> = liveSmoothingMode

    private val liveShowError = MutableLiveData<Boolean>()
    val showError: LiveData<Boolean> = liveShowError

    private var isTablet = false
    private var displayMetrics: DisplayMetrics? = null

    // Current smoothing mode
    private var smoothingMode = SmoothingMode.ADAPTIVE_KALMAN  // Default to adaptive

    // Current tracking method
    private var trackingMethod = EyeTrackingMethod.TRACKING_2D

    // Tracking method LiveData
    private val liveTrackingMethod = MutableLiveData(EyeTrackingMethod.TRACKING_2D)
    val trackingMethodLd: LiveData<EyeTrackingMethod> = liveTrackingMethod

    // Eye selection - which eye(s) to use for tracking
    private var eyeSelection = EyeSelection.BOTH_EYES

    // Eye selection LiveData
    private val liveEyeSelection = MutableLiveData(EyeSelection.BOTH_EYES)
    val eyeSelectionLd: LiveData<EyeSelection> = liveEyeSelection

    // Gaze amplification (for users with limited eye movement range)
    private var gazeAmplification = VocableSharedPreferences.DEFAULT_GAZE_AMPLIFICATION

    // Gaze amplification LiveData
    private val liveGazeAmplification = MutableLiveData(VocableSharedPreferences.DEFAULT_GAZE_AMPLIFICATION)
    val gazeAmplificationLd: LiveData<Float> = liveGazeAmplification

    // Double-blink detection state
    private var lastBlinkEndTime: Long = 0L
    private var blinkStartTime: Long = 0L
    private var wasBlinking: Boolean = false
    private var blinkCount: Int = 0
    private var lastRecenterTime: Long = 0L

    // Auto-recenter state (when looking straight ahead)
    private var gazeCenteredStartTime: Long = 0L
    private var isGazeCentered: Boolean = false
    private var autoRecenterEnabled: Boolean = true

    // Gaze offset for recentering (applied before amplification)
    private var gazeOffsetX: Float = 0f
    private var gazeOffsetY: Float = 0f
    private var lastRawGazeX: Float? = null
    private var lastRawGazeY: Float? = null

    // LiveData for recenter events (for UI feedback)
    private val liveRecenterEvent = MutableLiveData<RecenterSource?>()
    val recenterEvent: LiveData<RecenterSource?> = liveRecenterEvent

    // Simple lerp state (like head tracking's oldVector)
    private var oldGazeX: Float? = null
    private var oldGazeY: Float? = null
    private var oldScreenX: Float? = null
    private var oldScreenY: Float? = null

    // Standard Kalman filter for complex smoothing
    private val gazeKalmanFilter = KalmanFilter2D(PROCESS_NOISE, MEASUREMENT_NOISE)
    private val screenKalmanFilter = KalmanFilter2D(PROCESS_NOISE * 100, MEASUREMENT_NOISE * 10)

    // Adaptive Kalman filter with velocity-aware noise
    private val adaptiveKalmanFilter = AdaptiveKalmanFilter2D(
        baseProcessNoise = PROCESS_NOISE,
        baseMeasurementNoise = MEASUREMENT_NOISE,
        lowVelocityThreshold = LOW_VELOCITY_THRESHOLD,
        highVelocityThreshold = HIGH_VELOCITY_THRESHOLD,
        dwellMeasurementMultiplier = DWELL_MEASUREMENT_MULTIPLIER,
        rapidMeasurementMultiplier = RAPID_MEASUREMENT_MULTIPLIER
    )

    private var lastDetectedFaceTime = 0L

    // Gaze out-of-bounds tracking (for auto-hide cursor)
    private var gazeOutOfBoundsStartTime: Long = 0L
    private var isGazeOutOfBounds: Boolean = false

    // LiveData for cursor visibility (separate from face detection error)
    private val liveGazeInBounds = MutableLiveData<Boolean>(true)
    val gazeInBounds: LiveData<Boolean> = liveGazeInBounds

    // Calibration system (using polynomial by default for better accuracy)
    private var gazeCalibration: GazeCalibration? = null

    init {
        sharedPrefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
        sensitivity = sharedPrefs.getSensitivity()
        eyeGazeEnabled = sharedPrefs.getEyeGazeEnabled()
        isTablet = get<Context>().resources.getBoolean(R.bool.is_tablet)

        // Load eye selection preference
        val selectionString = sharedPrefs.getEyeSelection()
        eyeSelection = EyeSelection.fromString(selectionString)
        liveEyeSelection.value = eyeSelection

        // Load gaze amplification preference
        gazeAmplification = sharedPrefs.getGazeAmplification()
        liveGazeAmplification.value = gazeAmplification
    }

    fun setDisplayMetrics(metrics: DisplayMetrics) {
        this.displayMetrics = metrics

        // Initialize calibration for this screen size (using polynomial for better accuracy)
        if (gazeCalibration == null) {
            gazeCalibration = GazeCalibration(
                metrics.widthPixels,
                metrics.heightPixels,
                CalibrationMode.POLYNOMIAL  // Use polynomial calibration by default
            )

            // Try to load existing calibration
            try {
                val context = get<Context>()
                if (gazeCalibration?.loadCalibration(context) == true) {
                    Timber.d("Loaded existing gaze calibration (${gazeCalibration?.getCurrentCalibrationMode()?.name})")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load calibration")
            }
        }
    }

    fun onResume() {
        lastDetectedFaceTime = System.currentTimeMillis()
    }

    /**
     * Set the smoothing mode.
     */
    fun setSmoothingMode(mode: SmoothingMode) {
        smoothingMode = mode
        liveSmoothingMode.postValue(mode)
        resetFilters()  // Reset state when switching modes
        Timber.d("Smoothing mode set to: $mode")
    }

    /**
     * Get the current smoothing mode.
     */
    fun getSmoothingMode(): SmoothingMode = smoothingMode

    /**
     * Cycle through smoothing modes (for easy testing).
     */
    fun cycleSmoothingMode(): SmoothingMode {
        val modes = SmoothingMode.values()
        val currentIndex = modes.indexOf(smoothingMode)
        val nextIndex = (currentIndex + 1) % modes.size
        setSmoothingMode(modes[nextIndex])
        return smoothingMode
    }

    /**
     * Set the tracking method (2D or 3D).
     */
    fun setTrackingMethod(method: EyeTrackingMethod) {
        trackingMethod = method
        liveTrackingMethod.postValue(method)
        resetFilters()
        Timber.d("Tracking method set to: $method")
    }

    /**
     * Get the current tracking method.
     */
    fun getTrackingMethod(): EyeTrackingMethod = trackingMethod

    /**
     * Cycle through tracking methods.
     */
    fun cycleTrackingMethod(): EyeTrackingMethod {
        val methods = EyeTrackingMethod.values()
        val currentIndex = methods.indexOf(trackingMethod)
        val nextIndex = (currentIndex + 1) % methods.size
        setTrackingMethod(methods[nextIndex])
        return trackingMethod
    }

    /**
     * Set which eye(s) to use for gaze tracking.
     */
    fun setEyeSelection(selection: EyeSelection) {
        eyeSelection = selection
        liveEyeSelection.postValue(selection)
        resetFilters()
        Timber.d("Eye selection set to: $selection")
    }

    /**
     * Get the current eye selection.
     */
    fun getEyeSelection(): EyeSelection = eyeSelection

    /**
     * Cycle through eye selection options.
     */
    fun cycleEyeSelection(): EyeSelection {
        val selections = EyeSelection.values()
        val currentIndex = selections.indexOf(eyeSelection)
        val nextIndex = (currentIndex + 1) % selections.size
        setEyeSelection(selections[nextIndex])
        return eyeSelection
    }

    /**
     * Set the gaze amplification factor.
     * Values > 1.0 exaggerate eye movement direction, useful for users with limited eye range.
     */
    fun setGazeAmplification(amplification: Float) {
        gazeAmplification = amplification.coerceIn(AMPLIFICATION_LEVELS.first(), AMPLIFICATION_LEVELS.last())
        liveGazeAmplification.postValue(gazeAmplification)
        sharedPrefs.setGazeAmplification(gazeAmplification)
        Timber.d("Gaze amplification set to: ${gazeAmplification}x")
    }

    /**
     * Get the current gaze amplification factor.
     */
    fun getGazeAmplification(): Float = gazeAmplification

    /**
     * Cycle through gaze amplification levels.
     */
    fun cycleGazeAmplification(): Float {
        val currentIndex = AMPLIFICATION_LEVELS.indexOfFirst { it == gazeAmplification }
        val nextIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % AMPLIFICATION_LEVELS.size
        setGazeAmplification(AMPLIFICATION_LEVELS[nextIndex])
        return gazeAmplification
    }

    /**
     * Recenter the cursor to screen center.
     * Sets the current gaze position as the new "center" reference point.
     *
     * @param source What triggered the recenter (for UI feedback)
     * @param currentGazeX Current raw gaze X (optional, uses last known if not provided)
     * @param currentGazeY Current raw gaze Y (optional, uses last known if not provided)
     */
    fun recenterCursor(source: RecenterSource, currentGazeX: Float? = null, currentGazeY: Float? = null) {
        val currentTime = System.currentTimeMillis()

        // Prevent rapid recentering
        if (currentTime - lastRecenterTime < BLINK_COOLDOWN_MS && source == RecenterSource.DOUBLE_BLINK) {
            return
        }

        // Update gaze offset to make current position the new center
        // The offset is subtracted from raw gaze, so if looking right (+0.2), offset becomes +0.2
        // Next time we look at +0.2, it becomes 0 (center)
        val recenterX = currentGazeX ?: lastRawGazeX
        val recenterY = currentGazeY ?: lastRawGazeY
        if (recenterX != null && recenterY != null) {
            gazeOffsetX = recenterX
            gazeOffsetY = recenterY
        }

        // Reset filters so cursor smoothly moves to center
        resetFilters()

        // Move cursor to screen center immediately
        val metrics = displayMetrics
        if (metrics != null) {
            val centerX = metrics.widthPixels / 2f
            val centerY = metrics.heightPixels / 2f
            livePointerLocation.postValue(Pair(centerX, centerY))
        }

        lastRecenterTime = currentTime
        liveRecenterEvent.postValue(source)

        Timber.d("Cursor recentered via $source (offset: $gazeOffsetX, $gazeOffsetY)")

        // Clear the event after a short delay
        backgroundScope.launch {
            kotlinx.coroutines.delay(1500)
            liveRecenterEvent.postValue(null)
        }
    }

    /**
     * Manual recenter from button press.
     */
    fun recenterCursorManual() {
        recenterCursor(RecenterSource.MANUAL_BUTTON)
    }

    /**
     * Enable or disable auto-recenter when looking straight ahead.
     */
    fun setAutoRecenterEnabled(enabled: Boolean) {
        autoRecenterEnabled = enabled
        if (!enabled) {
            isGazeCentered = false
            gazeCenteredStartTime = 0L
        }
        Timber.d("Auto-recenter ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if auto-recenter is enabled.
     */
    fun isAutoRecenterEnabled(): Boolean = autoRecenterEnabled

    /**
     * Reset gaze offset (clear any recentering adjustments).
     */
    fun resetGazeOffset() {
        gazeOffsetX = 0f
        gazeOffsetY = 0f
        Timber.d("Gaze offset reset to zero")
    }

    /**
     * Process blink events for double-blink detection.
     * Call this with the blink state from each gaze result.
     */
    private fun processBlinkDetection(isBlinking: Boolean, rawGazeX: Float, rawGazeY: Float) {
        val currentTime = System.currentTimeMillis()

        if (isBlinking && !wasBlinking) {
            // Blink just started
            blinkStartTime = currentTime
        } else if (!isBlinking && wasBlinking) {
            // Blink just ended
            val blinkDuration = currentTime - blinkStartTime

            // Only count blinks that are long enough (not just noise)
            if (blinkDuration >= BLINK_MIN_DURATION_MS) {
                val timeSinceLastBlink = currentTime - lastBlinkEndTime

                if (timeSinceLastBlink <= DOUBLE_BLINK_WINDOW_MS && lastBlinkEndTime > 0) {
                    // This is the second blink of a double-blink!
                    blinkCount = 0
                    lastBlinkEndTime = 0L
                    recenterCursor(RecenterSource.DOUBLE_BLINK, rawGazeX, rawGazeY)
                } else {
                    // First blink, start counting
                    blinkCount = 1
                    lastBlinkEndTime = currentTime
                }
            }
        }

        wasBlinking = isBlinking

        // Reset blink count if too much time passed
        if (lastBlinkEndTime > 0 && currentTime - lastBlinkEndTime > DOUBLE_BLINK_WINDOW_MS) {
            blinkCount = 0
            lastBlinkEndTime = 0L
        }
    }

    /**
     * Process auto-recenter when looking straight ahead.
     * Call this with the raw gaze coordinates.
     */
    private fun processAutoRecenter(rawGazeX: Float, rawGazeY: Float) {
        if (!autoRecenterEnabled) return

        val currentTime = System.currentTimeMillis()

        // Check if gaze is near center (accounting for current offset)
        val adjustedX = rawGazeX - gazeOffsetX
        val adjustedY = rawGazeY - gazeOffsetY
        val isNearCenter = kotlin.math.abs(adjustedX) <= CENTER_GAZE_THRESHOLD &&
                          kotlin.math.abs(adjustedY) <= CENTER_GAZE_THRESHOLD

        if (isNearCenter) {
            if (!isGazeCentered) {
                // Just started looking at center
                gazeCenteredStartTime = currentTime
                isGazeCentered = true
            } else {
                // Still looking at center - check if long enough
                if (currentTime - gazeCenteredStartTime >= AUTO_RECENTER_DURATION_MS) {
                    // Auto-recenter!
                    recenterCursor(RecenterSource.AUTO_STRAIGHT_GAZE, rawGazeX, rawGazeY)
                    isGazeCentered = false
                    gazeCenteredStartTime = 0L
                }
            }
        } else {
            // Not looking at center anymore
            isGazeCentered = false
            gazeCenteredStartTime = 0L
        }
    }

    /**
     * Process the gaze result from MediaPipe Iris tracker.
     * 
     * This is called frequently from the camera executor, so we process
     * synchronously on the calling thread for simple lerp (fastest), or
     * on the background thread for more complex smoothing.
     */
    @SuppressLint("NullSafeMutableLiveData")
    fun onIrisGazeResult(gazeResult: MediaPipeIrisGazeTracker.GazeResult?, faceDetected: Boolean) {
        if (!eyeGazeEnabled) {
            liveShowError.postValue(false)
            return
        }

        // Handle face detection timeout
        if (faceDetected) {
            lastDetectedFaceTime = System.currentTimeMillis()
        }

        val faceDetectionTimeoutExpired = System.currentTimeMillis() - lastDetectedFaceTime > FACE_DETECTION_TIMEOUT

        if (!faceDetected && faceDetectionTimeoutExpired) {
            liveShowError.postValue(true)
            return
        }

        if (liveShowError.value == true) {
            liveShowError.postValue(false)
        }

        if (gazeResult == null) {
            return
        }

        val metrics = displayMetrics ?: return

        val rawX = gazeResult.gazeX
        val rawY = gazeResult.gazeY
        lastRawGazeX = rawX
        lastRawGazeY = rawY

        // Post raw gaze for calibration to use
        liveRawGaze.postValue(Pair(rawX, rawY))

        // Process double-blink detection (both eyes must blink)
        val isBlinking = gazeResult.leftBlink && gazeResult.rightBlink
        processBlinkDetection(isBlinking, rawX, rawY)

        // Process auto-recenter when looking straight ahead
        processAutoRecenter(rawX, rawY)

        // Apply gaze offset (from recentering)
        val offsetX = rawX - gazeOffsetX
        val offsetY = rawY - gazeOffsetY

        // Check if gaze is out of bounds (user looking far away from screen)
        val isCurrentlyOutOfBounds = checkGazeOutOfBounds(offsetX, offsetY)
        updateGazeOutOfBoundsState(isCurrentlyOutOfBounds)

        // For SIMPLE_LERP, process directly without launching a coroutine
        // This is the fastest path and avoids job scheduling overhead
        if (smoothingMode == SmoothingMode.SIMPLE_LERP) {
            processGazeDirectly(offsetX, offsetY, metrics)
            return
        }

        // For other modes, use background processing but don't block on busy job
        if (gazeProcessingJob?.isActive == true) {
            return
        }

        gazeProcessingJob = backgroundScope.launch {
            processGazeAsync(offsetX, offsetY, metrics)
        }
    }

    /**
     * Process gaze directly on calling thread (for simple lerp - fastest).
     */
    private fun processGazeDirectly(rawX: Float, rawY: Float, metrics: DisplayMetrics) {
        val (smoothedX, smoothedY) = applySimplerLerp(rawX, rawY)
        val (screenX, screenY) = gazeToScreen(smoothedX, smoothedY, metrics)
        val (finalX, finalY) = applyScreenLerp(screenX, screenY)

        val clampedX = finalX.coerceIn(0f, metrics.widthPixels.toFloat())
        val clampedY = finalY.coerceIn(0f, metrics.heightPixels.toFloat())

        livePointerLocation.postValue(Pair(clampedX, clampedY))
    }

    /**
     * Process gaze on background thread (for complex smoothing modes).
     */
    private fun processGazeAsync(rawX: Float, rawY: Float, metrics: DisplayMetrics) {
        val (smoothedX, smoothedY) = when (smoothingMode) {
            SmoothingMode.SIMPLE_LERP -> applySimplerLerp(rawX, rawY)
            SmoothingMode.KALMAN_FILTER -> applyKalmanFilter(rawX, rawY)
            SmoothingMode.ADAPTIVE_KALMAN -> applyAdaptiveKalmanFilter(rawX, rawY)
            SmoothingMode.COMBINED -> applyCombined(rawX, rawY)
        }

        val (screenX, screenY) = gazeToScreen(smoothedX, smoothedY, metrics)

        // Apply screen-level smoothing only for SIMPLE_LERP mode
        val (finalX, finalY) = if (smoothingMode == SmoothingMode.SIMPLE_LERP) {
            applyScreenLerp(screenX, screenY)
        } else {
            Pair(screenX, screenY)
        }

        val clampedX = finalX.coerceIn(0f, metrics.widthPixels.toFloat())
        val clampedY = finalY.coerceIn(0f, metrics.heightPixels.toFloat())

        livePointerLocation.postValue(Pair(clampedX, clampedY))
    }

    /**
     * Simple lerp smoothing (like head tracking).
     * Fast response, can be jittery.
     */
    private fun applySimplerLerp(rawX: Float, rawY: Float): Pair<Float, Float> {
        return when (oldGazeX) {
            null -> {
                // First detection - immediately center (like head tracking)
                oldGazeX = rawX
                oldGazeY = rawY
                Pair(rawX, rawY)
            }
            else -> {
                // Apply tablet adjustment to Y
                var adjustedY = rawY
                if (!isTablet) {
                    adjustedY *= 1.5f
                }

                // Lerp smoothing
                val newX = lerp(oldGazeX!!, rawX, sensitivity)
                val newY = lerp(oldGazeY!!, adjustedY, sensitivity)
                oldGazeX = newX
                oldGazeY = newY
                Pair(newX, newY)
            }
        }
    }

    /**
     * Apply screen-level lerp for simple mode.
     */
    private fun applyScreenLerp(screenX: Float, screenY: Float): Pair<Float, Float> {
        return when (oldScreenX) {
            null -> {
                oldScreenX = screenX
                oldScreenY = screenY
                Pair(screenX, screenY)
            }
            else -> {
                val newX = lerp(oldScreenX!!, screenX, sensitivity)
                val newY = lerp(oldScreenY!!, screenY, sensitivity)
                oldScreenX = newX
                oldScreenY = newY
                Pair(newX, newY)
            }
        }
    }

    /**
     * Kalman filter smoothing.
     * Smoother output, better noise rejection, slightly more lag.
     */
    private fun applyKalmanFilter(rawX: Float, rawY: Float): Pair<Float, Float> {
        // Apply tablet adjustment
        var adjustedY = rawY
        if (!isTablet) {
            adjustedY *= 1.5f
        }

        val filtered = gazeKalmanFilter.update(rawX, adjustedY)
        return Pair(filtered[0], filtered[1])
    }

    /**
     * Adaptive Kalman filter smoothing.
     * Velocity-adaptive noise: responsive during saccades, smooth during fixation.
     */
    private fun applyAdaptiveKalmanFilter(rawX: Float, rawY: Float): Pair<Float, Float> {
        // Apply tablet adjustment
        var adjustedY = rawY
        if (!isTablet) {
            adjustedY *= 1.5f
        }

        val filtered = adaptiveKalmanFilter.update(rawX, adjustedY)
        return Pair(filtered[0], filtered[1])
    }

    /**
     * Combined smoothing: Kalman on gaze, then lerp on screen.
     * Best of both worlds - smooth and responsive.
     */
    private fun applyCombined(rawX: Float, rawY: Float): Pair<Float, Float> {
        // First apply Kalman filter to raw gaze
        var adjustedY = rawY
        if (!isTablet) {
            adjustedY *= 1.5f
        }

        val kalmanFiltered = gazeKalmanFilter.update(rawX, adjustedY)

        // Then apply lerp for responsiveness
        return when (oldGazeX) {
            null -> {
                oldGazeX = kalmanFiltered[0]
                oldGazeY = kalmanFiltered[1]
                Pair(kalmanFiltered[0], kalmanFiltered[1])
            }
            else -> {
                val newX = lerp(oldGazeX!!, kalmanFiltered[0], sensitivity * 1.5f)
                val newY = lerp(oldGazeY!!, kalmanFiltered[1], sensitivity * 1.5f)
                oldGazeX = newX
                oldGazeY = newY
                Pair(newX, newY)
            }
        }
    }

    /**
     * Convert gaze coordinates to screen coordinates.
     * Applies gaze amplification to exaggerate eye movement direction.
     */
    private fun gazeToScreen(gazeX: Float, gazeY: Float, metrics: DisplayMetrics): Pair<Float, Float> {
        // Apply gaze amplification (exaggerate the direction from center)
        // This helps users with limited eye movement range by amplifying small movements
        val amplifiedX = gazeX * gazeAmplification
        val amplifiedY = gazeY * gazeAmplification

        return if (gazeCalibration?.isCalibrated() == true) {
            // Use calibrated affine transform
            val calibrated = gazeCalibration!!.gazeToScreen(amplifiedX, amplifiedY)
            Pair(calibrated.first.toFloat(), calibrated.second.toFloat())
        } else {
            // Fallback: simple linear mapping centered on screen
            val centerX = metrics.widthPixels / 2f
            val centerY = metrics.heightPixels / 2f

            val rangeX = metrics.widthPixels * 0.6f
            val rangeY = metrics.heightPixels * 0.6f

            val x = centerX + amplifiedX * rangeX
            val y = centerY + amplifiedY * rangeY

            Pair(x, y)
        }
    }

    /**
     * Get the calibration system for external use (e.g., calibration UI).
     */
    fun getCalibration(): GazeCalibration? = gazeCalibration

    /**
     * Process the 3D gaze result from MediaPipe 3D Eyeball tracker.
     */
    @SuppressLint("NullSafeMutableLiveData")
    fun on3DGazeResult(gazeResult: MediaPipe3DEyeballTracker.Gaze3DResult?, faceDetected: Boolean) {
        if (!eyeGazeEnabled) {
            liveShowError.postValue(false)
            return
        }

        // Handle face detection timeout
        if (faceDetected) {
            lastDetectedFaceTime = System.currentTimeMillis()
        }

        val faceDetectionTimeoutExpired = System.currentTimeMillis() - lastDetectedFaceTime > FACE_DETECTION_TIMEOUT

        if (!faceDetected && faceDetectionTimeoutExpired) {
            liveShowError.postValue(true)
            return
        }

        if (liveShowError.value == true) {
            liveShowError.postValue(false)
        }

        if (gazeResult == null) {
            return
        }

        val metrics = displayMetrics ?: return

        val rawX = gazeResult.gazeX
        val rawY = gazeResult.gazeY
        lastRawGazeX = rawX
        lastRawGazeY = rawY

        // Post raw gaze for calibration to use
        liveRawGaze.postValue(Pair(rawX, rawY))

        // Process double-blink detection (both eyes must blink)
        val isBlinking = gazeResult.leftBlink && gazeResult.rightBlink
        processBlinkDetection(isBlinking, rawX, rawY)

        // Process auto-recenter when looking straight ahead
        processAutoRecenter(rawX, rawY)

        // Apply gaze offset (from recentering)
        val offsetX = rawX - gazeOffsetX
        val offsetY = rawY - gazeOffsetY

        // Check if gaze is out of bounds (user looking far away from screen)
        val isCurrentlyOutOfBounds = checkGazeOutOfBounds(offsetX, offsetY)
        updateGazeOutOfBoundsState(isCurrentlyOutOfBounds)

        // For SIMPLE_LERP, process directly without launching a coroutine
        if (smoothingMode == SmoothingMode.SIMPLE_LERP) {
            processGazeDirectly(offsetX, offsetY, metrics)
            return
        }

        // For other modes, use background processing
        if (gazeProcessingJob?.isActive == true) {
            return
        }

        gazeProcessingJob = backgroundScope.launch {
            processGazeAsync(offsetX, offsetY, metrics)
        }
    }

    /**
     * Check if the raw gaze coordinates are outside the expected bounds.
     * Normal gaze range is roughly ±0.5, so anything beyond ±1.2 means
     * the user is likely looking far away from the screen.
     */
    private fun checkGazeOutOfBounds(gazeX: Float, gazeY: Float): Boolean {
        return kotlin.math.abs(gazeX) > GAZE_OUT_OF_BOUNDS_THRESHOLD ||
               kotlin.math.abs(gazeY) > GAZE_OUT_OF_BOUNDS_THRESHOLD
    }

    /**
     * Update the out-of-bounds state with time-based hysteresis.
     * This prevents flickering by requiring the gaze to be out of bounds
     * for a minimum duration before hiding the cursor.
     */
    private fun updateGazeOutOfBoundsState(isCurrentlyOutOfBounds: Boolean) {
        val currentTime = System.currentTimeMillis()

        if (isCurrentlyOutOfBounds) {
            if (!isGazeOutOfBounds) {
                // Just went out of bounds - start the timer
                gazeOutOfBoundsStartTime = currentTime
                isGazeOutOfBounds = true
            } else {
                // Already out of bounds - check if timeout expired
                if (currentTime - gazeOutOfBoundsStartTime > OUT_OF_BOUNDS_TIMEOUT_MS) {
                    // Hide cursor after timeout
                    if (liveGazeInBounds.value != false) {
                        liveGazeInBounds.postValue(false)
                        Timber.d("Gaze out of bounds - hiding cursor")
                    }
                }
            }
        } else {
            // Gaze is back in bounds - reset state and show cursor immediately
            if (isGazeOutOfBounds) {
                isGazeOutOfBounds = false
                gazeOutOfBoundsStartTime = 0L
                if (liveGazeInBounds.value != true) {
                    liveGazeInBounds.postValue(true)
                    Timber.d("Gaze back in bounds - showing cursor")
                }
            }
        }
    }

    /**
     * Check if gaze is currently considered in bounds (cursor should be visible).
     */
    fun isGazeCurrentlyInBounds(): Boolean = liveGazeInBounds.value ?: true

    /**
     * Check if currently in dwelling mode (useful for UI feedback).
     */
    fun isDwelling(): Boolean = adaptiveKalmanFilter.isDwelling()

    /**
     * Get current gaze velocity (useful for debugging/UI).
     */
    fun getGazeVelocity(): Float = adaptiveKalmanFilter.velocityMagnitude()

    /**
     * Reset smoothing state and center cursor.
     * Call this when tracking needs to be reset or after calibration.
     */
    fun resetFilters() {
        // Reset simple lerp state
        oldGazeX = null
        oldGazeY = null
        oldScreenX = null
        oldScreenY = null

        // Reset Kalman filters
        gazeKalmanFilter.reset()
        screenKalmanFilter.reset()
        adaptiveKalmanFilter.reset()

        Timber.d("Gaze filters reset - cursor will center on next detection")
    }

    /**
     * Save the current calibration to persistent storage.
     */
    fun saveCalibration(): Boolean {
        val context = try {
            get<Context>()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get context for saving calibration")
            return false
        }
        return gazeCalibration?.saveCalibration(context) ?: false
    }

    /**
     * Load calibration from persistent storage.
     */
    fun loadCalibration(): Boolean {
        val context = try {
            get<Context>()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get context for loading calibration")
            return false
        }
        return gazeCalibration?.loadCalibration(context) ?: false
    }

    /**
     * Reset/delete calibration.
     */
    fun resetCalibration() {
        val context = try {
            get<Context>()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get context for resetting calibration")
            return
        }
        gazeCalibration?.deleteCalibration(context)
        resetFilters()
    }

    /**
     * Check if calibration is available.
     */
    fun isCalibrated(): Boolean = gazeCalibration?.isCalibrated() ?: false

    /**
     * Linear interpolation between two values.
     */
    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + fraction * (end - start)
    }

    override fun onCleared() {
        viewModelJob.cancel()
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)
    }
}
