package eu.kanade.tachiyomi.data.backup

import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovelContent
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.ui.reader.novel.ChapterOpener
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.novel.NovelMapper
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.backup.repository.RestoreRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.novel.repository.ChapterContentRepository
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgressRepository
import tachiyomi.domain.track.repository.TrackRepository
import kotlin.time.Instant
import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.Novel as NovelEntry

/**
 * What a novel chapter looks like on its way out of and back into the database through a backup.
 *
 * The two things that have to survive are the position inside the text — the block fraction above all, since it is
 * what makes the position survive a font-size change — and the text itself, which is cached as a JSON file under
 * an absolute path in the app's cache directory and therefore cannot be carried as a path at all.
 *
 * Everything here runs against the real creator, the real restorer and the real serializer with the repositories
 * stubbed, because the decisions this phase makes are *which* repositories to ask, *what* to put on the wire and
 * *what to hand the repository*. The write-back into the database is `RestoreRepositoryImpl`'s, and it cannot be
 * reached from here: `:app`'s test compile classpath carries no `app.cash.sqldelight` at all, so
 * `tachiyomi.data.Database` cannot even be named from a test in this module. That is the one part of this phase
 * with no unit test behind it.
 */
class NovelBackupRoundTripTest {

    @Test
    fun `a novel chapter's position keeps its fraction through the backup and back`() = runTest {
        givenNovelData(
            chapters = listOf(novelChapter(CHAPTER_ID)),
            progress = mapOf(CHAPTER_ID to progressAt(7, 0.375f)),
        )

        val chapter = backup().chapters.single()

        assertEquals(7L, chapter.lastPageRead, "the block index has to travel in the field the schema already had")
        assertEquals(0.375f, chapter.lastBlockFraction)
    }

    @Test
    fun `the fraction is exact after a real ProtoBuf round trip, not close to it`() = runTest {
        val fraction = 0.1234567f
        givenNovelData(
            chapters = listOf(novelChapter(CHAPTER_ID)),
            progress = mapOf(CHAPTER_ID to progressAt(3, fraction)),
        )

        val decoded = throughAProtoBufRoundTrip(backup())

        // ProtoBuf writes a float as fixed32, so the value comes back bit for bit. An encoding that went through a
        // decimal string would drift here and quietly move the reader's place in the paragraph.
        assertEquals(fraction, decoded.chapters.single().lastBlockFraction)
        assertEquals(3L, decoded.chapters.single().lastPageRead)
    }

    @Test
    fun `a chapter with no saved position carries no fraction`() = runTest {
        givenNovelData(chapters = listOf(novelChapter(CHAPTER_ID)))

        val chapter = backup().chapters.single()

        assertEquals(0f, chapter.lastBlockFraction)
        assertEquals(0L, chapter.lastPageRead)
    }

    @Test
    fun `a novel chapter with content cached carries the text and not a path`() = runTest {
        givenNovelData(chapters = listOf(novelChapter(CHAPTER_ID)), cached = setOf(CHAPTER_ID), content = CONTENT)

        val payload = backup().novelContents.single()

        assertEquals("c1", payload.chapterUrl, "the key has to be what the chapter row keeps across devices")
        // The whole point of carrying this at all: `chapter_content.file_path` is an absolute path into the cache
        // directory of whichever device wrote the backup, and restoring it as one yields a chapter that says it is
        // downloaded and then fails to open.
        assertEquals(Json.encodeToString(ChapterContent.serializer(), CHAPTER_CONTENT), payload.content)
    }

    @Test
    fun `a chapter with no cached content carries no payload`() = runTest {
        givenNovelData(chapters = listOf(novelChapter(CHAPTER_ID)))

        assertTrue(backup().novelContents.isEmpty())
    }

    @Test
    fun `a chapter listed as cached whose file will not decode is left out`() = runTest {
        givenNovelData(chapters = listOf(novelChapter(CHAPTER_ID)), cached = setOf(CHAPTER_ID), content = emptyMap())

        assertTrue(backup().novelContents.isEmpty())
    }

