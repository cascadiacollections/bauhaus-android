package com.cascadiacollections.bauhaus.data

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ArchiveIndexTest {

    private val oct4 = LocalDate.of(2026, 10, 4)

    /** The live archive's newest stretch: no 2026-10-02. */
    private val published = listOf(
        LocalDate.of(2026, 10, 4),
        LocalDate.of(2026, 10, 3),
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 9, 30),
        LocalDate.of(2026, 9, 29)
    )

    /** Serves [published] in pages of [pageSize], recording each `before`. */
    private class FakeArchive(private val dates: List<LocalDate>, private val pageSize: Int) {
        val requests = mutableListOf<LocalDate>()
        var failNext = false

        suspend fun page(before: LocalDate): ArchiveIndexPage {
            requests += before
            if (failNext) {
                failNext = false
                throw BauhausNetworkException("/api/archive", IOException("offline"))
            }
            val eligible = dates.filter { it.isBefore(before) }
            val page = eligible.take(pageSize)
            return ArchiveIndexPage(
                dates = page.map(LocalDate::toString),
                next = if (eligible.size > page.size) "/api/archive?before=${page.last()}" else null
            )
        }
    }

    @Test
    fun `the newest day before a gap is the day before the gap`() = runTest {
        val index = ArchiveIndex(FakeArchive(published, pageSize = 100)::page)

        assertThat(index.newestBefore(LocalDate.of(2026, 10, 3))).isEqualTo(LocalDate.of(2026, 10, 1))
    }

    @Test
    fun `nothing before the archive's first day`() = runTest {
        val index = ArchiveIndex(FakeArchive(published, pageSize = 100)::page)

        assertThat(index.newestBefore(LocalDate.of(2026, 9, 29))).isNull()
    }

    @Test
    fun `walking back costs one request while the read pages cover it`() = runTest {
        val archive = FakeArchive(published, pageSize = 100)
        val index = ArchiveIndex(archive::page)

        var cursor: LocalDate? = oct4
        val walked = mutableListOf<LocalDate>()
        while (cursor != null) {
            cursor = index.newestBefore(cursor)?.also { walked += it }
        }

        assertThat(walked).containsExactly(*published.drop(1).toTypedArray())
        assertThat(archive.requests).containsExactly(oct4)
    }

    @Test
    fun `pages are followed down as far as the question needs`() = runTest {
        val archive = FakeArchive(published, pageSize = 2)
        val index = ArchiveIndex(archive::page)

        val span = index.publishedBetween(from = LocalDate.of(2026, 9, 29), until = oct4)

        assertThat(span).containsExactly(*published.drop(1).toTypedArray())
        assertThat(archive.requests).containsExactly(oct4, LocalDate.of(2026, 10, 1))
    }

    @Test
    fun `a span excludes days that were never published`() = runTest {
        val index = ArchiveIndex(FakeArchive(published, pageSize = 100)::page)

        val span = index.publishedBetween(from = LocalDate.of(2026, 10, 1), until = oct4)

        assertThat(span).containsExactly(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 1))
    }

    @Test
    fun `a failed read is retried from where it stopped`() = runTest {
        val archive = FakeArchive(published, pageSize = 2)
        val index = ArchiveIndex(archive::page)
        index.newestBefore(oct4)
        archive.failNext = true

        assertFailure { index.newestBefore(LocalDate.of(2026, 10, 1)) }.isInstanceOf<BauhausNetworkException>()
        val next = index.newestBefore(LocalDate.of(2026, 10, 1))

        assertThat(next).isEqualTo(LocalDate.of(2026, 9, 30))
        assertThat(archive.requests).hasSize(3)
    }

    @Test
    fun `a page with a next link but no dates does not loop forever`() = runTest {
        val index = ArchiveIndex { ArchiveIndexPage(dates = emptyList(), next = "/api/archive?before=x") }

        assertThat(index.newestBefore(oct4)).isNull()
    }
}
