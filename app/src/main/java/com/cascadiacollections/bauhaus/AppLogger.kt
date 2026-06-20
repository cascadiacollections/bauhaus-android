package com.cascadiacollections.bauhaus

import android.util.Log

/**
 * Shared logging facade that mirrors to logcat and crash reporting.
 */
object AppLogger {
    data class Event(
        val name: String,
        val attributes: Map<String, String> = emptyMap(),
    )

    fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun info(tag: String, event: Event, message: String) {
        val payload = format(event, message)
        Log.i(tag, payload)
    }

    fun warn(tag: String, message: String) {
        Log.w(tag, message)
        CrashReporter.log("W/$tag: $message")
    }

    fun warn(tag: String, event: Event, message: String) {
        val payload = format(event, message)
        Log.w(tag, payload)
        CrashReporter.log("W/$tag: $payload")
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        CrashReporter.log("E/$tag: $message")
        throwable?.let(CrashReporter::recordException)
    }

    fun error(tag: String, event: Event, message: String, throwable: Throwable? = null) {
        val payload = format(event, message)
        Log.e(tag, payload, throwable)
        CrashReporter.log("E/$tag: $payload")
        throwable?.let(CrashReporter::recordException)
    }

    private fun format(event: Event, message: String): String = buildString {
        append(event.name)
        append(": ")
        append(message)
        if (event.attributes.isNotEmpty()) {
            append(" | ")
            append(event.attributes.entries.joinToString(",") { "${it.key}=${it.value}" })
        }
    }
}
