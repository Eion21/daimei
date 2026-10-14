package tachiyomi.data.novel

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
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
            .executeAsOneOrNull()
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
}
