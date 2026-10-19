package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.database.DatabaseRepositoryImpl
import mihon.data.extension.repository.ExtensionStoreRepositoryImpl
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.interactor.GetExtensionStores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.backup.RestoreRepositoryImpl
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.novel.ChapterContentRepositoryImpl
import tachiyomi.data.novel.NovelContentDir
import tachiyomi.data.novel.NovelMapper
import tachiyomi.data.novel.ReadingProgressRepositoryImpl
import tachiyomi.data.source.StubSourceRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.File
import java.util.UUID
import kotlin.time.Instant
import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.Novel as NovelEntry

/**
 * Emulator-only production-file backup/restore proof, with no app graph or persistent app preferences.
 * Required: library membership, chapter identities/read flags, logical position (block index/fraction),
 * and history timestamp/duration survive a close/reopen. Generated primary keys are not compared.
 * Downloads are not seeded or required. Cached prose and derived progress percent are observed only;
 * neither cache restoration nor its absence is a pass condition.
 *
 * Instrumentation executes as the target app UID, not the test APK UID. Its test cache here is an
 * owned UUID subdirectory of targetContext.cacheDir, writable by the executing process. Nothing
 * outside that UUID directory is deleted. The real app database and persistent preferences are not
 * written; production restore notifications may appear under the target app's valid UID/package.
 *
 * After installing the matching app/test APKs on an emulator, invoke the runner directly:
 * ```
 * adb -e shell am instrument -w -e clearPackageData false -e class \
 *   eu.kanade.tachiyomi.data.backup.NovelBackupRestoreIntegrationTest \
 *   app.daimei.dev.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * Do not use connected tests or the orchestrator: package data must not be cleared. The negative
 * control is the same invocation with `-e skip_restore true` before the runner component. It must
 * FAIL the same persisted-state comparison, not pass by expecting an exception. No other phase is
 * skipped. The owner separately compares emulator app preferences before/after, including
 * downloadOnlyOverWifi.
 */
@RunWith(AndroidJUnit4::class)
class NovelBackupRestoreIntegrationTest {

