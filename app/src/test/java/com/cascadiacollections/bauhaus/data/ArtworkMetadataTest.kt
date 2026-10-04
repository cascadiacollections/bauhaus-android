package com.cascadiacollections.bauhaus.data

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Test

class ArtworkMetadataTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes complete metadata`() {
        val input = """
            {
                "title": "Sunset over Fuji",
                "artist": "Hokusai",
                "source": "Metropolitan Museum of Art",
                "license": "CC0",
                "date": "2026-03-22"
            }
        """.trimIndent()

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertThat(metadata.title).isEqualTo("Sunset over Fuji")
        assertThat(metadata.artist).isEqualTo("Hokusai")
        assertThat(metadata.source).isEqualTo("Metropolitan Museum of Art")
        assertThat(metadata.license).isEqualTo("CC0")
        assertThat(metadata.date).isEqualTo("2026-03-22")
    }

    @Test
    fun `missing fields default to empty strings`() {
        val input = """{"title": "Minimal"}"""

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertThat(metadata.title).isEqualTo("Minimal")
        assertThat(metadata.artist).isEqualTo("")
        assertThat(metadata.source).isEqualTo("")
        assertThat(metadata.license).isEqualTo("")
        assertThat(metadata.date).isEqualTo("")
    }

    @Test
    fun `unknown fields are ignored`() {
        val input = """
            {
                "title": "Test",
                "unknown_field": 42,
                "nested": {"foo": "bar"}
            }
        """.trimIndent()

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertThat(metadata.title).isEqualTo("Test")
    }

    @Test
    fun `empty object deserializes with all defaults`() {
        val metadata = json.decodeFromString<ArtworkMetadata>("{}")

        assertThat(metadata.title).isEqualTo("")
        assertThat(metadata.artist).isEqualTo("")
        assertThat(metadata.source).isEqualTo("")
        assertThat(metadata.license).isEqualTo("")
        assertThat(metadata.date).isEqualTo("")
        assertThat(metadata.publishedDate).isNull()
        assertThat(metadata.aspectRatio).isNull()
        assertThat(metadata.licenseDetails).isNull()
        assertThat(metadata.variants).isEmpty()
    }

    /** Shaped after what the pipeline actually uploads for a scheduled Met run. */
    private val fullPayload = """
        {
          "title": "A View of the Seine",
          "artist": "Claude Monet",
          "date": "2026-07-31",
          "source": "met",
          "source_url": "https://www.metmuseum.org/art/collection/search/437123",
          "license": "CC0-1.0",
          "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
          "license_details": {
            "type": "CC0-1.0",
            "url": "https://creativecommons.org/publicdomain/zero/1.0/",
            "source": "met",
            "source_url": "https://www.metmuseum.org/art/collection/search/437123"
          },
          "style_title": "The Great Wave off Kanagawa",
          "style_artist": "Katsushika Hokusai",
          "generated_at": "2026-07-31T04:03:11.482913+00:00",
          "aesthetic": 0.62,
          "alpha": 1.0,
          "variants": [
            {
              "type": "stylized",
              "format": "image/jpeg",
              "width": 1280,
              "height": 853,
              "url": "/api/2026-07-31",
              "size_bytes": 412233
            },
            {
              "type": "original",
              "format": "image/jpeg",
              "width": 3000,
              "height": 2000,
              "url": "/api/2026-07-31/original",
              "size_bytes": 2913004
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `deserializes the published snake_case schema`() {
        val metadata = json.decodeFromString<ArtworkMetadata>(fullPayload)

        assertThat(metadata.publishedDate).isEqualTo(LocalDate.of(2026, 7, 31))
        assertThat(metadata.sourceUrl).isEqualTo("https://www.metmuseum.org/art/collection/search/437123")
        assertThat(metadata.licenseUrl).isEqualTo("https://creativecommons.org/publicdomain/zero/1.0/")
        assertThat(metadata.licenseDetails?.type).isEqualTo("CC0-1.0")
        assertThat(metadata.styleCredit).isEqualTo("The Great Wave off Kanagawa — Katsushika Hokusai")
        assertThat(metadata.generatedAt).isEqualTo("2026-07-31T04:03:11.482913+00:00")
        assertThat(metadata.variants).hasSize(2)
    }

    @Test
    fun `aspect ratio comes from the stylized variant not the original`() {
        val metadata = json.decodeFromString<ArtworkMetadata>(fullPayload)

        assertThat(metadata.stylizedVariant?.width).isEqualTo(1280)
        assertThat(metadata.aspectRatio).isNotNull().isCloseTo(1280f / 853f, 0.0001f)
    }

    @Test
    fun `falls back to license_details when the flat keys are absent`() {
        val input = """
            {
              "license_details": {
                "type": "Unsplash License",
                "url": "https://unsplash.com/license",
                "source": "unsplash",
                "source_url": "https://unsplash.com/photos/abc123"
              }
            }
        """.trimIndent()

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertThat(metadata.licenseLabel).isEqualTo("Unsplash License")
        assertThat(metadata.licenseLink).isEqualTo("https://unsplash.com/license")
        assertThat(metadata.attributionUrl).isEqualTo("https://unsplash.com/photos/abc123")
    }

    @Test
    fun `credits the photographer on unsplash days`() {
        val input = """{"photographer": "Ansel Adams", "photographer_url": "https://unsplash.com/@ansel"}"""

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertThat(metadata.creator).isEqualTo("Ansel Adams")
        assertThat(metadata.attributionUrl).isEqualTo("https://unsplash.com/@ansel")
    }

    @Test
    fun `malformed date does not throw`() {
        val metadata = json.decodeFromString<ArtworkMetadata>("""{"date": "not-a-date"}""")

        assertThat(metadata.publishedDate).isNull()
    }

    @Test
    fun `an artwork's own date is not mistaken for the publish date`() {
        // Shaped after the live /api/today.json for 2026-10-04: `date` is the Met
        // object date, and the entry predates `published_date`.
        val input = """
            {
              "artist": "Ishikawa Toyonobu",
              "date": "ca. 1750",
              "generated_at": "2026-10-04T05:27:53.363896+00:00"
            }
        """.trimIndent()

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertThat(metadata.publishedDate).isEqualTo(LocalDate.of(2026, 10, 4))
    }

    @Test
    fun `published_date wins over every other field`() {
        val input = """
            {
              "date": "1868-07-01",
              "published_date": "2026-10-05",
              "generated_at": "2026-10-04T23:59:59+00:00"
            }
        """.trimIndent()

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertThat(metadata.publishedDate).isEqualTo(LocalDate.of(2026, 10, 5))
    }

    @Test
    fun `an ISO date is used when published_date is absent`() {
        val input = """{"date": "2026-07-31", "generated_at": "2026-08-01T00:00:01+00:00"}"""

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertThat(metadata.publishedDate).isEqualTo(LocalDate.of(2026, 7, 31))
    }

    @Test
    fun `an unparseable published_date falls through to the other fields`() {
        val input = """{"published_date": "soon", "date": "1868–78", "generated_at": "2026-10-03T17:30:48+00:00"}"""

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertThat(metadata.publishedDate).isEqualTo(LocalDate.of(2026, 10, 3))
    }

    @Test
    fun `generated_at is read as a UTC calendar day whatever its offset`() {
        // 21:30 on the 3rd in UTC-7 is 04:30 on the 4th in UTC.
        val input = """{"date": "", "generated_at": "2026-10-03T21:30:00-07:00"}"""

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertThat(metadata.publishedDate).isEqualTo(LocalDate.of(2026, 10, 4))
    }

    @Test
    fun `no usable field yields no publish date`() {
        val input = """{"date": "ca. 1750", "generated_at": "yesterday", "published_date": ""}"""

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertThat(metadata.publishedDate).isNull()
    }

    @Test
    fun `variant without dimensions yields no aspect ratio`() {
        val input = """{"variants":[{"type":"stylized","width":0,"height":0}]}"""

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertThat(metadata.aspectRatio).isNull()
    }
}

class ServiceHealthTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses a healthy report`() {
        val health = json.decodeFromString<ServiceHealth>(
            """{"status":"ok","date":"2026-07-31","stale_days":0}"""
        )

        assertThat(health.isCurrent).isTrue()
        assertThat(health.latestDate).isEqualTo(LocalDate.of(2026, 7, 31))
    }

    @Test
    fun `parses the stale 503 body`() {
        val health = json.decodeFromString<ServiceHealth>(
            """{"status":"stale","date":"2026-07-28","stale_days":3}"""
        )

        assertThat(health.isCurrent).isFalse()
        assertThat(health.staleDays).isEqualTo(3)
        assertThat(health.latestDate).isEqualTo(LocalDate.of(2026, 7, 28))
    }

    @Test
    fun `parses the unhealthy 503 body which carries no date`() {
        val health = json.decodeFromString<ServiceHealth>(
            """{"status":"unhealthy","error":"no artwork published"}"""
        )

        assertThat(health.isCurrent).isFalse()
        assertThat(health.latestDate).isNull()
        assertThat(health.error).isEqualTo("no artwork published")
    }
}
