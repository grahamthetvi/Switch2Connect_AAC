package com.vocable.platform

import platform.Foundation.NSLog

/**
 * iOS actual implementation for Logger using NSLog.
 */
actual fun createLogger(tag: String): Logger = NSLogLogger(tag)

/**
 * NSLog-based logger implementation for iOS.
 *
 * Note: For production apps, consider using os_log for better performance.
 * This uses NSLog for simplicity and compatibility.
 */
class NSLogLogger(private val tag: String) : Logger {
    override fun debug(message: String) {
        NSLog("[$tag] DEBUG: $message")
    }

    override fun info(message: String) {
        NSLog("[$tag] INFO: $message")
    }

    override fun warn(message: String) {
        NSLog("[$tag] WARN: $message")
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) {
            NSLog("[$tag] ERROR: $message - ${throwable.message}")
            throwable.printStackTrace()
        } else {
            NSLog("[$tag] ERROR: $message")
        }
    }
}
