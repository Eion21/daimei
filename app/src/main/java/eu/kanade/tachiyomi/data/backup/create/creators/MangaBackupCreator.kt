// Modified for Daimei; see MODIFICATIONS.md.

package eu.kanade.tachiyomi.data.backup.create.creators

import app.novel.source.api.ChapterContent
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovelContent
import eu.kanade.tachiyomi.data.backup.models.toBackupChapter
import eu.kanade.tachiyomi.data.backup.models.toBackupTracking
import eu.kanade.tachiyomi.ui.reader.novel.ChapterOpener
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import mihon.core.common.extensions.toByteArray
import mihon.core.common.extensions.toEpochMillisOrZero
import tachiyomi.data.backup.ContentFingerprint
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.novel.repository.ChapterContentRepository
import tachiyomi.domain.novel.repository.ReadingProgressRepository
import tachiyomi.domain.track.repository.TrackRepository

@Inject
class MangaBackupCreator(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val trackRepository: TrackRepository,
    private val getCategories: GetCategories,
    private val getHistory: GetHistory,
    private val readingProgressRepository: ReadingProgressRepository,
    private val chapterContentRepository: ChapterContentRepository,
) {

    private val json = Json

    suspend operator fun invoke(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        return mangas.map {
            backupManga(it, options)
        }
    }

    private suspend fun backupManga(manga: Manga, options: BackupOptions): BackupManga {
        // Entry for this manga
        val mangaObject = manga.toBackupManga()

        mangaObject.excludedScanlators = mangaRepository.getExcludedScanlators(manga.id).toList()

        if (options.chapters) {
            // Backup all the chapters
            val chapters = chapterRepository.getChapterByMangaId(manga.id, applyScanlatorFilter = false)
            if (chapters.isNotEmpty()) {
                mangaObject.chapters = chapters.map { it.toBackupChapter().withNovelPosition(it) }
                novelContentsFor(manga, chapters, mangaObject.chapters.associateBy { it.url })
                    .takeIf { it.isNotEmpty() }
                    ?.let { mangaObject.novelContents = it }
            }
        }

        if (options.categories) {
            // Backup categories for this manga
            val categoriesForManga = getCategories.await(manga.id)
            if (categoriesForManga.isNotEmpty()) {
                mangaObject.categories = categoriesForManga.map { it.order }
            }
        }

        if (options.tracking) {
            val tracks = trackRepository.getTracksByMangaId(manga.id).map { it.toBackupTracking() }
            if (tracks.isNotEmpty()) {
                mangaObject.tracking = tracks
            }
        }

        if (options.history) {
            val historyByMangaId = getHistory.await(manga.id)
            if (historyByMangaId.isNotEmpty()) {
                val chapterUrlsById = chapterRepository.getChapterByMangaId(manga.id).associate { it.id to it.url }
                val history = historyByMangaId.mapNotNull { history ->
                    // A chapter removed since its history was read takes that history with it
                    val url = chapterUrlsById[history.chapterId] ?: return@mapNotNull null
                    BackupHistory(url, history.readAt.toEpochMillisOrZero(), history.readDuration)
                }
                if (history.isNotEmpty()) {
                    mangaObject.history = history
                }
            }
        }

        return mangaObject
    }

    /**
     * The saved position of a novel chapter, written onto the chapter entry itself.
     *
     * The block index goes in `lastPageRead`, which is the same slot an image chapter's page occupies: it is the
     * closest thing the shared schema has to "how far in", and it is 0 for every novel chapter in a backup taken
     * before this existed, so nothing is overwritten by accident. The fraction needs a field of its own, and it
     * is the half that is actually load-bearing — see [BackupChapter.lastBlockFraction].
     *
     * Both reads are behind [ChapterOpener.shouldOpenNovelReader], so an image chapter is left with exactly the
     * values it would have had without this method. `reading_progress` is per chapter and has no read that
     * covers a whole manga, so this is one indexed lookup per novel chapter and none at all for an image one.
     */
    private suspend fun BackupChapter.withNovelPosition(chapter: Chapter): BackupChapter {
        if (!ChapterOpener.shouldOpenNovelReader(chapter)) return this

        val progress = readingProgressRepository.get(chapter.id) ?: return this
        lastPageRead = progress.blockIndex.toLong()
        lastBlockFraction = progress.blockFraction
        return this
    }

    /**
     * The text of every chapter of this manga that has it cached, as JSON.
     *
     * Whether a chapter is cached is settled by one query for the whole manga rather than by asking each chapter
     * in turn, because the per-chapter read logs a warning when there is nothing cached and a novel that was read
     * online but never downloaded would fill the log with one line per chapter. A manga with no novel chapter
     * asks nothing at all, so an image backup pays no extra query.
     *
     * A chapter whose row exists but whose file will not decode is left out: the reader cannot open it either, so
     * carrying unreadable text would only make a backup look complete.
     */
    private suspend fun novelContentsFor(
        manga: Manga,
        chapters: List<Chapter>,
        backupChaptersByUrl: Map<String, BackupChapter>,
    ): List<BackupNovelContent> {
        val novelChapters = chapters.filter { ChapterOpener.shouldOpenNovelReader(it) }
        if (novelChapters.isEmpty()) return emptyList()

        val cachedChapterIds = chapterContentRepository.getCachedChapterIdsByMangaId(manga.id).first()
        return novelChapters
            .filter { it.id in cachedChapterIds }
            .mapNotNull { chapter ->
                chapterContentRepository.get(chapter.id)?.let { content ->
                    backupChaptersByUrl.getValue(chapter.url).contentFingerprint = ContentFingerprint.of(content)
                    BackupNovelContent(
                        chapterUrl = chapter.url,
                        content = json.encodeToString(ChapterContent.serializer(), content),
                    )
                }
            }
    }
}

private fun Manga.toBackupManga() =
    BackupManga(
        url = this.url,
        title = this.title,
        artist = this.artist,
        author = this.author,
        description = this.description,
        genre = this.genre.orEmpty(),
        status = this.status.toInt(),
        thumbnailUrl = this.thumbnailUrl,
        favorite = this.favorite,
        source = this.source,
        dateAdded = this.favoriteAt.toEpochMillisOrZero(),
        viewer = (this.viewerFlags.toInt() and ReadingMode.MASK),
        viewer_flags = this.viewerFlags.toInt(),
        chapterFlags = this.chapterFlags.toInt(),
        updateStrategy = this.updateStrategy,
        notes = this.notes,
        initialized = this.initialized,
        memo = this.memo.toByteArray(),
        nextUpdate = this.nextUpdate.toEpochMillisOrZero(),
    )
