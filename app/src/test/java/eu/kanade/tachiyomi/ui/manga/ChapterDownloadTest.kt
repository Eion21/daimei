package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.novel.ChapterOpener
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.novel.NovelMapper
import tachiyomi.domain.chapter.model.Chapter
import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.Novel as NovelEntry

/**
 * What "download" means now that a chapter can be prose.
 *
 * A novel chapter's download is a `ChapterContent` on disk and an image chapter's is a page in the download
 * queue, and the whole point of this phase is that the two never get mixed up: a novel chapter reaching the
 * image manager asks `NovelSourceAdapter.getPageList` for pages that throw by design, and an image chapter
 * reading its state from the content cache would show a download that was never made.
 *
 * Everything below is the decision half of that, which is pure and so testable without a device. What is not
 * covered here is the plumbing around it — the fetch itself, the file on disk, and the indicator that renders
 * the state.
 */
class ChapterDownloadTest {

    // State: what a row shows.

    @Test
    fun `a novel chapter with content on disk is downloaded`() {
        assertEquals(
            Download.State.DOWNLOADED,
            novelState(isContentCached = true),
        )
    }

    @Test
    fun `a novel chapter with nothing on disk is not downloaded`() {
        assertEquals(
            Download.State.NOT_DOWNLOADED,
            novelState(isContentCached = false),
        )
    }

    @Test
    fun `a novel chapter being cached says so instead of sitting still`() {
        // Otherwise the tap does nothing visible for the length of a network fetch, which is the same silent
        // no-op this phase exists to remove.
        assertEquals(
            Download.State.DOWNLOADING,
            novelState(isContentCached = false, isNovelDownloading = true),
        )
    }

    @Test
    fun `a novel chapter already on disk stays downloaded while a refetch runs`() {
        // What the row promises is that the content opens with no network, which a running refetch cannot take
        // away. Reporting DOWNLOADING here would make a readable chapter look unavailable.
        assertEquals(
            Download.State.DOWNLOADED,
            novelState(isContentCached = true, isNovelDownloading = true),
        )
    }

    // State: an image chapter is unaffected by anything the novel side knows.

    @Test
    fun `an image chapter keeps reading its state from the queue`() {
        assertEquals(Download.State.QUEUE, imageState(queued = Download.State.QUEUE))
        assertEquals(Download.State.DOWNLOADING, imageState(queued = Download.State.DOWNLOADING))
        assertEquals(Download.State.ERROR, imageState(queued = Download.State.ERROR))
    }

    @Test
    fun `an image chapter still reports a download the queue no longer knows about`() {
        // The pages are already on disk, so the row says downloaded with nothing queued: a download that
        // finished and left the queue.
        assertEquals(
            Download.State.DOWNLOADED,
            imageState(queued = null, isImageDownloaded = true),
        )
    }

    @Test
    fun `an image chapter is not downloaded just because some novel chapter is cached`() {
        // The regression this guards is drift in one direction only: if the cached-content set leaked into the
        // image branch, every image chapter of every manga would start reading as downloaded the first time any
        // novel chapter anywhere was cached.
        assertEquals(
            Download.State.NOT_DOWNLOADED,
            imageState(queued = null, isContentCached = true),
        )
    }

    @Test
    fun `an image chapter is not downloading just because some novel chapter is`() {
        assertEquals(
            Download.State.NOT_DOWNLOADED,
            imageState(queued = null, isNovelDownloading = true),
        )
    }

    // State: the image queue's override cannot reach a novel row.

    @Test
    fun `a transient image state for a chapter the queue no longer holds is dropped`() {
        val dropped = queuedDownloadStatesFor(
            states = mapOf(NOVEL_ID to Download.State.DOWNLOADING),
            queue = emptyList(),
        )

        assertTrue(dropped.isEmpty(), "a state the queue does not hold survived and would override the row's own")
    }

    @Test
    fun `a transient image state for a chapter still queued is kept`() {
        val kept = queuedDownloadStatesFor(
            states = mapOf(IMAGE_ID to Download.State.DOWNLOADING),
            queue = listOf(downloadFor(IMAGE_ID)),
        )

        assertEquals(mapOf(IMAGE_ID to Download.State.DOWNLOADING), kept)
    }

    @Test
    fun `a novel row keeps its own state when the queue has nothing to say`() {
        val item = ChapterList.Item(novelChapter(NOVEL_ID), Download.State.DOWNLOADING)

        assertSame(item, withQueuedDownloadState(item, queuedDownloadState = null, selected = false))
    }

    @Test
    fun `the queue's state still overrides an image row, and selection still lands`() {
        val item = ChapterList.Item(imageChapter(IMAGE_ID), Download.State.NOT_DOWNLOADED)

        val queued = withQueuedDownloadState(item, queuedDownloadState = Download.State.QUEUE, selected = false)
        assertEquals(Download.State.QUEUE, queued.downloadState)
        assertFalse(queued.selected)

        val selected = withQueuedDownloadState(item, queuedDownloadState = null, selected = true)
        assertEquals(Download.State.NOT_DOWNLOADED, selected.downloadState)
        assertTrue(selected.selected)
    }

