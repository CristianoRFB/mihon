package eu.kanade.presentation.more.settings.screen.advanced

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.model.SourceWithCount

class ClearDatabaseSourceFilterTest {

    private val installed = SourceWithCount(
        Source(id = 1L, lang = "en", name = "MangaDex", supportsLatest = false, isStub = false),
        count = 7L,
    )
    private val orphaned = SourceWithCount(
        Source(id = 2L, lang = "pt-BR", name = "Old Source", supportsLatest = false, isStub = true),
        count = 4L,
    )
    private val all = listOf(installed, orphaned)

    @Test
    fun `default filters show all sources`() {
        filterDatabaseSources(all, "", false) shouldBe all
    }

    @Test
    fun `search ignores case and trims whitespace`() {
        filterDatabaseSources(all, "  MANGA ", false) shouldBe listOf(installed)
    }

    @Test
    fun `search matches source language and identifier`() {
        filterDatabaseSources(all, "pt-br", false) shouldBe listOf(orphaned)
        filterDatabaseSources(all, "2", false) shouldBe listOf(orphaned)
    }

    @Test
    fun `orphan filter only shows missing sources`() {
        filterDatabaseSources(all, "", true) shouldBe listOf(orphaned)
    }

    @Test
    fun `orphan filter combines with text search`() {
        filterDatabaseSources(all, "Old", true) shouldBe listOf(orphaned)
        filterDatabaseSources(all, "MangaDex", true) shouldBe emptyList()
    }

    @Test
    fun `filtering never mutates the source list`() {
        filterDatabaseSources(all, "missing", true) shouldBe emptyList()
        all shouldBe listOf(installed, orphaned)
    }
}
