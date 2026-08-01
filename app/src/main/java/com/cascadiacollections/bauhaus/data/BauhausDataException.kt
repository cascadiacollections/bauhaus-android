package com.cascadiacollections.bauhaus.data

import java.io.IOException

sealed class BauhausDataException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class BauhausHttpException(
    val code: Int,
    endpoint: String,
) : BauhausDataException("HTTP $code while requesting $endpoint")

class BauhausEmptyBodyException(
    endpoint: String,
) : BauhausDataException("Empty response body from $endpoint")

class BauhausDecodeException(
    endpoint: String,
    cause: Throwable,
) : BauhausDataException("Failed to decode response from $endpoint", cause)

class BauhausNetworkException(
    endpoint: String,
    cause: IOException,
) : BauhausDataException("Network request failed for $endpoint", cause)

/**
 * `true` when this throwable means "the request never completed", as opposed to
 * "the service answered something we did not want".
 *
 * [BauhausNetworkException] deliberately sits in the [BauhausDataException]
 * hierarchy rather than extending [IOException], so a bare `catch (e: IOException)`
 * does **not** see it. Callers that want to distinguish connectivity failures
 * from genuine faults must go through this, otherwise every fetch made while the
 * device is offline lands in the generic branch — surfacing a "something went
 * wrong" message and reporting a non-bug to the crash reporter.
 */
val Throwable.isConnectivityFailure: Boolean
    get() = this is BauhausNetworkException || this is IOException