    // The dispatcher: a novel chapter never reaches the image queue.

    @Test
    fun `downloading a novel chapter caches its content instead of queueing pages`() {
        assertEquals(
            ChapterDownloadPlan.CacheNovelContent(listOf(NOVEL_ID)),
            planChapterDownloadAction(listOf(novelItem(NOVEL_ID)), ChapterDownloadAction.START),
        )
    }

    @Test
    fun `downloading a novel chapter now is the same call as downloading it`() {
        // There is no queue to jump to the front of, so START_NOW is not a distinct operation for a novel.
        assertEquals(
            ChapterDownloadPlan.CacheNovelContent(listOf(NOVEL_ID)),
            planChapterDownloadAction(listOf(novelItem(NOVEL_ID)), ChapterDownloadAction.START_NOW),
        )
    }

    @Test
    fun `cancelling a novel download cancels the fetch rather than the queue`() {
        assertEquals(
            ChapterDownloadPlan.CancelNovelContent(listOf(NOVEL_ID)),
            planChapterDownloadAction(listOf(novelItem(NOVEL_ID)), ChapterDownloadAction.CANCEL),
        )
    }

    @Test
    fun `deleting a novel download drops the cached content`() {
        assertEquals(
            ChapterDownloadPlan.DeleteNovelContent(listOf(NOVEL_ID)),
            planChapterDownloadAction(listOf(novelItem(NOVEL_ID)), ChapterDownloadAction.DELETE),
        )
    }

    @Test
    fun `no action on a novel chapter produces a plan that can reach the download manager`() {
        // This is the test that fails loudly if a novel chapter ever starts queueing an image download: all four
        // actions resolve to a novel plan, and none of those plans carries chapters.
        val plans = ChapterDownloadAction.values().map { planChapterDownloadAction(listOf(novelItem(NOVEL_ID)), it) }

        assertTrue(
            plans.all {
                it is ChapterDownloadPlan.CacheNovelContent ||
                    it is ChapterDownloadPlan.CancelNovelContent ||
                    it is ChapterDownloadPlan.DeleteNovelContent
            },
            "a novel chapter produced $plans",
        )
    }

    @Test
    fun `a multi chapter novel download caches every one of them`() {
        assertEquals(
            ChapterDownloadPlan.CacheNovelContent(listOf(NOVEL_ID, OTHER_NOVEL_ID)),
            planChapterDownloadAction(
                listOf(novelItem(NOVEL_ID), novelItem(OTHER_NOVEL_ID)),
                ChapterDownloadAction.START,
            ),
        )
    }

    // The dispatcher: an image chapter keeps every behaviour it had.

    @Test
    fun `an image chapter still queues its pages`() {
        assertEquals(
            ChapterDownloadPlan.QueueImagePages(listOf(imageChapter(IMAGE_ID)), restartFailedDownloads = false),
            planChapterDownloadAction(
                listOf(imageItem(IMAGE_ID, Download.State.NOT_DOWNLOADED)),
                ChapterDownloadAction.START,
            ),
        )
    }

    @Test
    fun `a failed image download still asks the queue to start again`() {
        assertEquals(
            ChapterDownloadPlan.QueueImagePages(
                listOf(imageChapter(IMAGE_ID), imageChapter(OTHER_IMAGE_ID)),
                restartFailedDownloads = true,
            ),
            planChapterDownloadAction(
                listOf(
                    imageItem(IMAGE_ID, Download.State.ERROR),
                    imageItem(OTHER_IMAGE_ID, Download.State.NOT_DOWNLOADED),
                ),
                ChapterDownloadAction.START,
            ),
        )
        assertEquals(
            ChapterDownloadPlan.QueueImagePages(listOf(imageChapter(IMAGE_ID)), restartFailedDownloads = false),
            planChapterDownloadAction(
                listOf(imageItem(IMAGE_ID, Download.State.NOT_DOWNLOADED)),
                ChapterDownloadAction.START,
            ),
        )
    }

    @Test
    fun `an image chapter still starts now, cancels and deletes through the queue`() {
        val item = imageItem(IMAGE_ID, Download.State.QUEUE)

        assertEquals(
            ChapterDownloadPlan.StartImagePagesNow(listOf(imageChapter(IMAGE_ID))),
            planChapterDownloadAction(listOf(item), ChapterDownloadAction.START_NOW),
        )
        assertEquals(
            ChapterDownloadPlan.CancelImageDownload(IMAGE_ID),
            planChapterDownloadAction(listOf(item), ChapterDownloadAction.CANCEL),
        )
        assertEquals(
            ChapterDownloadPlan.DeleteImagePages(listOf(imageChapter(IMAGE_ID))),
            planChapterDownloadAction(listOf(item), ChapterDownloadAction.DELETE),
        )
    }

