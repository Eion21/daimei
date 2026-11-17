package eu.kanade.tachiyomi.source.novel

import app.novel.source.api.ChapterContent
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo
import app.novel.source.demo.DemoSource
import eu.kanade.tachiyomi.extension.novel.NovelExecutionHoldStore
import eu.kanade.tachiyomi.extension.novel.NovelInstalledPackageFacts
import eu.kanade.tachiyomi.extension.novel.NovelPackageSigners
import eu.kanade.tachiyomi.extension.novel.NovelSignerObservation
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.novel.NovelMapper
import tachiyomi.domain.source.model.StubSource
import app.novel.source.api.Chapter as NovelChapter
import tachiyomi.domain.source.model.Source as DomainSource
import java.nio.file.Path

class NovelSourceAdapterTest {

    @TempDir
    lateinit var temp: Path

    private val adapter = NovelSourceAdapter(DemoSource())

    @Test
    fun `id matches NovelMapper generateSourceId so an existing library keeps pointing at this source`() {
        assertEquals(NovelMapper.generateSourceId("demo"), adapter.id)
    }

    @Test
    fun `name and lang come from the novel source info`() {
        assertEquals("Demo", adapter.name)
        assertEquals("es", adapter.lang)
    }

    @Test
    fun `getPopularManga maps every novel and uses the novel id as the remote url`() = runBlocking {
        val page = adapter.getPopularManga(1)

        assertEquals(listOf("n1", "n2"), page.mangas.map { it.url })
        assertEquals(listOf("La torre de cristal", "Crónicas del valle"), page.mangas.map { it.title })
        assertEquals(listOf("Autor Uno", "Autor Dos"), page.mangas.map { it.author })
        assertEquals(false, page.hasNextPage)
    }

    @Test
    fun `absolute cover survives popular latest search and details`() = runBlocking {
        val cover = "https://img.jabrascan.net/IBecameMarriedMan/IBecameMarriedMan01-225w.webp"
        val fixture = NovelSourceAdapter(FixtureSource(cover))
        val popular = fixture.getPopularManga(1).mangas.single()
        val latest = fixture.getLatestUpdates(1).mangas.single()
        val search = fixture.getSearchManga(1, "Fixture", FilterList()).mangas.single()
        val details = fixture.getMangaUpdate(popular, emptyList(), fetchDetails = true, fetchChapters = false).manga

        assertTrue(fixture.supportsLatest)
        for (manga in listOf(popular, latest, search, details)) {
            assertEquals("novel-42", manga.url)
            assertEquals("Fixture novel", manga.title)
            assertEquals("Autor", manga.author)
            assertEquals(cover, manga.thumbnail_url)
        }
        assertEquals(NovelMapper.generateSourceId("fixture"), fixture.id)
        assertEquals("Fixture details", details.description)
    }

    @Test
    fun `getPopularManga on a later page returns nothing`() = runBlocking {
        assertEquals(0, adapter.getPopularManga(2).mangas.size)
    }

    @Test
    fun `getLatestUpdates mirrors popular because the novel contract has no latest concept`() = runBlocking {
        assertEquals(
            adapter.getPopularManga(1).mangas.map { it.url },
            adapter.getLatestUpdates(1).mangas.map { it.url },
        )
    }

    @Test
    fun `getSearchManga delegates the query and filters the results`() = runBlocking {
        assertEquals(listOf("n2"), adapter.getSearchManga(1, "valle", FilterList()).mangas.map { it.url })
        assertEquals(0, adapter.getSearchManga(1, "nothing matches this", FilterList()).mangas.size)
    }

    @Test
    fun `getMangaUpdate returns the details and the chapters`() = runBlocking {
        val manga = adapter.getPopularManga(1).mangas.first()
        val update = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)

