package tachiyomi.data.novel

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Chapter
import tachiyomi.data.Chapter_content
import tachiyomi.data.Database
import tachiyomi.data.History
import tachiyomi.data.InstantColumnAdapter
import tachiyomi.data.IntColumnAdapter
import tachiyomi.data.Manga
import tachiyomi.data.Manga_track
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.Reading_progress
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterContentRepositoryDispatcherTest {
    private class RecordingDriver(private val jdbc: SqlDriver) : SqlDriver by jdbc {
        val threads = Collections.synchronizedList(mutableListOf<String>())

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            if (sql.contains("chapter_content", ignoreCase = true)) threads.add(Thread.currentThread().name)
            return jdbc.execute(identifier, sql, parameters, binders)
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            if (sql.contains("chapter_content", ignoreCase = true)) threads.add(Thread.currentThread().name)
            return jdbc.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    private class Fixture(dir: File) : AutoCloseable {
        val driver = RecordingDriver(JdbcSqliteDriver("jdbc:sqlite:${File(dir, "test.db").absolutePath}"))
        val database = Database(
            driver = driver,
            chapterAdapter = Chapter.Adapter(
                remote_date_uploadAdapter = InstantColumnAdapter,
                remote_memoAdapter = MemoColumnAdapter,
                user_last_page_readAdapter = IntColumnAdapter,
                state_date_fetchAdapter = InstantColumnAdapter,
            ),
            chapter_contentAdapter = Chapter_content.Adapter(state_date_downloadAdapter = InstantColumnAdapter),
            historyAdapter = History.Adapter(read_atAdapter = InstantColumnAdapter),
            mangaAdapter = Manga.Adapter(
                remote_genreAdapter = StringListColumnAdapter,
                remote_update_strategyAdapter = UpdateStrategyColumnAdapter,
                remote_memoAdapter = MemoColumnAdapter,
                user_favorite_atAdapter = InstantColumnAdapter,
                state_chapter_last_updateAdapter = InstantColumnAdapter,
                state_chapter_next_updateAdapter = InstantColumnAdapter,
                state_cover_last_modifiedAdapter = InstantColumnAdapter,
            ),
            manga_trackAdapter = Manga_track.Adapter(
                start_dateAdapter = InstantColumnAdapter,
                finish_dateAdapter = InstantColumnAdapter,
            ),
            reading_progressAdapter = Reading_progress.Adapter(
                block_indexAdapter = IntColumnAdapter,
                block_offset_pxAdapter = IntColumnAdapter,
                state_date_updateAdapter = InstantColumnAdapter,
            ),
        )
        val repository = ChapterContentRepositoryImpl(database, NovelContentDir(File(dir, "content")))

        init {
            driver.execute(
                null,
                "CREATE TABLE chapter_content (chapter_id INTEGER NOT NULL PRIMARY KEY, file_path TEXT NOT NULL, state_date_download INTEGER NOT NULL)",
                0,
            )
            driver.threads.clear()
        }

        override fun close() = driver.close()
    }

    private fun onMain(block: suspend () -> Unit) {
        val main = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "repository-test-main")
        }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        try {
            runBlocking(Dispatchers.Main) { block() }
        } finally {
            Dispatchers.resetMain()
            main.close()
        }
    }

    // Coroutine debugging appends its ID to the Java thread name while the coroutine runs.
    private fun threadNameWithoutCoroutineId(name: String): String = name.substringBefore(" @coroutine#")

    private fun assertOffMain(threads: List<String>) {
        assertTrue(threads.isNotEmpty(), "expected observable SQL or encoding work")
        assertTrue(
            threads.none { threadNameWithoutCoroutineId(it) == "repository-test-main" },
            "work ran on Main; observed thread names: $threads",
        )
    }

    private val content = ChapterContent(listOf(ContentBlock.Paragraph("hello"), ContentBlock.Heading("title")))

    @Test
    fun `save encodes and writes row off main preserving path and JSON`(@TempDir dir: File) = onMain {
        Fixture(dir).use { fixture ->
            val encodingThreads = Collections.synchronizedList(mutableListOf<String>())
            val observed = ChapterContent(object : AbstractList<ContentBlock>() {
                override val size: Int get() = content.blocks.size
                override fun get(index: Int): ContentBlock {
                    encodingThreads.add(Thread.currentThread().name)
                    return content.blocks[index]
                }
            })
            val path = fixture.repository.save(1, observed)
            val file = File(path)
            assertEquals(File(dir, "content/chapter_1.json").absolutePath, path)
            assertEquals(Json.encodeToString(ChapterContent.serializer(), content), file.readText())
            assertEquals(content, fixture.repository.get(1))
            assertOffMain(encodingThreads)
            assertOffMain(fixture.driver.threads)
        }
    }

    @Test
    fun `get reads off main`(@TempDir dir: File) = onMain {
        Fixture(dir).use { fixture ->
            fixture.repository.save(1, content)
            fixture.driver.threads.clear()
            assertEquals(content, fixture.repository.get(1))
            assertOffMain(fixture.driver.threads.toList())
        }
    }

    @Test
    fun `flow reads off main and emits on collector context`(@TempDir dir: File) = onMain {
        Fixture(dir).use { fixture ->
            fixture.repository.save(1, content)
            fixture.driver.threads.clear()
            var collectorThread = ""
            val emitted = fixture.repository.getAsFlow(1).onEach {
                collectorThread = Thread.currentThread().name
            }.first()
            assertEquals(content, emitted)
            assertEquals(
                "repository-test-main",
                threadNameWithoutCoroutineId(collectorThread),
                "collector thread name: $collectorThread",
            )
            assertOffMain(fixture.driver.threads.toList())
        }
    }

    @Test
    fun `delete removes file and row off main`(@TempDir dir: File) = onMain {
        Fixture(dir).use { fixture ->
            val file = File(fixture.repository.save(1, content))
            fixture.driver.threads.clear()
            fixture.repository.delete(1)
            val deleteThreads = fixture.driver.threads.toList()
            assertFalse(file.exists())
            assertNull(fixture.repository.get(1))
            assertOffMain(deleteThreads)
        }
    }

    @Test
    fun `missing row missing file and corrupt JSON remain null`(@TempDir dir: File) = onMain {
        Fixture(dir).use { fixture ->
            assertNull(fixture.repository.get(1))
            val file = File(fixture.repository.save(1, content))
            assertTrue(file.delete())
            assertNull(fixture.repository.get(1))
            file.writeText("{bad json")
            assertNull(fixture.repository.get(1))
        }
    }

    @Test
    fun `cancelled calls propagate cancellation and save IO errors propagate`(@TempDir dir: File) = onMain {
        Fixture(dir).use { fixture ->
            val cancelled = Job().apply { cancel() }
            try {
                withContext(cancelled) { fixture.repository.save(1, content) }
                fail<Unit>("cancelled save returned")
            } catch (_: CancellationException) {
                assertNull(fixture.repository.get(1))
            }
            val notDirectory = File(dir, "not-directory").apply { writeText("occupied") }
            val repository = ChapterContentRepositoryImpl(fixture.database, NovelContentDir(notDirectory))
            try {
                repository.save(2, content)
                fail<Unit>("save unexpectedly succeeded")
            } catch (_: IOException) {
                assertNull(fixture.repository.get(2))
                assertEquals("occupied", notDirectory.readText())
            }
        }
    }
}
