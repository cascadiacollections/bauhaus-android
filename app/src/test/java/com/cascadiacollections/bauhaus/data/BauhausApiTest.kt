package com.cascadiacollections.bauhaus.data

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.prop
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class BauhausApiTest {

    // ── decode sizing ────────────────────────────────────────────────────────

    @Test
    fun `scaleToFit fits a landscape source into a portrait target`() {
        // The old power-of-two path left this case at full size, because it
        // required both axes to still exceed the target before halving.
        assertThat(scaleToFit(3000, 2000, 1080, 2400)).isEqualTo(1080 to 720)
    }

    @Test
    fun `scaleToFit fits a portrait source into a portrait target`() {
        assertThat(scaleToFit(2160, 3840, 1080, 2400)).isEqualTo(1080 to 1920)
    }

    @Test
    fun `scaleToFit preserves aspect ratio`() {
        val (width, height) = scaleToFit(4000, 3000, 1000, 1000)
        assertThat(width).isEqualTo(1000)
        assertThat(height).isEqualTo(750)
    }

    @Test
    fun `scaleToFit never upscales a source smaller than the target`() {
        assertThat(scaleToFit(800, 600, 1080, 2400)).isEqualTo(800 to 600)
    }

    @Test
    fun `scaleToFit does not collapse an extreme ratio to zero`() {
        val (width, height) = scaleToFit(10_000, 5, 100, 100)
        assertThat(width).isGreaterThanOrEqualTo(1)
        assertThat(height).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `scaleToFit falls back to the target for a degenerate source`() {
        assertThat(scaleToFit(0, 0, 1080, 2400)).isEqualTo(1080 to 2400)
    }

    @Test
    fun `fetchTodayMetadata decodes successful response`() = runTest {
        val api = BauhausApi(clientResponding(200, """{"title":"Composition VIII","artist":"Kandinsky"}"""))

        val metadata = api.fetchTodayMetadata()

        assertThat(metadata.title).isEqualTo("Composition VIII")
        assertThat(metadata.artist).isEqualTo("Kandinsky")
    }

    @Test
    fun `fetchTodayMetadata throws typed http exception`() = runTest {
        val api = BauhausApi(clientResponding(503, """{"error":"unavailable"}"""))

        assertFailure { api.fetchTodayMetadata() }
            .isInstanceOf<BauhausHttpException>()
            .prop(BauhausHttpException::code)
            .isEqualTo(503)
    }

    @Test
    fun `fetchTodayMetadata throws decode exception for invalid json`() = runTest {
        val api = BauhausApi(clientResponding(200, """{"title":123}"""))

        assertFailure { api.fetchTodayMetadata() }.isInstanceOf<BauhausDecodeException>()
    }

    @Test
    fun `fetchTodayImage wraps io failures as network exception`() = runTest {
        val api = BauhausApi(
            OkHttpClient.Builder()
                .addInterceptor { throw IOException("offline") }
                .build()
        )

        assertFailure { api.fetchTodayImage() }.isInstanceOf<BauhausNetworkException>()
    }

    @Test
    fun `network exception is classified as a connectivity failure not a fault`() = runTest {
        // BauhausNetworkException is not an IOException, so a bare catch of
        // IOException misses it and every offline fetch looks like a crash.
        val api = BauhausApi(
            OkHttpClient.Builder()
                .addInterceptor { throw IOException("offline") }
                .build()
        )

        assertFailure { api.fetchTodayMetadata() }
            .isInstanceOf<BauhausNetworkException>()
            .prop(Throwable::isConnectivityFailure)
            .isTrue()
        assertThat(BauhausHttpException(404, "/api/today.json").isConnectivityFailure).isFalse()
        assertThat(BauhausDecodeException("/api/today.json", RuntimeException()).isConnectivityFailure).isFalse()
    }

    @Test
    fun `fetchArchivePage parses the live archive shape, gaps included`() = runTest {
        // Shaped after /api/archive on 2026-10-04: 2026-10-02 was never published.
        val api = BauhausApi(
            clientResponding(
                200,
                """
                {"dates":["2026-10-03","2026-10-01","2026-09-30"],"count":3,"total":183,
                 "next":"/api/archive?limit=3&before=2026-09-30"}
                """.trimIndent()
            )
        )

        val page = api.fetchArchivePage(LocalDate.of(2026, 10, 4))

        assertThat(page.publishedDates).containsExactly(
            LocalDate.of(2026, 10, 3),
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 9, 30)
        )
        assertThat(page.hasMore).isTrue()
    }

    @Test
    fun `the archive's last page has no more`() = runTest {
        val api = BauhausApi(clientResponding(200, """{"dates":["2026-01-02"],"count":1,"total":1}"""))

        assertThat(api.fetchArchivePage(LocalDate.of(2026, 1, 3)).hasMore).isFalse()
    }

    @Test
    fun `fetchArchivePage asks for the largest page of dates before the given day`() = runTest {
        var observedUrl: HttpUrl? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                observedUrl = chain.request().url
                respond(chain.request(), 200, """{"dates":[]}""")
            }
            .build()

        BauhausApi(client).fetchArchivePage(LocalDate.of(2026, 7, 31))

        assertThat(observedUrl?.encodedPath).isEqualTo("/api/archive")
        assertThat(observedUrl?.queryParameter("before")).isEqualTo("2026-07-31")
        assertThat(observedUrl?.queryParameter("limit")).isEqualTo("1000")
    }

    @Test
    fun `fetchArchivePage throws typed http exception`() = runTest {
        val api = BauhausApi(clientResponding(400, """{"error":"limit must be between 1 and 1000"}"""))

        assertFailure { api.fetchArchivePage(LocalDate.of(2026, 7, 31)) }
            .isInstanceOf<BauhausHttpException>()
            .prop(BauhausHttpException::code)
            .isEqualTo(400)
    }

    @Test
    fun `fetchHealth parses the 503 stale body instead of treating it as an error`() = runTest {
        val api = BauhausApi(
            clientResponding(503, """{"status":"stale","date":"2026-07-28","stale_days":3}""")
        )

        val health = api.fetchHealth()

        assertThat(health.status).isEqualTo(ServiceHealth.STATUS_STALE)
        assertThat(health.latestDate).isEqualTo(LocalDate.of(2026, 7, 28))
        assertThat(health.isCurrent).isFalse()
    }

    @Test
    fun `fetchHealth parses a healthy report`() = runTest {
        val api = BauhausApi(clientResponding(200, """{"status":"ok","date":"2026-07-31","stale_days":0}"""))

        assertThat(api.fetchHealth().isCurrent).isTrue()
    }

    @Test
    fun `fetchHealth throws for statuses it cannot interpret`() = runTest {
        val api = BauhausApi(clientResponding(418, ""))

        assertFailure { api.fetchHealth() }.isInstanceOf<BauhausHttpException>()
    }

    private fun clientResponding(code: Int, body: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(mockResponseInterceptor(code, body))
        .build()

    private fun mockResponseInterceptor(code: Int, body: String): Interceptor = Interceptor { chain ->
        respond(chain.request(), code, body)
    }

    private fun respond(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("mock")
        .body(body.toResponseBody())
        .build()
}
