package com.cascadiacollections.bauhaus.data

import java.time.LocalDate

/**
 * The service's archive index (`/api/archive`), read lazily from the top down.
 *
 * Publishing is daily but has gaps — a failed pipeline run leaves a day with no
 * artwork — so the pager cannot assume that the day before a published day was
 * published too. This answers "which days exist" from the index instead, reading
 * a page only when the pager needs dates older than any it has seen.
 *
 * Everything read is kept: the dates before a given day never change (publishing
 * is write-once), so a later question about an older day only costs a request
 * if it reaches past what has already been read.
 *
 * Not thread-safe; callers serialize access (the ViewModel holds `archiveMutex`).
 *
 * @param fetchPage Reads the published dates strictly before its argument, newest
 *   first — [BauhausApiClient.fetchArchivePage].
 */
class ArchiveIndex(private val fetchPage: suspend (before: LocalDate) -> ArchiveIndexPage) {
    /** Published dates read so far, newest first, all before [readFrom]. */
    private val known = mutableListOf<LocalDate>()

    /** Exclusive upper bound of the span read; `null` before the first read. */
    private var readFrom: LocalDate? = null

    /** `true` once the oldest page has been read. */
    private var exhausted = false

    /** The newest published date before [date], or `null` when [date] is at or before the archive's start. */
    suspend fun newestBefore(date: LocalDate): LocalDate? {
        readDownTo(date) { known.any { it.isBefore(date) } }
        return known.firstOrNull { it.isBefore(date) }
    }

    /** Every published date in [`from`, `until`), newest first. */
    suspend fun publishedBetween(from: LocalDate, until: LocalDate): List<LocalDate> {
        readDownTo(until) { known.lastOrNull()?.let { !it.isAfter(from) } == true }
        return known.filter { !it.isBefore(from) && it.isBefore(until) }
    }

    /**
     * Reads pages until [satisfied] or the archive's start, covering everything
     * before [until]. A question about a span newer than what has been read
     * restarts the read from there.
     */
    private suspend fun readDownTo(until: LocalDate, satisfied: () -> Boolean) {
        var top = readFrom
        if (top == null || until.isAfter(top)) {
            known.clear()
            top = until
            readFrom = until
            exhausted = false
        }
        while (!exhausted && !satisfied()) {
            // Continue below what has been read, so the span stays gap-free even
            // when an earlier read failed part-way.
            val before = known.lastOrNull() ?: top
            val page = fetchPage(before)
            val dates = page.publishedDates.filter { it.isBefore(before) }
            known += dates
            // An empty page with a `next` link would otherwise loop forever.
            if (!page.hasMore || dates.isEmpty()) exhausted = true
        }
    }
}
