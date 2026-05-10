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
