package tachiyomi.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import tachiyomi.domain.novel.repository.ReadingProgress
import java.io.File
import kotlin.time.Clock
import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.Novel as NovelEntry

/**
 * Proves the novel tables survive a round trip through the real database, including the foreign keys onto
 * `chapter`. It runs on the JVM driver because the bundled SQLite used on-device ships only Android ABIs.
 */
class NovelDataTest {

    private val emptyMemo = Json.parseToJsonElement("{}") as JsonObject

    private suspend fun openDatabase(path: String): Pair<SqlDriver, Database> {
        val isNewDatabase = !File(path).exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        val database = Database(
            driver = driver,
            chapterAdapter = Chapter.Adapter(
                remote_date_uploadAdapter = InstantColumnAdapter,
                remote_memoAdapter = MemoColumnAdapter,
                user_last_page_readAdapter = IntColumnAdapter,
                state_date_fetchAdapter = InstantColumnAdapter,
            ),
            chapter_contentAdapter = Chapter_content.Adapter(
                state_date_downloadAdapter = InstantColumnAdapter,
            ),
            historyAdapter = History.Adapter(
                read_atAdapter = InstantColumnAdapter,
            ),
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
        // The driver is asynchronous, so the schema is only in place once this completes. Reopening an
        // existing file must not recreate it.
        if (isNewDatabase) {
            (Database.Schema.create(driver) as QueryResult.AsyncValue<Unit>).await()
        }
        return driver to database
    }

    /** Inserts the novel and chapter rows the new tables point at, so the foreign keys are exercised. */
    private suspend fun Database.insertNovelAndChapter(
        remoteUrl: String,
        chapterUrl: String,
    ): Long {
        val mangaId = mangaQueries.insertReturningId(
            sourceId = NovelMapper.generateSourceId("demo"),
            remoteUrl = remoteUrl,
            remoteTitle = "La torre de cristal",
            remoteAuthor = "Autor Uno",
            remoteArtist = null,
            remoteDescription = null,
            remoteGenre = null,
            remoteStatus = 0,
            remoteCover = null,
            remoteUpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            remoteMemo = emptyMemo,
            userFavoriteAt = null,
            userNotes = "",
            userReaderFlags = 0,
            userChapterFlags = 0,
            stateChapterLastUpdate = null,
            stateChapterNextUpdate = null,
            stateChapterFetchInterval = 0,
            stateCoverLastModified = null,
            stateInitialized = true,
        ).awaitAsOne()
        return chapterQueries.insertReturningId(
            mangaId = mangaId,
            remoteUrl = chapterUrl,
            remoteName = "Capítulo 1",
            remoteScanlator = null,
            remoteChapterNumber = 1.0,
            remoteDateUpload = null,
            remoteOrder = 0,
            remoteMemo = emptyMemo,
            userRead = false,
            userBookmark = false,
            userLastPageRead = 0,
            stateDateFetch = Clock.System.now(),
        ).awaitAsOne()
    }

    @Test
    fun `a mapped novel and chapter are stored and read back unchanged`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "mapper-test.db").absolutePath
        val novel = NovelEntry("demo", "n1", "La torre de cristal", author = "Autor Uno")
        val chapter = NovelChapter("demo", "n1", "c1", "Capítulo 1", number = 1f, url = "https://example.com/c1")

