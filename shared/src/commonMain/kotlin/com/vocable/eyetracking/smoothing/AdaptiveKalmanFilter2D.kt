package com.vocable.eyetracking.smoothing

import kotlin.math.sqrt

/**
 * Adaptive 2D Kalman Filter for gaze smoothing with velocity-adaptive noise parameters.
 *
 * Unlike the standard Kalman filter with fixed noise values, this adaptive version
 * dynamically adjusts the measurement and process noise based on the current velocity:
 *
 * - High velocity (rapid eye movement) → Lower measurement noise, trust measurements more
 *   This allows quick response to saccades and intentional movements
 *
 * - Low velocity (dwelling/fixation) → Higher measurement noise, trust predictions more
 *   This provides smooth, stable gaze during fixation periods
 *
 * This approach is particularly effective for eye gaze tracking where:
 * - Saccades (rapid eye movements) need to be tracked accurately
 * - Fixations (dwelling) need to be smooth and stable for selection
 *
 * State vector: [x, y, vx, vy] (position and velocity)
 *
 * @param baseProcessNoise Base process noise covariance (affects model uncertainty)
 * @param baseMeasurementNoise Base measurement noise covariance (affects smoothness)
 * @param lowVelocityThreshold Velocity below which we consider "dwelling" (normalized)
 * @param highVelocityThreshold Velocity above which we consider "rapid movement" (normalized)
 * @param dwellMeasurementMultiplier Multiplier for measurement noise during dwelling (higher = smoother)
 * @param rapidMeasurementMultiplier Multiplier for measurement noise during rapid movement (lower = more responsive)
 */
