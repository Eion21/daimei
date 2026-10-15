package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
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
 * What "download" means now that a chapter can be prose, and what the button has to do about it.
 *
 * A novel chapter's download is one text file and an image chapter's is a folder of images or a CBZ, and the
 * whole point of this phase is that the user cannot tell the difference: both are queued, both are reported, both
 * are cancelled and both are deleted by the same code. `Downloader` is what knows a chapter of prose has no pages,
 * so the dispatcher in front of it has nothing to branch on.
 *
 * Everything below is the decision half, which is pure and so testable without a device. What is not covered here
 * is the plumbing around it — the queue, the file on disk, and the indicator that renders the state.
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
    fun `a novel chapter in the queue shows what the queue is doing`() {
        // A novel chapter is queued like any other, so the tap is visible from the first frame instead of only
        // after the fetch lands. This is the state the retired novel-only in-flight set used to produce.
        assertEquals(Download.State.QUEUE, novelState(isContentCached = false, queued = Download.State.QUEUE))
        assertEquals(
            Download.State.DOWNLOADING,
            novelState(isContentCached = false, queued = Download.State.DOWNLOADING),
        )
        assertEquals(Download.State.ERROR, novelState(isContentCached = false, queued = Download.State.ERROR))
    }

    @Test
    fun `a novel chapter already on disk stays downloaded while the queue has nothing to say`() {
        // What the row promises is that the content opens with no network. A finished download has left the queue
        // and can never report itself again, so a null here is silence rather than an answer.
        assertEquals(
            Download.State.DOWNLOADED,
            novelState(isContentCached = true, queued = null),
        )
    }

    // State: a queued chapter outranks what is already on disk, for both kinds.

    @Test
    fun `a chapter already on disk reports the queue while the queue is working on it`() {
        // The same rule for both kinds, which is the point of having one rule: a re-download is visibly in
        // progress rather than silently hidden behind a finished download's state.
        assertEquals(Download.State.QUEUE, novelState(isContentCached = true, queued = Download.State.QUEUE))
        assertEquals(
            Download.State.DOWNLOADING,
            imageState(queued = Download.State.DOWNLOADING, isImageDownloaded = true),
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

    // State: the queue's own answer cannot outlive it.

    @Test
    fun `a state for a chapter the queue no longer holds is dropped`() {
        val dropped = queuedDownloadStatesFor(
            states = mapOf(NOVEL_ID to Download.State.DOWNLOADING),
            queue = emptyList(),
        )

        assertTrue(dropped.isEmpty(), "a state the queue does not hold survived and would override the row's own")
    }

    @Test
    fun `a state for a chapter still queued is kept`() {
        val kept = queuedDownloadStatesFor(
            states = mapOf(IMAGE_ID to Download.State.DOWNLOADING),
            queue = listOf(downloadFor(IMAGE_ID)),
        )

        assertEquals(mapOf(IMAGE_ID to Download.State.DOWNLOADING), kept)
    }

    @Test
    fun `a row keeps its own state when the queue has nothing to say`() {
        val item = ChapterList.Item(novelChapter(NOVEL_ID), Download.State.DOWNLOADING)

        assertSame(item, withQueuedDownloadState(item, queuedDownloadState = null, selected = false))
    }

    @Test
    fun `the queue's state still overrides a row, and selection still lands`() {
        val item = ChapterList.Item(imageChapter(IMAGE_ID), Download.State.NOT_DOWNLOADED)

        val queued = withQueuedDownloadState(item, queuedDownloadState = Download.State.QUEUE, selected = false)
        assertEquals(Download.State.QUEUE, queued.downloadState)
        assertFalse(queued.selected)

        val selected = withQueuedDownloadState(item, queuedDownloadState = null, selected = true)
        assertEquals(Download.State.NOT_DOWNLOADED, selected.downloadState)
        assertTrue(selected.selected)
    }

    // The dispatcher: a novel chapter is queued like any other.

    @Test
    fun `a novel chapter is queued like any other`() {
        assertEquals(
            ChapterDownloadPlan.QueueImagePages(listOf(novelChapter(NOVEL_ID)), restartFailedDownloads = false),
            planChapterDownloadAction(listOf(novelItem(NOVEL_ID)), ChapterDownloadAction.START),
        )
    }

    @Test
    fun `a novel chapter downloaded now goes to the front of the queue`() {
        assertEquals(
            ChapterDownloadPlan.StartImagePagesNow(listOf(novelChapter(NOVEL_ID))),
            planChapterDownloadAction(listOf(novelItem(NOVEL_ID)), ChapterDownloadAction.START_NOW),
        )
    }

    @Test
    fun `cancelling a novel download takes it back out of the queue`() {
        assertEquals(
            ChapterDownloadPlan.CancelImageDownload(NOVEL_ID),
            planChapterDownloadAction(listOf(novelItem(NOVEL_ID)), ChapterDownloadAction.CANCEL),
        )
    }

    @Test
    fun `deleting a novel download goes through the download manager`() {
        assertEquals(
            ChapterDownloadPlan.DeleteImagePages(listOf(novelChapter(NOVEL_ID))),
            planChapterDownloadAction(listOf(novelItem(NOVEL_ID)), ChapterDownloadAction.DELETE),
        )
    }

    @Test
    fun `every action on a novel chapter reaches the download manager`() {
        // The test that fails loudly if a novel chapter ever starts resolving to a plan of its own: all four
        // actions resolve to a queue plan, and every one of those plans carries chapters.
        val plans = ChapterDownloadAction.values().map { planChapterDownloadAction(listOf(novelItem(NOVEL_ID)), it) }

        assertTrue(
            plans.all {
                it is ChapterDownloadPlan.QueueImagePages ||
                    it is ChapterDownloadPlan.StartImagePagesNow ||
                    it is ChapterDownloadPlan.CancelImageDownload ||
                    it is ChapterDownloadPlan.DeleteImagePages
            },
            "a novel chapter produced $plans",
        )
    }

    @Test
    fun `a multi chapter novel download queues every one of them`() {
        assertEquals(
            ChapterDownloadPlan.QueueImagePages(
                listOf(novelChapter(NOVEL_ID), novelChapter(OTHER_NOVEL_ID)),
                restartFailedDownloads = false,
            ),
            planChapterDownloadAction(
                listOf(novelItem(NOVEL_ID), novelItem(OTHER_NOVEL_ID)),
                ChapterDownloadAction.START,
            ),
        )
    }

    @Test
    fun `a selection of both kinds is queued whole rather than split`() {
        // The split this replaces is what made the manga screen behave differently from the library, the updates
        // tab and auto-download, all of which always went through the manager.
        val items = listOf(novelItem(NOVEL_ID), imageItem(IMAGE_ID, Download.State.NOT_DOWNLOADED))

        assertEquals(
            ChapterDownloadPlan.QueueImagePages(
                listOf(novelChapter(NOVEL_ID), imageChapter(IMAGE_ID)),
                restartFailedDownloads = false,
            ),
            planChapterDownloadAction(items, ChapterDownloadAction.START),
        )
    }

    @Test
    fun `a selection of both kinds is deleted by one call, not one call per kind`() {
        // The same shape as the download, for the same reason: two deletes meant the prompt could clear one half
        // of what it offered and leave the other behind on disk.
        val items = listOf(novelItem(NOVEL_ID), imageItem(IMAGE_ID, Download.State.DOWNLOADED))

        assertEquals(
            ChapterDownloadPlan.DeleteImagePages(listOf(novelChapter(NOVEL_ID), imageChapter(IMAGE_ID))),
            planChapterDownloadAction(items, ChapterDownloadAction.DELETE),
        )
    }

    @Test
    fun `a novel chapter deleted is the one the prompt claimed was downloaded`() {
        // "The same truth" means the chapters the plan hands the delete are the chapters the row said were
        // downloaded, so it cannot offer to clear a chapter that was never downloaded or skip one that was.
        val downloaded = novelItem(NOVEL_ID).copy(downloadState = Download.State.DOWNLOADED)

        val plan = planChapterDownloadAction(listOf(downloaded), ChapterDownloadAction.DELETE)

        assertEquals(
            listOf(NOVEL_ID),
            (plan as ChapterDownloadPlan.DeleteImagePages).chapters.map { it.id },
            "the delete was offered a different chapter than the one the prompt resolved",
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
                chapters = listOf(imageChapter(IMAGE_ID), imageChapter(OTHER_IMAGE_ID)),
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
        val twoNovelItems = listOf(novelItem(NOVEL_ID), novelItem(OTHER_NOVEL_ID))

        assertEquals(ChapterDownloadPlan.Nothing, planChapterDownloadAction(twoItems, ChapterDownloadAction.START_NOW))
        assertEquals(ChapterDownloadPlan.Nothing, planChapterDownloadAction(twoItems, ChapterDownloadAction.CANCEL))
        assertEquals(ChapterDownloadPlan.Nothing, planChapterDownloadAction(emptyList(), ChapterDownloadAction.START))
        assertEquals(ChapterDownloadPlan.Nothing, planChapterDownloadAction(emptyList(), ChapterDownloadAction.DELETE))
        // The guard is the queue's, so it holds for a novel selection exactly as it does for an image one
        assertEquals(
            ChapterDownloadPlan.Nothing,
            planChapterDownloadAction(twoNovelItems, ChapterDownloadAction.START_NOW),
        )
        assertEquals(
            ChapterDownloadPlan.Nothing,
            planChapterDownloadAction(twoNovelItems, ChapterDownloadAction.CANCEL),
        )
    }

    // Helpers.

    private fun novelState(
        isContentCached: Boolean,
        queued: Download.State? = null,
    ) = chapterDownloadState(
        isNovel = true,
        isContentCached = isContentCached,
        queuedDownloadState = queued,
        isImageDownloaded = false,
    )

    private fun imageState(
        queued: Download.State?,
        isImageDownloaded: Boolean = false,
        isContentCached: Boolean = false,
    ) = chapterDownloadState(
        isNovel = false,
        isContentCached = isContentCached,
        queuedDownloadState = queued,
        isImageDownloaded = isImageDownloaded,
    )

    /**
     * A chapter carrying the memo `NovelMapper` writes, which is what makes it a novel chapter.
     *
     * `dateFetch` is pinned because `Chapter` is a data class and the plans now carry whole chapters rather than
     * ids: two calls a few microseconds apart would otherwise build two unequal chapters and every plan
     * comparison would fail on a timestamp nobody is testing.
     */
    private fun novelChapter(id: Long): Chapter = NovelMapper.toChapter(
        novel = NovelEntry(sourceId = "demo", id = "n1", title = "La torre de cristal"),
        chapter = NovelChapter(sourceId = "demo", novelId = "n1", id = "c$id", title = "Capítulo $id", number = 1f),
    ).copy(id = id, mangaId = MANGA_ID, dateFetch = FIXED_DATE_FETCH)

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

        val FIXED_DATE_FETCH = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000)
    }
}
