package com.vocable.eyetracking.calibration

/**
 * Calibration mode for gaze-to-screen mapping.
 */
enum class CalibrationMode {
    /**
     * Affine (linear) calibration - faster but less accurate at edges.
     * Uses 3 coefficients per axis: screen_x = a₀ + a₁·gx + a₂·gy
     */
    AFFINE,

    /**
     * Polynomial (2nd order) calibration - more accurate, especially at edges.
     * Uses 6 coefficients per axis:
     * screen_x = a₀ + a₁·gx + a₂·gy + a₃·gx² + a₄·gy² + a₅·gx·gy
     *
     * This handles the nonlinear eye-to-screen mapping better, particularly
     * at screen edges where simple linear transforms are insufficient.
     */
    POLYNOMIAL
}