    @Test
    fun `only the chapters that have content carry one`() = runTest {
        givenNovelData(
            chapters = listOf(
                novelChapter(FIRST_ID, "c1"),
                novelChapter(SECOND_ID, "c2"),
                novelChapter(THIRD_ID, "c3"),
            ),
            cached = setOf(FIRST_ID, THIRD_ID),
            content = mapOf(
                FIRST_ID to CHAPTER_CONTENT,
                THIRD_ID to ChapterContent(listOf(ContentBlock.Paragraph("otro"))),
            ),
        )

        assertEquals(listOf("c1", "c3"), backup().novelContents.map { it.chapterUrl })
    }

    @Test
    fun `an image chapter in a novel manga keeps the entry it always had`() = runTest {
        givenNovelData(
            chapters = listOf(novelChapter(CHAPTER_ID), imageChapter()),
            cached = setOf(CHAPTER_ID, IMAGE_ID),
            content = mapOf(CHAPTER_ID to CHAPTER_CONTENT, IMAGE_ID to CHAPTER_CONTENT),
            progress = mapOf(IMAGE_ID to progressAt(9, 0.9f)),
        )

        val entry = backup()

        val imageBackup = entry.chapters.single { it.url == "/chapter-1" }
        assertEquals(0L, imageBackup.lastPageRead, "an image chapter's lastPageRead is a page, and nothing touched it")
        assertEquals(0f, imageBackup.lastBlockFraction)
        assertEquals(1, entry.novelContents.size, "the image chapter was cached too, and is still not a novel one")
    }

    @Test
    fun `an image manga is asked about neither positions nor content`() = runTest {
        givenNovelData(chapters = listOf(imageChapter()))

        mangaBackupCreator().invoke(listOf(imageManga()), BackupOptions())

        // The whole cost of this feature on the library it was not written for: nothing.
        coVerify(exactly = 0) { readingProgressRepository.get(any()) }
        coVerify(exactly = 0) { chapterContentRepository.getCachedChapterIdsByMangaId(any()) }
        coVerify(exactly = 0) { chapterContentRepository.get(any()) }
    }

    @Test
    fun `a backup with no chapters option carries no novel data`() = runTest {
        givenNovelData(
            chapters = listOf(novelChapter(CHAPTER_ID)),
            cached = setOf(CHAPTER_ID),
            content = CONTENT,
            progress = mapOf(CHAPTER_ID to progressAt(7, 0.375f)),
        )

        val entry = mangaBackupCreator().invoke(listOf(novelManga()), BackupOptions(chapters = false)).single()

        assertTrue(entry.chapters.isEmpty())
        assertTrue(entry.novelContents.isEmpty())
    }

    @Test
    fun `a novel entry read back by the restorer keeps its fraction and its text`() = runTest {
        givenNovelData(
            chapters = listOf(novelChapter(CHAPTER_ID)),
            cached = setOf(CHAPTER_ID),
            content = CONTENT,
            progress = mapOf(CHAPTER_ID to progressAt(7, 0.375f)),
        )

        val restored = restore(backup())

        assertEquals(mapOf("c1" to 0.375f), restored.novelPositions)
        assertEquals(
            CHAPTER_CONTENT,
            Json.decodeFromString(ChapterContent.serializer(), restored.novelContents.getValue("c1")),
        )
    }

    @Test
    fun `an old backup with no novel fields still restores as an ordinary entry`() = runTest {
        // What a backup from before this phase holds: every field it always had, with the two new ones absent
        // rather than empty, because ProtoBuf does not write a default. `BackupSchemaCompatibilityTest` proves the
        // bytes themselves still decode; this proves the restorer still treats the entry as a library entry.
        val oldEntry = BackupManga(
            source = NovelMapper.generateSourceId("demo"),
            url = "n1",
            title = "La torre de cristal",
            chapters = listOf(
                BackupChapter(
                    url = "c1",
                    name = "Capítulo 1",
                    memo = novelMemo(),
                ),
            ),
        )

        val restored = restore(oldEntry)

        assertTrue(restored.novelPositions.isEmpty())
        assertTrue(restored.novelContents.isEmpty())
        assertEquals("c1", restored.chapters.single().url)
        assertTrue(ChapterOpener.shouldOpenNovelReader(restored.chapters.single()))
    }

