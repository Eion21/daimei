package eu.kanade.tachiyomi.data.download

import app.novel.source.demo.DemoSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.novel.NovelSourceAdapter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

/**
 * Which sources a download folder can be resolved against.
 *
 * `renewCache` turns every download folder name into a source id by asking this list. A source missing from here has
 * no folder it can ever answer for, and each of its chapters reads as "not downloaded" however much is on disk —
 * which is what happened to novel chapters, whose download badge never turned into the downloaded state.
 *
 * The mistake pinned here is narrow and real: the list used to be `getOnlineSources() + getStubSources()`, and
 * `AndroidSourceManager.getOnlineSources()` is typed `List<HttpSource>` and filters with `filterIsInstance<HttpSource>`.
 * A `NovelSourceAdapter` is a plain `Source`, so it was filtered out and its folder was indexed under nothing.
 */
class DownloadCacheSourceIndexTest {

    private val manager = mockk<SourceManager>()
    private val novelSource = NovelSourceAdapter(DemoSource())

    @Test
    fun `a novel source is indexed even though it is not an HttpSource`() = runTest {
        // Exactly what the real manager answers for this source: `getOnlineSources()` drops it, `getAll()` has it.
        coEvery { manager.getOnlineSources() } returns emptyList()
        coEvery { manager.getAll() } returns listOf(novelSource)
        coEvery { manager.getStubSources() } returns emptyList<StubSource>()

        val sources = manager.sourcesForDownloadIndex()

        assertTrue(
            sources.any { it.id == novelSource.id },
            "the novel source is not among the sources its download folder can be resolved against, so no " +
                "novel chapter can ever read as downloaded",
        )
    }

    @Test
    fun `stub sources are still indexed alongside the registered ones`() = runTest {
        coEvery { manager.getOnlineSources() } returns emptyList()
        coEvery { manager.getAll() } returns listOf<Source>(novelSource)
        coEvery { manager.getStubSources() } returns listOf(stub(OTHER_SOURCE_ID))

        val sources = manager.sourcesForDownloadIndex()

        assertTrue(sources.any { it.id == novelSource.id })
        assertTrue(sources.any { it.id == OTHER_SOURCE_ID }, "a stub source lost its folder")
    }

    @Test
    fun `an online source is indexed too`() = runTest {
        val online = mockk<Source> { every { id } returns ONLINE_SOURCE_ID }
        coEvery { manager.getOnlineSources() } returns emptyList()
        coEvery { manager.getAll() } returns listOf(online)
        coEvery { manager.getStubSources() } returns emptyList<StubSource>()

        assertTrue(manager.sourcesForDownloadIndex().any { it.id == ONLINE_SOURCE_ID })
    }

    private fun stub(id: Long): StubSource = mockk<StubSource> { every { this@mockk.id } returns id }

    private companion object {
        const val OTHER_SOURCE_ID = 7_000_002L
        const val ONLINE_SOURCE_ID = 7_000_003L
    }
}
