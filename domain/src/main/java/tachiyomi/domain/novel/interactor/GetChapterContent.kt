package tachiyomi.domain.novel.interactor

import app.novel.runtime.SourceRegistry
import app.novel.source.api.ChapterContent
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.novel.repository.ChapterContentRepository
import app.novel.source.api.Chapter as NovelChapter

/**
 * The content of a novel chapter: from disk when it is there, from the source when it is not.
 *
 * Disk first, then the source, then disk again — the last step is what makes the second read free. A chapter that
 * nobody has ever cached resolves through the source, so the reader has something to show for a chapter that was
 * only ever listed and never opened.
 *
 * Which source to ask is recovered from the row, not remembered by the caller: the chapter memo carries the source
 * and novel ids that `tachiyomi.data.novel.NovelMapper` wrote when the chapter was listed, and the manga row is
 * only read when that memo cannot answer. Nothing about a novel source reaches the reader, which is what lets this
 * live in `:domain` at all.
 *
 * Every failure is a null plus a log naming the step that failed. A chapter that cannot be fetched is a recoverable
 * miss, and throwing here would take the reader down with it. A chapter that comes back with no content at all is
 * treated as one of those failures and is never written to the content cache.
 */
@Inject
class GetChapterContent(
    private val repository: ChapterContentRepository,
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: MangaRepository,
    private val sourceRegistry: SourceRegistry,
) {

    suspend fun await(chapterId: Long): ChapterContent? {
        // An empty cached answer is not content, it is a failed fetch that was stored before `fetch` learned to
        // refuse one. Answering from it would make the chapter permanently unreadable, with no retry able to help,
        // because the cache would be asked before the source every single time. Those entries are already on real
        // devices, so evicting them here is the only way the fix reaches a chapter that was already poisoned.
        val cached = repository.get(chapterId)
        if (cached != null && cached.blocks.isNotEmpty()) return cached
        if (cached != null) repository.delete(chapterId)

        return fetch(chapterId)
    }

    private suspend fun fetch(chapterId: Long): ChapterContent? {
        val chapter = chapterRepository.getChapterById(chapterId)
            ?: return miss(chapterId, "there is no chapter row to fetch from")

        val sourceId = chapter.memo.stringOrNull(KEY_SOURCE_ID)
        val novelId = chapter.memo.stringOrNull(KEY_NOVEL_ID)
        // Read only when the memo cannot answer on its own, which is the whole reason the memo is written.
        val manga = if (sourceId != null && novelId != null) null else mangaOrNull(chapter.mangaId)

        val source = if (sourceId != null) {
            // The memo is authoritative even when it names a source this build does not ship: falling back to the
            // manga row would fetch the chapter from a source other than the one that listed it.
            sourceRegistry.get(sourceId) ?: return miss(
                chapterId,
                "its memo names novel source '$sourceId', which is not registered",
            )
        } else {
            manga?.let { fromMangaRow(it) }
                ?: return miss(chapterId, "it carries no novel memo and its manga row resolves to no source")
        }

        val novelEntryId = novelId ?: manga?.url
            ?: return miss(chapterId, "the novel it belongs to is not recoverable from the memo or the manga")

        val content = try {
            source.getChapterContent(chapter.toNovelChapter(source.info.id, novelEntryId))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) {
                "[novel] Source ${source.info.id} threw while fetching chapter $chapterId"
            }
            return null
        }

        // Nothing to read is not a chapter worth keeping. An empty chapter stored here answers from the content
        // cache before the source is ever asked again, so one fetch that failed while offline — or a source that
        // returned a page nobody could parse — would poison the chapter permanently: no later attempt could reach
        // the source to replace it. Correcting the source alone would not be enough, because any other source can
        // still answer empty, and this layer is the one that decides what is stored.
        if (content.blocks.isEmpty()) {
            return miss(chapterId, "the source answered it with no readable content")
        }

        repository.save(chapterId, content)
        return content
    }

    /** The source a manga row was listed from, when it was a novel source at all. */
    private fun fromMangaRow(manga: Manga): app.novel.source.api.NovelSource? =
        sourceRegistry.getBySourceId(manga.source)

    /**
     * Rebuilds the chapter handle the source expects.
     *
     * The domain row already holds everything the contract asks for: `url` is the remote chapter id the source
     * minted, `name` its title, and the memo the source-specific url. Only the numeric chapter number needs
     * translating, because the domain stores an unrecognized one as -1 and the contract spells that as absent.
     */
    private fun Chapter.toNovelChapter(sourceId: String, novelId: String) = NovelChapter(
        sourceId = sourceId,
        novelId = novelId,
        id = url,
        title = name,
        number = chapterNumber.toFloat().takeIf { isRecognizedNumber },
        url = memo.stringOrNull(KEY_URL),
    )

    /** A missing manga row is a normal state here, not an error: it only means the fallback has nothing to offer. */
    private suspend fun mangaOrNull(mangaId: Long): Manga? = runCatching { mangaRepository.getMangaById(mangaId) }
        .getOrElse {
            logcat(LogPriority.WARN, it) { "[novel] Manga $mangaId could not be read for a chapter fetch" }
            null
        }

    private fun miss(chapterId: Long, why: String): ChapterContent? {
        logcat(LogPriority.WARN) { "[novel] Chapter $chapterId has no stored content and $why" }
        return null
    }

    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        /** Written by `tachiyomi.data.novel.NovelMapper.toChapter` and read back by the source adapter. */
        const val KEY_SOURCE_ID = "novelSourceId"
        const val KEY_NOVEL_ID = "novelId"
        const val KEY_URL = "novelChapterUrl"
    }
}
