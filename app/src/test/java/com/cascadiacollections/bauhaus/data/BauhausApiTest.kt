package com.cascadiacollections.bauhaus.data

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

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

    private fun clientResponding(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(mockResponseInterceptor(code, body))
            .build()

    private fun mockResponseInterceptor(code: Int, body: String): Interceptor = Interceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("mock")
            .body(body.toResponseBody())
            .build()
    }

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
