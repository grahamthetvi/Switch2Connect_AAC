package com.vocable.eyetracking.calibration

import com.vocable.eyetracking.models.CalibrationData
import com.vocable.eyetracking.models.CalibrationPoint
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Calibration system for eye gaze tracking using 9-point calibration.
 *
 * This system collects gaze samples at known screen positions and computes
 * a transformation to map raw gaze vectors to accurate screen coordinates.
 *
 * Supports two calibration modes:
 *
 * 1. AFFINE (linear): Simple and fast, but less accurate at edges
 *    screen_x = a₀ + a₁·gx + a₂·gy
 *    screen_y = b₀ + b₁·gx + b₂·gy
 *
 * 2. POLYNOMIAL (2nd order): More accurate, handles nonlinear eye-to-screen mapping
 *    screen_x = a₀ + a₁·gx + a₂·gy + a₃·gx² + a₄·gy² + a₅·gx·gy
 *    screen_y = b₀ + b₁·gx + b₂·gy + b₃·gx² + b₄·gy² + b₅·gx·gy
 *
 * The polynomial mode is recommended as it better captures the nonlinear
 * relationship between eye gaze and screen position, especially at edges.
 *
 * Based on: "MediaPipe Iris and Kalman Filter for Robust Eye Gaze Tracking"
 *
 * Usage:
 * 1. Call generateCalibrationPoints() to get screen positions for calibration
 * 2. For each point, call addCalibrationSample() with gaze data while user looks at point
 * 3. Call computeCalibration() to calculate the transformation matrix
 * 4. Use gazeToScreen() to convert raw gaze to calibrated screen coordinates
 */