    @Test
    fun libraryChapterPositionsAndHistorySurviveProductionBackupAndRestore() = runBlocking {
        assertTrue("Refusing to run outside an Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        assertEquals("Context must belong to the executing UID", Process.myUid(), targetContext.applicationInfo.uid)
        val testCache = targetContext.cacheDir.canonicalFile
        val root = File(testCache, ROOT_PREFIX + UUID.randomUUID()).canonicalFile
        val stateDir = File(root, "state")
        val databaseFile = File(stateDir, "tachiyomi.db")
        val appDatabase = targetContext.getDatabasePath("tachiyomi.db").canonicalFile
        assertNotEquals("Test database must never be the app database", appDatabase, databaseFile.canonicalFile)
        assertEquals("Owned UUID root must be a direct child of the process cache", testCache, root.parentFile)
        assertTrue("Test database must be below the owned UUID root", databaseFile.canonicalPath.startsWith(root.path + File.separator))
        assertTrue("Test database must be below the test cache", databaseFile.canonicalPath.startsWith(testCache.path + File.separator))
        assertTrue("Unique test directory must not already exist", root.mkdir())
        var session: Session? = null
        try {
            assertTrue(stateDir.mkdir())
            val cacheDir = File(root, "cache").also { assertTrue(it.mkdir()) }
            val storageDir = File(root, "storage").also { assertTrue(it.mkdir()) }
            val context = IsolatedContext(targetContext, databaseFile, cacheDir)
            assertEquals(targetContext.packageName, context.packageName)
            assertEquals("Notifier context must belong to the executing UID", Process.myUid(), context.applicationInfo.uid)
            assertEquals(databaseFile.canonicalFile, context.applicationContext.getDatabasePath("tachiyomi.db").canonicalFile)
            gate("phase=isolation distinct=true path=${databaseFile.canonicalPath} hardware=${Build.HARDWARE}")

            val seeded = Session(context, stateDir)
            session = seeded
            seeded.open()
            assertTrue("Production driver must create a real file", databaseFile.isFile)
            val originalMangaId = seed(seeded)
            val originalChapters = seeded.chapters.getChapterByMangaId(originalMangaId, false)
            val originalChapterIds = originalChapters.map { it.id }
            val expected = snapshot(seeded)
            assertEquals(1, expected.size)
            assertTrue(expected.single().favorite)
            assertEquals(2, expected.single().chapters.size)
            assertEquals(setOf(false, true), expected.single().chapters.values.map { it.read }.toSet())
            assertTrue(expected.single().chapters.values.all { it.position != null && it.position.blockIndex > 0 && it.position.fraction > 0f })
            assertEquals(2, expected.single().history.size)
            assertTrue(expected.single().history.values.all { it.readAt != null && it.duration > 0L })
            gate("phase=seed library=1 chapters=2 history=2 logical_positions=2")
            observeCache(seeded, "seed")

            val backupFile = File(root, "novel.tachibk")
            assertTrue(backupFile.createNewFile())
            val backupUri = Uri.parse(creator(seeded, context).backup(Uri.fromFile(backupFile), BACKUP_OPTIONS))
            assertEquals(backupFile.canonicalFile, File(checkNotNull(backupUri.path)).canonicalFile)
            backupFile.inputStream().use {
                assertEquals("Production backup must be gzip", 0x1f, it.read())
                assertEquals("Production backup must be gzip", 0x8b, it.read())
            }
            val decoded = BackupDecoder(context, ProtoBuf).decode(backupUri)
            assertEquals(1, decoded.backupManga.size)
            assertEquals(2, decoded.backupManga.single().chapters.size)
            assertEquals(2, decoded.backupManga.single().history.size)
            assertTrue(decoded.backupManga.single().tracking.isEmpty())
            gate("phase=backup bytes=${backupFile.length()} tracking=0")

            // A fresh lifetime at the SAME path is the deletion method. This group contains only
            // this fixture's SQLite file/WAL/SHM and prose; the backup is its sibling, not a child.
            seeded.close()
            session = null
            deleteOwned(stateDir, root, testCache)
            assertFalse(stateDir.exists())
            assertTrue(stateDir.mkdir())
            val empty = Session(context, stateDir)
            session = empty
            empty.open()
            assertTrue(empty.manga.getFavorites().isEmpty())
            assertNull(empty.manga.getMangaByUrlAndSourceId(NOVEL_URL, SOURCE_ID))
            assertTrue(empty.chapters.getChapterByMangaId(originalMangaId, false).isEmpty())
            assertTrue(empty.history.getHistoryByMangaId(originalMangaId).isEmpty())
            assertEquals(0L, empty.history.getTotalReadDuration())
            originalChapterIds.forEach { id ->
                assertNull(empty.chapters.getChapterById(id))
                assertNull(empty.progress.get(id))
            }
            assertTrue(empty.progress.getPercentByMangaId(originalMangaId).first().isEmpty())
            assertTrue(empty.content.getCachedChapterIdsByMangaId(originalMangaId).first().isEmpty())
            gate("phase=empty library=0 chapters=0 history=0 logical_positions=0")
            observeCache(empty, "empty")

            val skipRestore = (InstrumentationRegistry.getArguments().getString("skip_restore") ?: "false").toBooleanStrict()
            if (!skipRestore) {
                restorer(empty, context, storageDir).restore(backupUri, RESTORE_OPTIONS)
            }
            gate("phase=restore skipped=$skipRestore")
            observeCache(empty, "after_restore")
            empty.close()
            session = null

            val reopened = Session(context, stateDir)
            session = reopened
            reopened.open()
            gate("phase=reopened real_file=${databaseFile.isFile}")
            observeCache(reopened, "reopened")
            // The negative control reaches this exact comparison with an empty persisted library.
            assertEquals("Persisted library/chapters/logical positions/history must match the pre-backup state", expected, snapshot(reopened))
            gate("phase=compare matches=true")
        } finally {
            withContext(NonCancellable) {
                // Never remove files if closing a live session fails.
                session?.close()
                deleteOwned(root, root, testCache)
                gate("phase=cleanup root_exists=${root.exists()}")
            }
        }
    }

    private suspend fun seed(session: Session): Long {
        val novel = NovelEntry(sourceId = SOURCE_KEY, id = NOVEL_URL, title = "The Clockwork Orchard")
        val manga = session.manga.insertNetworkManga(
            listOf(NovelMapper.toManga(novel).copy(favoriteAt = Instant.fromEpochMilliseconds(1_700_000_000_000))),
        ).single()
        val chapters = session.chapters.updateFromRemote(
            removedIds = emptyList(),
            added = (1..2).map { number ->
                NovelMapper.toChapter(
                    novel,
                    NovelChapter(sourceId = SOURCE_KEY, novelId = NOVEL_URL, id = "chapter-$number", title = "Chapter $number", number = number.toFloat()),
                    sourceOrder = number.toLong(),
                ).copy(mangaId = manga.id, read = number == 1, lastPageRead = number + 1)
            },
            updated = emptyList(),
        )
        assertEquals(2, chapters.size)
        chapters.forEachIndexed { index, chapter ->
            session.progress.save(
                chapter.id,
                ReadingProgress(blockIndex = index + 2, blockOffsetPx = 0, blockFraction = 0.375f, percent = (index + 2.375f) / 8f),
            )
            session.history.upsertHistory(
                HistoryUpdate(chapter.id, Instant.fromEpochMilliseconds(1_700_000_010_000 + index * 1_000L), 12_345L + index),
            )
            // Synthetic prose is confined to this fixture; its return is not an assertion.
            session.content.save(chapter.id, ChapterContent(List(8) { ContentBlock.Paragraph("Invented chapter ${index + 1}, paragraph $it.") }))
        }
        return manga.id
    }

    private suspend fun snapshot(session: Session): List<BookState> = session.manga.getFavorites().map { manga ->
        val chapters = session.chapters.getChapterByMangaId(manga.id, false)
        val urlsById = chapters.associate { it.id to it.url }
        BookState(
            source = manga.source,
            url = manga.url,
            title = manga.title,
            favorite = manga.favorite,
            favoriteAt = manga.favoriteAt,
            chapters = chapters.associate { chapter ->
                val progress = session.progress.get(chapter.id)
                chapter.url to ChapterState(
                    chapter.name, chapter.read, chapter.lastPageRead,
                    progress?.let { Position(it.blockIndex, it.blockOffsetPx, it.blockFraction) },
                )
            },
            history = session.history.getHistoryByMangaId(manga.id).associate {
                checkNotNull(urlsById[it.chapterId]) to HistoryState(it.readAt, it.readDuration)
            },
        )
    }.sortedWith(compareBy(BookState::source, BookState::url))

    private suspend fun observeCache(session: Session, phase: String) {
        val manga = session.manga.getMangaByUrlAndSourceId(NOVEL_URL, SOURCE_ID)
        val ids = manga?.let { session.content.getCachedChapterIdsByMangaId(it.id).first() }.orEmpty()
        var decodable = 0
        ids.forEach { if (session.content.get(it) != null) decodable++ }
        val percents = manga?.let {
            session.chapters.getChapterByMangaId(it.id, false).sortedBy { chapter -> chapter.url }.map { chapter ->
                "${chapter.url}:${session.progress.get(chapter.id)?.percent}"
            }
        }.orEmpty()
        gate("phase=cache observation=$phase cached_rows=${ids.size} decodable=$decodable derived_percent=$percents downloads=not_seeded")
    }

    private fun creator(session: Session, context: Context): BackupCreator {
        val decoder = BackupDecoder(context, ProtoBuf)
        return BackupCreator(
            isAutoBackup = false,
            context = context,
            parser = ProtoBuf,
            getFavorites = GetFavorites(session.manga),
            backupPreferences = BackupPreferences(session.preferences),
            mangaRepository = session.manga,
            categoriesBackupCreator = CategoriesBackupCreator(session.getCategories),
            mangaBackupCreator = MangaBackupCreator(session.manga, session.chapters, session.tracks, session.getCategories, GetHistory(session.history), session.progress, session.content),
            preferenceBackupCreator = PreferenceBackupCreator(session.sources, session.preferences),
            extensionStoresBackupCreator = ExtensionStoresBackupCreator(GetExtensionStores(session.extensionStores(context))),
            sourcesBackupCreator = SourcesBackupCreator(session.sources),
            backupFileValidator = BackupFileValidator(session.sources, lazy { error("Tracking must remain disabled") }, decoder),
        )
    }

    private fun restorer(session: Session, context: Context, storageDir: File): BackupRestorer {
        val folder = object : FolderProvider {
            override fun directory() = storageDir
            override fun path() = Uri.fromFile(storageDir).toString()
        }
        val libraryPreferences = LibraryPreferences(session.preferences)
        val storage = StorageManager(session.scope, context, StoragePreferences(folder, session.preferences))
        val downloads = DownloadCache(session.scope, context, DownloadProvider(context, storage, libraryPreferences), session.sources, storage)
        return BackupRestorer(
            notifier = BackupNotifier(context, SecurityPreferences(session.preferences)),
            isSync = false,
            context = context,
            downloadCache = downloads,
            categoriesRestorer = CategoriesRestorer(session.categories, session.getCategories, libraryPreferences),
            preferenceRestorer = PreferenceRestorer(context, session.getCategories, session.preferences),
            extensionStoreRestorer = ExtensionStoreRestorer(session.extensionStores(context)),
            mangaRestorer = MangaRestorer(
                RestoreRepositoryImpl(session.database, session.manga, session.chapters, session.tracks, session.content, session.progress),
                session.getCategories,
                FetchInterval(GetChaptersByMangaId(session.chapters)),
            ),
            backupDecoder = BackupDecoder(context, ProtoBuf),
            sourceManager = session.sources,
            stubSourceRepository = session.stubSources,
        )
    }

    private class Session(context: Context, stateDir: File) {
        private lateinit var driver: SqlDriver
        private val databaseRepository = DatabaseRepositoryImpl(lazy { driver })
        private val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        private var closed = false

        init {
            driver = DatabaseBindings.providesSqlDriver(context, databaseRepository)
            gate("phase=driver class=${driver.javaClass.name} sqlite=BundledSQLiteDriver configuration=production_bindings")
        }

        val database = DatabaseBindings.providesDatabase(driver)
        val preferences = InMemoryPreferenceStore()
        val manga = MangaRepositoryImpl(database)
        val content = ChapterContentRepositoryImpl(database, NovelContentDir(File(stateDir, "novel-content")))
        val progress = ReadingProgressRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database, content, progress)
        val history = HistoryRepositoryImpl(database)
        val categories = CategoryRepositoryImpl(database)
        val tracks = TrackRepositoryImpl(database)
        val stubSources = StubSourceRepositoryImpl(database)
        val getCategories = GetCategories(categories)
        val sources = FixtureSources()

        suspend fun open() = databaseRepository.awaitOpen()

        fun extensionStores(context: Context) = ExtensionStoreRepositoryImpl(
            scope,
            ExtensionStoreService(NetworkHelper(scope, context, NetworkPreferences(preferences, false)), Json, ProtoBuf),
            database,
        )

        suspend fun close() {
            if (closed) return
            job.cancelAndJoin()
            driver.close()
            closed = true
        }
    }

