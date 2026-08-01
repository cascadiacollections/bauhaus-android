package com.cascadiacollections.bauhaus.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Structured license block published at `license_details` in the metadata JSON.
 *
 * Duplicates the flat `license` / `license_url` / `source` / `source_url` keys;
 * the service emits both so older consumers keep working. Prefer the flat keys
 * and fall back to this — see [ArtworkMetadata.licenseLink].
 */
@Serializable
data class LicenseDetails(
    val type: String = "",
    val url: String = "",
    val source: String = "",
    @SerialName("source_url") val sourceUrl: String = "",
)

/**
 * One entry of the metadata `variants` array — a concrete rendition of the day's
 * artwork that the service has already generated and can serve.
 *
 * [type] is `stylized` or `original`; [url] is service-relative (`/api/<date>`).
 * The pixel dimensions are the reason this matters to the app: they let the
 * preview reserve the artwork's true aspect ratio without a `HEAD` request or a
 * decode, so the first frame does not have to crop to a guessed 4:3 box.
 */
@Serializable
data class ArtworkVariant(
    val type: String = "",
    val format: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val url: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
)

/**
 * Metadata returned by the bauhaus service for a given day's artwork.
 *
 * Mirrors the JSON written by the publishing pipeline (`src/main.py`) and served
 * at `/api/today.json` and `/api/YYYY-MM-DD.json`.
 *
 * Every field defaults, so deserialization never fails on missing keys — early
 * archive entries predate several of them, and the service adds keys without
 * versioning the endpoint.
 *
 * ## The `date` field is authoritative
 *
 * The service keys artwork by **UTC** date and publishes at 04:00 UTC. A device
 * clock therefore disagrees with the service for part of every day — ahead of it
 * east of UTC, behind it west of UTC, and always during the four-hour window
 * before a day's run completes. [publishedDate] is the service's own answer for
 * which day `/api/today` just resolved to, so the app anchors browsing to that
 * rather than to `LocalDate.now()`.
 */
@Serializable
data class ArtworkMetadata(
    val title: String = "",
    val artist: String = "",
    val source: String = "",
    val license: String = "",
    val date: String = "",
    @SerialName("source_url") val sourceUrl: String = "",
    @SerialName("license_url") val licenseUrl: String = "",
    val photographer: String = "",
    @SerialName("photographer_url") val photographerUrl: String = "",
    @SerialName("style_title") val styleTitle: String = "",
    @SerialName("style_artist") val styleArtist: String = "",
    @SerialName("license_details") val licenseDetails: LicenseDetails? = null,
    val variants: List<ArtworkVariant> = emptyList(),
    @SerialName("generated_at") val generatedAt: String = "",
) {
    /** [date] parsed, or `null` when absent or malformed. */
    val publishedDate: LocalDate?
        get() = date.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The stylized rendition — the one `/api/<date>` serves. */
    val stylizedVariant: ArtworkVariant?
        get() = variants.firstOrNull { it.type == "stylized" }

    /**
     * Width / height of the stylized artwork, or `null` when the service did not
     * publish variant dimensions for this date.
     */
    val aspectRatio: Float?
        get() = stylizedVariant
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { it.width.toFloat() / it.height.toFloat() }

    /** Credited creator — the photographer for Unsplash days, the artist otherwise. */
    val creator: String
        get() = artist.trim().ifBlank { photographer.trim() }

    /** Link to the artwork in the upstream collection, preferring the flat key. */
    val attributionUrl: String
        get() = sourceUrl.ifBlank { licenseDetails?.sourceUrl.orEmpty() }
            .ifBlank { photographerUrl }
            .trim()

    /** Link to the licence text, preferring the flat key. */
    val licenseLink: String
        get() = licenseUrl.ifBlank { licenseDetails?.url.orEmpty() }.trim()

    /** Human-readable licence name (`CC0-1.0`, `Unsplash License`, …). */
    val licenseLabel: String
        get() = license.ifBlank { licenseDetails?.type.orEmpty() }.trim()

    /** `"Monet — Water Lilies"`-style credit for the style reference, if published. */
    val styleCredit: String
        get() = listOf(styleTitle.trim(), styleArtist.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" — ")
}

/**
 * Response shape of `GET /api/health`.
 *
 * The endpoint reports publish freshness rather than reachability: `200` with
 * `status: "ok"` when the current day is published, `503` with `status: "stale"`
 * when the pipeline has fallen behind, and `503` with `status: "unhealthy"` when
 * nothing is published at all or storage is down. The app reads it only after a
 * metadata fetch has already failed, to tell "the service has no artwork for
 * that day yet" apart from "this device is offline".
 */
@Serializable
data class ServiceHealth(
    val status: String = "",
    val date: String = "",
    @SerialName("stale_days") val staleDays: Int? = null,
    val error: String = "",
) {
    /** Latest date the service reports as published, or `null` when it has none. */
    val latestDate: LocalDate?
        get() = date.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** `true` only when the service considers today's artwork published and current. */
    val isCurrent: Boolean
        get() = status == STATUS_OK

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_STALE = "stale"
        const val STATUS_UNHEALTHY = "unhealthy"
    }
}
