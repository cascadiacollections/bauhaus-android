package com.cascadiacollections.bauhaus.data

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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

        assertThat(exception.isConnectivityFailure).isTrue()
    }

    @Test
    fun `wrapped network failure is not an IOException`() {
        // The reason isConnectivityFailure has to exist: a catch (e: IOException)
        // ladder silently skips this branch.
        val exception: Throwable = BauhausNetworkException("/api/today.json", SocketTimeoutException())

        assertThat(exception).isNotInstanceOf<IOException>()
    }

    @Test
    fun `raw IOException is a connectivity failure`() {
        assertThat(IOException("socket closed").isConnectivityFailure).isTrue()
    }

    @Test
    fun `http error is not a connectivity failure`() {
        val exception = BauhausHttpException(code = 404, endpoint = "/api/2025-01-01.json")

        assertThat(exception.isConnectivityFailure).isFalse()
    }

    @Test
    fun `empty body is not a connectivity failure`() {
        assertThat(BauhausEmptyBodyException("/api/today.json").isConnectivityFailure).isFalse()
    }

    @Test
    fun `decode failure is not a connectivity failure`() {
        val exception = BauhausDecodeException("/api/today.json", IllegalArgumentException("bad json"))

        assertThat(exception.isConnectivityFailure).isFalse()
    }

    @Test
    fun `unrelated runtime failure is not a connectivity failure`() {
        assertThat(IllegalStateException("bug").isConnectivityFailure).isFalse()
    }

    @Test
    fun `http exception message names the code and endpoint`() {
        val exception = BauhausHttpException(code = 503, endpoint = "/api/health")

        assertThat(exception).messageContains("503")
        assertThat(exception).messageContains("/api/health")
    }
}
