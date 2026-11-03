package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.SuspendingTransacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mihon.data.database.DatabaseRepositoryImpl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.Reading_progress
import tachiyomi.data.backup.RestoreRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.novel.ChapterContentRepositoryImpl
import tachiyomi.data.novel.NovelContentDir
import tachiyomi.data.novel.NovelMapper
import tachiyomi.data.novel.ReadingProgressRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.novel.repository.ReadingProgress
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.Novel

/** Physical Android SQLite and content-cache coverage; all paths belong to one test-owned root. */
@RunWith(AndroidJUnit4::class)
class NovelRestorePersistenceAndroidTest {
    private suspend fun <T : Any> ExecutableQuery<T>.awaitAsOneOrNull(): T? = execute { cursor ->
        QueryResult.AsyncValue {
            if (!cursor.next().await()) {
                null
            } else {
                val value = mapper(cursor)
                check(!cursor.next().await()) { "ResultSet returned more than 1 row for $this" }
                value
            }
        }
    }.await()

    private suspend fun <T : Any> ExecutableQuery<T>.awaitAsOne(): T =
        awaitAsOneOrNull() ?: throw NullPointerException("ResultSet returned null for $this")

    private val novel = Novel("fixture", "novel", "Fixture novel")
    private val manga = NovelMapper.toManga(novel)
    private val chapter = NovelMapper.toChapter(novel, NovelChapter("fixture", "novel", "chapter", "Chapter"))
    private val localContent = content("local sentinel")

    private fun content(sentinel: String) = ChapterContent(
        (0 until 50).map { ContentBlock.Paragraph("$sentinel block $it") },
    )

