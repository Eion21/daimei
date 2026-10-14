package tachiyomi.data.novel

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.security.MessageDigest
import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.Novel as NovelEntry

/**
 * Translates the novel contract into the domain models Mihon already stores, so a novel reuses the `manga`
 * and `chapter` tables instead of duplicating library, category and history handling.
 *
 * The remote id becomes `remote_url`: together with the source id it is what makes a row unique, and it is
 * what a later fetch needs to ask the source for the same entry again.
 */
object NovelMapper {

    /**
     * Stable numeric id for a source, derived from its string id the same way [eu.kanade.tachiyomi.source
     * .online.HttpSource] derives its own. MD5 is used rather than [String.hashCode], whose value is not
     * guaranteed across runtimes, which would orphan a user's library after an update.
     */
    fun generateSourceId(infoId: String): Long {
        val bytes = MessageDigest.getInstance("MD5").digest("novel/$infoId".toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    fun toManga(novel: NovelEntry): Manga = Manga(
        id = NO_ID,
        source = generateSourceId(novel.sourceId),
        url = novel.id,
        title = novel.title,
        author = novel.author,
        artist = null,
        description = novel.description,
        genre = null,
        thumbnailUrl = novel.coverUrl,
        status = 0L,
        // A listing entry carries only a title; `initialized` marks that full details were fetched.
        initialized = novel.description != null || novel.author != null,
        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        lastUpdate = null,
        nextUpdate = null,
        fetchInterval = 0,
        favoriteAt = null,
        viewerFlags = 0L,
        chapterFlags = 0L,
        coverLastModified = null,
        notes = "",
        memo = buildJsonObject { },
    )

    /**
     * Maps a chapter, leaving [Chapter.id] and [Chapter.mangaId] unset so the repository can assign them
     * once the parent novel row exists. A missing number becomes -1, which Mihon reads as unrecognized.
     */
    fun toChapter(
        novel: NovelEntry,
        chapter: NovelChapter,
        sourceOrder: Long = 0L,
    ): Chapter = Chapter(
        id = NO_ID,
        mangaId = NO_ID,
        url = chapter.id,
        name = chapter.title,
        chapterNumber = chapter.number?.toDouble() ?: UNRECOGNIZED_NUMBER,
        sourceOrder = sourceOrder,
        dateUpload = null,
        scanlator = null,
        read = false,
        bookmark = false,
        lastPageRead = 0,
        dateFetch = kotlin.time.Clock.System.now(),
        // The source-specific payload travels in the memo, so nothing about a novel leaks into the shared
        // domain models and the web url is still recoverable when the content is fetched.
        memo = buildJsonObject {
            put(KEY_SOURCE_ID, JsonPrimitive(novel.sourceId))
            put(KEY_NOVEL_ID, JsonPrimitive(novel.id))
            chapter.url?.let { put(KEY_URL, JsonPrimitive(it)) }
        },
    )

    fun sourceId(chapter: Chapter): String? = chapter.memo.stringOrNull(KEY_SOURCE_ID)

    fun novelId(chapter: Chapter): String? = chapter.memo.stringOrNull(KEY_NOVEL_ID)

    fun chapterUrl(chapter: Chapter): String? = chapter.memo.stringOrNull(KEY_URL)

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private const val NO_ID = -1L
    private const val UNRECOGNIZED_NUMBER = -1.0

    private const val KEY_SOURCE_ID = "novelSourceId"
    private const val KEY_NOVEL_ID = "novelId"
    private const val KEY_URL = "novelChapterUrl"
}
