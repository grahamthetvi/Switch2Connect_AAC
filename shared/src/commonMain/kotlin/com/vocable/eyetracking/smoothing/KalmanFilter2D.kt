package com.vocable.eyetracking.smoothing

/**
 * 2D Kalman Filter for gaze smoothing.
 *
 * Implements a constant velocity model to predict and smooth gaze positions,
 * reducing noise and handling rapid movements. This provides much smoother
 * gaze tracking than simple linear interpolation.
 *
 * State vector: [x, y, vx, vy] (position and velocity)
 *
 * Based on: "MediaPipe Iris and Kalman Filter for Robust Eye Gaze Tracking"
 *
 * @param processNoise Process noise covariance (lower = smoother but more lag)
 * @param measurementNoise Measurement noise covariance (higher = trust predictions more)
 */
class KalmanFilter2D(
    private val processNoise: Float = 1e-4f,
    private val measurementNoise: Float = 1e-2f
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

    // Process noise covariance matrix
    private val Q = createIdentityMatrix(4).apply {
        for (i in indices) {
            for (j in this[i].indices) {
                this[i][j] *= processNoise
            }
        }
    }

    // Measurement noise covariance matrix
    private val R = arrayOf(
        floatArrayOf(measurementNoise, 0f),
        floatArrayOf(0f, measurementNoise)
    )

    private var initialized = false

    /**
     * Predict the next state based on the velocity model.
     * @return Predicted [x, y] position
     */
    fun predict(): FloatArray {
        if (!initialized) {
            return floatArrayOf(state[0], state[1])
        }

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

        return floatArrayOf(state[0], state[1])
    }

    /**
     * Reset the filter state.
     */
    fun reset() {
        state = floatArrayOf(0f, 0f, 0f, 0f)
        P = createIdentityMatrix(4)
        initialized = false
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
     * Check if filter has been initialized with at least one measurement.
     */
    fun isInitialized(): Boolean = initialized

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