class GazeCalibration(
    private val screenWidth: Int,
    private val screenHeight: Int,
    var calibrationMode: CalibrationMode = CalibrationMode.POLYNOMIAL,
    private val logger: ((String) -> Unit)? = null
) {
    companion object {
        const val MIN_SAMPLES_PER_POINT = 10
        const val RECOMMENDED_SAMPLES_PER_POINT = 30

        // Number of coefficients for each calibration mode
        private const val AFFINE_COEFFICIENTS = 3      // a₀, a₁, a₂
        private const val POLYNOMIAL_COEFFICIENTS = 6  // a₀, a₁, a₂, a₃, a₄, a₅
    }

    // Calibration state
    private val calibrationPoints = mutableListOf<CalibrationPoint>()
    private var transformX: FloatArray? = null  // Coefficients for X transform
    private var transformY: FloatArray? = null  // Coefficients for Y transform
    private var isCalibrated = false
    private var calibrationError = 0f
    private var currentMode = calibrationMode  // Mode used for current calibration

    /**
     * Generate the 9 calibration points in a 3x3 grid.
     *
     * @param marginPercent Margin from screen edges as a percentage (default 10%)
     * @return List of (x, y) screen coordinates for calibration
     */
    fun generateCalibrationPoints(marginPercent: Float = 0.1f): List<Pair<Int, Int>> {
        val marginX = (screenWidth * marginPercent).toInt()
        val marginY = (screenHeight * marginPercent).toInt()

        val points = mutableListOf<Pair<Int, Int>>()

        for (row in 0..2) {
            for (col in 0..2) {
                val x = marginX + col * (screenWidth - 2 * marginX) / 2
                val y = marginY + row * (screenHeight - 2 * marginY) / 2
                points.add(Pair(x, y))
            }
        }

        // Initialize calibration points storage
        calibrationPoints.clear()
        points.forEach { (x, y) ->
            calibrationPoints.add(CalibrationPoint(x, y))
        }

        return points
    }

    /**
     * Generate 5-point calibration (corners + center) for faster calibration.
     */
    fun generateCalibrationPoints5(): List<Pair<Int, Int>> {
        val marginX = (screenWidth * 0.1f).toInt()
        val marginY = (screenHeight * 0.1f).toInt()

        val points = listOf(
            Pair(marginX, marginY),                           // Top-left
            Pair(screenWidth - marginX, marginY),             // Top-right
            Pair(screenWidth / 2, screenHeight / 2),          // Center
            Pair(marginX, screenHeight - marginY),            // Bottom-left
            Pair(screenWidth - marginX, screenHeight - marginY) // Bottom-right
        )

        calibrationPoints.clear()
        points.forEach { (x, y) ->
            calibrationPoints.add(CalibrationPoint(x, y))
        }

        return points
    }

    /**
     * Add a gaze sample for a specific calibration point.
     *
     * @param pointIndex Index of the calibration point (0-8 for 9-point, 0-4 for 5-point)
     * @param gazeX Raw gaze X coordinate (-1 to 1)
     * @param gazeY Raw gaze Y coordinate (-1 to 1)
     * @return Number of samples collected for this point
     */
    fun addCalibrationSample(pointIndex: Int, gazeX: Float, gazeY: Float): Int {
        if (pointIndex < 0 || pointIndex >= calibrationPoints.size) {
            logger?.invoke("Invalid calibration point index: $pointIndex")
            return 0
        }

        calibrationPoints[pointIndex].gazeSamples.add(floatArrayOf(gazeX, gazeY))
        return calibrationPoints[pointIndex].gazeSamples.size
    }

    /**
     * Get the number of samples collected for a specific point.
     */
    fun getSampleCount(pointIndex: Int): Int {
        return calibrationPoints.getOrNull(pointIndex)?.gazeSamples?.size ?: 0
    }

    /**
     * Check if enough samples have been collected for all points.
     */
    fun hasEnoughSamples(): Boolean {
        return calibrationPoints.all { it.gazeSamples.size >= MIN_SAMPLES_PER_POINT }
    }

    /**
     * Compute the calibration transformation from collected samples.
     *
     * For AFFINE mode (linear):
     *   screen_x = a₀ + a₁·gx + a₂·gy
     *   screen_y = b₀ + b₁·gx + b₂·gy
     *
     * For POLYNOMIAL mode (2nd order):
     *   screen_x = a₀ + a₁·gx + a₂·gy + a₃·gx² + a₄·gy² + a₅·gx·gy
     *   screen_y = b₀ + b₁·gx + b₂·gy + b₃·gx² + b₄·gy² + b₅·gx·gy
     *
     * @return true if calibration was successful
     */
    fun computeCalibration(): Boolean {
        val minPoints = if (calibrationMode == CalibrationMode.POLYNOMIAL) 6 else 4
        if (calibrationPoints.size < minPoints) {
            logger?.invoke("Not enough calibration points (need at least $minPoints, have ${calibrationPoints.size})")
            return false
        }

        // Collect averaged gaze data for each point with outlier rejection
        val gazePoints = mutableListOf<FloatArray>()
        val screenPoints = mutableListOf<FloatArray>()

        for (point in calibrationPoints) {
            if (point.gazeSamples.isEmpty()) {
                logger?.invoke("No samples for point (${point.screenX}, ${point.screenY})")
                continue
            }

            // Calculate average with outlier rejection using IQR method
            val (avgGazeX, avgGazeY) = computeRobustAverage(point.gazeSamples)

            gazePoints.add(floatArrayOf(avgGazeX, avgGazeY))
            screenPoints.add(floatArrayOf(point.screenX.toFloat(), point.screenY.toFloat()))

            logger?.invoke("Calibration point (${point.screenX}, ${point.screenY}) -> avg gaze [%.3f, %.3f] from ${point.gazeSamples.size} samples".format(avgGazeX, avgGazeY))
        }

        if (gazePoints.size < minPoints) {
            logger?.invoke("Not enough valid calibration data points")
            return false
        }

        val n = gazePoints.size

        // Screen X and Y values
        val screenX = FloatArray(n) { screenPoints[it][0] }
        val screenY = FloatArray(n) { screenPoints[it][1] }

        // Build design matrix and solve based on calibration mode
        when (calibrationMode) {
            CalibrationMode.AFFINE -> {
                // Build matrix A: [1, gaze_x, gaze_y]
                val A = Array(n) { i ->
                    floatArrayOf(1f, gazePoints[i][0], gazePoints[i][1])
                }
                transformX = solveLeastSquares(A, screenX, 3)
                transformY = solveLeastSquares(A, screenY, 3)
            }
            CalibrationMode.POLYNOMIAL -> {
                // Build matrix A: [1, gx, gy, gx², gy², gx·gy]
                val A = Array(n) { i ->
                    val gx = gazePoints[i][0]
                    val gy = gazePoints[i][1]
                    floatArrayOf(1f, gx, gy, gx * gx, gy * gy, gx * gy)
                }
                transformX = solveLeastSquares(A, screenX, 6)
                transformY = solveLeastSquares(A, screenY, 6)
            }
        }

        if (transformX == null || transformY == null) {
            logger?.invoke("Failed to compute calibration transform")
            return false
        }

        // Calculate calibration error
        var totalError = 0f
        for (i in 0 until n) {
            val predicted = gazeToScreenInternal(gazePoints[i][0], gazePoints[i][1])
            val actual = screenPoints[i]
            val error = sqrt(
                (predicted.first - actual[0]) * (predicted.first - actual[0]) +
                (predicted.second - actual[1]) * (predicted.second - actual[1])
            )
            totalError += error
        }
        calibrationError = totalError / n

        isCalibrated = true
        currentMode = calibrationMode

        logger?.invoke("Calibration complete (${calibrationMode.name})! Average error: %.1f pixels".format(calibrationError))
        logTransformCoefficients()

        return true
    }

    /**
     * Log the calibration transform coefficients for debugging.
     */
    private fun logTransformCoefficients() {
        when (currentMode) {
            CalibrationMode.AFFINE -> {
                logger?.invoke("Transform X (affine): [%.3f + %.3f·gx + %.3f·gy]".format(
                    transformX!![0], transformX!![1], transformX!![2]))
                logger?.invoke("Transform Y (affine): [%.3f + %.3f·gx + %.3f·gy]".format(
                    transformY!![0], transformY!![1], transformY!![2]))
            }
            CalibrationMode.POLYNOMIAL -> {
                logger?.invoke("Transform X (poly): [%.3f + %.3f·gx + %.3f·gy + %.3f·gx² + %.3f·gy² + %.3f·gx·gy]".format(
                    transformX!![0], transformX!![1], transformX!![2],
                    transformX!![3], transformX!![4], transformX!![5]))
                logger?.invoke("Transform Y (poly): [%.3f + %.3f·gx + %.3f·gy + %.3f·gx² + %.3f·gy² + %.3f·gx·gy]".format(
                    transformY!![0], transformY!![1], transformY!![2],
                    transformY!![3], transformY!![4], transformY!![5]))
            }
        }
    }

    /**
     * Convert raw gaze coordinates to screen coordinates using calibration.
     *
     * @param gazeX Raw gaze X (-1 to 1)
     * @param gazeY Raw gaze Y (-1 to 1)
     * @return Screen coordinates (x, y) in pixels
     */
    fun gazeToScreen(gazeX: Float, gazeY: Float): Pair<Int, Int> {
        return if (isCalibrated && transformX != null && transformY != null) {
            gazeToScreenInternal(gazeX, gazeY)
        } else {
            // Fallback: simple linear mapping
            val x = ((gazeX + 1f) / 2f * screenWidth).toInt()
            val y = ((gazeY + 1f) / 2f * screenHeight).toInt()
            Pair(
                x.coerceIn(0, screenWidth - 1),
                y.coerceIn(0, screenHeight - 1)
            )
        }
    }

    private fun gazeToScreenInternal(gazeX: Float, gazeY: Float): Pair<Int, Int> {
        val tx = transformX!!
        val ty = transformY!!

        val (screenX, screenY) = when (currentMode) {
            CalibrationMode.AFFINE -> {
                // screen = a₀ + a₁·gx + a₂·gy
                val sx = tx[0] + tx[1] * gazeX + tx[2] * gazeY
                val sy = ty[0] + ty[1] * gazeX + ty[2] * gazeY
                Pair(sx, sy)
            }
            CalibrationMode.POLYNOMIAL -> {
                // screen = a₀ + a₁·gx + a₂·gy + a₃·gx² + a₄·gy² + a₅·gx·gy
                val gx2 = gazeX * gazeX
                val gy2 = gazeY * gazeY
                val gxgy = gazeX * gazeY

                val sx = tx[0] + tx[1] * gazeX + tx[2] * gazeY +
                         tx[3] * gx2 + tx[4] * gy2 + tx[5] * gxgy
                val sy = ty[0] + ty[1] * gazeX + ty[2] * gazeY +
                         ty[3] * gx2 + ty[4] * gy2 + ty[5] * gxgy
                Pair(sx, sy)
            }
        }

        return Pair(
            screenX.toInt().coerceIn(0, screenWidth - 1),
            screenY.toInt().coerceIn(0, screenHeight - 1)
        )
    }

    /**
     * Compute robust average of gaze samples using IQR-based outlier rejection.
     * This removes samples that are more than 1.5*IQR away from the median.
     */
    private fun computeRobustAverage(samples: List<FloatArray>): Pair<Float, Float> {
        if (samples.size < 4) {
            // Not enough samples for IQR, just use simple average
            val avgX = samples.map { it[0] }.average().toFloat()
            val avgY = samples.map { it[1] }.average().toFloat()
            return Pair(avgX, avgY)
        }

        // Compute robust average for X
        val xValues = samples.map { it[0] }.sorted()
        val yValues = samples.map { it[1] }.sorted()

        val filteredX = removeOutliersIQR(xValues)
        val filteredY = removeOutliersIQR(yValues)

        val avgX = if (filteredX.isNotEmpty()) filteredX.average().toFloat() else xValues.average().toFloat()
        val avgY = if (filteredY.isNotEmpty()) filteredY.average().toFloat() else yValues.average().toFloat()

        val removed = samples.size - minOf(filteredX.size, filteredY.size)
        if (removed > 0) {
            logger?.invoke("Removed $removed outliers from ${samples.size} samples")
        }

        return Pair(avgX, avgY)
    }

    /**
     * Remove outliers using IQR (Interquartile Range) method.
     */
    private fun removeOutliersIQR(sortedValues: List<Float>): List<Float> {
        val n = sortedValues.size
        if (n < 4) return sortedValues

        val q1Index = n / 4
        val q3Index = 3 * n / 4

        val q1 = sortedValues[q1Index]
        val q3 = sortedValues[q3Index]
        val iqr = q3 - q1

        val lowerBound = q1 - 1.5f * iqr
        val upperBound = q3 + 1.5f * iqr

        return sortedValues.filter { it in lowerBound..upperBound }
    }

    /**
     * Solve least squares problem: A * x = b
     * Using normal equations: (A^T * A) * x = A^T * b
     *
     * @param A Design matrix (n x m)
     * @param b Target values (n x 1)
     * @param m Number of coefficients to solve for
     */
    private fun solveLeastSquares(A: Array<FloatArray>, b: FloatArray, m: Int): FloatArray? {
        val n = A.size

        // Compute A^T * A (m x m matrix)
        val AtA = Array(m) { FloatArray(m) }
        for (i in 0 until m) {
            for (j in 0 until m) {
                var sum = 0f
                for (k in 0 until n) {
                    sum += A[k][i] * A[k][j]
                }
                AtA[i][j] = sum
            }
        }

        // Compute A^T * b (m x 1 vector)
        val Atb = FloatArray(m)
        for (i in 0 until m) {
            var sum = 0f
            for (k in 0 until n) {
                sum += A[k][i] * b[k]
            }
            Atb[i] = sum
        }

        // Solve (A^T * A) * x = A^T * b using Gaussian elimination
        return solveLinearSystem(AtA, Atb, m)
    }

    /**
     * Solve n x n linear system using Gaussian elimination with partial pivoting.
     */
    private fun solveLinearSystem(matrix: Array<FloatArray>, rhs: FloatArray, n: Int): FloatArray? {
        val aug = Array(n) { i ->
            FloatArray(n + 1) { j ->
                if (j < n) matrix[i][j] else rhs[i]
            }
        }

        // Forward elimination with partial pivoting
        for (col in 0 until n) {
            // Find pivot
            var maxRow = col
            var maxVal = abs(aug[col][col])
            for (row in col + 1 until n) {
                val absVal = abs(aug[row][col])
                if (absVal > maxVal) {
                    maxVal = absVal
                    maxRow = row
                }
            }

            if (maxVal < 1e-10f) {
                logger?.invoke("Matrix is singular or nearly singular at column $col")
                return null
            }

            // Swap rows
            if (maxRow != col) {
                val temp = aug[col]
                aug[col] = aug[maxRow]
                aug[maxRow] = temp
            }

            // Eliminate column
            for (row in col + 1 until n) {
                val factor = aug[row][col] / aug[col][col]
                for (j in col until n + 1) {
                    aug[row][j] -= factor * aug[col][j]
                }
            }
        }

        // Back substitution
        val result = FloatArray(n)
        for (row in n - 1 downTo 0) {
            var sum = aug[row][n]
            for (col in row + 1 until n) {
                sum -= aug[row][col] * result[col]
            }
            result[row] = sum / aug[row][row]
        }

        return result
    }

    /**
     * Get calibration data for saving.
     */
    fun getCalibrationData(): CalibrationData? {
        if (!isCalibrated || transformX == null || transformY == null) {
            return null
        }

        return CalibrationData(
            transformX = transformX!!.copyOf(),
            transformY = transformY!!.copyOf(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            calibrationError = calibrationError,
            mode = currentMode
        )
    }

    /**
     * Load calibration data.
     */
    fun loadCalibrationData(data: CalibrationData): Boolean {
        // Check if calibration matches current screen size
        if (data.screenWidth != screenWidth || data.screenHeight != screenHeight) {
            logger?.invoke("Calibration was for different screen size (${data.screenWidth}x${data.screenHeight}), current is ${screenWidth}x${screenHeight}")
            return false
        }

        transformX = data.transformX
        transformY = data.transformY
        calibrationError = data.calibrationError
        currentMode = data.mode
        isCalibrated = true

        logger?.invoke("Calibration (${currentMode.name}) loaded! Error: %.1f pixels".format(calibrationError))
        return true
    }

    /**
     * Clear current calibration and reset to uncalibrated state.
     */
    fun resetCalibration() {
        calibrationPoints.clear()
        transformX = null
        transformY = null
        isCalibrated = false
        calibrationError = 0f
    }

    /**
     * Check if the system is calibrated.
     */
    fun isCalibrated(): Boolean = isCalibrated

    /**
     * Get the calibration error in pixels (average).
     */
    fun getCalibrationError(): Float = calibrationError

    /**
     * Get the number of calibration points.
     */
    fun getCalibrationPointCount(): Int = calibrationPoints.size

    /**
     * Get the current calibration mode.
     */
    fun getCurrentCalibrationMode(): CalibrationMode = currentMode

    /**
     * Check if polynomial calibration is being used.
     */
    fun isPolynomialCalibration(): Boolean = currentMode == CalibrationMode.POLYNOMIAL
}
