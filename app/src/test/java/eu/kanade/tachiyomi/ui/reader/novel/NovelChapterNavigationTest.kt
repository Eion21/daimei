package eu.kanade.tachiyomi.ui.reader.novel

import app.novel.source.api.ChapterContent
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo
import app.novel.source.http.parseChapterList
import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.novel.NovelSourceAdapter
import eu.kanade.tachiyomi.source.novel.UNRECOGNIZED_NUMBER
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import app.novel.source.api.Chapter as SourceChapter

class NovelChapterNavigationTest {

    private val manga = Manga.create().copy(id = 10, title = "Work")

    private fun chapter(id: Long, number: Double = id.toDouble()) = Chapter.create().copy(
        id = id,
        mangaId = manga.id,
        name = "Chapter $id",
        chapterNumber = number,
        sourceOrder = 100 - id,
        scanlator = "A",
    )

    private val first = chapter(1)
    private val second = chapter(2)
    private val third = chapter(3)

    private fun assertPlan(
        actual: NovelChapterNavigation,
        ids: List<Long>,
        next: Chapter?,
        status: NovelNavigationStatus = if (next == null) {
            NovelNavigationStatus.LAST_CHAPTER
        } else {
            NovelNavigationStatus.NEXT
        },
        downloadedOnly: Boolean = false,
    ) {
        assertEquals(ids, actual.chapters.map { it.id }, "Ordered list must retain the current anchor")
        assertEquals(next, actual.next)
        assertEquals(status, actual.status)
        assertEquals(downloadedOnly, actual.downloadedOnly)
    }

