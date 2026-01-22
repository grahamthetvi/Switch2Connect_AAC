package com.vocable.platform

/**
 * Platform-agnostic logger interface.
 * Implementations: Timber (Android), OSLog (iOS)
 */
interface Logger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

/**
 * Expect declaration for platform-specific logger.
 * Implemented by:
 * - Android: TimberLogger (wraps Timber)
 * - iOS: OSLogger (wraps os_log)
 */
expect fun createLogger(tag: String): Logger