        assertEquals("Novela de prueba con contenido determinista.", update.manga.description)
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), update.chapters.map { it.url })
        assertEquals(
            listOf("Capítulo 1", "Capítulo 5"),
            listOf(update.chapters.first().name, update.chapters.last().name),
        )
        assertEquals(
            listOf(1f, 5f),
            listOf(update.chapters.first().chapter_number, update.chapters.last().chapter_number),
        )
    }

    @Test
    fun `ascending source list retains chapter identity and memo through adapter`() = runBlocking {
        val novel = Novel("ascending-fixture", "work", "Work")
        val rows = (1..405).map {
            NovelChapter(novel.sourceId, novel.id, "chapter-$it", "Chapter $it", number = it.toFloat())
        }
        val source = object : NovelSource {
            override val info = SourceInfo(novel.sourceId, "Fixture", "1", "es")
            override suspend fun popular(page: Int): NovelPage = error("not used")
            override suspend fun search(query: String, page: Int): NovelPage = error("not used")
            override suspend fun getNovelDetails(novel: Novel): Novel = error("not used")
            override suspend fun getChapters(novel: Novel): List<NovelChapter> = rows
            override suspend fun getChapterContent(chapter: NovelChapter): ChapterContent = error("not used")
        }
        val manga = eu.kanade.tachiyomi.source.model.SManga.create().apply {
            url = novel.id
            title = novel.title
        }
        val mapped = NovelSourceAdapter(source).getMangaUpdate(manga, emptyList(), false, true).chapters
        assertEquals(rows.map { it.id }, mapped.map { it.url })
        assertEquals(rows.map { it.number }, mapped.map { it.chapter_number })
        assertEquals(rows.map { NovelMapper.toChapter(novel, it).memo }, mapped.map { it.memo })
    }

    @Test
    fun `getMangaUpdate keeps the manga untouched when details are not requested`() = runBlocking {
        val manga = adapter.getPopularManga(1).mangas.first()
        val update = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true)

        assertEquals(manga.title, update.manga.title)
        assertNull(update.manga.description)
        assertEquals(5, update.chapters.size)
    }

    @Test
    fun `getMangaUpdate returns no chapters when chapters are not requested`() = runBlocking {
        val manga = adapter.getPopularManga(1).mangas.first()
        val update = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)

        assertEquals(0, update.chapters.size)
    }

    @Test
    fun `a chapter with no number maps to -1`() = runBlocking {
        val fixture = NovelSourceAdapter(FixtureSource())
        val manga = fixture.getPopularManga(1).mangas.first()

        val update = fixture.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        val chapter = update.chapters.single()

        assertEquals(-1f, chapter.chapter_number)
    }

    @Test
    fun `chapter memo carries the ids the text reader needs and can be read back`() = runBlocking {
        val fixture = NovelSourceAdapter(FixtureSource())
        val manga = fixture.getPopularManga(1).mangas.first()

        val update = fixture.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        val chapter = update.chapters.single()
        val memo = NovelMapper.toChapter(FixtureSource.novel, FixtureSource.chapter).memo

        assertEquals(FixtureSource.sourceInfo.id, memo["novelSourceId"]?.jsonPrimitive?.content)
        assertEquals("novel-42", memo["novelId"]?.jsonPrimitive?.content)
        assertEquals("https://example.test/ch-1", memo["novelChapterUrl"]?.jsonPrimitive?.content)

        // The adapter writes the same keys, so whatever the text reader reads back agrees with the database.
        assertEquals(memo, chapter.memo)
    }

    @Test
    fun `chapter memo omits the chapter url when the novel source does not provide one`() = runBlocking {
        val manga = adapter.getPopularManga(1).mangas.first()

        val update = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        val chapter = update.chapters.first()

        assertNull(chapter.memo["novelChapterUrl"])
        assertEquals("demo", chapter.memo["novelSourceId"]?.jsonPrimitive?.content)
        assertEquals("n1", chapter.memo["novelId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `getPageList explains that novel text does not go through the page list`() = runBlocking {
        val manga = adapter.getPopularManga(1).mangas.first()
        val update = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        val chapter = update.chapters.first()

        val error = runCatching { adapter.getPageList(chapter) }.exceptionOrNull()

        assertTrue(error is UnsupportedOperationException, "expected UnsupportedOperationException, got $error")
        val message = checkNotNull(checkNotNull(error).message)
        assertTrue(message.contains("text reader"), "message should point at the text reader, was: $message")
    }

    @Test
    fun `adapter urls and memos are the same strings NovelMapper writes into the database`() = runBlocking {
        val page = adapter.getPopularManga(1)
        val listingNovel = Novel("demo", "n1", "La torre de cristal", author = "Autor Uno")
        val update = adapter.getMangaUpdate(page.mangas.first(), emptyList(), fetchDetails = true, fetchChapters = true)
        val firstChapter = NovelChapter("demo", "n1", "c1", "Capítulo 1", number = 1f)

        val persistedManga = NovelMapper.toManga(listingNovel)
        val persistedChapter = NovelMapper.toChapter(listingNovel, firstChapter)

        // `source_id` + `remote_url` is what makes a manga row unique, so a drift here silently orphans the library.
        assertEquals(NovelMapper.generateSourceId("demo"), persistedManga.source)
        assertEquals(adapter.id, persistedManga.source)
        assertEquals(persistedManga.url, page.mangas.first().url)
        assertEquals(persistedManga.title, page.mangas.first().title)
        assertEquals(persistedManga.author, page.mangas.first().author)
        assertEquals(persistedManga.memo, page.mangas.first().memo)

        assertEquals(persistedChapter.url, update.chapters.first().url)
        assertEquals(persistedChapter.name, update.chapters.first().name)
        assertEquals(persistedChapter.memo, update.chapters.first().memo)
    }

    @Test
    fun `two novel sources produce distinct ids so both can sit in the seed map at once`() {
        val fixture = NovelSourceAdapter(FixtureSource())

        assertEquals(NovelMapper.generateSourceId("fixture"), fixture.id)
        assertNotEquals(adapter.id, fixture.id)
    }

    @Test
    fun `isNovelSource recognizes an adapter and rejects any other source`() {
        val notANovelSource: Source = StubSource(id = adapter.id, lang = "en", name = "Something else")

        assertTrue(adapter.isNovelSource())
        assertFalse(notANovelSource.isNovelSource())
    }

    @Test
    fun `the domain source helper matches against the ids the adapters expose`() {
        val novelSourceIds = setOf(adapter.id)
        val novel = DomainSource(id = adapter.id, lang = "es", name = "Demo", supportsLatest = true, isStub = false)

        assertTrue(novel.isNovelSource(novelSourceIds))
        assertFalse(novel.isNovelSource(emptySet()))
        assertFalse(DomainSource(1L, "en", "Other", true, false).isNovelSource(novelSourceIds))
    }

    // --- Retained-reference execution guard (T14 correction: revocation must block held references) ---

    /** [assertThrows] cannot take a suspending lambda, so blocked execution entries are asserted through this. */
    private fun assertBlocked(executable: suspend () -> Unit): NovelExecutionRevokedException {
        val error = runCatching { runBlocking { executable() } }.exceptionOrNull()
        assertTrue(error is NovelExecutionRevokedException, "expected NovelExecutionRevokedException, got $error")
        return error as NovelExecutionRevokedException
    }

    @Test
    fun `retained adapter reference works under a valid lease and is blocked after revoke`() = runBlocking {
        val store = NovelExecutionHoldStore(temp.resolve("holds").toFile())
        val signers = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf("a".repeat(64)))
        val pkg = "app.novel.extension.matchanovels"
        val facts = NovelInstalledPackageFacts(pkg, 7L, "v7", signers)
        val counter = CountingSource()
        val adapter = NovelSourceAdapter(counter) { store.permits(pkg, 7L, signers) }

        assertTrue(store.enroll("op", pkg, 7L, signers.current, "c".repeat(64)))
        assertTrue(store.reconcile("op", facts))
        assertTrue(store.grantExecution("op", facts, true))

        // Under the current lease the held reference delegates normally.
        assertEquals(1, adapter.getPopularManga(1).mangas.size)
        assertEquals(1, counter.popular)

        // Revoke the exact lease: the SAME retained reference must be blocked...
        assertTrue(store.revokeExecution(pkg, 7L))
        val error = assertBlocked { adapter.getPopularManga(1) }
        // ...without any delegate side effects.
        assertEquals(1, counter.popular)
        assertEquals(0, counter.details)
        assertEquals(0, counter.chapters)

        // Every execution entry is blocked, not just the popular listing.
        val manga = eu.kanade.tachiyomi.source.model.SManga.create().apply {
            url = "n1"
            title = "Held novel"
        }
        assertBlocked { adapter.getLatestUpdates(1) }
        assertBlocked { adapter.getSearchManga(1, "q", FilterList()) }
        assertBlocked { adapter.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true) }
        assertBlocked { adapter.getPageList(Chapterless) }
        assertEquals(1, counter.popular)
        assertEquals(0, counter.details)
        assertEquals(0, counter.chapters)

        // Metadata stays readable on the blocked reference.
        assertEquals(NovelMapper.generateSourceId(CountingSource.ID), adapter.id)
        assertEquals("Counter", adapter.name)
        assertEquals("es", adapter.lang)
        assertTrue(adapter.supportsLatest)
        // The blocked message names the reason without leaking the delegate.
        assertTrue(error.message!!.contains("revoked"))
    }

    @Test
    fun `an unrelated package keeps executing after one package is revoked`() = runBlocking {
        val store = NovelExecutionHoldStore(temp.resolve("unrelated-holds").toFile())
        val revokedSigners = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf("a".repeat(64)))
        val otherSigners = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf("d".repeat(64)))
        val revokedPkg = "app.novel.extension.matchanovels"
        val otherPkg = "app.novel.extension.skynovels"
        val revokedFacts = NovelInstalledPackageFacts(revokedPkg, 7L, "v7", revokedSigners)
        val otherFacts = NovelInstalledPackageFacts(otherPkg, 3L, "v3", otherSigners)

        assertTrue(store.enroll("op-a", revokedPkg, 7L, revokedSigners.current, "c".repeat(64)))
        assertTrue(store.enroll("op-b", otherPkg, 3L, otherSigners.current, "e".repeat(64)))
        assertTrue(store.reconcile("op-a", revokedFacts))
        assertTrue(store.reconcile("op-b", otherFacts))
        assertTrue(store.grantExecution("op-a", revokedFacts, true))
        assertTrue(store.grantExecution("op-b", otherFacts, true))

        val revokedAdapter = NovelSourceAdapter(CountingSource()) { store.permits(revokedPkg, 7L, revokedSigners) }
        val otherAdapter = NovelSourceAdapter(CountingSource()) { store.permits(otherPkg, 3L, otherSigners) }
        assertEquals(1, revokedAdapter.getPopularManga(1).mangas.size)
        assertEquals(1, otherAdapter.getPopularManga(1).mangas.size)

        assertTrue(store.revokeExecution(revokedPkg, 7L))
        assertBlocked { revokedAdapter.getPopularManga(1) }
        // The unrelated package still executes through its own held reference.
        assertEquals(1, otherAdapter.getPopularManga(1).mangas.size)
    }

    @Test
    fun `a restarted process keeps the revoked reference blocked`() = runBlocking<Unit> {
        val dir = temp.resolve("restart-holds").toFile()
        val signers = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf("a".repeat(64)))
        val pkg = "app.novel.extension.matchanovels"
        val facts = NovelInstalledPackageFacts(pkg, 7L, "v7", signers)
        val store = NovelExecutionHoldStore(dir)
        assertTrue(store.enroll("op", pkg, 7L, signers.current, "c".repeat(64)))
        assertTrue(store.reconcile("op", facts))
        assertTrue(store.grantExecution("op", facts, true))
        val adapter = NovelSourceAdapter(CountingSource()) { store.permits(pkg, 7L, signers) }
        assertEquals(1, adapter.getPopularManga(1).mangas.size)
        assertTrue(store.revokeExecution(pkg, 7L))

        // A fresh store over the same durable directory is a process restart: still DENY.
        val restarted = NovelExecutionHoldStore(dir)
        assertFalse(restarted.permits(pkg, 7L, signers))
        assertBlocked { adapter.getPopularManga(1) }
    }

    @Test
    fun `an adapter without a guard keeps the legacy no-hold behavior`() = runBlocking {
        val store = NovelExecutionHoldStore(temp.resolve("legacy-holds").toFile())
        val signers = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf("a".repeat(64)))
        val pkg = "app.novel.extension.matchanovels"
        val facts = NovelInstalledPackageFacts(pkg, 7L, "v7", signers)
        assertTrue(store.enroll("op", pkg, 7L, signers.current, "c".repeat(64)))
        assertTrue(store.reconcile("op", facts))
        assertTrue(store.grantExecution("op", facts, true))
        assertTrue(store.revokeExecution(pkg, 7L))

        val counter = CountingSource()
        val unguarded = NovelSourceAdapter(counter)
        // A source with no injected guard is decided by the loader gate alone, exactly as before.
        assertEquals(1, unguarded.getPopularManga(1).mangas.size)
        assertEquals(1, counter.popular)
    }

    // --- Loader seam: the raw novel source the reader resolves chapters through (T14 same-blocker correction) ---

    @Test
    fun `retained loader-seam source is blocked after revoke before any delegate side effect`() = runBlocking {
        val store = NovelExecutionHoldStore(temp.resolve("seam-holds").toFile())
        val signers = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf("a".repeat(64)))
        val pkg = "app.novel.extension.matchanovels"
        val facts = NovelInstalledPackageFacts(pkg, 7L, "v7", signers)
        val raw = CountingSource()
        val adapter = novelSourceAdapterForLoadedExtension(raw) { store.permits(pkg, 7L, signers) }
        // The reader (GetChapterContent) resolves chapters through the registry-held instance, and the
        // registry is handed exactly adapter.novelSource — so this is the reference a caller can retain.
        val retained = adapter.novelSource

        assertTrue(store.enroll("op", pkg, 7L, signers.current, "c".repeat(64)))
        assertTrue(store.reconcile("op", facts))
        assertTrue(store.grantExecution("op", facts, true))

        // Captured WHILE allowed: the chapter-content path and a catalog call both work.
        assertTrue(retained.getChapterContent(CountingSource.chapter).blocks.isEmpty())
        assertEquals(1, raw.content)
        assertEquals(1, retained.popular(1).novels.size)
        assertEquals(1, raw.popular)

        assertTrue(store.revokeExecution(pkg, 7L))

        // The SAME retained reference is denied on every contract execution entry, reader path first...
        assertBlocked { retained.getChapterContent(CountingSource.chapter) }
        assertBlocked { retained.popular(1) }
        assertBlocked { retained.search("q", 1) }
        assertBlocked { retained.getNovelDetails(CountingSource.novel) }
        assertBlocked { retained.getChapters(CountingSource.novel) }
        // ...before any delegate side effect.
        assertEquals(1, raw.content)
        assertEquals(1, raw.popular)
        assertEquals(0, raw.details)
        assertEquals(0, raw.chapters)

        // The seam exposes a guarded view, never the unguarded original.
        assertTrue(retained !== raw, "loader seam must not hand out the raw source")
        // Metadata stays readable on the blocked reference: same ids, same name.
        assertEquals(CountingSource.ID, retained.info.id)
        assertEquals("Counter", retained.info.name)
        assertEquals(NovelMapper.generateSourceId(CountingSource.ID), adapter.id)
    }

    @Test
    fun `loader-seam source stays blocked across restart and update while an unrelated lease still executes`() = runBlocking {
        val dir = temp.resolve("seam-restart-holds").toFile()
        val signers = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf("a".repeat(64)))
        val otherSigners = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf("d".repeat(64)))
        val pkg = "app.novel.extension.matchanovels"
        val otherPkg = "app.novel.extension.skynovels"
        val store = NovelExecutionHoldStore(dir)
        val facts = NovelInstalledPackageFacts(pkg, 7L, "v7", signers)
        val otherFacts = NovelInstalledPackageFacts(otherPkg, 3L, "v3", otherSigners)
        val retained = novelSourceAdapterForLoadedExtension(CountingSource()) { store.permits(pkg, 7L, signers) }
            .novelSource
        val unrelated = novelSourceAdapterForLoadedExtension(CountingSource()) { store.permits(otherPkg, 3L, otherSigners) }
            .novelSource

        assertTrue(store.enroll("op-a", pkg, 7L, signers.current, "c".repeat(64)))
        assertTrue(store.enroll("op-b", otherPkg, 3L, otherSigners.current, "e".repeat(64)))
        assertTrue(store.reconcile("op-a", facts))
        assertTrue(store.reconcile("op-b", otherFacts))
        assertTrue(store.grantExecution("op-a", facts, true))
        assertTrue(store.grantExecution("op-b", otherFacts, true))
        // Both leases execute while allowed.
        assertTrue(retained.getChapterContent(CountingSource.chapter).blocks.isEmpty())
        assertTrue(unrelated.getChapterContent(CountingSource.chapter).blocks.isEmpty())

        assertTrue(store.revokeExecution(pkg, 7L))
        assertBlocked { retained.getChapterContent(CountingSource.chapter) }

        // A restarted process is a fresh store over the same durable directory: still DENY.
        val restarted = NovelExecutionHoldStore(dir)
        assertFalse(restarted.permits(pkg, 7L, signers))
        assertBlocked { retained.getChapterContent(CountingSource.chapter) }

        // A monotonic version update revokes nothing implicitly: the old reference stays blocked until a
        // fresh grant for the new version exists, and its exact-version guard still answers false.
        assertTrue(restarted.enroll("op-c", pkg, 8L, signers.current, "f".repeat(64)))
        assertFalse(restarted.permits(pkg, 7L, signers))
        assertBlocked { retained.getChapterContent(CountingSource.chapter) }

        // The unrelated leased source keeps executing throughout.
        assertTrue(unrelated.getChapterContent(CountingSource.chapter).blocks.isEmpty())
    }

    @Test
    fun `legacy adapter constructors keep the original source instance and behavior`() = runBlocking {
        val store = NovelExecutionHoldStore(temp.resolve("seam-legacy-holds").toFile())
        val signers = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf("a".repeat(64)))
        val pkg = "app.novel.extension.matchanovels"
        val facts = NovelInstalledPackageFacts(pkg, 7L, "v7", signers)
        val raw = CountingSource()

        // Only the loader seam may hand out a guarded view; both plain constructors keep the exact instance.
        assertTrue(NovelSourceAdapter(raw).novelSource === raw)
        val guardedAdapter = NovelSourceAdapter(raw) { store.permits(pkg, 7L, signers) }
        assertTrue(guardedAdapter.novelSource === raw)

        assertTrue(store.enroll("op", pkg, 7L, signers.current, "c".repeat(64)))
        assertTrue(store.reconcile("op", facts))
        assertTrue(store.grantExecution("op", facts, true))
        assertTrue(store.revokeExecution(pkg, 7L))

        // The adapter-level blocking from the first correction still holds for the plain constructor.
        assertBlocked { guardedAdapter.getPopularManga(1) }
        assertEquals(0, raw.popular)
    }
}