    @Test
    fun `descending source display advances to the higher chapter number not the row above`() {
        val work = manga.copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SORT_DESC)
        assertTrue(work.sortDescending())
        val plan = resolveNovelChapterNavigation(work, listOf(third, second, first), second)
        assertPlan(plan, listOf(1, 2, 3), third)
        assertTrue(plan.next!!.chapterNumber > second.chapterNumber)
    }

    @Test
    fun `ascending source display uses the same reading progression`() {
        val work = manga.copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SORT_ASC)
        assertFalse(work.sortDescending())
        assertPlan(resolveNovelChapterNavigation(work, listOf(third, second, first), first), listOf(1, 2, 3), second)
    }

    @Test
    fun `numeric sorting follows the work mode independently of source order`() {
        val work = manga.copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_DESC)
        val reversedSource = listOf(first, second, third).map { it.copy(sourceOrder = it.id) }
        val current = reversedSource.first()
        assertPlan(resolveNovelChapterNavigation(work, reversedSource, current), listOf(1, 2, 3), reversedSource[1])
    }

    @Test
    fun `unknown current number never silently advances under numeric sorting`() {
        val work = manga.copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        val unknown = first.copy(chapterNumber = -1.0)
        val plan = resolveNovelChapterNavigation(work, listOf(third, second, unknown), unknown)
        assertTrue(plan.chapters.any { it.id == unknown.id })
        assertNull(plan.next)
        assertEquals(NovelNavigationStatus.UNKNOWN_NUMBER, plan.status)
    }

    @Test
    fun `unknown candidate number blocks numeric navigation instead of guessing its place`() {
        val work = manga.copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        val unknown = second.copy(chapterNumber = -1.0)
        val plan = resolveNovelChapterNavigation(work, listOf(third, unknown, first), first)
        assertTrue(plan.chapters.any { it.id == first.id })
        assertNull(plan.next)
        assertEquals(NovelNavigationStatus.UNKNOWN_NUMBER, plan.status)
    }

    @Test
    fun `duplicate numbers prefer the current scanlator for the destination`() {
        val otherScanlator = chapter(20, 2.0).copy(scanlator = "B", sourceOrder = second.sourceOrder)
        val prefs = NovelNavigationPreferences(skipDuplicates = true)
        assertPlan(
            resolveNovelChapterNavigation(manga, listOf(third, otherScanlator, second, first), first, prefs),
            listOf(1, 2, 3), second,
        )
    }

    @Test
    fun `duplicate filtering preserves the current chapter instead of replacing the anchor`() {
        val duplicate = chapter(11, 1.0).copy(sourceOrder = first.sourceOrder)
        assertPlan(
            resolveNovelChapterNavigation(
                manga, listOf(third, second, duplicate, first), first,
                NovelNavigationPreferences(skipDuplicates = true),
            ),
            listOf(1, 2, 3), second,
        )
    }

    @Test
    fun `downloaded only skips an undownloaded destination but retains an undownloaded anchor`() {
        assertPlan(
            resolveNovelChapterNavigation(
                manga, listOf(third, second, first), first,
                NovelNavigationPreferences(downloadedOnly = true), setOf(third.id),
            ),
            listOf(1, 2, 3), third, downloadedOnly = true,
        )
    }

    @Test
    fun `no downloaded destination after the anchor has no button and a distinct footer status`() {
        assertPlan(
            resolveNovelChapterNavigation(
                manga, listOf(third, second, first), second,
                NovelNavigationPreferences(downloadedOnly = true), setOf(first.id, second.id),
            ),
            listOf(1, 2, 3), null, NovelNavigationStatus.NO_MORE_DOWNLOADED, downloadedOnly = true,
        )
    }

    @Test
    fun `undownloaded current with nothing downloaded retains its reference and never offers itself`() {
        val plan = resolveNovelChapterNavigation(
            manga, listOf(third, second, first), second,
            NovelNavigationPreferences(downloadedOnly = true), emptySet(),
        )
        assertPlan(plan, listOf(1, 2, 3), null, NovelNavigationStatus.NO_MORE_DOWNLOADED, downloadedOnly = true)
        assertEquals(second, plan.chapters.single { it.id == second.id })
    }

    @Test
    fun `disabled downloaded only offers the immediate successor despite a download hole`() {
        assertPlan(
            resolveNovelChapterNavigation(
                manga, listOf(third, second, first), first,
                NovelNavigationPreferences(downloadedOnly = false), setOf(third.id),
            ),
            listOf(1, 2, 3), second,
        )
    }

    @Test
    fun `skip read omits read destinations but retains the read current`() {
        val current = first.copy(read = true)
        assertPlan(
            resolveNovelChapterNavigation(
                manga, listOf(third, second.copy(read = true), current), current,
                NovelNavigationPreferences(skipRead = true),
            ),
            listOf(1, 3), third,
        )
    }

    @Test
    fun `skip filtered applies bookmark filter but retains the filtered current`() {
        val work = manga.copy(chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED)
        val bookmarked = third.copy(bookmark = true)
        assertPlan(
            resolveNovelChapterNavigation(
                work, listOf(bookmarked, second, first), first, NovelNavigationPreferences(skipFiltered = true),
            ),
            listOf(1, 3), bookmarked,
        )
    }

    @Test
    fun `skip filtered applies read filter without dropping an unread anchor`() {
        val work = manga.copy(chapterFlags = Manga.CHAPTER_SHOW_READ)
        val read = third.copy(read = true)
        assertPlan(
            resolveNovelChapterNavigation(
                work, listOf(read, second, first), first, NovelNavigationPreferences(skipFiltered = true),
            ),
            listOf(1, 3), read,
        )
    }

    @Test
    fun `skip filtered applies downloaded filter without dropping its anchor`() {
        val work = manga.copy(chapterFlags = Manga.CHAPTER_SHOW_DOWNLOADED)
        assertPlan(
            resolveNovelChapterNavigation(
                work, listOf(third, second, first), first,
                NovelNavigationPreferences(skipFiltered = true), setOf(third.id),
            ),
            listOf(1, 3), third,
        )
    }

    @Test
    fun `disabled omission preferences leave read and filtered destinations available`() {
        val work = manga.copy(chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED)
        val read = second.copy(read = true)
        assertPlan(resolveNovelChapterNavigation(work, listOf(third, read, first), first), listOf(1, 2, 3), read)
    }

    @Test
    fun `scanlator filtered list missing the active chapter regains its anchor`() {
        assertPlan(resolveNovelChapterNavigation(manga, listOf(third, first), second), listOf(1, 2, 3), third)
    }

    @Test
    fun `last chapter has no successor and a last chapter footer status`() {
        assertPlan(resolveNovelChapterNavigation(manga, listOf(third, second, first), third), listOf(1, 2, 3), null)
    }

    @Test
    fun `single chapter work keeps its anchor and has no successor`() {
        assertPlan(resolveNovelChapterNavigation(manga, listOf(first), first), listOf(1), null)
    }

    // S31.5: these five cases are additional to the original nineteen, not replacements.
    @Test
    fun s31SourceUnknownChaptersRetainIdentityAndNext() {
        val rows = listOf(third, second, first).map { it.copy(chapterNumber = UNRECOGNIZED_NUMBER.toDouble()) }
        val current = rows.last()
        assertUnchangedPlan(
            manga.copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE), rows, current,
            NovelNavigationPreferences(skipDuplicates = true), ids = listOf(1, 2, 3), next = rows[1],
        )
    }

    @Test
    fun s31NumericUnknownInMiddleRefusesAmbiguousNext() {
        val unknown = second.copy(chapterNumber = UNRECOGNIZED_NUMBER.toDouble())
        // Unknown is in the middle of INPUT/source progression, not artificially placed between 1 and 3
        // numerically. ChapterSort orders -1 first. Like the original unknown-candidate guard, refuse
        // to infer its intended numeric position while an eligible unknown remains; NEVER use SOURCE.
        assertUnchangedPlan(
            manga.copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER), listOf(third, unknown, first), first,
            NovelNavigationPreferences(skipDuplicates = true), ids = listOf(2, 1, 3), next = null,
            status = NovelNavigationStatus.UNKNOWN_NUMBER,
        )
    }

    @Test
    fun s31ZeroIsRecognizedAndDeduplicated() {
        val current = first.copy(chapterNumber = 0.0)
        val duplicate = second.copy(chapterNumber = 0.0)
        assertUnchangedPlan(
            manga, listOf(third, duplicate, current), current, NovelNavigationPreferences(skipDuplicates = true),
            ids = listOf(1, 3), next = third,
        )
    }

    @Test
    fun s31RecognizedDuplicatesPreferCurrentThenScanlatorThenFirst() {
        val prefs = NovelNavigationPreferences(skipDuplicates = true)
        val current12 = first.copy(chapterNumber = 12.0)
        val duplicate12 = second.copy(chapterNumber = 12.0)
        val chapter13 = third.copy(chapterNumber = 13.0)
        assertUnchangedPlan(
            manga, listOf(chapter13, duplicate12, current12), current12, prefs,
            ids = listOf(1, 3), next = chapter13,
        )

        val current11 = first.copy(chapterNumber = 11.0)
        val matching12 = second.copy(chapterNumber = 12.0, scanlator = "A")
        val other12 = chapter(20, 12.0).copy(scanlator = "B", sourceOrder = matching12.sourceOrder)
        // S32 accepted loss: 12-B is hidden from navigation when 12-A matches current11-A.
        // These may be distinct PARTS on some sources; OFF must preserve both (separate control below).
        assertUnchangedPlan(
            manga, listOf(chapter13, other12, matching12, current11), current11, prefs,
            ids = listOf(1, 2, 3), next = matching12,
        )

        val first12 = matching12.copy(scanlator = "B")
        val later12 = other12.copy(scanlator = "C")
        assertUnchangedPlan(
            manga, listOf(chapter13, first12, later12, current11), current11, prefs,
            ids = listOf(1, 2, 3), next = first12,
        )
    }

    @Test
    fun s31UnknownActiveWithDownloadedOnlyKeepsAnchorAndOtherUnknown() {
        val current = first.copy(chapterNumber = UNRECOGNIZED_NUMBER.toDouble())
        val unknown = second.copy(chapterNumber = UNRECOGNIZED_NUMBER.toDouble())
        assertUnchangedPlan(
            manga.copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE), listOf(third, unknown, current), current,
            NovelNavigationPreferences(skipDuplicates = true, downloadedOnly = true), setOf(third.id),
            ids = listOf(1, 2, 3), next = third,
        )
    }

    @Test
    fun s32SkipDuplicatesOffRetainsRecognizedPartsAndUnknowns() {
        val current = first.copy(chapterNumber = UNRECOGNIZED_NUMBER.toDouble())
        val unknown = second.copy(chapterNumber = UNRECOGNIZED_NUMBER.toDouble())
        val partA = third.copy(chapterNumber = 12.0)
        val partB = chapter(4, 12.0).copy(scanlator = "B")
        assertUnchangedPlan(
            manga, listOf(partB, partA, unknown, current), current,
            NovelNavigationPreferences(skipDuplicates = false), ids = listOf(1, 2, 3, 4), next = unknown,
        )
        assertUnchangedPlan(
            manga, listOf(partB, partA, unknown, current), partA,
            NovelNavigationPreferences(skipDuplicates = false), ids = listOf(1, 2, 3, 4), next = partB,
        )
    }

    @Test
    fun s32ReaderPreferenceDefaultDoesNotSkipDuplicates() {
        val store = InMemoryPreferenceStore()
        val before = store.getAll().toMap()
        val preference = ReaderPreferences(store).skipDupe
        val actual = preference.get()
        println("S32_DEFAULT readerSkipDupe=$actual")
        assertFalse(actual, "If true, stop and ask owner; never change the production default")
        assertFalse(preference.isSet())
        assertEquals(before, store.getAll())
    }

    @Test
    fun s32NovelPreferenceDefaultDoesNotSkipDuplicates() {
        val actual = NovelNavigationPreferences().skipDuplicates
        println("S32_DEFAULT novelSkipDuplicates=$actual")
        assertFalse(actual, "If true, stop and ask owner; never change the policy default")
    }

    @Test
    fun s32PublicMappingKeepsFixedUnknownZeroAndRecognizedValues() {
        val source = listOf(sourceChapter("unknown", null), sourceChapter("zero", 0f), sourceChapter("known", 12.5f))
        val before = source.map { it.copy() }
        val mapped = mapThroughPublicAdapter(source)
        assertEquals(-1f, UNRECOGNIZED_NUMBER, "The existing mapping sentinel value must not drift")
        assertEquals(listOf(-1f, 0f, 12.5f), mapped.map { it.chapter_number })
        assertEquals(UNRECOGNIZED_NUMBER, mapped.first().chapter_number)
        val domain = mapped.mapIndexed { index, remote -> chapter(index + 1L).copyFromSChapter(remote) }
        assertEquals(listOf(-1.0, 0.0, 12.5), domain.map { it.chapterNumber })
        assertEquals(source.map { it.id }, domain.map { it.url })
        assertEquals(source.map { it.title }, domain.map { it.name })
        assertEquals(source, before)
    }

    @Test
    fun s32MappedMissingNumbersShareHelperRecognitionAndRemainSeparate() {
        val source = listOf(sourceChapter("missing-a", null), sourceChapter("missing-b", null))
        val mapped = mapThroughPublicAdapter(source)
        assertEquals(-1f, UNRECOGNIZED_NUMBER)
        assertEquals(listOf(UNRECOGNIZED_NUMBER, UNRECOGNIZED_NUMBER), mapped.map { it.chapter_number })
        val domain = mapped.mapIndexed { index, remote -> chapter(index + 1L).copyFromSChapter(remote) }
        assertEquals(listOf(-1.0, -1.0), domain.map { it.chapterNumber })
        // A different literal in the helper's recognition predicate must fail BEHAVIOR here:
        // both real mapped missing-number chapters are supplied, so erroneous grouping loses one.
        assertUnchangedPlan(
            manga.copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE), domain, domain.first(),
            NovelNavigationPreferences(skipDuplicates = true), ids = listOf(1, 2), next = domain[1],
        )
        assertUnchangedPlan(
            manga.copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER), domain, domain.first(),
            NovelNavigationPreferences(skipDuplicates = true), ids = listOf(1, 2), next = null,
            status = NovelNavigationStatus.UNKNOWN_NUMBER,
        )
    }

    @Test
    fun s32FrozenHttpFixtureHasFourRowsOneUnknownAndSourceSuccessor() {
        // Frozen chapter-list/ad/sidebar markup from the actual offline 803/S31 measurement:
        // offline-number-measurement-s314/execution/inputs/http-torre.txt and counts.json.
        // Full original document: novel-http-source/src/test/resources/fixtures/novel/torre-de-cristal.html
        // SHA256 441bb610a40021a3a908a5090aee14007361d8793f4163bb466501c8604077d8.
        // This saved synthetic HTTP fixture is NOT evidence about complete/live source catalogues.
        val html = """
            <article class="novel-detail" data-novel-id="torre-de-cristal">
              <ul class="chapter-list">
                <li><a href="../chapter/torre-de-cristal-prologo.html">Prologo</a></li>
                <li><a href="../chapter/torre-de-cristal-1.html">Capitulo 1: El ascensor</a></li>
                <li><a href="../chapter/torre-de-cristal-2.html">Capitulo 2: La sala de mapas</a></li>
                <li><a href="../chapter/torre-de-cristal-3.html">Capitulo 3: Viento</a></li>
              </ul>
            </article>
            <div class="ad-slot" data-ad-network="fixture"><ul class="chapter-list">
              <li><a href="../chapter/torre-de-cristal-99.html">Capitulo 99: El que no existe</a></li>
            </ul></div>
            <aside class="sidebar-recommended"><ul class="chapter-list">
              <li><a href="../chapter/quimera-urbana-1.html">Quimera urbana 1</a></li>
            </ul></aside>
        """.trimIndent()
        val novel = Novel("http-fixtures", "torre-de-cristal", "La torre de cristal")
        val rows = parseChapterList(html, novel)
        val before = rows.map { it.copy() }
        assertEquals(4, rows.size)
        assertEquals(1, rows.count { it.number == null })
        assertEquals(listOf(null, 1f, 2f, 3f), rows.map { it.number })
        assertEquals(
            listOf("torre-de-cristal-prologo", "torre-de-cristal-1", "torre-de-cristal-2", "torre-de-cristal-3"),
            rows.map { it.id },
        )
        assertEquals(
            listOf("Prologo", "Capitulo 1: El ascensor", "Capitulo 2: La sala de mapas", "Capitulo 3: Viento"),
            rows.map { it.title },
        )
        val mapped = mapThroughPublicAdapter(rows, novel)
        assertEquals(listOf(-1f, 1f, 2f, 3f), mapped.map { it.chapter_number })
        println("S32_HTTP parsed=${rows.size} sourceMissing=${rows.count { it.number == null }} " +
            "mappedUnknown=${mapped.count { it.chapter_number == UNRECOGNIZED_NUMBER }}")
        val domain = mapped.mapIndexed { index, remote -> chapter(index + 1L).copyFromSChapter(remote) }
        assertUnchangedPlan(
            manga.copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE), domain, domain.first(),
            NovelNavigationPreferences(skipDuplicates = true), ids = listOf(1, 2, 3, 4), next = domain[1],
        )
        assertEquals(before, rows)
    }

    private fun sourceChapter(id: String, number: Float?) = SourceChapter(
        "policy-fixture", "work", id, "Fixture $id", number = number,
    )

    private fun mapThroughPublicAdapter(
        rows: List<SourceChapter>,
        novel: Novel = Novel("policy-fixture", "work", "Fixture work"),
    ): List<SChapter> = runBlocking {
        val source = object : NovelSource {
            override val info = SourceInfo(novel.sourceId, "Policy fixture", "1", "es")
            override suspend fun popular(page: Int): NovelPage = error("No catalogue IO in this test")
            override suspend fun search(query: String, page: Int): NovelPage = error("No search IO in this test")
            override suspend fun getNovelDetails(novel: Novel): Novel = error("Details must not be fetched")
            override suspend fun getChapters(novel: Novel): List<SourceChapter> {
                assertEquals(rows.map { it.novelId }.distinct(), listOf(novel.id))
                return rows
            }
            override suspend fun getChapterContent(chapter: SourceChapter): ChapterContent = error("No content IO")
        }
        val remote = SManga.create().apply {
            url = novel.id
            title = novel.title
        }
        NovelSourceAdapter(source).getMangaUpdate(
            remote, emptyList(), fetchDetails = false, fetchChapters = true,
        ).chapters
    }

    /** Check every planning output and unchanged caller-owned models, even when an assertion fails. */
    private fun assertUnchangedPlan(
        work: Manga,
        rows: List<Chapter>,
        current: Chapter,
        prefs: NovelNavigationPreferences,
        downloads: Set<Long> = emptySet(),
        ids: List<Long>,
        next: Chapter?,
        status: NovelNavigationStatus = if (next == null) NovelNavigationStatus.LAST_CHAPTER else NovelNavigationStatus.NEXT,
    ) {
        val workBefore = work.copy()
        val rowsBefore = rows.map { it.copy() }
        val currentBefore = current.copy()
        val prefsBefore = prefs.copy()
        val downloadsBefore = downloads.toSet()
        try {
            assertPlan(
                resolveNovelChapterNavigation(work, rows, current, prefs, downloads),
                ids, next, status, prefs.downloadedOnly,
            )
        } finally {
            assertEquals(workBefore, work, "Planning must not mutate the work")
            assertEquals(rowsBefore, rows, "Planning must not mutate input chapters or their order")
            assertEquals(currentBefore, current, "Planning must not mutate the current anchor")
            assertEquals(prefsBefore, prefs)
            assertEquals(downloadsBefore, downloads)
        }
    }
}