class AdaptiveKalmanFilter2D(
    private val baseProcessNoise: Float = 1e-4f,
    private val baseMeasurementNoise: Float = 1e-2f,
    private val lowVelocityThreshold: Float = 0.02f,
    private val highVelocityThreshold: Float = 0.15f,
    private val dwellMeasurementMultiplier: Float = 3.0f,
    private val rapidMeasurementMultiplier: Float = 0.3f
) {
    // State: [x, y, vx, vy]
    private var state = floatArrayOf(0f, 0f, 0f, 0f)

    // State covariance matrix (4x4)
    private var P = createIdentityMatrix(4)

    // State transition matrix (constant velocity model)
    // x' = x + vx, y' = y + vy, vx' = vx, vy' = vy
    private val F = arrayOf(
        floatArrayOf(1f, 0f, 1f, 0f),
        floatArrayOf(0f, 1f, 0f, 1f),
        floatArrayOf(0f, 0f, 1f, 0f),
        floatArrayOf(0f, 0f, 0f, 1f)
    )

    // Measurement matrix (we only observe position)
    private val H = arrayOf(
        floatArrayOf(1f, 0f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f, 0f)
    )

    private var initialized = false

    // Current adaptive noise values (for debugging/monitoring)
    private var currentMeasurementNoise = baseMeasurementNoise
    private var currentProcessNoise = baseProcessNoise

    // Velocity history for smoothing the velocity estimate
    private val velocityHistory = mutableListOf<Float>()
    private val velocityHistorySize = 5

    /**
     * Predict the next state based on the velocity model.
     * @return Predicted [x, y] position
     */
    fun predict(): FloatArray {
        if (!initialized) {
            return floatArrayOf(state[0], state[1])
        }

        // Get adaptive Q based on velocity
        val Q = getAdaptiveProcessNoise()

        // Predict state: x = F * x
        state = multiplyMatrixVector(F, state)

        // Predict covariance: P = F * P * F^T + Q
        val FP = multiplyMatrices(F, P)
        val FPFt = multiplyMatrices(FP, transpose(F))
        P = addMatrices(FPFt, Q)

        return floatArrayOf(state[0], state[1])
    }

    /**
     * Update the filter with a new measurement.
     *
     * @param x Measured x position
     * @param y Measured y position
     * @return Filtered [x, y] position
     */
    fun update(x: Float, y: Float): FloatArray {
        val measurement = floatArrayOf(x, y)

        if (!initialized) {
            state[0] = x
            state[1] = y
            state[2] = 0f
            state[3] = 0f
            initialized = true
            return measurement
        }

        // Predict step
        predict()

        // Get adaptive R based on velocity
        val R = getAdaptiveMeasurementNoise()

        // Innovation (measurement residual): y = z - H * x
        val Hx = multiplyMatrixVector(H, state)
        val innovation = floatArrayOf(
            measurement[0] - Hx[0],
            measurement[1] - Hx[1]
        )

        // Innovation covariance: S = H * P * H^T + R
        val HP = multiplyMatrices(H, P)
        val HPHt = multiplyMatrices(HP, transpose(H))
        val S = addMatrices2x2(HPHt, R)

        // Kalman gain: K = P * H^T * S^-1
        val Ht = transpose(H)
        val PHt = multiplyMatrices(P, Ht)
        val SInv = inverse2x2(S)
        val K = multiplyMatrices(PHt, SInv)

        // Update state: x = x + K * innovation
        val Kinnovation = multiplyMatrixVector4x2(K, innovation)
        for (i in state.indices) {
            state[i] += Kinnovation[i]
        }

        // Update covariance: P = (I - K * H) * P
        val KH = multiplyMatrices(K, H)
        val IMinusKH = subtractFromIdentity(KH)
        P = multiplyMatrices(IMinusKH, P)

        // Update velocity history
        updateVelocityHistory()

        return floatArrayOf(state[0], state[1])
    }

    /**
     * Get adaptive measurement noise based on current velocity.
     *
     * Low velocity → Higher noise (smoother, trusts prediction)
     * High velocity → Lower noise (responsive, trusts measurement)
     */
    private fun getAdaptiveMeasurementNoise(): Array<FloatArray> {
        val velocity = getSmoothedVelocity()

        // Calculate adaptive multiplier using smooth interpolation
        val adaptiveMultiplier = when {
            velocity <= lowVelocityThreshold -> {
                // Dwelling - use high measurement noise for smoothness
                dwellMeasurementMultiplier
            }
            velocity >= highVelocityThreshold -> {
                // Rapid movement - use low measurement noise for responsiveness
                rapidMeasurementMultiplier
            }
            else -> {
                // Interpolate between the two
                val t = (velocity - lowVelocityThreshold) /
                        (highVelocityThreshold - lowVelocityThreshold)
                // Smooth interpolation using smoothstep
                val smoothT = t * t * (3f - 2f * t)
                dwellMeasurementMultiplier + smoothT * (rapidMeasurementMultiplier - dwellMeasurementMultiplier)
            }
        }

        currentMeasurementNoise = baseMeasurementNoise * adaptiveMultiplier

        return arrayOf(
            floatArrayOf(currentMeasurementNoise, 0f),
            floatArrayOf(0f, currentMeasurementNoise)
        )
    }

    /**
     * Get adaptive process noise based on current velocity.
     *
     * Low velocity → Lower process noise (model is stable)
     * High velocity → Higher process noise (allow for model changes)
     */
    private fun getAdaptiveProcessNoise(): Array<FloatArray> {
        val velocity = getSmoothedVelocity()

        // Process noise increases with velocity to allow the model to adapt
        val velocityFactor = (velocity / highVelocityThreshold).coerceIn(0.5f, 2.0f)
        currentProcessNoise = baseProcessNoise * velocityFactor

        return createIdentityMatrix(4).apply {
            for (i in indices) {
                for (j in this[i].indices) {
                    this[i][j] *= currentProcessNoise
                }
            }
        }
    }

    /**
     * Update velocity history for smoothed velocity calculation.
     */
    private fun updateVelocityHistory() {
        val currentVelocity = getCurrentVelocityMagnitude()
        velocityHistory.add(currentVelocity)
        if (velocityHistory.size > velocityHistorySize) {
            velocityHistory.removeAt(0)
        }
    }

    /**
     * Get smoothed velocity magnitude from history.
     */
    private fun getSmoothedVelocity(): Float {
        if (velocityHistory.isEmpty()) {
            return getCurrentVelocityMagnitude()
        }
        return velocityHistory.average().toFloat()
    }

    /**
     * Get current velocity magnitude from state.
     */
    private fun getCurrentVelocityMagnitude(): Float {
        return sqrt(state[2] * state[2] + state[3] * state[3])
    }

    /**
     * Reset the filter state.
     */
    fun reset() {
        state = floatArrayOf(0f, 0f, 0f, 0f)
        P = createIdentityMatrix(4)
        initialized = false
        velocityHistory.clear()
        currentMeasurementNoise = baseMeasurementNoise
        currentProcessNoise = baseProcessNoise
    }

    /**
     * Get current position without updating.
     */
    fun currentPosition(): FloatArray = floatArrayOf(state[0], state[1])

    /**
     * Get current velocity.
     */
    fun currentVelocity(): FloatArray = floatArrayOf(state[2], state[3])

    /**
     * Get current velocity magnitude.
     */
    fun velocityMagnitude(): Float = getCurrentVelocityMagnitude()

    /**
     * Check if filter has been initialized with at least one measurement.
     */
    fun isInitialized(): Boolean = initialized

    /**
     * Get current adaptive measurement noise value (for debugging).
     */
    fun getCurrentMeasurementNoise(): Float = currentMeasurementNoise

    /**
     * Get current adaptive process noise value (for debugging).
     */
    fun getCurrentProcessNoise(): Float = currentProcessNoise

    /**
     * Check if currently in "dwelling" mode (low velocity).
     */
    fun isDwelling(): Boolean = getSmoothedVelocity() <= lowVelocityThreshold

    // Matrix helper functions
    private fun createIdentityMatrix(size: Int): Array<FloatArray> {
        return Array(size) { i ->
            FloatArray(size) { j -> if (i == j) 1f else 0f }
        }
    }

    private fun multiplyMatrixVector(matrix: Array<FloatArray>, vector: FloatArray): FloatArray {
        val result = FloatArray(matrix.size)
        for (i in matrix.indices) {
            var sum = 0f
            for (j in vector.indices) {
                sum += matrix[i][j] * vector[j]
            }
            result[i] = sum
        }
        return result
    }

    private fun multiplyMatrixVector4x2(matrix: Array<FloatArray>, vector: FloatArray): FloatArray {
        val result = FloatArray(4)
        for (i in 0 until 4) {
            var sum = 0f
            for (j in 0 until 2) {
                sum += matrix[i][j] * vector[j]
            }
            result[i] = sum
        }
        return result
    }

    private fun multiplyMatrices(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val rowsA = a.size
        val colsA = a[0].size
        val colsB = b[0].size
        val result = Array(rowsA) { FloatArray(colsB) }

        for (i in 0 until rowsA) {
            for (j in 0 until colsB) {
                var sum = 0f
                for (k in 0 until colsA) {
                    sum += a[i][k] * b[k][j]
                }
                result[i][j] = sum
            }
        }
        return result
    }

    private fun transpose(matrix: Array<FloatArray>): Array<FloatArray> {
        val rows = matrix.size
        val cols = matrix[0].size
        return Array(cols) { j ->
            FloatArray(rows) { i -> matrix[i][j] }
        }
    }

    private fun addMatrices(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        return Array(a.size) { i ->
            FloatArray(a[i].size) { j -> a[i][j] + b[i][j] }
        }
    }

    private fun addMatrices2x2(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        return arrayOf(
            floatArrayOf(a[0][0] + b[0][0], a[0][1] + b[0][1]),
            floatArrayOf(a[1][0] + b[1][0], a[1][1] + b[1][1])
        )
    }

    private fun subtractFromIdentity(matrix: Array<FloatArray>): Array<FloatArray> {
        val size = matrix.size
        return Array(size) { i ->
            FloatArray(size) { j ->
                (if (i == j) 1f else 0f) - matrix[i][j]
            }
        }
    }

    private fun inverse2x2(matrix: Array<FloatArray>): Array<FloatArray> {
        val a = matrix[0][0]
        val b = matrix[0][1]
        val c = matrix[1][0]
        val d = matrix[1][1]
        val det = a * d - b * c

        if (det == 0f) {
            // Return identity if singular
            return arrayOf(
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f)
            )
        }

        val invDet = 1f / det
        return arrayOf(
            floatArrayOf(d * invDet, -b * invDet),
            floatArrayOf(-c * invDet, a * invDet)
        )
    }
}
