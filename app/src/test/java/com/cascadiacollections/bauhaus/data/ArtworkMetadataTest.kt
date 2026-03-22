package com.cascadiacollections.bauhaus.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
    }
}
