package tachiyomi.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.novel.source.api.ChapterContent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.novel.repository.ChapterContentRepository
import java.io.File
import kotlin.time.Clock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterContentRepositoryImpl(
    private val database: Database,
    private val contentDir: NovelContentDir,
) : ChapterContentRepository {
    private val json = Json

    private fun contentFile(chapterId: Long) = File(contentDir.path, "chapter_$chapterId.json")

    override suspend fun get(chapterId: Long): ChapterContent? {
        val filePath = database.chapter_contentQueries
            .getByChapterId(chapterId) { _: Long, path: String, _: kotlin.time.Instant -> path }
            .awaitAsOneOrNull()
        // The three ways this can come back empty are different problems, so each is logged on its own
        // rather than collapsing into a silent null.
        if (filePath == null) {
            logcat(LogPriority.WARN) { "No chapter_content row for chapter $chapterId" }
            return null
        }
        val file = File(filePath)
        if (!file.exists()) {
            logcat(LogPriority.WARN) { "Chapter content file missing for chapter $chapterId at $filePath" }
            return null
        }
        return try {
            json.decodeFromString(ChapterContent.serializer(), file.readText())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Could not decode chapter content for chapter $chapterId at $filePath" }
            null
        }
    }

    override fun getAsFlow(chapterId: Long): Flow<ChapterContent?> = flow {
        emit(get(chapterId))
    }

    override suspend fun save(chapterId: Long, content: ChapterContent): String {
        val file = contentFile(chapterId)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ChapterContent.serializer(), content))
        database.chapter_contentQueries.upsert(
            chapterId = chapterId,
            filePath = file.absolutePath,
            stateDateDownload = Clock.System.now(),
        )
        return file.absolutePath
    }

    override suspend fun delete(chapterId: Long) {
        val filePath = database.chapter_contentQueries
            .getByChapterId(chapterId) { _: Long, path: String, _: kotlin.time.Instant -> path }
            .awaitAsOneOrNull()
        filePath?.let { File(it).delete() }
        database.chapter_contentQueries.delete(chapterId)
    }
}
