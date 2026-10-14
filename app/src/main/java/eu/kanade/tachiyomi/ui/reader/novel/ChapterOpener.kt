package eu.kanade.tachiyomi.ui.reader.novel

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.novel.NovelMapper
import tachiyomi.domain.chapter.model.Chapter

/**
 * The one place that decides whether a chapter opens as prose or as images.
 *
 * Every entry point into a chapter — the manga screen, the library's continue button, history, updates, the
 * "new chapter" notification and a deep link — goes through here, because the decision is only correct once:
 * a novel chapter reaching [ReaderActivity] fails, and [NovelSourceAdapter.getPageList] throws by design rather
 * than because paging is broken. Spreading the check over six call sites is how the two readers drift apart.
 *
 * The decision reads the chapter memo, which [NovelMapper] writes and which survives both the database round
 * trip (`ChapterRepositoryImpl` stores it as `remoteMemo`) and a source refresh (`copyFromSChapter` copies
 * `SChapter.memo`, and [eu.kanade.tachiyomi.source.novel.NovelSourceAdapter] fills that memo with the same
 * keys). So the marker is already on the row and this needs no extra column, no extra table, and no new join.
 * Comparing `manga.source` against the novel source ids would work too, but it costs a database read and it
 * throws that read away at the four call sites that are already holding the chapter.
 */
object ChapterOpener {

    /**
     * Whether [chapter] belongs to a novel source and therefore has to be read as text.
     *
     * Pure and free of Android types, so the routing rule can be tested without a device. `novelSourceId` is
     * the discriminator because [NovelMapper] cannot write a memo for a non-novel chapter, and it is only ever
     * absent when the key is — a null or blank value is treated as absent rather than trusted.
     */
    fun shouldOpenNovelReader(chapter: Chapter): Boolean = !NovelMapper.sourceId(chapter).isNullOrBlank()

    /**
     * The intent [chapter] should be opened with, without starting it.
     *
     * Separate from [openChapter] because a notification cannot start an activity: it has to hand an intent to
     * a `PendingIntent` at post time, long before the row can be read back.
     */
    fun chapterIntent(context: Context, chapter: Chapter): Intent = if (shouldOpenNovelReader(chapter)) {
        NovelReaderActivity.newIntent(context, chapter.id)
    } else {
        ReaderActivity.newIntent(context, chapter.mangaId, chapter.id)
    }

    /** Opens [chapter] in the reader that can actually read it. */
    fun openChapter(context: Context, chapter: Chapter) {
        context.startActivity(chapterIntent(context, chapter))
    }

    /**
     * Opens the chapter a caller only knows by id, for the entry points that hold an id and nothing else —
     * updates and deep links, which get their chapter from a list item or a parsed link.
     *
     * The read is what makes this overload `suspend`, and it is paid only where it cannot be avoided. A row
     * that is gone by the time it is opened still goes to the image reader, because that is where the previous
     * behaviour sent it: a missing novel chapter is a miss worth logging, not a reason to leave the user
     * staring at a screen that never opens.
     */
    suspend fun openChapter(context: Context, mangaId: Long, chapterId: Long) {
        val chapter = context.appGraph.novelChapterRepository.getChapterById(chapterId)
        if (chapter == null) {
            logcat(LogPriority.WARN) { "Chapter $chapterId is gone; falling back to the image reader" }
            context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId))
            return
        }
        openChapter(context, chapter)
    }
}