        openDatabase(dbPath).let { (driver, database) ->
            val manga = NovelMapper.toManga(novel)
            val mangaId = database.mangaQueries.insertReturningId(
                sourceId = manga.source,
                remoteUrl = manga.url,
                remoteTitle = manga.title,
                remoteAuthor = manga.author,
                remoteArtist = manga.artist,
                remoteDescription = manga.description,
                remoteGenre = manga.genre,
                remoteStatus = manga.status,
                remoteCover = manga.thumbnailUrl,
                remoteUpdateStrategy = manga.updateStrategy,
                remoteMemo = manga.memo,
                userFavoriteAt = manga.favoriteAt,
                userNotes = manga.notes,
                userReaderFlags = manga.viewerFlags,
                userChapterFlags = manga.chapterFlags,
                stateChapterLastUpdate = manga.lastUpdate,
                stateChapterNextUpdate = manga.nextUpdate,
                stateChapterFetchInterval = manga.fetchInterval.toLong(),
                stateCoverLastModified = manga.coverLastModified,
                stateInitialized = manga.initialized,
            ).awaitAsOne()

            val mapped = NovelMapper.toChapter(novel, chapter)
            database.chapterQueries.insertReturningId(
                mangaId = mangaId,
                remoteUrl = mapped.url,
                remoteName = mapped.name,
                remoteScanlator = mapped.scanlator,
                remoteChapterNumber = mapped.chapterNumber,
                remoteDateUpload = mapped.dateUpload,
                remoteOrder = mapped.sourceOrder,
                remoteMemo = mapped.memo,
                userRead = mapped.read,
                userBookmark = mapped.bookmark,
                userLastPageRead = mapped.lastPageRead,
                stateDateFetch = mapped.dateFetch,
            ).awaitAsOne()

            // Read back through the same path the library uses.
            val storedManga = database.mangaQueries.getMangaById(mangaId) {
                    id,
                    sourceId,
                    remoteUrl,
                    title,
                    author,
                    _,
                    description,
                    _,
                    status,
                    cover,
                    _,
                    _,
                    favoriteAt,
                    notes,
                    viewerFlags,
                    chapterFlags,
                    _,
                    _,
                    fetchInterval,
                    coverModified,
                    initialized,
                ->
                Triple(remoteUrl, title, sourceId)
            }.executeAsOne()
            assertEquals(Triple("n1", "La torre de cristal", NovelMapper.generateSourceId("demo")), storedManga)

            val storedChapter = database.chapterQueries.getChapterByUrlAndMangaId(mangaId, "c1").executeAsOne()
            assertEquals("Capítulo 1", storedChapter.remote_name)
            assertEquals(1.0, storedChapter.remote_chapter_number)

            driver.close()
        }
    }

    @Test
    fun `content and progress survive closing and reopening the database`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "novel-test.db").absolutePath
        val contentDir = NovelContentDir(File(tempDir, "content"))
        val content = ChapterContent(
            blocks = listOf(
                ContentBlock.Heading("Capítulo 1", level = 1),
                ContentBlock.Paragraph("It was a bright cold day in April."),
                ContentBlock.Image(url = "https://example.com/a.jpg", alt = "ilustración"),
                ContentBlock.Divider,
            ),
        )
        val progress = ReadingProgress(blockIndex = 2, blockOffsetPx = 140, blockFraction = 0.5f, percent = 0.42f)

        val chapterId: Long
        // Write, then drop the connection so the next open reads from disk rather than from memory.
        openDatabase(dbPath).let { (driver, database) ->
            chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")

            ChapterContentRepositoryImpl(database, contentDir).save(chapterId, content)
            ReadingProgressRepositoryImpl(database).save(chapterId, progress)
            driver.close()
        }

        openDatabase(dbPath).let { (driver, database) ->
            val reopenedContent = ChapterContentRepositoryImpl(database, contentDir).get(chapterId)
            val reopenedProgress = ReadingProgressRepositoryImpl(database).get(chapterId)

            assertEquals(content, reopenedContent)
            assertEquals(progress, reopenedProgress)
            driver.close()
        }
    }

    @Test
    fun `deleting content removes the file and the row`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "novel-test.db").absolutePath
        val contentDir = NovelContentDir(File(tempDir, "content"))
        val content = ChapterContent(listOf(ContentBlock.Paragraph("hola")))

        openDatabase(dbPath).let { (driver, database) ->
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")

            val repository = ChapterContentRepositoryImpl(database, contentDir)
            val path = File(repository.save(chapterId, content))
            assertTrue(path.exists())

            repository.delete(chapterId)
            assertNull(repository.get(chapterId))
            assertFalse(path.exists())
            driver.close()
        }
    }

    @Test
    fun `migration 20 creates the novel tables on an existing database`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "migration-test.db").absolutePath
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        val database = createDatabase(driver)
        (Database.Schema.create(driver) as QueryResult.AsyncValue<Unit>).await()

        // Roll back to the state a real upgrade starts from: the schema minus the two novel tables.
        suspend fun exec(sql: String) = driver.execute(null, sql, 0).await()

        exec("DROP TABLE chapter_content")
        exec("DROP TABLE reading_progress")
        // `create` stamps the newest version, so it is rewound to make the migration do real work.
        exec("PRAGMA user_version = 19")

        // SQLDelight generates migration N as `oldVersion <= N && newVersion > N`, so `migrate(N, N + 1)` runs
        // file N and nothing else. A wider range re-runs the earlier migrations as well.
        (Database.Schema.migrate(driver, 20, 21) as QueryResult.AsyncValue<Unit>).await()

        val tables = driver.executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('chapter_content', 'reading_progress')",
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    buildList {
                        while (cursor.next().value) add(cursor.getString(0))
                    },
                )
            },
            0,
        )
        assertEquals(
            setOf("chapter_content", "reading_progress"),
            (tables as QueryResult.Value<List<String>>).value.toSet(),
        )
        driver.close()
    }

    @Test
    fun `migration 21 adds the block fraction to an existing progress row`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "fraction-test.db").absolutePath
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        val database = createDatabase(driver)
        (Database.Schema.create(driver) as QueryResult.AsyncValue<Unit>).await()

        suspend fun exec(sql: String) = driver.execute(null, sql, 0).await()

        val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")

        // Rewind to the shape the table had before the fraction existed, with a row already in it. These are raw
        // SQLite statements, so no SQLDelight `AS Int` adapter annotations here.
        exec("DROP TABLE reading_progress")
        exec(
            """
            CREATE TABLE reading_progress(
                chapter_id INTEGER NOT NULL PRIMARY KEY,
                block_index INTEGER NOT NULL,
                block_offset_px INTEGER NOT NULL,
                percent REAL NOT NULL,
                state_date_update INTEGER NOT NULL,
                FOREIGN KEY(chapter_id) REFERENCES chapter(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        exec(
            "INSERT INTO reading_progress(chapter_id, block_index, block_offset_px, percent, state_date_update) " +
                "VALUES ($chapterId, 7, 0, 0.35, 0)",
        )
        exec("PRAGMA user_version = 20")

        (Database.Schema.migrate(driver, 21, 22) as QueryResult.AsyncValue<Unit>).await()

        // The pre-existing row survives and reads back as "top of block 7", which is the only thing it could
        // have meant before the fraction was recorded.
        val restored = ReadingProgressRepositoryImpl(database).get(chapterId)
        assertEquals(7, restored?.blockIndex)
        assertEquals(0.35f, restored?.percent)
        assertEquals(0f, restored?.blockFraction)

        // And a fresh save round-trips the fraction.
        ReadingProgressRepositoryImpl(database)
            .save(chapterId, ReadingProgress(3, 0, blockFraction = 0.25f, percent = 0.5f))
        assertEquals(0.25f, ReadingProgressRepositoryImpl(database).get(chapterId)?.blockFraction)

        driver.close()
    }

    /** The database without creating a schema, so a test can start from a migrated state. */
    private fun createDatabase(driver: SqlDriver): Database = Database(
        driver = driver,
        chapterAdapter = Chapter.Adapter(
            remote_date_uploadAdapter = InstantColumnAdapter,
            remote_memoAdapter = MemoColumnAdapter,
            user_last_page_readAdapter = IntColumnAdapter,
            state_date_fetchAdapter = InstantColumnAdapter,
        ),
        chapter_contentAdapter = Chapter_content.Adapter(
            state_date_downloadAdapter = InstantColumnAdapter,
        ),
        historyAdapter = History.Adapter(
            read_atAdapter = InstantColumnAdapter,
        ),
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
}
