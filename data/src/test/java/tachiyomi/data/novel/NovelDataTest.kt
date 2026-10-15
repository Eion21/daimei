package tachiyomi.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.novel.repository.ReadingProgress
import java.io.File
import kotlin.time.Clock
import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.Novel as NovelEntry
import tachiyomi.domain.chapter.model.Chapter as SyncedChapter

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

    /** Inserts a second chapter under the manga [insertNovelAndChapter] created, so a list can be exercised. */
    private suspend fun Database.insertChapterOf(mangaId: Long, chapterUrl: String, remoteName: String): Long {
        return chapterQueries.insertReturningId(
            mangaId = mangaId,
            remoteUrl = chapterUrl,
            remoteName = remoteName,
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

    /** The manga a chapter belongs to, so a per-manga read can be pointed at it. */
    private suspend fun Database.mangaIdOfChapter(chapterId: Long): Long {
        return chapterQueries.getChapterById(chapterId).awaitAsOne().manga_id
    }

    @Test
    fun `progress percentages for a manga's chapters come back in one read`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "percent-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val firstChapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(firstChapterId)
            val secondChapterId = database.insertChapterOf(mangaId, "c2", "Capítulo 2")

            // Only one of the three chapters has been opened, so only it may appear.
            val repository = ReadingProgressRepositoryImpl(database)
            repository.save(firstChapterId, ReadingProgress(3, 0, blockFraction = 0.5f, percent = 0.456f))
            repository.save(secondChapterId, ReadingProgress(0, 0, blockFraction = 0f, percent = 0f))

            val percents = repository.getPercentByMangaId(mangaId).first()

            assertEquals(mapOf(firstChapterId to 0.456f, secondChapterId to 0f), percents)
            driver.close()
        }
    }

    @Test
    fun `a manga with no saved progress reads back as an empty map`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "no-percent-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(chapterId)

            assertEquals(
                emptyMap<Long, Float>(),
                ReadingProgressRepositoryImpl(database).getPercentByMangaId(mangaId).first(),
            )
            driver.close()
        }
    }

    @Test
    fun `one manga's progress is never handed to another`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "leak-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val repository = ReadingProgressRepositoryImpl(database)

            val readChapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val readMangaId = database.mangaIdOfChapter(readChapterId)
            repository.save(readChapterId, ReadingProgress(2, 0, blockFraction = 0.25f, percent = 0.75f))

            val untouchedChapterId = database.insertNovelAndChapter(remoteUrl = "n2", chapterUrl = "c1")
            val untouchedMangaId = database.mangaIdOfChapter(untouchedChapterId)

            // The chapter ids are distinct but the URLs and source are identical, so only the manga_id join keeps
            // the second entry's position out of the first one's list.
            assertEquals(mapOf(readChapterId to 0.75f), repository.getPercentByMangaId(readMangaId).first())
            assertEquals(emptyMap<Long, Float>(), repository.getPercentByMangaId(untouchedMangaId).first())
            driver.close()
        }
    }

    @Test
    fun `a position saved after the read starts showing up on its own`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "live-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(chapterId)
            val repository = ReadingProgressRepositoryImpl(database)

            // The point of returning a Flow rather than a one-shot read: the chapter list is on screen when the
            // reader saves, and it has to learn about it without being rebuilt by hand.
            val emitted = mutableListOf<Map<Long, Float>>()
            val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.getPercentByMangaId(mangaId).take(2).toList(emitted)
            }

            repository.save(chapterId, ReadingProgress(5, 0, blockFraction = 0f, percent = 0.521f))
            collectJob.join()

            assertEquals(listOf(emptyMap(), mapOf(chapterId to 0.521f)), emitted)
            driver.close()
        }
    }

    @Test
    fun `the chapters of a manga whose content is cached come back in one read`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "cached-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val cachedChapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(cachedChapterId)
            val uncachedChapterId = database.insertChapterOf(mangaId, "c2", "Capítulo 2")

            val repository = ChapterContentRepositoryImpl(database, NovelContentDir(File(tempDir, "content")))
            repository.save(cachedChapterId, ChapterContent(listOf(ContentBlock.Paragraph("hola"))))

            val cached = repository.getCachedChapterIdsByMangaId(mangaId).first()
            assertEquals(setOf(cachedChapterId), cached)
            assertFalse(uncachedChapterId in cached)
            driver.close()
        }
    }

    @Test
    fun `a manga with nothing cached reads back as an empty set`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "no-content-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(chapterId)

            // A manga of image chapters never writes here at all, so the empty set is the normal answer, not an
            // error case: the chapter list asks this for every manga it shows.
            assertEquals(
                emptySet<Long>(),
                ChapterContentRepositoryImpl(database, NovelContentDir(File(tempDir, "content")))
                    .getCachedChapterIdsByMangaId(mangaId)
                    .first(),
            )
            driver.close()
        }
    }

    @Test
    fun `one manga's cached content is never handed to another`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "content-leak-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val repository = ChapterContentRepositoryImpl(database, NovelContentDir(File(tempDir, "content")))

            val cachedChapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val cachedMangaId = database.mangaIdOfChapter(cachedChapterId)
            repository.save(cachedChapterId, ChapterContent(listOf(ContentBlock.Paragraph("hola"))))

            // Identical source and chapter url, so only the manga_id join keeps this one out of the other's list.
            val otherChapterId = database.insertNovelAndChapter(remoteUrl = "n2", chapterUrl = "c1")
            val otherMangaId = database.mangaIdOfChapter(otherChapterId)

            assertEquals(setOf(cachedChapterId), repository.getCachedChapterIdsByMangaId(cachedMangaId).first())
            assertEquals(emptySet<Long>(), repository.getCachedChapterIdsByMangaId(otherMangaId).first())
            driver.close()
        }
    }

    @Test
    fun `content cached after the read starts showing up on its own`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "live-content-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(chapterId)
            val repository = ChapterContentRepositoryImpl(database, NovelContentDir(File(tempDir, "content")))

            // The query joins two tables, so this is only true if SQLDelight notifies on both of them and not
            // just the one the save writes to. The chapter list is on screen while a chapter is cached, and has
            // to learn about it without being rebuilt by hand.
            val emitted = mutableListOf<Set<Long>>()
            val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.getCachedChapterIdsByMangaId(mangaId).take(2).toList(emitted)
            }

            repository.save(chapterId, ChapterContent(listOf(ContentBlock.Paragraph("hola"))))
            collectJob.join()

            assertEquals(listOf(emptySet(), setOf(chapterId)), emitted)
            driver.close()
        }
    }

    @Test
    fun `deleting cached content takes the chapter back out of the cached set`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "delete-cached-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(chapterId)
            val repository = ChapterContentRepositoryImpl(database, NovelContentDir(File(tempDir, "content")))

            val path = File(repository.save(chapterId, ChapterContent(listOf(ContentBlock.Paragraph("hola")))))
            assertEquals(setOf(chapterId), repository.getCachedChapterIdsByMangaId(mangaId).first())

            repository.delete(chapterId)

            // Both halves, because a row without its file would still read as downloaded and then fail to open,
            // and a file without its row is invisible here and harmless.
            assertEquals(emptySet<Long>(), repository.getCachedChapterIdsByMangaId(mangaId).first())
            assertFalse(path.exists())
            driver.close()
        }
    }

    // Gap 1: a chapter row that goes away takes its novel state with it.
    //
    // The only statement in the project that deletes a chapter row is `removeChaptersWithIds`, reached from a
    // sync with the source, and the two novel tables declare `ON DELETE CASCADE` onto `chapter` for a cascade
    // that never fires because nothing in the project turns `PRAGMA foreign_keys` on. So without cleanup here a
    // chapter that the source withdrew leaves its text on disk and its position in the database forever.

    /** Removes chapter rows the only way the app does: a sync with the source reporting them gone. */
    private suspend fun removeChaptersFromSource(repository: ChapterRepository, chapterIds: List<Long>) {
        repository.updateFromRemote(removedIds = chapterIds, added = emptyList(), updated = emptyList())
    }

    /** The chapter repository, over the novel state it has to take with a removed chapter. */
    private fun Database.chapterRepository(contentDir: NovelContentDir): ChapterRepositoryImpl =
        ChapterRepositoryImpl(
            database = this,
            chapterContentRepository = ChapterContentRepositoryImpl(this, contentDir),
            readingProgressRepository = ReadingProgressRepositoryImpl(this),
        )

    private fun Database.contentRepository(contentDir: NovelContentDir): ChapterContentRepositoryImpl =
        ChapterContentRepositoryImpl(this, contentDir)

    /** The path a cached chapter's row still holds, or null once the row is gone. */
    private suspend fun Database.contentFilePathOf(chapterId: Long): String? =
        chapter_contentQueries.getByChapterId(chapterId).awaitAsOneOrNull()?.file_path

    private suspend fun Database.hasSavedPositionFor(chapterId: Long): Boolean =
        reading_progressQueries.getByChapterId(chapterId).awaitAsOneOrNull() != null

    private suspend fun Database.hasChapter(chapterId: Long): Boolean =
        chapterQueries.getChapterById(chapterId).awaitAsOneOrNull() != null

    private suspend fun Database.cacheContent(repository: ChapterContentRepositoryImpl, chapterId: Long): File =
        File(repository.save(chapterId, ChapterContent(listOf(ContentBlock.Paragraph("hola")))))

    @Test
    fun `a removed chapter takes its cached text and its saved position with it`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "removed-chapter-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val contentDir = NovelContentDir(File(tempDir, "content"))
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(chapterId)
            val contentRepository = ChapterContentRepositoryImpl(database, contentDir)
            val file = database.cacheContent(contentRepository, chapterId)
            ReadingProgressRepositoryImpl(database)
                .save(chapterId, ReadingProgress(2, 0, blockFraction = 0.5f, percent = 0.5f))
            assertEquals(setOf(chapterId), contentRepository.getCachedChapterIdsByMangaId(mangaId).first())

            removeChaptersFromSource(database.chapterRepository(contentDir), listOf(chapterId))

            // The chapter, its text and its position: the row, the file behind it, and the position that made
            // the chapter look part-read. Any one of the three surviving is the leak.
            assertFalse(database.hasChapter(chapterId))
            assertNull(database.contentFilePathOf(chapterId))
            assertFalse(file.exists())
            assertFalse(database.hasSavedPositionFor(chapterId))
            driver.close()
        }
    }

    @Test
    fun `removing a chapter that never had novel state fails on nothing`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "removed-plain-chapter-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val contentDir = NovelContentDir(File(tempDir, "content"))
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(chapterId)
            assertNull(database.contentFilePathOf(chapterId))
            assertFalse(database.hasSavedPositionFor(chapterId))

            removeChaptersFromSource(database.chapterRepository(contentDir), listOf(chapterId))

            // The overwhelming majority of removed chapters are images, so the cleanup has to be a no-op for
            // them rather than a query that finds nothing and a delete that throws.
            assertFalse(database.hasChapter(chapterId))
            assertNull(database.contentFilePathOf(chapterId))
            assertFalse(database.hasSavedPositionFor(chapterId))
            val cached = database.contentRepository(contentDir).getCachedChapterIdsByMangaId(mangaId).first()
            assertEquals(emptySet<Long>(), cached)
            driver.close()
        }
    }

    @Test
    fun `removing several chapters cleans up every one of them`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "removed-many-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val contentDir = NovelContentDir(File(tempDir, "content"))
            val contentRepository = ChapterContentRepositoryImpl(database, contentDir)
            val progressRepository = ReadingProgressRepositoryImpl(database)

            val firstChapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(firstChapterId)
            val secondChapterId = database.insertChapterOf(mangaId, "c2", "Capítulo 2")
            val thirdChapterId = database.insertChapterOf(mangaId, "c3", "Capítulo 3")

            val files = listOf(firstChapterId, secondChapterId, thirdChapterId)
                .associateWith { chapterId ->
                    database.cacheContent(contentRepository, chapterId).also { file ->
                        progressRepository.save(
                            chapterId,
                            ReadingProgress(1, 0, blockFraction = 0.25f, percent = 0.25f),
                        )
                        assertTrue(file.exists())
                    }
                }

            removeChaptersFromSource(database.chapterRepository(contentDir), listOf(firstChapterId, secondChapterId))

            listOf(firstChapterId, secondChapterId).forEach { chapterId ->
                assertFalse(database.hasChapter(chapterId), "chapter $chapterId survived")
                assertNull(database.contentFilePathOf(chapterId), "content row of $chapterId survived")
                assertFalse(files.getValue(chapterId).exists(), "content file of $chapterId survived")
                assertFalse(database.hasSavedPositionFor(chapterId), "saved position of $chapterId survived")
            }
            // And the one that was not removed keeps all three, so the cleanup is not a blanket wipe.
            assertTrue(database.hasChapter(thirdChapterId))
            assertNotNull(database.contentFilePathOf(thirdChapterId))
            assertTrue(files.getValue(thirdChapterId).exists())
            assertTrue(database.hasSavedPositionFor(thirdChapterId))
            driver.close()
        }
    }

    @Test
    fun `a chapter whose content file is already gone is still cleaned up`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "missing-file-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val contentDir = NovelContentDir(File(tempDir, "content"))
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val contentRepository = ChapterContentRepositoryImpl(database, contentDir)
            val file = database.cacheContent(contentRepository, chapterId)
            ReadingProgressRepositoryImpl(database)
                .save(chapterId, ReadingProgress(0, 0, blockFraction = 0f, percent = 0f))
            // The platform can reclaim the cache directory at any time, so the row can outlive its file.
            assertTrue(file.delete())
            assertFalse(file.exists())

            removeChaptersFromSource(database.chapterRepository(contentDir), listOf(chapterId))

            // The row has to go even though there is no file to delete, or it stays there forever pointing at
            // a chapter that no longer exists.
            assertFalse(database.hasChapter(chapterId))
            assertNull(database.contentFilePathOf(chapterId))
            assertFalse(database.hasSavedPositionFor(chapterId))
            driver.close()
        }
    }

    @Test
    fun `another entry's cached text survives another entry's removal`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "sibling-manga-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val contentDir = NovelContentDir(File(tempDir, "content"))
            val contentRepository = ChapterContentRepositoryImpl(database, contentDir)

            val removedChapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val removedMangaId = database.mangaIdOfChapter(removedChapterId)
            val otherChapterId = database.insertNovelAndChapter(remoteUrl = "n2", chapterUrl = "c1")
            val otherMangaId = database.mangaIdOfChapter(otherChapterId)
            val otherFile = database.cacheContent(contentRepository, otherChapterId)
            database.cacheContent(contentRepository, removedChapterId)

            removeChaptersFromSource(database.chapterRepository(contentDir), listOf(removedChapterId))

            // Two entries with the same chapter url, so only the id taken from the sync separates them.
            assertNotEquals(removedMangaId, otherMangaId)
            assertNull(database.contentFilePathOf(removedChapterId))
            assertEquals(
                setOf(otherChapterId),
                contentRepository.getCachedChapterIdsByMangaId(otherMangaId).first(),
            )
            assertTrue(otherFile.exists())
            driver.close()
        }
    }

    @Test
    fun `a sync that removes no chapter leaves the cached text alone`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "nothing-removed-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val contentDir = NovelContentDir(File(tempDir, "content"))
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(chapterId)
            val contentRepository = ChapterContentRepositoryImpl(database, contentDir)
            val file = database.cacheContent(contentRepository, chapterId)
            ReadingProgressRepositoryImpl(database)
                .save(chapterId, ReadingProgress(4, 0, blockFraction = 0.6f, percent = 0.6f))

            // The common sync: new chapters arrive, none go away.
            removeChaptersFromSource(database.chapterRepository(contentDir), emptyList())

            assertTrue(database.hasChapter(chapterId))
            assertNotNull(database.contentFilePathOf(chapterId))
            assertTrue(file.exists())
            assertTrue(database.hasSavedPositionFor(chapterId))
            assertEquals(setOf(chapterId), contentRepository.getCachedChapterIdsByMangaId(mangaId).first())
            driver.close()
        }
    }

    @Test
    fun `an image chapter reads as never having had novel state, before or after removal`(
        @TempDir tempDir: File,
    ) = runTest {
        val dbPath = File(tempDir, "image-chapter-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val contentDir = NovelContentDir(File(tempDir, "content"))
            val contentRepository = ChapterContentRepositoryImpl(database, contentDir)
            val progressRepository = ReadingProgressRepositoryImpl(database)

            // An image entry writes nothing on the novel side at all: no content, no position, no file.
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "chapter/1")
            val mangaId = database.mangaIdOfChapter(chapterId)
            assertEquals(emptySet<Long>(), contentRepository.getCachedChapterIdsByMangaId(mangaId).first())
            assertEquals(emptyMap<Long, Float>(), progressRepository.getPercentByMangaId(mangaId).first())

            removeChaptersFromSource(database.chapterRepository(contentDir), listOf(chapterId))

            // Nothing novel was there to remove, and the per-manga reads an image entry relies on still answer
            // the same thing afterwards.
            assertFalse(database.hasChapter(chapterId))
            assertEquals(emptySet<Long>(), contentRepository.getCachedChapterIdsByMangaId(mangaId).first())
            assertEquals(emptyMap<Long, Float>(), progressRepository.getPercentByMangaId(mangaId).first())
            driver.close()
        }
    }

    @Test
    fun `a sync that removes a chapter and adds another keeps the new one`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "sync-add-and-remove-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val contentDir = NovelContentDir(File(tempDir, "content"))
            val removedChapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(removedChapterId)
            val renamedChapterId = database.insertChapterOf(mangaId, "c2", "Capítulo 2")
            val contentRepository = ChapterContentRepositoryImpl(database, contentDir)
            val removedFile = database.cacheContent(contentRepository, removedChapterId)

            val stored = database.chapterRepository(contentDir).updateFromRemote(
                removedIds = listOf(removedChapterId),
                added = listOf(
                    SyncedChapter.create().copy(
                        mangaId = mangaId,
                        url = "c3",
                        name = "Capítulo 3",
                        chapterNumber = 3.0,
                        dateFetch = Clock.System.now(),
                    ),
                ),
                updated = listOf(
                    ChapterRemoteUpdate(
                        id = renamedChapterId,
                        name = "Capítulo dos",
                        scanlator = null,
                        chapterNumber = 2.0,
                        dateUpload = null,
                        sourceOrder = 1,
                        memo = emptyMemo,
                    ),
                ),
            )

            // All three halves of one sync: the chapter that went away is gone and cleaned up after, the one
            // that arrived is stored and returned, and the one that was renamed kept its row and its new name.
            assertEquals(1, stored.size)
            val addedChapterId = stored.single().id
            assertTrue(database.hasChapter(addedChapterId))
            assertNull(database.contentFilePathOf(removedChapterId))
            assertFalse(removedFile.exists())
            val renamed = database.chapterQueries.getChapterById(renamedChapterId).awaitAsOne()
            assertEquals("Capítulo dos", renamed.remote_name)
            driver.close()
        }
    }

    @Test
    fun `removing a chapter that is already gone fails on nothing`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "already-removed-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val contentDir = NovelContentDir(File(tempDir, "content"))
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(chapterId)
            val file = database.cacheContent(ChapterContentRepositoryImpl(database, contentDir), chapterId)

            // The chapter row can be gone while its novel state is not, which is exactly the state this cleanup
            // exists to heal. Cleaning up after an id that is no longer there has to be a no-op, not a throw.
            database.chapterQueries.removeChaptersWithIds(listOf(chapterId))
            assertFalse(database.hasChapter(chapterId))
            assertNotNull(database.contentFilePathOf(chapterId))
            assertTrue(file.exists())

            removeChaptersFromSource(database.chapterRepository(contentDir), listOf(chapterId))

            assertNull(database.contentFilePathOf(chapterId))
            assertFalse(file.exists())
            assertEquals(
                emptySet<Long>(),
                database.contentRepository(contentDir).getCachedChapterIdsByMangaId(mangaId).first(),
            )
            driver.close()
        }
    }

    // Gap 2: clearing a manga's downloads means clearing every cached chapter it has.

    @Test
    fun `clearing a manga's downloads takes every cached chapter's text with it`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "clear-manga-downloads-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val contentDir = NovelContentDir(File(tempDir, "content"))
            val repository = ChapterContentRepositoryImpl(database, contentDir)

            val firstChapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "c1")
            val mangaId = database.mangaIdOfChapter(firstChapterId)
            val secondChapterId = database.insertChapterOf(mangaId, "c2", "Capítulo 2")
            val firstFile = database.cacheContent(repository, firstChapterId)
            val secondFile = database.cacheContent(repository, secondChapterId)

            val otherChapterId = database.insertNovelAndChapter(remoteUrl = "n2", chapterUrl = "c1")
            val otherMangaId = database.mangaIdOfChapter(otherChapterId)
            val otherFile = database.cacheContent(repository, otherChapterId)

            // Exactly the read the manga screen asks before it offers the delete: which of this manga's
            // chapters have text on disk. Then the delete it runs for each of them.
            val cached = repository.getCachedChapterIdsByMangaId(mangaId).first()
            assertEquals(setOf(firstChapterId, secondChapterId), cached)
            cached.forEach { repository.delete(it) }

            assertEquals(emptySet<Long>(), repository.getCachedChapterIdsByMangaId(mangaId).first())
            listOf(firstChapterId, secondChapterId).forEach { chapterId ->
                assertNull(database.contentFilePathOf(chapterId))
            }
            assertFalse(firstFile.exists())
            assertFalse(secondFile.exists())
            // Another entry's text is none of this entry's business.
            assertEquals(setOf(otherChapterId), repository.getCachedChapterIdsByMangaId(otherMangaId).first())
            assertTrue(otherFile.exists())
            driver.close()
        }
    }

    @Test
    fun `an image entry has nothing to clear, and clearing it stays a no-op`(@TempDir tempDir: File) = runTest {
        val dbPath = File(tempDir, "clear-image-entry-test.db").absolutePath
        openDatabase(dbPath).let { (driver, database) ->
            val chapterId = database.insertNovelAndChapter(remoteUrl = "n1", chapterUrl = "chapter/1")
            val mangaId = database.mangaIdOfChapter(chapterId)
            val repository = ChapterContentRepositoryImpl(database, NovelContentDir(File(tempDir, "content")))

            // What the delete runs over, for an entry whose downloads are all pages in the image queue.
            val cached = repository.getCachedChapterIdsByMangaId(mangaId).first()
            assertEquals(emptySet<Long>(), cached)
            cached.forEach { repository.delete(it) }

            // The chapter row is the image queue's business and this loop must not have touched it.
            assertTrue(database.hasChapter(chapterId))
            assertNull(database.contentFilePathOf(chapterId))
            driver.close()
        }
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
