package com.cascadiacollections.bauhaus.data

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isConnectivityFailure] is the only correct way to ask "did this request ever
 * reach the service?".
 *
 * [BauhausNetworkException] deliberately does not extend [IOException], so a
 * bare `catch (e: IOException)` misses every wrapped connectivity failure and
 * routes an ordinary offline fetch into the generic branch — which reports a
 * non-bug to the crash reporter. These cases pin both halves of the predicate:
 * wrapped and unwrapped I/O failures are connectivity, and a service that
 * answered something unwanted is not.
 */
class BauhausDataExceptionTest {

    @Test
    fun `wrapped network failure is a connectivity failure`() {
        val exception = BauhausNetworkException("/api/today.json", UnknownHostException("no dns"))

        assertTrue(exception.isConnectivityFailure)
    }

    @Test
    fun `wrapped network failure is not an IOException`() {
        // The reason isConnectivityFailure has to exist: a catch (e: IOException)
        // ladder silently skips this branch.
        val exception: Throwable = BauhausNetworkException("/api/today.json", SocketTimeoutException())

        assertFalse(exception is IOException)
    }

    @Test
    fun `raw IOException is a connectivity failure`() {
        assertTrue(IOException("socket closed").isConnectivityFailure)
    }

    @Test
    fun `http error is not a connectivity failure`() {
        val exception = BauhausHttpException(code = 404, endpoint = "/api/2025-01-01.json")

        assertFalse(exception.isConnectivityFailure)
    }

    @Test
    fun `empty body is not a connectivity failure`() {
        assertFalse(BauhausEmptyBodyException("/api/today.json").isConnectivityFailure)
    }

    @Test
    fun `decode failure is not a connectivity failure`() {
        val exception = BauhausDecodeException("/api/today.json", IllegalArgumentException("bad json"))

        assertFalse(exception.isConnectivityFailure)
    }

    @Test
    fun `unrelated runtime failure is not a connectivity failure`() {
        assertFalse(IllegalStateException("bug").isConnectivityFailure)
    }

    @Test
    fun `http exception message names the code and endpoint`() {
        val exception = BauhausHttpException(code = 503, endpoint = "/api/health")

        assertTrue(exception.message!!.contains("503"))
        assertTrue(exception.message!!.contains("/api/health"))
    }
}
