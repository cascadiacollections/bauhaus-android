package com.cascadiacollections.bauhaus.data

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("source_url") val sourceUrl: String = ""
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
    @SerialName("size_bytes") val sizeBytes: Long = 0L
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
 * ## Which day this artwork was published for
 *
 * The service keys artwork by **UTC** date and publishes at 04:00 UTC. A device
 * clock therefore disagrees with the service for part of every day — ahead of it
 * east of UTC, behind it west of UTC, and always during the window before a
 * day's run completes. [publishedDate] is the service's own answer for which day
 * `/api/today` just resolved to, so the app anchors browsing to that rather than
 * to `LocalDate.now()`.
 *
 * [date] is **not** that answer. For most artworks it is the artwork's own date
 * (`"ca. 1750"`, `"1868–78"`); the pipeline writes the publish day there only
 * when the source has no date of its own. See [publishedDate].
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
    @SerialName("published_date") val publishedDateRaw: String = ""
) {
    /**
     * The UTC day the service published this artwork for — the same key the
     * archive uses — or `null` when the document gives no way to tell.
     *
     * Resolved in order of reliability:
     * 1. `published_date`, which the pipeline always writes as the publish day.
     *    Entries published before the field existed do not carry it.
     * 2. The UTC calendar day of [generatedAt], the upload timestamp. The pipeline
     *    takes the archive key and this timestamp from the UTC clock in the same
     *    run, so its day is the publish day.
     * 3. [date], but only when it is an ISO `yyyy-MM-dd`, and only as a last
     *    resort: for most artworks it is the artwork's own date, and an artwork
     *    dated `"2019-05-03"` would otherwise read as published that day.
     */
    val publishedDate: LocalDate?
        get() = publishedDateRaw.toIsoDateOrNull()
            ?: generatedAt.toUtcDateOrNull()
            ?: date.toIsoDateOrNull()

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
    val error: String = ""
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

/**
 * One page of `GET /api/archive` — the dates that have published artwork.
 *
 * Publishing is daily but not gap-free: a failed pipeline run leaves a day with
 * no artwork at all (2026-10-02 is one; 2026-07-20 to 07-30 is another), so the
 * only way to know which days exist is to ask.
 *
 * @property dates ISO dates, newest first.
 * @property next Service-relative URL of the following (older) page, absent on
 *   the last one.
 */
@Serializable
data class ArchiveIndexPage(val dates: List<String> = emptyList(), val next: String? = null) {
    /** [dates] parsed, newest first; entries that are not ISO dates are dropped. */
    val publishedDates: List<LocalDate>
        get() = dates.mapNotNull { it.toIsoDateOrNull() }

    /** `true` when older dates exist beyond this page. */
    val hasMore: Boolean
        get() = !next.isNullOrBlank()
}

/** This string as an ISO `yyyy-MM-dd` date, or `null` when it is not one. */
private fun String.toIsoDateOrNull(): LocalDate? =
    trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** The UTC calendar day of this ISO-8601 timestamp with offset, or `null` when it is not one. */
private fun String.toUtcDateOrNull(): LocalDate? = trim().takeIf { it.isNotEmpty() }?.let {
    runCatching { OffsetDateTime.parse(it).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate() }.getOrNull()
}
