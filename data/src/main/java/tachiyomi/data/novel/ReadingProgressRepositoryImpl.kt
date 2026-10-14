package tachiyomi.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgressRepository
import kotlin.time.Clock
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReadingProgressRepositoryImpl(
    private val database: Database,
) : ReadingProgressRepository {
    override suspend fun get(chapterId: Long): ReadingProgress? {
        return database.reading_progressQueries
            .getByChapterId(chapterId) {
                    _: Long,
                    blockIndex: Int,
                    blockOffsetPx: Int,
                    blockFraction: Double,
                    percent: Double,
                    _: kotlin.time.Instant,
                ->
                ReadingProgress(
                    blockIndex = blockIndex,
                    blockOffsetPx = blockOffsetPx,
                    blockFraction = blockFraction.toFloat(),
                    percent = percent.toFloat(),
                )
            }
            .awaitAsOneOrNull()
    }

    override suspend fun save(chapterId: Long, progress: ReadingProgress) {
        database.reading_progressQueries.upsert(
            chapterId = chapterId,
            blockIndex = progress.blockIndex,
            blockOffsetPx = progress.blockOffsetPx,
            blockFraction = progress.blockFraction.toDouble(),
            percent = progress.percent.toDouble(),
            stateDateUpdate = Clock.System.now(),
        )
    }

    override suspend fun delete(chapterId: Long) {
        database.reading_progressQueries.delete(chapterId)
    }

    /**
     * The saved position of every chapter of this manga that has one, in a single query.
     *
     * The join to `chapter` is done in SQL instead of filtering this manga's rows in Kotlin, so an image-only manga
     * reads nothing. The query listens on both `chapter` and `reading_progress`, which is what lets the chapter list
     * pick up a position saved from the reader without being rebuilt by hand.
     */
    override fun getPercentByMangaId(mangaId: Long): Flow<Map<Long, Float>> {
        return database.reading_progressQueries
            .getPercentByMangaId(mangaId) { chapterId: Long, percent: Double ->
                chapterId to percent.toFloat()
            }
            .subscribeToList()
            .map { it.toMap() }
    }
}
