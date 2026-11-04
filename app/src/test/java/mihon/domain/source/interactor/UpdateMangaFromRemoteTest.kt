package mihon.domain.source.interactor

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaRemoteUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Instant

class UpdateMangaFromRemoteTest {
    @Test
    fun `cover refresh preserves library identity and chapter progress`() = runBlocking {
        val cover = "https://img.jabrascan.net/IBecameMarriedMan/IBecameMarriedMan01-225w.webp"
        val manga = Manga.create().copy(
            id = 42, source = 123, url = "IBecameMarriedMan", title = "A novel", author = "Author",
            thumbnailUrl = "IBecameMarriedMan/IBecameMarriedMan01.webp",
            favoriteAt = Instant.fromEpochMilliseconds(1000), fetchInterval = 7, initialized = true,
        )
        val chapter = Chapter.create().copy(
            id = 99,
            mangaId = manga.id,
            url = "chapter.pdf",
            name = "Chapter 1",
            chapterNumber = 1.0,
            read = true,
            bookmark = true,
            lastPageRead = 12,
        )
        val chapters = mockk<ChapterRepository>()
        val repository = mockk<MangaRepository>()
        val cache = mockk<CoverCache>()
        val preferences = mockk<LibraryPreferences>()
        val downloads = mockk<DownloadManager>()
        val source = mockk<Source>()
        val update = slot<MangaRemoteUpdate>()
        val remote = manga.toSManga().apply { thumbnail_url = cover }
        val updated = manga.copy(thumbnailUrl = cover)

        every { source.id } returns manga.source
        every { preferences.updateMangaTitles.get() } returns false
        every { cache.hasCustomCover(manga.id) } returns false
        every { cache.deleteFromCache(manga, false) } returns 1
        coEvery { chapters.getChapterByMangaId(manga.id, false) } returns listOf(chapter)
        coEvery { source.getMangaUpdate(any(), any(), true, true) } returns
            SMangaUpdate(remote, listOf(chapter.toSChapter()))
        coEvery { repository.updateRemote(capture(update)) } returns true
        coEvery { repository.getMangaById(manga.id) } returns updated

        val sync = SyncChaptersWithSource(
            downloads,
            mockk<DownloadProvider>(),
            chapters,
            ShouldUpdateDbChapter(),
            mockk<UpdateManga>(),
            mockk<GetExcludedScanlators>(),
            preferences,
        )
        val useCase =
            UpdateMangaFromRemote(mockk<SourceManager>(), chapters, repository, sync, cache, preferences, downloads)

        val result = useCase(source, manga, fetchDetails = true, fetchChapters = true).getOrThrow()

        assertEquals(updated, result.manga)
        assertEquals(manga.id, result.manga.id)
        assertEquals(manga.source, result.manga.source)
        assertEquals(manga.url, result.manga.url)
        assertEquals(manga.favoriteAt, result.manga.favoriteAt)
        assertTrue(result.manga.favorite)
        assertTrue(result.newChapters.isEmpty())
        assertNotNull(update.captured.coverLastModified)
        assertEquals(
            MangaRemoteUpdate(
                id = manga.id, title = null, author = manga.author, artist = manga.artist,
                description = manga.description, genre = manga.genre, status = manga.status,
                thumbnailUrl = cover, updateStrategy = manga.updateStrategy, memo = manga.memo,
                initialized = true, coverLastModified = null,
            ),
            update.captured.copy(coverLastModified = null),
        )

        coVerify(exactly = 1) {
            source.getMangaUpdate(
                match { it.url == manga.url && it.thumbnail_url == manga.thumbnailUrl && it.title == manga.title },
                match { it.size == 1 && it.single().url == chapter.url },
                true,
                true,
            )
        }
        coVerify(exactly = 2) { chapters.getChapterByMangaId(manga.id, false) }
        confirmVerified(chapters) // No chapter inserts, removals or progress writes.
        coVerify(exactly = 1) { repository.updateRemote(any()) }
        coVerify(exactly = 1) { repository.getMangaById(manga.id) }
        confirmVerified(repository)
        verify(exactly = 1) { cache.hasCustomCover(manga.id) }
        verify(exactly = 1) { cache.deleteFromCache(manga, false) }
        confirmVerified(cache)
        confirmVerified(downloads)
    }
}
