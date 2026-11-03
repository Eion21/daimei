package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.ui.reader.novel.ChapterOpener
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.backup.model.RestoredHistory
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.backup.repository.RestoreRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.FetchInterval
import kotlin.time.Clock

@Inject
class MangaRestorer(
    private val restoreRepository: RestoreRepository,
    private val getCategories: GetCategories,
    private val fetchInterval: FetchInterval,
) {

    private val timeZone = TimeZone.currentSystemDefault()
    private val now = Clock.System.now().toLocalDateTime(timeZone)
    private val currentFetchWindow = fetchInterval.getWindow(now.date, timeZone)

    suspend fun sortByNew(backupMangas: List<BackupManga>): List<BackupManga> {
        val urlsBySource = restoreRepository.getMangaUrlsBySourceId()

        return backupMangas
            .sortedBy { it.url in urlsBySource[it.source].orEmpty() }
    }

    /**
     * Restores [backupMangas] all together, so either every one of them is restored or none is.
     */
    suspend fun restore(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) {
        val dbCategoriesByName = getCategories.await().associateBy { it.name }
        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val entries = backupMangas.map { backupManga ->
            val chapters = backupManga.chapters.map { it.toChapterImpl() }
            // The one rule the reader routes by decides what is novel data here too: a chapter without the memo
            // `NovelMapper` writes has neither a position inside its text nor text of its own to restore.
            val novelChapterUrls = chapters
                .filter { ChapterOpener.shouldOpenNovelReader(it) }
                .mapTo(mutableSetOf()) { it.url }

            RestoredManga(
                manga = backupManga.getMangaImpl(),
                chapters = chapters,
                categoryIds = backupManga.categories.mapNotNull { backupCategoryOrder ->
                    backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                        dbCategoriesByName[backupCategory.name]?.id
                    }
                },
                history = backupManga.history.map {
                    val history = it.getHistoryImpl()
                    RestoredHistory(it.url, history.readAt, history.readDuration)
                },
                tracks = backupManga.tracking.map { it.getTrackImpl() },
                excludedScanlators = backupManga.excludedScanlators,
                novelPositions = backupManga.chapters
                    .zip(chapters)
                    .filter { (_, chapter) -> chapter.url in novelChapterUrls }
                    .mapNotNull { (backupChapter, chapter) ->
                        // A fraction of 0 on block 0 is the top of the chapter, which is also what every novel
                        // chapter in a backup taken before this existed carries. Neither is a position worth
                        // writing a row for.
                        val isTopOfChapter = backupChapter.lastBlockFraction <= 0F &&
                            backupChapter.lastPageRead <= 0L
                        if (isTopOfChapter) return@mapNotNull null
                        chapter.url to backupChapter.lastBlockFraction
                    }
                    .toMap(),
                novelContents = backupManga.novelContents
                    .filter { it.chapterUrl in novelChapterUrls }
                    .associate { it.chapterUrl to it.content },
                novelContentFingerprints = backupManga.chapters
                    .filter { it.url in novelChapterUrls }
                    .associate { it.url to it.contentFingerprint },
            )
        }

        restoreRepository.restoreManga(entries) { manga ->
            fetchInterval.withFetchInterval(manga, now, timeZone, currentFetchWindow).apply {
                // A next update still ahead was worked out with the chapters the backup came from
                manga.nextUpdate
                    ?.takeIf { it >= currentFetchWindow.start }
                    ?.let { nextUpdate = it }
            }
        }
    }
}
