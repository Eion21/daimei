package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga

class NovelChapterReadingOrderTest {
    @BeforeEach
    fun disableGlobalDownloadedOnlyForFixture() {
        // The ordinary filter reads this preference through the Android app graph.
        mockkStatic("eu.kanade.domain.manga.model.MangaKt")
        every { any<Manga>().downloadedFilter } returns TriState.DISABLED
    }

    @AfterEach
    fun restoreDownloadedPreferenceGetter() {
        unmockkStatic("eu.kanade.domain.manga.model.MangaKt")
    }

    private val manga = Manga.create().copy(id = 10)
    private val chapters = (1L..405L).map {
        Chapter.create().copy(id = it, mangaId = manga.id, chapterNumber = it.toDouble(), sourceOrder = it - 1)
    }

    private fun items(rows: List<Chapter>) = rows.map { ChapterList.Item(it, Download.State.NOT_DOWNLOADED) }

    @Test
    fun s37StartAndResumeFollowAscendingSourceIndicesInBothDisplayDirections() {
        val resumed = chapters.map { if (it.id < 3) it.copy(read = true) else it }
        for (direction in listOf(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DESC)) {
            val work = manga.copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE or direction)
            assertEquals(1L, novelNextUnread(items(chapters), work)?.id, "start direction=$direction")
            assertEquals(3L, novelNextUnread(items(resumed), work)?.id, "resume direction=$direction")
            assertNull(novelNextUnread(items(chapters.map { it.copy(read = true) }), work))
        }
    }

    @Test
    fun s37StartUsesExistingBookmarkFilterBeforeSelectingUnread() {
        val work = manga.copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SHOW_BOOKMARKED)
        val rows = chapters.map { it.copy(bookmark = it.id == 2L || it.id == 3L) }
        assertEquals(2L, novelNextUnread(items(rows), work)?.id)
    }

    @Test
    fun s37StartHonorsGlobalDownloadedOnly() {
        every { any<Manga>().downloadedFilter } returns TriState.ENABLED_IS
        val work = manga.copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE)
        val rows = chapters.map {
            ChapterList.Item(
                it,
                if (it.id == 2L ||
                    it.id == 3L
                ) {
                    Download.State.DOWNLOADED
                } else {
                    Download.State.NOT_DOWNLOADED
                },
            )
        }
        assertEquals(2L, novelNextUnread(rows, work)?.id)
    }

    @Test
    fun s37GenericImageSourceSelectionRemainsUnchanged() {
        for (direction in listOf(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DESC)) {
            val work = manga.copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE or direction)
            assertEquals(405L, items(chapters).getNextUnread(work)?.id)
        }
    }

    @Test
    fun s37OtherSortModesKeepComparatorAndStartSelection() {
        val rows = chapters.take(3).map { it.copy(name = "Chapter ${4 - it.id}") }
        for (sort in listOf(
            Manga.CHAPTER_SORTING_NUMBER,
            Manga.CHAPTER_SORTING_UPLOAD_DATE,
            Manga.CHAPTER_SORTING_ALPHABET,
        )) {
            for (direction in listOf(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DESC)) {
                val work = manga.copy(chapterFlags = sort or direction)
                val oldOrder = getChapterSort(work, sortDescending = false)
                val newOrder = novelChapterReadingOrder(work)
                for (first in rows) {
                    for (second in rows) {
                        assertEquals(oldOrder(first, second), newOrder(first, second))
                    }
                }
                assertEquals(items(rows).getNextUnread(work), novelNextUnread(items(rows), work))
            }
        }
    }
}
