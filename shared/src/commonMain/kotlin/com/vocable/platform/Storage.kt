package com.vocable.platform

import com.vocable.eyetracking.models.CalibrationData

/**
 * Platform-agnostic storage interface for persisting data.
 * Implementations: SharedPreferences (Android), UserDefaults (iOS)
 */
interface Storage {
    /**
     * Save calibration data to persistent storage.
     */
    fun saveCalibrationData(data: CalibrationData, mode: String): Boolean

    /**
     * Load calibration data from persistent storage.
     */
    fun loadCalibrationData(mode: String): CalibrationData?

    /**
     * Delete calibration data from persistent storage.
     */
    fun deleteCalibrationData(mode: String): Boolean

    /**
     * Save a string preference.
     */
    fun saveString(key: String, value: String)

    /**
     * Load a string preference.
     */
    fun loadString(key: String, defaultValue: String = ""): String

    /**
     * Save a float preference.
     */
    fun saveFloat(key: String, value: Float)

    /**
     * Load a float preference.
     */
    fun loadFloat(key: String, defaultValue: Float = 0f): Float

    /**
     * Save a boolean preference.
     */
    fun saveBoolean(key: String, value: Boolean)

    /**
     * Load a boolean preference.
     */
    fun loadBoolean(key: String, defaultValue: Boolean = false): Boolean

    /**
     * Save an integer preference.
     */
    fun saveInt(key: String, value: Int)

    /**
     * Load an integer preference.
     */
    fun loadInt(key: String, defaultValue: Int = 0): Int
}

/**
 * Expect declaration for platform-specific storage.
 * Implemented by:
 * - Android: SharedPreferencesStorage
 * - iOS: UserDefaultsStorage
 */
expect fun createStorage(): Storage
