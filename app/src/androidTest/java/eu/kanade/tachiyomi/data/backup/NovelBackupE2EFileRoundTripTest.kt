package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.database.DatabaseRepositoryImpl
import mihon.data.extension.repository.ExtensionStoreRepositoryImpl
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.interactor.GetExtensionStores
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
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
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Instant
import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.Novel as NovelEntry

/** One public file pipeline, with owned real databases/cache and retained synthetic evidence. */
@RunWith(AndroidJUnit4::class)
class NovelBackupE2EFileRoundTripTest {
    @get:Rule val errors = ErrorCollector()

    @Test
    fun publicFileRoundTripPersistsNovelContentAndPosition() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val target = instrument.targetContext
        assertEquals("app.daimei.dev.s28", target.packageName)
        assertEquals(Process.myUid(), target.applicationInfo.uid)
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val cache = target.cacheDir.canonicalFile
        val id = UUID.randomUUID().toString()
        val evidence = File(cache, "s29-evidence/$id")
        assertTrue(evidence.mkdirs())
        instrument.sendStatus(2, Bundle().apply { putString("s29_evidence_dir", "cache/s29-evidence/$id") })
        val root = File(cache, "s29-state-$id")
        assertTrue(root.mkdir())
        var source: Session? = null
        var destination: Session? = null
        val log = File(evidence, "report.txt")
        fun record(value: String) { log.appendText(value + "\n") }
        try {
            val srcDir = File(root, "source").also { check(it.mkdir()) }
            val dstDir = File(root, "destination").also { check(it.mkdir()) }
            val srcContext = isolated(target, srcDir)
            val dstContext = isolated(target, dstDir)
            source = Session(srcContext, srcDir)
            source.open()
            val novel = NovelEntry(sourceId = SOURCE_KEY, id = NOVEL_URL, title = "Synthetic S29 novel")
            val manga = source.manga.insertNetworkManga(
                listOf(NovelMapper.toManga(novel).copy(favoriteAt = Instant.fromEpochMilliseconds(1_700_000_000_000))),
            ).single()
            val chapter = source.chapters.updateFromRemote(
                removedIds = emptyList(),
                added = listOf(NovelMapper.toChapter(novel, NovelChapter(sourceId = SOURCE_KEY, novelId = NOVEL_URL, id = CHAPTER_URL, title = "Synthetic chapter", number = 1f), sourceOrder = 1).copy(mangaId = manga.id)),
                updated = emptyList(),
            ).single()
            source.progress.save(chapter.id, ReadingProgress(17, 0, 0.375f, PERCENT))
            source.content.save(chapter.id, CONTENT)
            source.close()
            record("source_database_closed_sha256=${sha(File(srcDir, "tachiyomi.db").readBytes())}")
            source = Session(srcContext, srcDir)
            source.open()
            val sourceSnapshot = snapshot(source, srcDir)
            record("source_reopened=$sourceSnapshot")
            assertExpected(sourceSnapshot)

            destination = Session(dstContext, dstDir)
            destination.open()
            assertTrue(destination.manga.getFavorites().isEmpty())
            assertNull(destination.manga.getMangaByUrlAndSourceId(NOVEL_URL, SOURCE_ID))
            assertFalse(File(dstDir, "novel-content").exists())
            record("destination_before=empty favorites=0 novel=null content_dir_absent=true")

            val file = File(evidence, "produced.tachibk")
            assertTrue(file.createNewFile())
            val uri = Uri.fromFile(file)
            val produced = Uri.parse(creator(source, srcContext).backup(uri, BACKUP_OPTIONS))
            record("backup_uri=$produced bytes=${file.length()} sha256=${sha(file.readBytes())}")
            assertEquals(uri, produced)
            assertTrue(file.length() > 0)
            file.inputStream().use { assertEquals(0x1f, it.read()); assertEquals(0x8b, it.read()) }
            val decoded = BackupDecoder(srcContext, ProtoBuf).decode(produced)
            val entry = decoded.backupManga.single()
            val storedChapter = entry.chapters.single()
            val payload = entry.novelContents.single()
            val expectedFingerprint = expectedFingerprint()
            record("decoded source=${entry.source} url=${entry.url} favorite=${entry.favorite} chapter=${storedChapter.url} index=${storedChapter.lastPageRead} fraction=${storedChapter.lastBlockFraction} fingerprint=${storedChapter.contentFingerprint} expected_fingerprint=$expectedFingerprint content_url=${payload.chapterUrl} content_json=${payload.content}")
            File(evidence, "decoded-content.json").writeText(payload.content)
            assertEquals(SOURCE_ID, entry.source)
            assertEquals(NOVEL_URL, entry.url)
            assertTrue(entry.favorite)
            assertEquals(CHAPTER_URL, storedChapter.url)
            assertEquals(17L, storedChapter.lastPageRead)
            assertEquals(0.375f, storedChapter.lastBlockFraction, 0f)
            assertEquals(CHAPTER_URL, payload.chapterUrl)
            assertEquals(Json.encodeToString(ChapterContent.serializer(), CONTENT), payload.content)
            // Continue through public restore so the RED retains both producer and consumer evidence.
            errors.checkThat("v1 fingerprint in produced file", storedChapter.contentFingerprint, equalTo(expectedFingerprint))

            restorer(destination, dstContext, File(dstDir, "storage").also { check(it.mkdir()) }).restore(produced, RESTORE_OPTIONS)
            destination.close()
            record("destination_database_closed_sha256=${sha(File(dstDir, "tachiyomi.db").readBytes())}")
            destination = Session(dstContext, dstDir)
            destination.open()
            val restored = snapshot(destination, dstDir)
            record("destination_reopened=$restored")
            assertExpected(restored)
            assertEquals(sourceSnapshot, restored)
        } catch (t: Throwable) {
            record("failure=${t.stackTraceToString()}")
            throw t
        } finally {
            withContext(NonCancellable) {
                source?.close()
                destination?.close()
                // Only this run's UUID-owned fixture tree; retained evidence is a sibling.
                deleteOwned(root, cache)
            }
        }
    }

    private data class Snapshot(
        val source: Long, val url: String, val title: String, val favorite: Boolean,
        val chapterUrl: String, val index: Int?, val offset: Int?, val fraction: Float?, val percent: Float?,
        val content: String?, val physicalContent: String, val physicalSha256: String,
    )

    private suspend fun snapshot(session: Session, dir: File): Snapshot {
        val manga = checkNotNull(session.manga.getMangaByUrlAndSourceId(NOVEL_URL, SOURCE_ID))
        val chapter = session.chapters.getChapterByMangaId(manga.id, false).single()
        val position = session.progress.get(chapter.id)
        val content = session.content.get(chapter.id)
        val physical = File(dir, "novel-content/chapter_${chapter.id}.json")
        val bytes = physical.readBytes()
        return Snapshot(manga.source, manga.url, manga.title, manga.favorite, chapter.url,
            position?.blockIndex, position?.blockOffsetPx, position?.blockFraction, position?.percent,
            content?.let { Json.encodeToString(ChapterContent.serializer(), it) }, bytes.toString(Charsets.UTF_8), sha(bytes))
    }

    private fun assertExpected(s: Snapshot) {
        assertEquals(SOURCE_ID, s.source)
        assertEquals(NOVEL_URL, s.url)
        assertEquals("Synthetic S29 novel", s.title)
        assertTrue(s.favorite)
        assertEquals(CHAPTER_URL, s.chapterUrl)
        assertEquals(17L, checkNotNull(s.index).toLong())
        assertEquals(0L, checkNotNull(s.offset).toLong())
        assertEquals(0.375f, checkNotNull(s.fraction), 0f)
        assertEquals(PERCENT, checkNotNull(s.percent), 0f)
        val json = Json.encodeToString(ChapterContent.serializer(), CONTENT)
        assertNotNull(s.content)
        assertEquals(json, s.content)
        assertEquals(json, s.physicalContent)
        assertEquals(sha(json.toByteArray(Charsets.UTF_8)), s.physicalSha256)
    }

    private fun creator(s: Session, context: Context): BackupCreator = BackupCreator(
        isAutoBackup = false, context = context, parser = ProtoBuf, getFavorites = GetFavorites(s.manga),
        backupPreferences = BackupPreferences(s.preferences), mangaRepository = s.manga,
        categoriesBackupCreator = CategoriesBackupCreator(s.getCategories),
        mangaBackupCreator = MangaBackupCreator(s.manga, s.chapters, s.tracks, s.getCategories, GetHistory(s.history), s.progress, s.content),
        preferenceBackupCreator = PreferenceBackupCreator(s.sources, s.preferences),
        extensionStoresBackupCreator = ExtensionStoresBackupCreator(GetExtensionStores(s.extensionStores(context))),
        sourcesBackupCreator = SourcesBackupCreator(s.sources),
        backupFileValidator = BackupFileValidator(s.sources, lazy { error("Tracking disabled") }, BackupDecoder(context, ProtoBuf)),
    )

    private fun restorer(s: Session, context: Context, storageDir: File): BackupRestorer {
        val folder = object : FolderProvider {
            override fun directory() = storageDir
            override fun path() = Uri.fromFile(storageDir).toString()
        }
        val library = LibraryPreferences(s.preferences)
        val storage = StorageManager(s.scope, context, StoragePreferences(folder, s.preferences))
        val downloads = DownloadCache(s.scope, context, DownloadProvider(context, storage, library), s.sources, storage)
        return BackupRestorer(
            notifier = BackupNotifier(context, SecurityPreferences(s.preferences)), isSync = false, context = context,
            downloadCache = downloads, categoriesRestorer = CategoriesRestorer(s.categories, s.getCategories, library),
            preferenceRestorer = PreferenceRestorer(context, s.getCategories, s.preferences),
            extensionStoreRestorer = ExtensionStoreRestorer(s.extensionStores(context)),
            mangaRestorer = MangaRestorer(RestoreRepositoryImpl(s.database, s.manga, s.chapters, s.tracks, s.content, s.progress), s.getCategories, FetchInterval(GetChaptersByMangaId(s.chapters))),
            backupDecoder = BackupDecoder(context, ProtoBuf), sourceManager = s.sources, stubSourceRepository = s.stubSources,
        )
    }

    private class Session(context: Context, dir: File) {
        private lateinit var driver: SqlDriver
        private val databaseRepository = DatabaseRepositoryImpl(lazy { driver })
        private val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        private var closed = false
        init { driver = DatabaseBindings.providesSqlDriver(context, databaseRepository) }
        val database = DatabaseBindings.providesDatabase(driver)
        val preferences = InMemoryPreferenceStore()
        val manga = MangaRepositoryImpl(database)
        val content = ChapterContentRepositoryImpl(database, NovelContentDir(File(dir, "novel-content")))
        val progress = ReadingProgressRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database, content, progress)
        val history = HistoryRepositoryImpl(database)
        val categories = CategoryRepositoryImpl(database)
        val tracks = TrackRepositoryImpl(database)
        val stubSources = StubSourceRepositoryImpl(database)
        val getCategories = GetCategories(categories)
        val sources = FixtureSources()
        suspend fun open() = databaseRepository.awaitOpen()
        fun extensionStores(context: Context) = ExtensionStoreRepositoryImpl(scope, ExtensionStoreService(NetworkHelper(scope, context, NetworkPreferences(preferences, false)), Json, ProtoBuf), database)
        suspend fun close() { if (!closed) { job.cancelAndJoin(); driver.close(); closed = true } }
    }

    private class FixtureSources : SourceManager {
        private val fixture = StubSource(SOURCE_ID, "en", "Synthetic S29 source")
        override val sources = flowOf<List<Source>>(listOf(fixture))
        override suspend fun get(sourceKey: Long): Source? = fixture.takeIf { it.id == sourceKey }
        override suspend fun getOrStub(sourceKey: Long): Source = checkNotNull(get(sourceKey))
        override suspend fun getAll(): List<Source> = listOf(fixture)
        override suspend fun getOnlineSources(): List<HttpSource> = emptyList()
        override suspend fun getStubSources(): List<StubSource> = listOf(fixture)
    }

    private fun isolated(target: Context, dir: File): Context {
        val ownedCache = File(dir, "cache").also { check(it.mkdir()) }
        val db = File(dir, "tachiyomi.db")
        check(db.canonicalFile != target.getDatabasePath("tachiyomi.db").canonicalFile)
        return object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File { check(name == "tachiyomi.db"); return db }
            override fun getCacheDir(): File = ownedCache
            override fun getExternalCacheDir(): File = ownedCache
            override fun getResources(): Resources = target.resources
            override fun getTheme(): Resources.Theme = target.theme
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("Persistent preferences forbidden")
        }
    }

    companion object {
        private const val SOURCE_KEY = "s29-synthetic-source"
        private const val NOVEL_URL = "s29-synthetic-novel"
        private const val CHAPTER_URL = "s29-synthetic-chapter"
        private val SOURCE_ID = NovelMapper.generateSourceId(SOURCE_KEY)
        private val CONTENT = ChapterContent(List(20) { ContentBlock.Paragraph("Synthetic café block $it.") })
        private const val PERCENT = 17.375f / 20f
        private val BACKUP_OPTIONS = BackupOptions(libraryEntries = true, categories = false, chapters = true, tracking = false, history = false, readEntries = false, appSettings = false, extensionStores = false, sourceSettings = false, privateSettings = false)
        private val RESTORE_OPTIONS = RestoreOptions(libraryEntries = true, categories = false, appSettings = false, extensionStores = false, sourceSettings = false)

        // Frozen v1: JSON array of ordered paragraph objects, type then text, UTF-8; no defaults.
        private fun expectedFingerprint(): String {
            val canonical = (0 until 20).joinToString(",", "[", "]") { "{\"type\":\"app.novel.source.api.ContentBlock.Paragraph\",\"text\":\"Synthetic café block $it.\"}" }
            return "v1:sha256:" + sha(canonical.toByteArray(Charsets.UTF_8))
        }
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun deleteOwned(file: File, cache: File) {
            check(file.absoluteFile == file.canonicalFile)
            check(file.parentFile == cache && file.name.startsWith("s29-state-"))
            fun deleteTree(node: File) {
                check(node.absoluteFile == node.canonicalFile)
                check(node == file || node.path.startsWith(file.path + File.separator))
                if (node.isDirectory) checkNotNull(node.listFiles()).forEach(::deleteTree)
                check(node.delete())
            }
            if (file.exists()) deleteTree(file)
        }
    }
}
