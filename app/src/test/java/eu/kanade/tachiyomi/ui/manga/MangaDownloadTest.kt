package eu.kanade.tachiyomi.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What removing a novel from the library has to offer to delete, and what it actually deletes.
 *
 * A novel chapter's download is its text on disk and an image chapter's is pages in the download queue. Each
 * mechanism knows nothing about the other: `DownloadManager` is handed image chapters only, and the content
 * cache is written by the novel reader only. Asking just the queue was therefore a question a novel entry could
 * never answer yes to — the "delete downloads?" prompt never appeared for one, and its cached text stayed in the
 * cache directory with no way to ask for it back.
 *
 * Both halves are resolved into one plan here rather than at each call site, which is what keeps the prompt and
 * the delete from being able to disagree: the same call decides whether there is anything to offer and what
 * offering it will clear.
 *
 * What is not covered here is the plumbing around the decision — the repository that owns the cached text and
 * the files it deletes, which [tachiyomi.data.novel.NovelDataTest] exercises against a real database, and the
 * snackbar itself, which needs a device.
 */
class MangaDownloadTest {

    // A novel: cached text is a download, and only it.

    @Test
    fun `a novel with text cached on disk has downloads`() {
        // The whole of gap 2. Before this the count came from the download queue alone, which is always zero
        // for a novel, so this entry was told it had nothing downloaded while its chapters sat on disk.
        assertEquals(
            MangaDownloadPlan.DeleteNovelContent(setOf(NOVEL_ID, OTHER_NOVEL_ID)),
            planMangaDownloads(imageDownloadCount = 0, cachedNovelChapterIds = setOf(NOVEL_ID, OTHER_NOVEL_ID)),
        )
    }

    @Test
    fun `a single cached chapter is enough`() {
        // Asking "does this manga have anything" is a question about emptiness, not about how much, and a
        // user who cached one chapter out of a hundred has still something to clear.
        assertEquals(
            MangaDownloadPlan.DeleteNovelContent(setOf(NOVEL_ID)),
            planMangaDownloads(imageDownloadCount = 0, cachedNovelChapterIds = setOf(NOVEL_ID)),
        )
    }

    @Test
    fun `a novel whose chapters were never cached has nothing to offer`() {
        assertEquals(
            MangaDownloadPlan.Nothing,
            planMangaDownloads(imageDownloadCount = 0, cachedNovelChapterIds = emptySet()),
        )
    }

    // An image entry: exactly what it answered before novels existed.

    @Test
    fun `an image entry with downloaded pages deletes its pages`() {
        assertEquals(
            MangaDownloadPlan.DeleteImagePages,
            planMangaDownloads(imageDownloadCount = 3, cachedNovelChapterIds = emptySet()),
        )
    }

    @Test
    fun `an image entry with nothing downloaded offers nothing`() {
        assertEquals(
            MangaDownloadPlan.Nothing,
            planMangaDownloads(imageDownloadCount = 0, cachedNovelChapterIds = emptySet()),
        )
    }

    // The two never mix.

    @Test
    fun `a cached novel chapter never turns into an image delete`() {
        // The failure this guards is drift in the other direction: a novel's cached text sent to
        // `DownloadManager.deleteManga`, which looks for page directories that were never created, so the user
        // accepts a delete and the text stays exactly where it was.
        assertFalse(
            planMangaDownloads(imageDownloadCount = 0, cachedNovelChapterIds = setOf(NOVEL_ID))
                is MangaDownloadPlan.DeleteImagePages,
            "a novel's cached text resolved to an image delete",
        )
    }

    @Test
    fun `an image entry is never told it has a novel chapter cached`() {
        // A manga whose chapters are all images writes nothing to the content cache, so its cached set is
        // empty. If it ever were not, the delete would quietly skip the image path that is all it has.
        assertEquals(
            MangaDownloadPlan.DeleteImagePages,
            planMangaDownloads(imageDownloadCount = 1, cachedNovelChapterIds = emptySet()),
        )
    }

    @Test
    fun `the novel half wins if a manga somehow has both`() {
        // Impossible for one entry, whose chapters all come from one source. The tie is broken the same way a
        // mixed chapter selection is: towards the text, which nothing else on the app is holding.
        assertEquals(
            MangaDownloadPlan.DeleteNovelContent(setOf(NOVEL_ID)),
            planMangaDownloads(imageDownloadCount = 2, cachedNovelChapterIds = setOf(NOVEL_ID)),
        )
    }

    // The prompt and the delete are the same decision.

    @Test
    fun `anything but nothing is something to offer`() {
        // What the prompt asks is `plan != Nothing`, and what it then runs is the plan. Walking the grid is
        // what pins the two together: no combination can produce a prompt with nothing behind it, or a
        // delete that was never offered.
        val counts = listOf(0, 1, 7)
        val cachedSets = listOf(emptySet(), setOf(NOVEL_ID), setOf(NOVEL_ID, OTHER_NOVEL_ID))

        counts.forEach { count ->
            cachedSets.forEach { cached ->
                val plan = planMangaDownloads(imageDownloadCount = count, cachedNovelChapterIds = cached)
                val somethingToOffer = plan != MangaDownloadPlan.Nothing
                assertEquals(
                    count > 0 || cached.isNotEmpty(),
                    somethingToOffer,
                    "a plan of $plan disagreed with a count of $count and $cached cached",
                )
            }
        }
    }

    @Test
    fun `a novel's delete clears every cached chapter and no others`() {
        val plan = planMangaDownloads(imageDownloadCount = 0, cachedNovelChapterIds = setOf(NOVEL_ID, OTHER_NOVEL_ID))

        val cleared = (plan as MangaDownloadPlan.DeleteNovelContent).chapterIds
        assertEquals(setOf(NOVEL_ID, OTHER_NOVEL_ID), cleared)
        assertTrue(cleared.isNotEmpty(), "the prompt would appear with nothing to clear behind it")
    }

    private companion object {
        const val NOVEL_ID = 10L
        const val OTHER_NOVEL_ID = 11L
    }
}
