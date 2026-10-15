package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * What a queued download is after the app is closed and opened again.
 *
 * The store holds a chapter id and a manga id and nothing else, so the source is looked up again on every start.
 * That lookup used to be cast to `HttpSource`, which meant a novel chapter was written to the store by exactly
 * the same `addAll` as every other chapter and then silently skipped on the way back in: the download appeared to
 * work, the file appeared on disk, and the next launch quietly forgot a queue the user had already waited for.
 *
 * A missing source is still skipped, because a manga whose source is not installed cannot be downloaded by
 * anything — but that is a different case from a source that is installed and is simply not an HTTP one.
 */
class DownloadStoreNovelRestoreTest {

    @Test
    fun `a queued novel download is still queued after a restart`() = runTest {
        val source = novelSource()

        val restored = store(source).restore()

        assertEquals(
            listOf(CHAPTER_ID to source),
            restored.map { it.chapter.id to it.source },
            "a queued novel download was silently dropped on the way back in",
        )
    }

    @Test
    fun `a queued manga download is still queued after a restart`() = runTest {
        val source = imageSource()

        assertEquals(listOf(CHAPTER_ID to source), store(source).restore().map { it.chapter.id to it.source })
    }

    @Test
    fun `a download whose source is not installed is still skipped`() = runTest {
        // Not the same case as "not an HTTP source": here there is genuinely nothing to download with, and
        // widening the type must not turn it into a queued chapter that then fails on the worker.
        assertTrue(store(null).restore().isEmpty(), "a chapter with no installed source was queued")
    }

    @Test
    fun `a novel download written to the store is the one read back`() = runTest {
        // The round trip, because the two halves disagreeing is exactly the bug: `addAll` writes every download
        // without knowing what kind it is, and `restore` used to read only some of them back.
        val source = novelSource()
        val written = mutableMapOf<String, String>()
        val store = store(source, preferences = preferencesHolding("", written))

        store.addAll(listOf(Download(source, manga(), chapter())))
        assertEquals(1, written.size, "the download was never written to the store")

        assertEquals(listOf(CHAPTER_ID), store.restore().map { it.chapter.id })
    }

    private fun store(source: Source?, preferences: SharedPreferences? = null): DownloadStore {
        val sourceManager = mockk<SourceManager>()
        coEvery { sourceManager.get(SOURCE_ID) } returns source
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(MANGA_ID) } returns manga()
        val getChapter = mockk<GetChapter>()
        coEvery { getChapter.await(CHAPTER_ID) } returns chapter()
        val context = mockk<Context>()
        every { context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE) } returns
            (preferences ?: preferencesHolding(SERIALIZED_DOWNLOAD))
        return DownloadStore(
            context = context,
            sourceManager = sourceManager,
            json = Json,
            getManga = getManga,
            getChapter = getChapter,
        )
    }

    /**
     * A preferences mock holding [serialized], and keeping what `edit {}` writes so a round trip can be read back.
     *
     * The seeded value is what `DownloadStore.serialize` writes — a manga id, a chapter id and the queue order —
     * which is the whole of what survives a restart.
     */
    private fun preferencesHolding(
        serialized: String,
        written: MutableMap<String, String> = mutableMapOf(),
    ): SharedPreferences {
        written[CHAPTER_ID.toString()] = serialized
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putString(any(), any()) } answers {
            written[firstArg()] = secondArg()
            null
        }
        every { editor.remove(any()) } answers {
            written.remove(firstArg<String>())
            null
        }
        every { editor.clear() } answers {
            written.clear()
            null
        }
        every { editor.apply() } returns Unit
        every { editor.commit() } returns true
        return mockk<SharedPreferences>().also {
            every { it.all } answers { written.toMap() }
            every { it.edit() } returns editor
        }
    }

    private fun manga() = Manga.create().copy(id = MANGA_ID, title = "La torre de cristal", source = SOURCE_ID)

    private fun chapter() = Chapter.create().copy(id = CHAPTER_ID, mangaId = MANGA_ID, url = "/chapter/1")

    /** A novel source is a plain [Source]. It is not an [HttpSource] and it cannot be one. */
    private fun novelSource(): Source = mockk<Source>().also {
        every { it.name } returns "Novel Web"
        every { it.lang } returns "es"
    }

    private fun imageSource(): HttpSource = mockk<HttpSource>().also {
        every { it.name } returns "Manga Web"
        every { it.lang } returns "es"
    }

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 42L
        const val CHAPTER_ID = 3L

        /** Exactly what `DownloadStore.serialize` writes: the whole of what survives a restart. */
        const val SERIALIZED_DOWNLOAD = """{"mangaId":1,"chapterId":3,"order":0}"""
    }
}
