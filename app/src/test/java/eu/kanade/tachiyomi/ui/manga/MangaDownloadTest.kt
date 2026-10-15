package eu.kanade.tachiyomi.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What removing a novel from the library has to offer to delete, and what it actually deletes.
 *
 * There is one mechanism now. A novel chapter's download is a text file in the same download folder a manga's
 * pages are in, indexed by the same code and removed by the same call, so asking the download manager alone is the
 * whole question. The version that also asked the content cache existed because each mechanism could hold
 * something the other could not see — which is exactly how a novel entry came to be told it had nothing
 * downloaded while its chapters sat on disk, and how a delete could take one half and leave the other.
 *
 * What is not covered here is the plumbing around the decision — the folder and its files, the repository that
 * owns the cached text, and the snackbar itself, which needs a device.
 */
class MangaDownloadTest {

    @Test
    fun `a novel with its text downloaded offers to delete its downloads`() {
        // The counter-test to the bug this phase fixed: the download count used to come from the image queue
        // alone, which is always zero for a novel, so this entry was told it had nothing while its chapters sat
        // on disk with no way to ask for them back.
        assertEquals(
            MangaDownloadPlan.DeleteImagePages,
            planMangaDownloads(imageDownloadCount = 1),
        )
    }

    @Test
    fun `a novel with nothing downloaded has nothing to offer`() {
        assertEquals(MangaDownloadPlan.Nothing, planMangaDownloads(imageDownloadCount = 0))
    }

    @Test
    fun `one downloaded chapter of many is enough to offer something`() {
        // Asking "does this manga have anything" is a question about emptiness, not about how much, and a user
        // who downloaded one chapter out of a hundred has still something to clear.
        assertEquals(MangaDownloadPlan.DeleteImagePages, planMangaDownloads(imageDownloadCount = 1))
    }

    @Test
    fun `an image entry with downloaded pages still deletes them`() {
        assertEquals(MangaDownloadPlan.DeleteImagePages, planMangaDownloads(imageDownloadCount = 3))
    }

    // The prompt and the delete are the same decision.

    @Test
    fun `anything but nothing is something to offer`() {
        // What the prompt asks is `plan != Nothing`, and what it then runs is the plan. Walking the counts is what
        // pins the two together: no number can produce a prompt with nothing behind it, or a delete that was
        // never offered.
        listOf(0, 1, 7).forEach { count ->
            val plan = planMangaDownloads(imageDownloadCount = count)
            assertEquals(
                count > 0,
                plan != MangaDownloadPlan.Nothing,
                "a plan of $plan disagreed with a count of $count",
            )
        }
    }
}