/**
 * [DemoSource] only produces chapters that carry a number and no web url, so the adapter's handling of a
 * missing number and of a missing chapter url needs a source that exercises both.
 */
private class FixtureSource(private val coverUrl: String? = null) : NovelSource {

    override val info = sourceInfo

    override suspend fun popular(page: Int) = NovelPage(listOf(novel.copy(coverUrl = coverUrl)), hasNextPage = false)

    override suspend fun search(query: String, page: Int) = NovelPage(
        listOf(novel.copy(coverUrl = coverUrl)),
        hasNextPage = false,
    )

    override suspend fun getNovelDetails(novel: Novel) = novel.copy(description = "Fixture details")

    override suspend fun getChapters(novel: Novel) = listOf(chapter)

    override suspend fun getChapterContent(chapter: NovelChapter) = ChapterContent(emptyList())

    companion object {
        val sourceInfo = SourceInfo("fixture", "Fixture", "1", "en")
        val novel = Novel(sourceInfo.id, "novel-42", "Fixture novel", author = "Autor")
        val chapter = NovelChapter(
            sourceId = sourceInfo.id,
            novelId = novel.id,
            id = "ch-1",
            title = "Prólogo",
            number = null,
            url = "https://example.test/ch-1",
        )
    }
}

/**
 * A [NovelSource] that counts every delegate invocation, so a test can prove a blocked call never
 * reached the wrapped source.
 */