    /** Passive source metadata only; every database read/write still uses production repositories. */
    private class FixtureSources : SourceManager {
        private val fixture = StubSource(SOURCE_ID, "en", "Invented integration source")
        override val sources = flowOf<List<Source>>(listOf(fixture))
        override suspend fun get(sourceKey: Long): Source? = fixture.takeIf { it.id == sourceKey }
        override suspend fun getOrStub(sourceKey: Long): Source = checkNotNull(get(sourceKey))
        override suspend fun getAll(): List<Source> = listOf(fixture)
        override suspend fun getOnlineSources(): List<HttpSource> = emptyList()
        override suspend fun getStubSources(): List<StubSource> = listOf(fixture)
    }

    /** Same-UID target attribution and resources, isolated paths; persistent preferences are forbidden. */
    private class IsolatedContext(
        private val appResourcesContext: Context,
        private val databaseFile: File,
        private val ownedCache: File,
    ) : ContextWrapper(appResourcesContext) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File {
            check(name == "tachiyomi.db") { "Unexpected database name" }
            return databaseFile
        }
        override fun getCacheDir(): File = ownedCache
        override fun getExternalCacheDir(): File = ownedCache
        override fun getResources(): Resources = appResourcesContext.resources
        override fun getTheme(): Resources.Theme = appResourcesContext.theme
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("Persistent preferences are forbidden")
    }

    private data class BookState(
        val source: Long,
        val url: String,
        val title: String,
        val favorite: Boolean,
        val favoriteAt: Instant?,
        val chapters: Map<String, ChapterState>,
        val history: Map<String, HistoryState>,
    )
    private data class ChapterState(val name: String, val read: Boolean, val lastPageRead: Int, val position: Position?)
    private data class Position(val blockIndex: Int, val offsetPx: Int, val fraction: Float)
    private data class HistoryState(val readAt: Instant?, val duration: Long)

    companion object {
        private const val ROOT_PREFIX = "novel-backup-integration-"
        private const val SOURCE_KEY = "backup-integration-invented"
        private const val NOVEL_URL = "clockwork-orchard"
        private val SOURCE_ID = NovelMapper.generateSourceId(SOURCE_KEY)
        private val BACKUP_OPTIONS = BackupOptions(
            libraryEntries = true, categories = false, chapters = true, tracking = false, history = true,
            readEntries = false, appSettings = false, extensionStores = false, sourceSettings = false, privateSettings = false,
        )
        private val RESTORE_OPTIONS = RestoreOptions(
            libraryEntries = true, categories = false, appSettings = false, extensionStores = false, sourceSettings = false,
        )

        private fun gate(message: String) { Log.i("NOVEL_BACKUP_GATE", message) }

        /** Refuse symlinks (including ancestors) rather than traversing anything outside this UUID root. */
        private fun deleteOwned(file: File, root: File, testCache: File) {
            check(root.absoluteFile == root.canonicalFile && root.parentFile == testCache)
            check(root.name.startsWith(ROOT_PREFIX))
            check(file.absoluteFile == file.canonicalFile) { "Refusing symlink cleanup" }
            check(file == root || file.path.startsWith(root.path + File.separator)) { "Refusing unowned cleanup" }
            if (!file.exists()) return
            if (file.isDirectory) checkNotNull(file.listFiles()).forEach { deleteOwned(it, root, testCache) }
            check(file.delete()) { "Could not remove owned fixture path: $file" }
        }
    }
}
