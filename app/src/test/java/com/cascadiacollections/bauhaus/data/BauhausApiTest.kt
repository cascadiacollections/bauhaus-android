package com.cascadiacollections.bauhaus.data

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class BauhausApiTest {
    @Test
    fun `fetchTodayMetadata decodes successful response`() = runTest {
        val api = BauhausApi(clientResponding(200, """{"title":"Composition VIII","artist":"Kandinsky"}"""))

        val metadata = api.fetchTodayMetadata()

        assertEquals("Composition VIII", metadata.title)
        assertEquals("Kandinsky", metadata.artist)
    }

    @Test
    fun `fetchTodayMetadata throws typed http exception`() = runTest {
        val api = BauhausApi(clientResponding(503, """{"error":"unavailable"}"""))

        val error = expectThrows<BauhausHttpException> {
            api.fetchTodayMetadata()
        }

        assertEquals(503, error.code)
    }

    @Test
    fun `fetchTodayMetadata throws decode exception for invalid json`() = runTest {
        val api = BauhausApi(clientResponding(200, """{"title":123}"""))

        expectThrows<BauhausDecodeException> {
            api.fetchTodayMetadata()
        }
    }

    @Test
    fun `fetchTodayImage wraps io failures as network exception`() = runTest {
        val api = BauhausApi(
            OkHttpClient.Builder()
                .addInterceptor { throw IOException("offline") }
                .build(),
        )

        expectThrows<BauhausNetworkException> {
            api.fetchTodayImage()
        }
    }

    @Test
    fun `network exception is classified as a connectivity failure not a fault`() = runTest {
        // BauhausNetworkException is not an IOException, so a bare catch of
        // IOException misses it and every offline fetch looks like a crash.
        val api = BauhausApi(
            OkHttpClient.Builder()
                .addInterceptor { throw IOException("offline") }
                .build(),
        )

        val error = expectThrows<BauhausNetworkException> { api.fetchTodayMetadata() }

        assertTrue(error.isConnectivityFailure)
        assertFalse(BauhausHttpException(404, "/api/today.json").isConnectivityFailure)
        assertFalse(BauhausDecodeException("/api/today.json", RuntimeException()).isConnectivityFailure)
    }

    @Test
    fun `hasArtworkForDate is true for 200 and issues a HEAD`() = runTest {
        var observedMethod: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                observedMethod = chain.request().method
                respond(chain.request(), 200, "")
            }
            .build()

        assertTrue(BauhausApi(client).hasArtworkForDate(LocalDate.of(2026, 7, 31)))
        assertEquals("HEAD", observedMethod)
    }

    @Test
    fun `hasArtworkForDate is false for 404 rather than throwing`() = runTest {
        val api = BauhausApi(clientResponding(404, ""))

        assertFalse(api.hasArtworkForDate(LocalDate.of(2001, 1, 1)))
    }

    @Test
    fun `hasArtworkForDate throws for statuses that are not a clean yes or no`() = runTest {
        val api = BauhausApi(clientResponding(500, ""))

        val error = expectThrows<BauhausHttpException> {
            api.hasArtworkForDate(LocalDate.of(2026, 7, 31))
        }

        assertEquals(500, error.code)
    }

    @Test
    fun `hasArtworkForDate probes the metadata document for the date`() = runTest {
        var observedPath: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                observedPath = chain.request().url.encodedPath
                respond(chain.request(), 200, "")
            }
            .build()

        BauhausApi(client).hasArtworkForDate(LocalDate.of(2026, 7, 31))

        assertEquals("/api/2026-07-31.json", observedPath)
    }

    @Test
    fun `fetchHealth parses the 503 stale body instead of treating it as an error`() = runTest {
        val api = BauhausApi(
            clientResponding(503, """{"status":"stale","date":"2026-07-28","stale_days":3}"""),
        )

        val health = api.fetchHealth()

        assertEquals(ServiceHealth.STATUS_STALE, health.status)
        assertEquals(LocalDate.of(2026, 7, 28), health.latestDate)
        assertFalse(health.isCurrent)
    }

    @Test
    fun `fetchHealth parses a healthy report`() = runTest {
        val api = BauhausApi(clientResponding(200, """{"status":"ok","date":"2026-07-31","stale_days":0}"""))

        assertTrue(api.fetchHealth().isCurrent)
    }

    @Test
    fun `fetchHealth throws for statuses it cannot interpret`() = runTest {
        val api = BauhausApi(clientResponding(418, ""))

        expectThrows<BauhausHttpException> { api.fetchHealth() }
    }

    private fun clientResponding(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(mockResponseInterceptor(code, body))
            .build()

    private fun mockResponseInterceptor(code: Int, body: String): Interceptor = Interceptor { chain ->
        respond(chain.request(), code, body)
    }

    private fun respond(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("mock")
            .body(body.toResponseBody())
            .build()

    private suspend inline fun <reified T : Throwable> expectThrows(
        crossinline block: suspend () -> Unit,
    ): T {
        return try {
            block()
            fail("Expected ${T::class.java.simpleName} to be thrown")
            throw AssertionError("Unreachable")
        } catch (error: Throwable) {
            if (error is T) {
                error
            } else {
                throw error
            }
        }
    }
}
