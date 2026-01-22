package com.vocable.platform

import com.vocable.eyetracking.calibration.CalibrationMode
import com.vocable.eyetracking.models.CalibrationData
import platform.Foundation.NSUserDefaults

/**
 * iOS actual implementation for Storage using UserDefaults.
 */
actual fun createStorage(): Storage = UserDefaultsStorage()

/**
 * UserDefaults-based storage implementation for iOS.
 */
class UserDefaultsStorage : Storage {
    private val userDefaults = NSUserDefaults.standardUserDefaults

    companion object {
        private const val KEY_CALIBRATION_PREFIX = "calibration_data_"
        private const val KEY_TRANSFORM_X = "_transform_x"
        private const val KEY_TRANSFORM_Y = "_transform_y"
        private const val KEY_SCREEN_WIDTH = "_screen_width"
        private const val KEY_SCREEN_HEIGHT = "_screen_height"
        private const val KEY_ERROR = "_error"
        private const val KEY_MODE = "_mode"
    }

    override fun saveCalibrationData(data: CalibrationData, mode: String): Boolean {
        return try {
            val prefix = KEY_CALIBRATION_PREFIX + mode

            // Save transform X coefficients as comma-separated string
            userDefaults.setObject(
                data.transformX.joinToString(","),
                forKey = prefix + KEY_TRANSFORM_X
            )

            // Save transform Y coefficients as comma-separated string
            userDefaults.setObject(
                data.transformY.joinToString(","),
                forKey = prefix + KEY_TRANSFORM_Y
            )

            userDefaults.setInteger(
                data.screenWidth.toLong(),
                forKey = prefix + KEY_SCREEN_WIDTH
            )

            userDefaults.setInteger(
                data.screenHeight.toLong(),
                forKey = prefix + KEY_SCREEN_HEIGHT
            )

            userDefaults.setFloat(
                data.calibrationError.toDouble(),
                forKey = prefix + KEY_ERROR
            )

            userDefaults.setObject(
                data.mode.name,
                forKey = prefix + KEY_MODE
            )

            // Synchronize to persist immediately
            userDefaults.synchronize()

            NSLog("Saved calibration data for mode: $mode")
            true
        } catch (e: Exception) {
            NSLog("Failed to save calibration data: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    override fun loadCalibrationData(mode: String): CalibrationData? {
        return try {
            val prefix = KEY_CALIBRATION_PREFIX + mode

            val transformXStr = userDefaults.stringForKey(prefix + KEY_TRANSFORM_X) ?: return null
            val transformYStr = userDefaults.stringForKey(prefix + KEY_TRANSFORM_Y) ?: return null

            val transformX = transformXStr.split(",").map { it.toFloat() }.toFloatArray()
            val transformY = transformYStr.split(",").map { it.toFloat() }.toFloatArray()

            val screenWidth = userDefaults.integerForKey(prefix + KEY_SCREEN_WIDTH).toInt()
            val screenHeight = userDefaults.integerForKey(prefix + KEY_SCREEN_HEIGHT).toInt()
            val error = userDefaults.floatForKey(prefix + KEY_ERROR).toFloat()
            val modeStr = userDefaults.stringForKey(prefix + KEY_MODE) ?: CalibrationMode.AFFINE.name
            val calibrationMode = CalibrationMode.valueOf(modeStr)

            if (screenWidth == 0 || screenHeight == 0) {
                return null
            }

            NSLog("Loaded calibration data for mode: $mode")
            CalibrationData(
                transformX = transformX,
                transformY = transformY,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                calibrationError = error,
                mode = calibrationMode
            )
        } catch (e: Exception) {
            NSLog("Failed to load calibration data: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    override fun deleteCalibrationData(mode: String): Boolean {
        return try {
            val prefix = KEY_CALIBRATION_PREFIX + mode

            userDefaults.removeObjectForKey(prefix + KEY_TRANSFORM_X)
            userDefaults.removeObjectForKey(prefix + KEY_TRANSFORM_Y)
            userDefaults.removeObjectForKey(prefix + KEY_SCREEN_WIDTH)
            userDefaults.removeObjectForKey(prefix + KEY_SCREEN_HEIGHT)
            userDefaults.removeObjectForKey(prefix + KEY_ERROR)
            userDefaults.removeObjectForKey(prefix + KEY_MODE)

            userDefaults.synchronize()

            NSLog("Deleted calibration data for mode: $mode")
            true
        } catch (e: Exception) {
            NSLog("Failed to delete calibration data: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    override fun saveString(key: String, value: String) {
        userDefaults.setObject(value, forKey = key)
        userDefaults.synchronize()
    }

    override fun loadString(key: String, defaultValue: String): String {
        return userDefaults.stringForKey(key) ?: defaultValue
    }

    override fun saveFloat(key: String, value: Float) {
        userDefaults.setFloat(value.toDouble(), forKey = key)
        userDefaults.synchronize()
    }

    override fun loadFloat(key: String, defaultValue: Float): Float {
        return userDefaults.floatForKey(key).toFloat()
    }

    override fun saveBoolean(key: String, value: Boolean) {
        userDefaults.setBool(value, forKey = key)
        userDefaults.synchronize()
    }

    override fun loadBoolean(key: String, defaultValue: Boolean): Boolean {
        return userDefaults.boolForKey(key)
    }

    override fun saveInt(key: String, value: Int) {
        userDefaults.setInteger(value.toLong(), forKey = key)
        userDefaults.synchronize()
    }

    override fun loadInt(key: String, defaultValue: Int): Int {
        return userDefaults.integerForKey(key).toInt()
    }
}
