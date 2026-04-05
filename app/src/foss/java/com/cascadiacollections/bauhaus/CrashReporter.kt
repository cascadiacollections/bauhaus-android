package com.cascadiacollections.bauhaus

/**
 * No-op crash reporter for the FOSS build variant.
 * Provides the same API surface as the full variant but discards all reports.
 */
object CrashReporter {
    fun init(@Suppress("UNUSED_PARAMETER") app: android.app.Application) = Unit
    fun log(message: String) = Unit
    fun recordException(throwable: Throwable) = Unit
    fun setUserId(id: String) = Unit
}
