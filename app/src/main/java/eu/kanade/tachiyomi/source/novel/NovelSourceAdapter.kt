package eu.kanade.tachiyomi.source.novel

import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import app.novel.source.api.NovelSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import tachiyomi.data.novel.NovelMapper
import app.novel.source.api.Chapter as NovelChapter
import tachiyomi.domain.source.model.Source as DomainSource

/**
 * Presents a [NovelSource] through Mihon's [Source] interface, so Explore, the library and the update pipeline
 * treat a novel exactly like a manga instead of needing a parallel implementation of all three.
 *
 * One instance wraps exactly one [NovelSource]. The numeric id and the mapping rules are owned by [NovelMapper]
 * on purpose: a novel is stored in the same `manga`/`chapter` tables as a manga, and `source_id` + `remote_url`
 * is what makes a row unique. If this class derived its own id the rows already written would become unreachable
 * and a user's library would silently empty itself.
 *
 * It is deliberately a plain class rather than a graph node. It holds no state, needs nothing from the graph, and
 * staying outside it is what lets the mapping be tested without building one.
 *
 * [novelSource] is exposed rather than private because the source a loaded extension declares has to be mirrored
 * into `SourceRegistry` — the list `GetEnabledSources` reads to keep a novel source regardless of its language — and
 * the registry must hold the one instance this adapter wraps, not a second wrapper of the same declaration.
 */
class NovelSourceAdapter(val novelSource: NovelSource) : Source {

    override val id: Long = NovelMapper.generateSourceId(novelSource.info.id)

    override val name: String = novelSource.info.name

    override val lang: String = novelSource.info.language

    /**
     * [getLatestUpdates] currently answers with the popular listing, so declaring support keeps the two paths
     * consistent. Set this to false if a source ever should not show a "Latest" tab at all.
     */
    override val supportsLatest: Boolean = true

    override suspend fun getPopularManga(page: Int): MangasPage = novelSource.popular(page).toMangasPage()

    /**
     * The novel contract has no notion of a recent update, so this serves the popular listing instead. Replace it
     * when a source can order its novels by recency.
     */
    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    /** The novel contract takes no filters, so [filters] is accepted and ignored. */
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        novelSource.search(query, page).toMangasPage()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val novel = manga.toNovel()
        return SMangaUpdate(
            // SMangaUpdate holds a non-null SManga, so a detail the caller did not ask for is passed back
            // unchanged, as the Source contract allows, rather than fabricated.
            manga = if (fetchDetails) novelSource.getNovelDetails(novel).toSManga() else manga,
            chapters = if (fetchChapters) {
                novelSource.getChapters(novel).map { it.toSChapter(novel) }
            } else {
                emptyList()
            },
        )
    }

    /**
     * Never called for a novel. A chapter is prose, not a sequence of images: the text reader asks the source for
     * its `ChapterContent` directly. Reaching this method means a novel chapter was routed to the image reader,
     * which is a missing wiring on the reader side, not a paging problem.
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        throw UnsupportedOperationException(
            "${novelSource.info.name} is a novel source: '${chapter.name}' is read as text, not as pages. " +
                "Load its ChapterContent in the text reader instead of asking for a page list.",
        )
    }

    private fun NovelPage.toMangasPage() = MangasPage(novels.map { it.toSManga() }, hasNextPage)

    /**
     * Rebuilds the [Novel] this manga came from. Everything the source needs is on the [SManga] already, so the
     * round trip through listing and back does not lose detail.
     */
    private fun SManga.toNovel() = Novel(
        sourceId = novelSource.info.id,
        id = url,
        title = title,
        author = author,
        description = description,
        coverUrl = thumbnail_url,
    )

    private fun Novel.toSManga() = SManga.create().also {
        it.url = id
        it.title = title
        it.author = author
        it.description = description
        it.thumbnail_url = coverUrl
        it.artist = null
        it.genre = null
        it.status = SManga.UNKNOWN
        it.update_strategy = UpdateStrategy.ALWAYS_UPDATE
        // Same rule as NovelMapper: a listing entry only has a title, anything richer means details were fetched.
        it.initialized = description != null || author != null
        it.memo = buildJsonObject { }
    }

    private fun NovelChapter.toSChapter(novel: Novel) = SChapter.create().also {
        it.url = id
        it.name = title
        // NovelMapper stores this as a Double in the domain model, but SChapter declares Float: this narrows.
        it.chapter_number = number ?: UNRECOGNIZED_NUMBER
        it.scanlator = null
        it.date_upload = 0L
        // Same keys NovelMapper writes, so the text reader can recover them with NovelMapper's own accessors.
        it.memo = buildJsonObject {
            put(KEY_SOURCE_ID, JsonPrimitive(novel.sourceId))
            put(KEY_NOVEL_ID, JsonPrimitive(novel.id))
            url?.let { chapterUrl -> put(KEY_URL, JsonPrimitive(chapterUrl)) }
        }
    }

    private companion object {
        const val UNRECOGNIZED_NUMBER = -1f

        const val KEY_SOURCE_ID = "novelSourceId"
        const val KEY_NOVEL_ID = "novelId"
        const val KEY_URL = "novelChapterUrl"
    }
}

/**
 * A novel source declares whatever language it publishes in, which the user may not have enabled, so it bypasses
 * the language filter the same way a local source does. Mirrors `tachiyomi.source.local.isLocal`.
 */
fun Source.isNovelSource(): Boolean = this is NovelSourceAdapter

/**
 * The domain source model carries no runtime type, only the numeric id, so membership is decided against the ids
 * the adapters expose. Mirrors `tachiyomi.source.local.isLocal`, which compares against `LocalSource.ID`.
 */
fun DomainSource.isNovelSource(novelSourceIds: Set<Long>): Boolean = id in novelSourceIds