    // This is the frozen v1 encoding for the paragraph-only synthetic fixtures, not a storage substitute.
    private fun fingerprint(value: ChapterContent): String {
        val array = JsonArray(value.blocks.map { block ->
            require(block is ContentBlock.Paragraph)
            JsonObject(linkedMapOf(
                "type" to JsonPrimitive("app.novel.source.api.ContentBlock.Paragraph"),
                "text" to JsonPrimitive(block.text),
            ))
        })
        return "v1:sha256:" + sha(array.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class AuditingDriver(private val delegate: SqlDriver) :
        SqlDriver by delegate,
        SuspendingTransacter.TransactionDispatcher by (delegate as SuspendingTransacter.TransactionDispatcher) {
        private val progress = AtomicInteger()
        private val content = AtomicInteger()
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
            when (dml.find(sql)?.groupValues?.get(1)?.lowercase()) {
                "reading_progress" -> progress.incrementAndGet()
                "chapter_content" -> content.incrementAndGet()
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }

        fun clear() { progress.set(0); content.set(0) }
        fun counts() = progress.get() to content.get()
    }

    private class IsolatedContext(base: Context, private val db: File, private val cache: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File {
            check(name == "tachiyomi.db")
            return db
        }
        override fun getCacheDir(): File = cache
        override fun getExternalCacheDir(): File = cache
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("Persistent preferences forbidden")
    }

    private class Session(context: Context, root: File) {
        private lateinit var driver: AuditingDriver
        private val databaseRepository = DatabaseRepositoryImpl(lazy { driver })
        init {
            val real = DatabaseBindings.providesSqlDriver(context, databaseRepository)
            driver = AuditingDriver(real)
            Log.i("S28Restore", "phase=driver class=${real.javaClass.name}")
        }
        val database = DatabaseBindings.providesDatabase(driver)
        val content = ChapterContentRepositoryImpl(database, NovelContentDir(File(root, "content")))
        val progress = ReadingProgressRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database, content, progress)
        val manga = MangaRepositoryImpl(database)
        val tracks = TrackRepositoryImpl(database)
        val restore = RestoreRepositoryImpl(database, manga, chapters, tracks, content, progress)
        private var closed = false
        suspend fun open() = databaseRepository.awaitOpen()
        fun clearAudit() = driver.clear()
        fun counts() = driver.counts()
        fun close() {
            if (!closed) {
                driver.close()
                closed = true
            }
        }
    }

    private suspend fun Session.seed(index: Int, position: ReadingProgress?, root: File): Pair<Long, File> {
        val mangaId = database.mangaQueries.insertReturningId(
            sourceId = manga().source, remoteUrl = manga().url, remoteTitle = manga().title,
            remoteAuthor = manga().author, remoteArtist = manga().artist, remoteDescription = manga().description,
            remoteGenre = manga().genre, remoteStatus = manga().status, remoteCover = manga().thumbnailUrl,
            remoteUpdateStrategy = manga().updateStrategy, remoteMemo = manga().memo, userFavoriteAt = manga().favoriteAt,
            userNotes = manga().notes, userReaderFlags = manga().viewerFlags, userChapterFlags = manga().chapterFlags,
            stateChapterLastUpdate = manga().lastUpdate, stateChapterNextUpdate = manga().nextUpdate,
            stateChapterFetchInterval = manga().fetchInterval.toLong(), stateCoverLastModified = manga().coverLastModified,
            stateInitialized = manga().initialized,
        ).awaitAsOne()
        val id = database.chapterQueries.insertReturningId(
            mangaId = mangaId, remoteUrl = chapter.url, remoteName = chapter.name,
            remoteScanlator = chapter.scanlator, remoteChapterNumber = chapter.chapterNumber,
            remoteDateUpload = chapter.dateUpload, remoteOrder = chapter.sourceOrder, remoteMemo = chapter.memo,
            userRead = chapter.read, userBookmark = chapter.bookmark, userLastPageRead = index,
            stateDateFetch = chapter.dateFetch,
        ).awaitAsOne()
        assertEquals("fixture", NovelMapper.sourceId(requireNotNull(chapters.getChapterById(id))))
        val file = File(content.save(id, localContent))
        assertTrue(file.canonicalPath.startsWith(File(root, "content").canonicalPath + File.separator))
        assertTrue(file.isFile && file.length() > 0)
        if (position != null) progress.save(id, position)
        return id to file
    }

    // Avoid shadowing the fixture manga by Session.manga.
    private fun manga() = manga

    private suspend fun Session.restore(index: Int, fraction: Float, incoming: ChapterContent, fingerprint: String?) {
        val entry = RestoredManga(
            manga = manga(), chapters = listOf(chapter.copy(lastPageRead = index)),
            categoryIds = emptyList(), history = emptyList(), tracks = emptyList(), excludedScanlators = emptyList(),
            novelPositions = mapOf(chapter.url to fraction),
            novelContents = mapOf(chapter.url to Json.encodeToString(ChapterContent.serializer(), incoming)),
            novelContentFingerprints = if (fingerprint == null) emptyMap() else mapOf(chapter.url to fingerprint),
        )
        restore.restoreManga(listOf(entry)) { MangaUpdate(it.id) {} }
    }

    private suspend fun withRoot(case: String, action: suspend (File, File, Context, MutableList<Session>) -> Unit) {
        assertTrue("Emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(Process.myUid(), target.applicationInfo.uid)
        val cache = target.cacheDir.canonicalFile
        val root = File(cache, "s28-restore-${UUID.randomUUID()}").canonicalFile
        val db = File(root, "state/tachiyomi.db")
        assertEquals(cache, root.parentFile)
        assertNotEquals(target.getDatabasePath("tachiyomi.db").canonicalFile, db.canonicalFile)
        assertTrue(root.mkdir())
        val sessions = mutableListOf<Session>()
        try {
            assertTrue(db.parentFile.mkdir())
            val fixtureCache = File(root, "content")
            assertTrue(fixtureCache.mkdir())
            val context = IsolatedContext(target, db, fixtureCache)
            assertEquals(target.packageName, context.packageName)
            assertEquals(Process.myUid(), context.applicationInfo.uid)
            assertEquals(db.canonicalFile, context.applicationContext.getDatabasePath("tachiyomi.db").canonicalFile)
            Log.i("S28Restore", "case=$case phase=start root=$root db=$db")
            action(root, db, context, sessions)
        } finally {
            sessions.forEach { it.close() }
            deleteOwned(root, root, cache)
            Log.i("S28Restore", "case=$case phase=cleanup root_exists=${root.exists()}")
        }
    }

    private fun deleteOwned(file: File, root: File, cache: File) {
        check(root.absoluteFile == root.canonicalFile && root.parentFile == cache && root.name.startsWith("s28-restore-"))
        check(file.absoluteFile == file.canonicalFile)
        check(file == root || file.path.startsWith(root.path + File.separator))
        if (!file.exists()) return
        if (file.isDirectory) checkNotNull(file.listFiles()).forEach { deleteOwned(it, root, cache) }
        check(file.delete())
    }

    private suspend fun open(context: Context, root: File, sessions: MutableList<Session>): Session {
        val session = Session(context, root)
        sessions.add(session)
        session.open()
        return session
    }

    private suspend fun verify(
        context: Context, root: File, db: File, sessions: MutableList<Session>, id: Long, file: File,
        expectedContent: ChapterContent, expected: ReadingProgress?, index: Int, bytes: ByteArray,
        row: Reading_progress? = null, checkRow: Boolean = false,
    ) {
        assertTrue(db.isFile && db.length() > 0)
        assertTrue(file.isFile && file.length() > 0)
        assertArrayEquals(bytes, file.readBytes())
        val reopened = open(context, root, sessions)
        assertEquals(expectedContent, reopened.content.get(id))
        assertEquals(expected, reopened.progress.get(id))
        assertEquals(index, requireNotNull(reopened.chapters.getChapterById(id)).lastPageRead)
        if (checkRow) assertEquals(row, reopened.database.reading_progressQueries.getByChapterId(id).awaitAsOneOrNull())
        Log.i("S28Restore", "phase=reopen db_length=${db.length()} cache_sha=${sha(file.readBytes())}")
        reopened.close()
    }

    @Test
    fun directPositionAndContentSurviveReopen() = runBlocking {
        withRoot("direct", action = { root, db, context, sessions ->
            val expected = ReadingProgress(12, 137, 0.25f, 12.25f / 50f)
            val session = open(context, root, sessions)
            val (id, file) = session.seed(12, expected, root)
            val bytes = file.readBytes()
            session.close()
            verify(context, root, db, sessions, id, file, localContent, expected, 12, bytes)
        })
    }

    private suspend fun scenario(
        case: String, local: ReadingProgress?, backupIndex: Int, backupFraction: Float,
        incoming: ChapterContent, fingerprint: String?, expected: ReadingProgress?, expectedIndex: Int,
        auditStart: Boolean = false,
    ) = withRoot(case) { root, db, context, sessions ->
        val session = open(context, root, sessions)
        val (id, file) = session.seed(local?.blockIndex ?: 0, local, root)
        val bytes = file.readBytes()
        if (incoming != localContent) assertNotEquals(bytes.toList(), Json.encodeToString(ChapterContent.serializer(), incoming).toByteArray().toList())
        val row = session.database.reading_progressQueries.getByChapterId(id).awaitAsOneOrNull()
        session.clearAudit()
        session.restore(backupIndex, backupFraction, incoming, fingerprint)
        val counts = session.counts()
        Log.i("S28Restore", "case=$case phase=restore dml_progress=${counts.first} dml_content=${counts.second} cache_sha=${sha(file.readBytes())}")
        if (auditStart) {
            assertEquals(0, counts.first)
            assertEquals(0, counts.second)
            assertArrayEquals(bytes, file.readBytes())
        }
        session.close()
        verify(context, root, db, sessions, id, file, localContent, expected, expectedIndex, bytes, row, auditStart)
    }

    @Test
    fun incompatibleFingerprintPreservesLocal() = runBlocking {
        val incoming = content("incompatible backup sentinel")
        assertNotEquals(fingerprint(localContent), fingerprint(incoming))
        val local = ReadingProgress(20, 91, 0.2f, 20.2f / 50f)
        scenario("incompatible", local, 20, 0.8f, incoming, fingerprint(incoming), local, 20)
    }

    @Test
    fun legacyBackupPreservesLocal() = runBlocking {
        val local = ReadingProgress(40, 92, 0.2f, 40.2f / 50f)
        scenario("legacy", local, 10, 0.8f, content("legacy backup sentinel"), null, local, 40)
    }

    @Test
    fun compatibleLocalWins() = runBlocking {
        val local = ReadingProgress(40, 93, 0.2f, 40.2f / 50f)
        scenario("localWins", local, 10, 0.8f, localContent, fingerprint(localContent), local, 40)
    }

    @Test
    fun compatibleBackupWins() = runBlocking {
        val local = ReadingProgress(10, 94, 0.2f, 10.2f / 50f)
        val expected = ReadingProgress(40, 0, 0.8f, 40.8f / 50f)
        scenario("backupWins", local, 40, 0.8f, localContent, fingerprint(localContent), expected, 40)
    }

    @Test
    fun startWithExistingProgressDoesNotWrite() = runBlocking {
        val start = ReadingProgress(0, 0, 0f, 0f)
        scenario("startExisting", start, 0, 0f, localContent, fingerprint(localContent), start, 0, true)
    }

    @Test
    fun startWithAbsentProgressDoesNotWrite() = runBlocking {
        scenario("startAbsent", null, 0, 0f, localContent, fingerprint(localContent), null, 0, true)
    }

    private suspend fun savesContent(
        case: String, local: ReadingProgress, backupIndex: Int, backupFraction: Float,
        incoming: ChapterContent, prepare: (File) -> Unit = {},
    ) = withRoot(case) { root, db, context, sessions ->
        val session = open(context, root, sessions)
        val (id, file) = session.seed(local.blockIndex, local, root)
        prepare(file)
        assertTrue(session.database.chapter_contentQueries.getByChapterId(id).awaitAsOneOrNull() != null)
        val row = session.database.reading_progressQueries.getByChapterId(id).awaitAsOneOrNull()
        session.clearAudit()
        session.restore(backupIndex, backupFraction, incoming, fingerprint(incoming))
        val counts = session.counts()
        Log.i("S28Restore", "case=$case phase=restore dml_progress=${counts.first} dml_content=${counts.second} cache_sha=${sha(file.readBytes())}")
        assertEquals(0, counts.first)
        assertEquals(1, counts.second)
        session.close()
        val bytes = Json.encodeToString(ChapterContent.serializer(), incoming).toByteArray(Charsets.UTF_8)
        verify(context, root, db, sessions, id, file, incoming, local, local.blockIndex, bytes, row, true)
    }

    @Test
    fun startWithDifferentIncomingUpdatesCache() = runBlocking {
        val incoming = content("different START backup")
        assertNotEquals(localContent, incoming)
        savesContent("different", ReadingProgress(0, 0, 0f, 0f), 0, 0f, incoming)
    }

    @Test
    fun startWithMissingCacheRecreatesFile() = runBlocking {
        savesContent("missing", ReadingProgress(0, 0, 0f, 0f), 0, 0f, localContent) {
            assertTrue(it.delete())
            assertTrue(!it.exists())
        }
    }

    @Test
    fun startWithCorruptCacheRepairsFile() = runBlocking {
        savesContent("corrupt", ReadingProgress(0, 0, 0f, 0f), 0, 0f, localContent) { it.writeText("{invalid JSON") }
    }

    @Test
    fun nonStartMatchingLocalRetainsCacheSave() = runBlocking {
        savesContent("nonStart", ReadingProgress(40, 93, 0.2f, 40.2f / 50f), 10, 0.8f, localContent)
    }
}