private class CountingSource : NovelSource {

    var popular = 0
        private set
    var details = 0
        private set
    var chapters = 0
        private set
    var content = 0
        private set

    override val info = SourceInfo(ID, "Counter", "1", "es")

    override suspend fun popular(page: Int): NovelPage {
        popular++
        return NovelPage(listOf(novel), hasNextPage = false)
    }

    override suspend fun search(query: String, page: Int): NovelPage {
        popular++
        return NovelPage(listOf(novel), hasNextPage = false)
    }

    override suspend fun getNovelDetails(novel: Novel): Novel {
        details++
        return novel
    }

    override suspend fun getChapters(novel: Novel): List<NovelChapter> {
        chapters++
        return listOf(chapter)
    }

    override suspend fun getChapterContent(chapter: NovelChapter): ChapterContent {
        content++
        return ChapterContent(emptyList())
    }

    companion object {
        const val ID = "counter"
        val novel = Novel(ID, "n1", "Held novel")
        val chapter = NovelChapter(ID, "n1", "c1", "Chapter 1", number = 1f)
    }
}

/** An [SChapter] for the one adapter method that never reaches a novel source: [NovelSourceAdapter.getPageList]. */
private val Chapterless = eu.kanade.tachiyomi.source.model.SChapter.create().apply {
    url = "c1"
    name = "Chapter 1"
}
