package tachiyomi.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import tachiyomi.data.backup.ContentFingerprint
import tachiyomi.data.backup.RestoreRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.novel.repository.ReadingProgress
import java.io.File
import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.Novel as Novel

class NovelPersistenceHostTest {
    private val novel = Novel("fixture", "novel", "Fixture novel")
    private val manga = NovelMapper.toManga(novel)
    private val chapter = NovelMapper.toChapter(novel, NovelChapter("fixture", "novel", "chapter", "Chapter"))
    private val localContent = content("local sentinel")

    private fun content(sentinel: String) = ChapterContent(
        (0 until 50).map { ContentBlock.Paragraph("$sentinel block $it") },
    )

    /** Delegate every operation to JDBC; observe only SQL statements directed at the two novel tables. */
    private class AuditingDriver(private val jdbc: SqlDriver) : SqlDriver by jdbc {
        val mutations = mutableMapOf<String, Int>()
        private val dml = Regex(
            "^\\s*(?:INSERT\\s+(?:OR\\s+\\w+\\s+)?INTO|REPLACE\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+[`\"\\[]?(reading_progress|chapter_content)\\b",
            RegexOption.IGNORE_CASE,
        )

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            dml.find(sql)?.groupValues?.get(1)?.lowercase()?.let { table ->
                mutations[table] = (mutations[table] ?: 0) + 1
            }
            return jdbc.execute(identifier, sql, parameters, binders)
        }
    }

    private class Fixture(val driver: AuditingDriver, val database: Database, dir: File) {
        val content = ChapterContentRepositoryImpl(database, NovelContentDir(File(dir, "content")))
        val progress = ReadingProgressRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database, content, progress)
        val mangaRepository = MangaRepositoryImpl(database)
        val tracks = TrackRepositoryImpl(database)
        val restore = RestoreRepositoryImpl(database, mangaRepository, chapters, tracks, content, progress)
    }

    private suspend fun open(dir: File): Fixture {
        val path = File(dir, "novel.db")
        val isNew = !path.exists()
        val driver = AuditingDriver(JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}"))
        try {
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
                manga_trackAdapter = Manga_track.Adapter(start_dateAdapter = InstantColumnAdapter, finish_dateAdapter = InstantColumnAdapter),
                reading_progressAdapter = Reading_progress.Adapter(
                    block_indexAdapter = IntColumnAdapter,
                    block_offset_pxAdapter = IntColumnAdapter,
                    state_date_updateAdapter = InstantColumnAdapter,
                ),
            )
            if (isNew) (Database.Schema.create(driver) as QueryResult.AsyncValue<Unit>).await()
            return Fixture(driver, database, dir)
        } catch (e: Throwable) {
            driver.close()
            throw e
        }
    }

    private suspend fun Fixture.seed(index: Int, position: ReadingProgress?, dir: File): Pair<Long, File> {
        val mangaId = database.mangaQueries.insertReturningId(
            sourceId = manga.source, remoteUrl = manga.url, remoteTitle = manga.title,
            remoteAuthor = manga.author, remoteArtist = manga.artist, remoteDescription = manga.description,
            remoteGenre = manga.genre, remoteStatus = manga.status, remoteCover = manga.thumbnailUrl,
            remoteUpdateStrategy = manga.updateStrategy, remoteMemo = manga.memo, userFavoriteAt = manga.favoriteAt,
            userNotes = manga.notes, userReaderFlags = manga.viewerFlags, userChapterFlags = manga.chapterFlags,
            stateChapterLastUpdate = manga.lastUpdate, stateChapterNextUpdate = manga.nextUpdate,
            stateChapterFetchInterval = manga.fetchInterval.toLong(), stateCoverLastModified = manga.coverLastModified,
            stateInitialized = manga.initialized,
        ).awaitAsOne()
        val chapterId = database.chapterQueries.insertReturningId(
            mangaId = mangaId, remoteUrl = chapter.url, remoteName = chapter.name,
            remoteScanlator = chapter.scanlator, remoteChapterNumber = chapter.chapterNumber,
            remoteDateUpload = chapter.dateUpload, remoteOrder = chapter.sourceOrder, remoteMemo = chapter.memo,
            userRead = chapter.read, userBookmark = chapter.bookmark, userLastPageRead = index,
            stateDateFetch = chapter.dateFetch,
        ).awaitAsOne()
        assertEquals("fixture", NovelMapper.sourceId(requireNotNull(chapters.getChapterById(chapterId))))
        val file = File(content.save(chapterId, localContent))
        assertTrue(file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath()))
        assertTrue(file.isFile)
        if (position != null) progress.save(chapterId, position)
        return chapterId to file
    }

    private suspend fun Fixture.restore(index: Int, fraction: Float, incoming: ChapterContent, fingerprint: String?) {
        val entry = RestoredManga(
            manga = manga,
            chapters = listOf(chapter.copy(lastPageRead = index)),
            categoryIds = emptyList(), history = emptyList(), tracks = emptyList(), excludedScanlators = emptyList(),
            novelPositions = mapOf(chapter.url to fraction),
            novelContents = mapOf(chapter.url to Json.encodeToString(ChapterContent.serializer(), incoming)),
            novelContentFingerprints = if (fingerprint == null) emptyMap() else mapOf(chapter.url to fingerprint),
        )
        restore.restoreManga(listOf(entry)) { MangaUpdate(it.id) {} }
    }

    private suspend fun verify(
        dir: File,
        chapterId: Long,
        file: File,
        expectedContent: ChapterContent,
        expectedProgress: ReadingProgress?,
        expectedIndex: Int,
        expectedBytes: ByteArray,
        expectedRow: Reading_progress? = null,
        checkRow: Boolean = false,
    ) {
        val db = File(dir, "novel.db")
        assertTrue(db.isFile && db.length() > 0)
        assertTrue(file.isFile)
        assertArrayEquals(expectedBytes, file.readBytes())
        val reopened = open(dir)
        try {
            assertEquals(expectedContent, reopened.content.get(chapterId))
            assertEquals(expectedProgress, reopened.progress.get(chapterId))
            assertEquals(expectedIndex, requireNotNull(reopened.chapters.getChapterById(chapterId)).lastPageRead)
            if (checkRow) assertEquals(expectedRow, reopened.database.reading_progressQueries.getByChapterId(chapterId).awaitAsOneOrNull())
        } finally {
            reopened.driver.close()
        }
    }

    @Test
    fun `file backed content and complete position survive driver reopen`(@TempDir dir: File) = runTest {
        val expected = ReadingProgress(12, 137, 0.25f, 12.25f / 50f)
        val fixture = open(dir)
        val chapterId: Long
        val file: File
        val bytes: ByteArray
        try {
            val seed = fixture.seed(12, expected, dir)
            chapterId = seed.first
            file = seed.second
            bytes = file.readBytes()
        } finally {
            fixture.driver.close()
        }
        verify(dir, chapterId, file, localContent, expected, 12, bytes)
    }

    private suspend fun scenario(
        dir: File,
        local: ReadingProgress?,
        backupIndex: Int,
        backupFraction: Float,
        incoming: ChapterContent,
        fingerprint: String?,
        expected: ReadingProgress?,
        expectedIndex: Int,
        auditStart: Boolean = false,
    ) {
        val fixture = open(dir)
        val chapterId: Long
        val file: File
        val expectedBytes: ByteArray
        var row: Reading_progress? = null
        var progressMutations = 0
        var contentMutations = 0
        var bytesAfterRestore = byteArrayOf()
        try {
            val seed = fixture.seed(local?.blockIndex ?: 0, local, dir)
            chapterId = seed.first
            file = seed.second
            val localBytes = file.readBytes()
            val incomingBytes = Json.encodeToString(ChapterContent.serializer(), incoming).toByteArray(Charsets.UTF_8)
            if (incoming != localContent) assertNotEquals(localBytes.toList(), incomingBytes.toList())
            expectedBytes = if (expectedIndex == backupIndex && incoming == localContent) incomingBytes else localBytes
            row = fixture.database.reading_progressQueries.getByChapterId(chapterId).awaitAsOneOrNull()
            fixture.driver.mutations.clear()
            fixture.restore(backupIndex, backupFraction, incoming, fingerprint)
            if (auditStart) {
                progressMutations = fixture.driver.mutations["reading_progress"] ?: 0
                contentMutations = fixture.driver.mutations["chapter_content"] ?: 0
                bytesAfterRestore = file.readBytes()
            }
        } finally {
            fixture.driver.close()
        }
        verify(dir, chapterId, file, localContent, expected, expectedIndex, expectedBytes, row, auditStart)
        if (auditStart) {
            assertArrayEquals(expectedBytes, bytesAfterRestore)
            assertEquals(0, progressMutations, "START must not rewrite progress")
            assertEquals(0, contentMutations, "identical cached content must not be rewritten")
        }
    }

    @Test
    fun `incompatible fingerprint preserves local physical content and position`(@TempDir dir: File) = runTest {
        val incoming = content("incompatible backup sentinel")
        assertNotEquals(ContentFingerprint.of(localContent), ContentFingerprint.of(incoming))
        val local = ReadingProgress(20, 91, 0.2f, 20.2f / 50f)
        scenario(dir, local, 20, 0.8f, incoming, ContentFingerprint.of(incoming), local, 20)
    }

    @Test
    fun `legacy backup preserves local physical content and position`(@TempDir dir: File) = runTest {
        val local = ReadingProgress(40, 92, 0.2f, 40.2f / 50f)
        scenario(dir, local, 10, 0.8f, content("legacy backup sentinel"), null, local, 40)
    }

    @Test
    fun `matching backup retains advanced local position and content`(@TempDir dir: File) = runTest {
        val local = ReadingProgress(40, 93, 0.2f, 40.2f / 50f)
        scenario(dir, local, 10, 0.8f, localContent, ContentFingerprint.of(localContent), local, 40)
    }

    @Test
    fun `matching backup advances coherent position and content`(@TempDir dir: File) = runTest {
        val local = ReadingProgress(10, 94, 0.2f, 10.2f / 50f)
        val expected = ReadingProgress(40, 0, 0.8f, 40.8f / 50f)
        scenario(dir, local, 40, 0.8f, localContent, ContentFingerprint.of(localContent), expected, 40)
    }

    @Test
    fun `START on existing START performs no progress or identical content DML`(@TempDir dir: File) = runTest {
        val start = ReadingProgress(0, 0, 0f, 0f)
        scenario(dir, start, 0, 0f, localContent, ContentFingerprint.of(localContent), start, 0, auditStart = true)
    }

    @Test
    fun `START with no progress performs no progress or identical content DML`(@TempDir dir: File) = runTest {
        scenario(dir, null, 0, 0f, localContent, ContentFingerprint.of(localContent), null, 0, auditStart = true)
    }

    private suspend fun preservesPositionWhileSavingContent(
        dir: File,
        local: ReadingProgress,
        backupIndex: Int,
        backupFraction: Float,
        incoming: ChapterContent,
        prepareFile: (File) -> Unit = {},
    ) {
        val fixture = open(dir)
        val chapterId: Long
        val file: File
        val progressRow: Reading_progress?
        val contentMutations: Int
        val progressMutations: Int
        val expectedBytes = Json.encodeToString(ChapterContent.serializer(), incoming).toByteArray(Charsets.UTF_8)
        try {
            val seed = fixture.seed(local.blockIndex, local, dir)
            chapterId = seed.first
            file = seed.second
            assertTrue(file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath()))
            prepareFile(file)
            assertTrue(fixture.database.chapter_contentQueries.getByChapterId(chapterId).awaitAsOneOrNull() != null)
            progressRow = fixture.database.reading_progressQueries.getByChapterId(chapterId).awaitAsOneOrNull()
            fixture.driver.mutations.clear()
            fixture.restore(backupIndex, backupFraction, incoming, ContentFingerprint.of(incoming))
            contentMutations = fixture.driver.mutations["chapter_content"] ?: 0
            progressMutations = fixture.driver.mutations["reading_progress"] ?: 0
        } finally {
            fixture.driver.close()
        }
        verify(dir, chapterId, file, incoming, local, local.blockIndex, expectedBytes, progressRow, checkRow = true)
        assertEquals(1, contentMutations, "incoming content must be saved")
        assertEquals(0, progressMutations, "unchanged progress must not be rewritten")
    }

    @Test
    fun `START with different incoming content updates the physical cache`(@TempDir dir: File) = runTest {
        val incoming = content("different START backup")
        assertNotEquals(localContent, incoming)
        preservesPositionWhileSavingContent(dir, ReadingProgress(0, 0, 0f, 0f), 0, 0f, incoming)
    }

    @Test
    fun `START with missing cache file recreates it`(@TempDir dir: File) = runTest {
        preservesPositionWhileSavingContent(dir, ReadingProgress(0, 0, 0f, 0f), 0, 0f, localContent) { file ->
            assertTrue(file.delete())
            assertTrue(!file.exists())
        }
    }

    @Test
    fun `START with corrupt cache file repairs it`(@TempDir dir: File) = runTest {
        preservesPositionWhileSavingContent(dir, ReadingProgress(0, 0, 0f, 0f), 0, 0f, localContent) { file ->
            file.writeText("{invalid JSON")
        }
    }

    @Test
    fun `matching content with advanced local position retains cache save behavior`(@TempDir dir: File) = runTest {
        val local = ReadingProgress(40, 93, 0.2f, 40.2f / 50f)
        preservesPositionWhileSavingContent(dir, local, 10, 0.8f, localContent)
    }
}