    @Test
    fun `a position at the top of the chapter is not written back as a position`() = runTest {
        // 0 on block 0 is where a chapter opens, and it is also what every novel chapter in a backup from before
        // the fraction existed carries. Writing a row for it would put a progress entry on every novel chapter of
        // an old backup, at a block index that was never a block index.
        val entry = BackupManga(
            source = NovelMapper.generateSourceId("demo"),
            url = "n1",
            title = "La torre de cristal",
            chapters = listOf(
                BackupChapter(
                    url = "c1",
                    name = "Capítulo 1",
                    memo = novelMemo(),
                ),
            ),
            novelContents = listOf(BackupNovelContent("c1", """{"blocks":[{"type":"Paragraph","text":"hola"}]}""")),
        )

        val restored = restore(entry)

        assertTrue(restored.novelPositions.isEmpty())
        assertEquals(1, restored.novelContents.size)
    }

    @Test
    fun `a payload for a chapter that is not a novel one never reaches the repository`() = runTest {
        val entry = BackupManga(
            source = NovelMapper.generateSourceId("demo"),
            url = "n1",
            title = "La torre de cristal",
            chapters = listOf(BackupChapter(url = "/image-1", name = "Chapter 1")),
            novelContents = listOf(BackupNovelContent("/image-1", """{"blocks":[]}""")),
        )

        val restored = restore(entry)

        assertTrue(restored.novelContents.isEmpty())
        assertFalse(ChapterOpener.shouldOpenNovelReader(restored.chapters.single()))
    }

    @Test
    fun `a malformed payload is carried through the restorer instead of failing the restore`() = runTest {
        // A backup is deserialized as a whole, so this is the half of the tolerance that belongs to the schema:
        // a payload that will not decode is opaque text here, not a field of a type that rejects the file. What
        // happens to it afterwards — dropped for its own chapter, with the rest of the backup untouched — is
        // `RestoreRepositoryImpl`'s, and `:app` has no way to reach that code from a unit test: its test compile
        // classpath carries no `app.cash.sqldelight` at all, so `tachiyomi.data.Database` cannot be named.
        val entry = BackupManga(
            source = NovelMapper.generateSourceId("demo"),
            url = "n1",
            title = "La torre de cristal",
            chapters = listOf(
                BackupChapter(
                    url = "c1",
                    name = "Capítulo 1",
                    memo = novelMemo(),
                ),
            ),
            novelContents = listOf(BackupNovelContent("c1", "{not json at all")),
        )

        val restored = restore(entry)

        assertEquals("{not json at all", restored.novelContents.getValue("c1"))
    }

    @Test
    fun `an empty payload is carried through the restorer rather than dropped`() = runTest {
        // The absence of text is not the same as a payload that will not decode, and collapsing the two would
        // make a backup from a build that had no content column look corrupt.
        val entry = BackupManga(
            source = NovelMapper.generateSourceId("demo"),
            url = "n1",
            title = "La torre de cristal",
            chapters = listOf(
                BackupChapter(
                    url = "c1",
                    name = "Capítulo 1",
                    memo = novelMemo(),
                ),
            ),
            novelContents = listOf(BackupNovelContent("c1", "")),
        )

        assertEquals("", restore(entry).novelContents.getValue("c1"))
    }

    // Helpers.

    private val mangaRepository = mockk<MangaRepository> {
        coEvery { getExcludedScanlators(any()) } returns emptySet()
    }

    private val chapterRepository = mockk<ChapterRepository>()

    private val trackRepository = mockk<TrackRepository> {
        coEvery { getTracksByMangaId(any()) } returns emptyList()
    }

    private val getCategories = mockk<GetCategories> {
        coEvery { await(any()) } returns emptyList()
        coEvery { await() } returns emptyList()
    }

    private val getHistory = mockk<GetHistory> {
        coEvery { await(any()) } returns emptyList()
    }

    private val readingProgressRepository = mockk<ReadingProgressRepository>()

    private val chapterContentRepository = mockk<ChapterContentRepository>()

    /**
     * Stubs what the repositories know about [chapters].
     *
     * [cached] is the set the content repository answers for the whole manga and [content] what a single chapter
     * read comes back with, so a chapter can be cached and still unreadable — which is the state a corrupted or
     * half-deleted cache leaves behind.
     */
    private fun givenNovelData(
        chapters: List<Chapter>,
        progress: Map<Long, ReadingProgress> = emptyMap(),
        cached: Set<Long> = emptySet(),
        content: Map<Long, ChapterContent> = emptyMap(),
    ) {
        coEvery { chapterRepository.getChapterByMangaId(any(), any()) } returns chapters
        chapters.forEach { coEvery { readingProgressRepository.get(it.id) } returns progress[it.id] }
        coEvery { chapterContentRepository.getCachedChapterIdsByMangaId(any()) } returns flowOf(cached)
        cached.forEach { coEvery { chapterContentRepository.get(it) } returns content[it] }
    }

