package eu.kanade.tachiyomi.ui.reader.novel

import app.novel.source.api.Novel
import app.novel.source.demo.DemoSource
import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.novel.NovelSourceAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.novel.NovelMapper
import tachiyomi.domain.chapter.model.Chapter
import app.novel.source.api.Chapter as NovelChapter

/**
 * The routing rule only: a novel chapter has to reach [NovelReaderActivity] and an image chapter has to keep
 * reaching [eu.kanade.tachiyomi.ui.reader.ReaderActivity]. Nothing here touches an Activity, because
 * `openChapter` is a `Context` call and the interesting half is the decision it makes before that.
 */
class ChapterOpenerTest {

    @Test
    fun `a chapter NovelMapper wrote is routed to the novel reader`() {
        val chapter = NovelMapper.toChapter(
            novel = Novel(sourceId = "demo", id = "n1", title = "La torre de cristal"),
            chapter = NovelChapter(sourceId = "demo", novelId = "n1", id = "c1", title = "Capítulo 1", number = 1f),
        )

        assertTrue(ChapterOpener.shouldOpenNovelReader(chapter))
    }

    @Test
    fun `a chapter with an empty memo is an image chapter`() {
        assertFalse(ChapterOpener.shouldOpenNovelReader(Chapter.create()))
    }

    @Test
    fun `a memo carrying only the other novel keys is not enough to claim a chapter`() {
        // `novelId` and `novelChapterUrl` describe *which* novel, not that there is one, and only `novelSourceId`
        // proves the row came through a novel source.
        val chapter = Chapter.create().copy(
            memo = buildJsonObject {
                put("novelId", JsonPrimitive("n1"))
                put("novelChapterUrl", JsonPrimitive("https://example.test/c1"))
            },
        )

        assertFalse(ChapterOpener.shouldOpenNovelReader(chapter))
    }

    @Test
    fun `an unrelated memo is an image chapter`() {
        val chapter = Chapter.create().copy(
            memo = buildJsonObject {
                put("mihon.someExtensionKey", JsonPrimitive("value"))
                put("viewerFlags", JsonPrimitive(3))
            },
        )

        assertFalse(ChapterOpener.shouldOpenNovelReader(chapter))
    }

    @Test
    fun `a novelSourceId that is null or blank does not claim a chapter`() {
        val nullValue = Chapter.create().copy(memo = buildJsonObject { put("novelSourceId", JsonNull) })
        val blankValue = Chapter.create().copy(memo = buildJsonObject { put("novelSourceId", JsonPrimitive("")) })

        assertFalse(ChapterOpener.shouldOpenNovelReader(nullValue))
        assertFalse(ChapterOpener.shouldOpenNovelReader(blankValue))
    }

    @Test
    fun `the marker survives the source round trip the update pipeline performs`() = runBlocking {
        // The routing decision rests on the memo surviving a refresh, so this walks the real path: the adapter
        // fills `SChapter.memo` and `SyncChaptersWithSource` rebuilds the domain chapter from it. If either side
        // stopped writing `novelSourceId`, every refreshed novel chapter would silently fall back to the image
        // reader and only this test would notice.
        val adapter = NovelSourceAdapter(DemoSource())
        val manga = adapter.getPopularManga(1).mangas.first()
        val update = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        val novelChapter = Chapter.create().copyFromSChapter(update.chapters.first())

        assertTrue(ChapterOpener.shouldOpenNovelReader(novelChapter))
    }

    @Test
    fun `an image source chapter keeps going to the image reader`() = runBlocking {
        val imageChapter = Chapter.create().copyFromSChapter(
            SChapter.create().also {
                it.url = "/chapter-1"
                it.name = "Chapter 1"
            },
        )

        assertFalse(ChapterOpener.shouldOpenNovelReader(imageChapter))
    }
}
