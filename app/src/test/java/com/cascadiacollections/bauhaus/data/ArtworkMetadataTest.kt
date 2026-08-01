package com.cascadiacollections.bauhaus.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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

        assertEquals("Sunset over Fuji", metadata.title)
        assertEquals("Hokusai", metadata.artist)
        assertEquals("Metropolitan Museum of Art", metadata.source)
        assertEquals("CC0", metadata.license)
        assertEquals("2026-03-22", metadata.date)
    }

    @Test
    fun `missing fields default to empty strings`() {
        val input = """{"title": "Minimal"}"""

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertEquals("Minimal", metadata.title)
        assertEquals("", metadata.artist)
        assertEquals("", metadata.source)
        assertEquals("", metadata.license)
        assertEquals("", metadata.date)
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

        assertEquals("Test", metadata.title)
    }

    @Test
    fun `empty object deserializes with all defaults`() {
        val metadata = json.decodeFromString<ArtworkMetadata>("{}")

        assertEquals("", metadata.title)
        assertEquals("", metadata.artist)
        assertEquals("", metadata.source)
        assertEquals("", metadata.license)
        assertEquals("", metadata.date)
        assertNull(metadata.publishedDate)
        assertNull(metadata.aspectRatio)
        assertNull(metadata.licenseDetails)
        assertTrue(metadata.variants.isEmpty())
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

        assertEquals(LocalDate.of(2026, 7, 31), metadata.publishedDate)
        assertEquals("https://www.metmuseum.org/art/collection/search/437123", metadata.sourceUrl)
        assertEquals("https://creativecommons.org/publicdomain/zero/1.0/", metadata.licenseUrl)
        assertEquals("CC0-1.0", metadata.licenseDetails?.type)
        assertEquals("The Great Wave off Kanagawa — Katsushika Hokusai", metadata.styleCredit)
        assertEquals("2026-07-31T04:03:11.482913+00:00", metadata.generatedAt)
        assertEquals(2, metadata.variants.size)
    }

    @Test
    fun `aspect ratio comes from the stylized variant not the original`() {
        val metadata = json.decodeFromString<ArtworkMetadata>(fullPayload)

        assertEquals(1280, metadata.stylizedVariant?.width)
        assertEquals(1280f / 853f, metadata.aspectRatio!!, 0.0001f)
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

        assertEquals("Unsplash License", metadata.licenseLabel)
        assertEquals("https://unsplash.com/license", metadata.licenseLink)
        assertEquals("https://unsplash.com/photos/abc123", metadata.attributionUrl)
    }

    @Test
    fun `credits the photographer on unsplash days`() {
        val input = """{"photographer": "Ansel Adams", "photographer_url": "https://unsplash.com/@ansel"}"""

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertEquals("Ansel Adams", metadata.creator)
        assertEquals("https://unsplash.com/@ansel", metadata.attributionUrl)
    }

    @Test
    fun `malformed date does not throw`() {
        val metadata = json.decodeFromString<ArtworkMetadata>("""{"date": "not-a-date"}""")

        assertNull(metadata.publishedDate)
    }

    @Test
    fun `variant without dimensions yields no aspect ratio`() {
        val input = """{"variants":[{"type":"stylized","width":0,"height":0}]}"""

        val metadata = json.decodeFromString<ArtworkMetadata>(input)

        assertNull(metadata.aspectRatio)
    }
}

class ServiceHealthTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses a healthy report`() {
        val health = json.decodeFromString<ServiceHealth>(
            """{"status":"ok","date":"2026-07-31","stale_days":0}""",
        )

        assertTrue(health.isCurrent)
        assertEquals(LocalDate.of(2026, 7, 31), health.latestDate)
    }

    @Test
    fun `parses the stale 503 body`() {
        val health = json.decodeFromString<ServiceHealth>(
            """{"status":"stale","date":"2026-07-28","stale_days":3}""",
        )

        assertFalse(health.isCurrent)
        assertEquals(3, health.staleDays)
        assertEquals(LocalDate.of(2026, 7, 28), health.latestDate)
    }

    @Test
    fun `parses the unhealthy 503 body which carries no date`() {
        val health = json.decodeFromString<ServiceHealth>(
            """{"status":"unhealthy","error":"no artwork published"}""",
        )

        assertFalse(health.isCurrent)
        assertNull(health.latestDate)
        assertEquals("no artwork published", health.error)
    }
}