    @Test
    fun `the guards the image path already had still apply`() {
        // START_NOW and CANCEL act on one chapter. A wider selection resolves to nothing rather than quietly
        // taking the first row, which is what the old `singleOrNull() ?: return` did.
        val twoItems = listOf(
            imageItem(IMAGE_ID, Download.State.QUEUE),
            imageItem(OTHER_IMAGE_ID, Download.State.QUEUE),
        )

        assertEquals(ChapterDownloadPlan.Nothing, planChapterDownloadAction(twoItems, ChapterDownloadAction.START_NOW))
        assertEquals(ChapterDownloadPlan.Nothing, planChapterDownloadAction(twoItems, ChapterDownloadAction.CANCEL))
        assertEquals(ChapterDownloadPlan.Nothing, planChapterDownloadAction(emptyList(), ChapterDownloadAction.START))
        assertEquals(ChapterDownloadPlan.Nothing, planChapterDownloadAction(emptyList(), ChapterDownloadAction.DELETE))
    }

    // Bulk: the split every bulk action goes through.

    @Test
    fun `a bulk download splits novel chapters away from image ones`() {
        val novel = novelChapter(NOVEL_ID)
        val image = imageChapter(IMAGE_ID)
        val otherNovel = novelChapter(OTHER_NOVEL_ID)

        val (novelChapters, imageChapters) = listOf(novel, image, otherNovel).splitByDownloadMechanism()

        assertEquals(listOf(novel, otherNovel), novelChapters)
        assertEquals(listOf(image), imageChapters)
    }

    @Test
    fun `a bulk download of one kind leaves the other half empty`() {
        val (novelChapters, fromNovels) = listOf(novelChapter(NOVEL_ID), novelChapter(OTHER_NOVEL_ID))
            .splitByDownloadMechanism()
        assertEquals(2, novelChapters.size)
        assertTrue(fromNovels.isEmpty())

        val (fromImages, imageChapters) = listOf(imageChapter(IMAGE_ID)).splitByDownloadMechanism()
        assertTrue(fromImages.isEmpty())
        assertEquals(1, imageChapters.size)
    }

    @Test
    fun `a novel chapter and an image chapter are told apart the same way the reader routes them`() {
        // One rule, asked once: if these two ever disagreed, a row would show a download state for a kind of
        // chapter the action then refuses to download.
        val novel = novelChapter(NOVEL_ID)
        val image = imageChapter(IMAGE_ID)

        assertTrue(ChapterOpener.shouldOpenNovelReader(novel))
        assertFalse(ChapterOpener.shouldOpenNovelReader(image))
        assertEquals(listOf(novel), listOf(novel, image).splitByDownloadMechanism().first)
    }

    // Helpers.

    private fun novelState(
        isContentCached: Boolean,
        isNovelDownloading: Boolean = false,
    ) = chapterDownloadState(
        isNovel = true,
        isContentCached = isContentCached,
        isNovelDownloading = isNovelDownloading,
        queuedDownloadState = null,
        isImageDownloaded = false,
    )

    private fun imageState(
        queued: Download.State?,
        isImageDownloaded: Boolean = false,
        isContentCached: Boolean = false,
        isNovelDownloading: Boolean = false,
    ) = chapterDownloadState(
        isNovel = false,
        isContentCached = isContentCached,
        isNovelDownloading = isNovelDownloading,
        queuedDownloadState = queued,
        isImageDownloaded = isImageDownloaded,
    )

    /** A chapter carrying the memo `NovelMapper` writes, which is what makes it a novel chapter. */
    private fun novelChapter(id: Long): Chapter = NovelMapper.toChapter(
        novel = NovelEntry(sourceId = "demo", id = "n1", title = "La torre de cristal"),
        chapter = NovelChapter(sourceId = "demo", novelId = "n1", id = "c$id", title = "Capítulo $id", number = 1f),
    ).copy(id = id, mangaId = MANGA_ID)

    private fun imageChapter(id: Long): Chapter =
        Chapter.create().copy(id = id, mangaId = MANGA_ID, url = "/chapter-$id")

    private fun novelItem(id: Long) = ChapterList.Item(novelChapter(id), Download.State.NOT_DOWNLOADED)

    private fun imageItem(id: Long, state: Download.State) = ChapterList.Item(imageChapter(id), state)

    /**
     * A queue entry standing in for [id].
     *
     * `Download` carries a source and a manga that nothing here reads, so this is a stub rather than a
     * construction: the queue filter only ever asks `chapter.id`.
     */
    private fun downloadFor(id: Long): Download = mockk {
        every { chapter } returns Chapter.create().copy(id = id)
    }

    private companion object {
        const val MANGA_ID = 1L
        const val NOVEL_ID = 10L
        const val OTHER_NOVEL_ID = 11L
        const val IMAGE_ID = 20L
        const val OTHER_IMAGE_ID = 21L
    }
}