    private fun mangaBackupCreator() = MangaBackupCreator(
        mangaRepository = mangaRepository,
        chapterRepository = chapterRepository,
        trackRepository = trackRepository,
        getCategories = getCategories,
        getHistory = getHistory,
        readingProgressRepository = readingProgressRepository,
        chapterContentRepository = chapterContentRepository,
    )

    private suspend fun backup() = mangaBackupCreator().invoke(listOf(novelManga()), BackupOptions()).single()

    /** What a backup file is: the entry, encoded and decoded again exactly as `BackupCreator` would. */
    private fun throughAProtoBufRoundTrip(entry: BackupManga): BackupManga {
        val bytes = ProtoBuf.encodeToByteArray(
            Backup.serializer(),
            Backup(backupManga = listOf(entry)),
        )
        return ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes).backupManga.single()
    }

    private suspend fun restore(entry: BackupManga): RestoredManga {
        var captured: List<RestoredManga> = emptyList()
        val restoreRepository = object : RestoreRepository {
            override suspend fun getMangaUrlsBySourceId() = emptyMap<Long, Set<String>>()

            // `update` is what the caller wants changed on each entry once its chapters are in place; this phase is
            // not about it, so the fake records the entries and leaves it alone.
            override suspend fun restoreManga(
                entries: List<RestoredManga>,
                update: suspend (Manga) -> MangaUpdate,
            ) {
                captured = entries
            }
        }

        MangaRestorer(
            restoreRepository = restoreRepository,
            getCategories = getCategories,
            fetchInterval = FetchInterval(mockk<GetChaptersByMangaId>()),
        ).restore(listOf(entry), emptyList())

        return captured.single()
    }

    private fun progressAt(blockIndex: Int, blockFraction: Float) =
        ReadingProgress(blockIndex = blockIndex, blockOffsetPx = 0, blockFraction = blockFraction, percent = 0.5f)

    private fun novelManga() = Manga.create().copy(
        id = MANGA_ID,
        source = NovelMapper.generateSourceId("demo"),
        url = "n1",
        title = "La torre de cristal",
        favoriteAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    private fun imageManga() = Manga.create().copy(
        id = MANGA_ID,
        source = 1234L,
        url = "/manga-1",
        title = "A Town Where You Live",
        favoriteAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    private fun imageChapter() =
        Chapter.create().copy(id = IMAGE_ID, mangaId = MANGA_ID, url = "/chapter-1", name = "Chapter 1")

    /** What `BackupChapter.memo` holds for a novel chapter: the JSON text of the memo, as the backup carries it. */
    private fun novelMemo() = novelChapter(NOVEL_CHAPTER_ID).memo.toString().encodeToByteArray()

    /** A chapter carrying the memo `NovelMapper` writes, which is what makes it a novel chapter. */
    private fun novelChapter(id: Long, url: String = "c1") = NovelMapper.toChapter(
        novel = NovelEntry(sourceId = "demo", id = "n1", title = "La torre de cristal"),
        chapter = NovelChapter(sourceId = "demo", novelId = "n1", id = url, title = "Capítulo 1", number = 1f),
    ).copy(id = id, mangaId = MANGA_ID)

    private companion object {
        const val MANGA_ID = 1L
        const val NOVEL_CHAPTER_ID = 1L
        const val CHAPTER_ID = 10L
        const val FIRST_ID = 10L
        const val SECOND_ID = 11L
        const val THIRD_ID = 12L
        const val IMAGE_ID = 20L

        val CHAPTER_CONTENT = ChapterContent(
            blocks = listOf(
                ContentBlock.Heading("Capítulo 1", level = 1),
                ContentBlock.Paragraph("It was a bright cold day in April."),
                ContentBlock.Image(url = "https://example.com/a.jpg", alt = "ilustración"),
                ContentBlock.Divider,
            ),
        )

        val CONTENT = mapOf(CHAPTER_ID to CHAPTER_CONTENT)
    }
}
