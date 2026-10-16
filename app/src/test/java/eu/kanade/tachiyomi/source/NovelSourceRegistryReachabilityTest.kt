package eu.kanade.tachiyomi.source

import app.novel.runtime.SourceRegistry
import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.novel.NovelMapper
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.domain.source.model.Source as DomainSource

/**
 * Where an extension-installed novel source has to reach to be usable at all.
 *
 * Two things read the registry and neither can be proven in isolation: `AndroidSourceManager` builds the catalog
 * from it, and `GetEnabledSources` reads it to keep a novel source regardless of the user's enabled languages. A
 * source installed as an extension registers *after* both were constructed, so a snapshot taken at construction
 * time is exactly the bug: the source would never appear, or appear and be filtered out. These tests drive the real
 * `SourceRegistry` through the real manager and interactor, because that combination is what was never tested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NovelSourceRegistryReachabilityTest {

    private val registry = SourceRegistry()
    private val extensionManager = mockk<ExtensionManager>(relaxed = true)
    private val stubSourceRepository = mockk<StubSourceRepository>(relaxed = true)

    @Test
    fun `a novel source registered after the manager was constructed becomes visible`() = runTest {
        every { extensionManager.loadedExtensionsFlow } returns flowOf(emptyList<Extension.Loaded>())
        every { stubSourceRepository.subscribeAll() } returns emptyFlow()
        coEvery { stubSourceRepository.getStubSource(any()) } returns null

        // The built-in (DI-registered) source exists before the manager, the way Demo and HTTP do.
        registry.register(novelSource("built-in", "Built-in", "en"))
        val manager = newManager(collectorScope())
        advanceUntilIdle()

        assertTrue(
            manager.getAll().any { it.id == NovelMapper.generateSourceId("built-in") },
            "a novel source the registry held before construction is missing from the catalog",
        )

        // The late one arrives while the app runs, the way an installed extension's does.
        registry.register(novelSource("late", "Late", "en"))
        advanceUntilIdle()

        assertTrue(
            manager.getAll().any { it.id == NovelMapper.generateSourceId("late") },
            "a novel source registered after construction never appeared; the catalog is frozen at the constructor",
        )
    }

    @Test
    fun `a novel source unregistered after the manager was constructed disappears`() = runTest {
        every { extensionManager.loadedExtensionsFlow } returns flowOf(emptyList<Extension.Loaded>())
        every { stubSourceRepository.subscribeAll() } returns emptyFlow()
        coEvery { stubSourceRepository.getStubSource(any()) } returns null

        registry.register(novelSource("removable", "Removable", "en"))
        val manager = newManager(collectorScope())
        advanceUntilIdle()
        val id = NovelMapper.generateSourceId("removable")
        assertTrue(manager.getAll().any { it.id == id })

        registry.unregister("removable")
        advanceUntilIdle()

        assertTrue(
            manager.getAll().none { it.id == id },
            "a novel source whose extension was uninstalled kept answering from the catalog",
        )
    }

    @Test
    fun `a novel source registered after subscription is kept regardless of enabled languages`() = runTest {
        // The preferences are stubbed rather than driven through InMemoryPreferenceStore: that store's
        // `changes()` is an empty flow (only `asStateFlow` seeds a value), so the real store would never let the
        // interactor emit at all, and this test is about the registry, not the preference double.
        val preferences = mockk<SourcePreferences>(relaxed = true)
        every { preferences.pinnedSources } returns preferenceWith(emptySet<String>())
        every { preferences.enabledLanguages } returns preferenceWith(setOf("en"))
        every { preferences.disabledSources } returns preferenceWith(emptySet<String>())
        every { preferences.lastUsedSource } returns preferenceWith(-1L)

        val repository = mockk<SourceRepository>()
        val domainSource = DomainSource(
            id = NovelMapper.generateSourceId("late"),
            lang = "pt",
            name = "Late",
            supportsLatest = false,
            isStub = false,
        )
        every { repository.getSources() } returns MutableStateFlow(listOf(domainSource))

        val getEnabledSources = GetEnabledSources(repository, preferences, registry)

        val emitted = mutableListOf<List<DomainSource>>()
        collectorScope().launch { getEnabledSources.subscribe().collect { emitted += it } }
        advanceUntilIdle()

        // Registers the way an extension install does, after the interactor is already subscribed.
        registry.register(novelSource("late", "Late", "pt"))
        advanceUntilIdle()

        assertTrue(
            emitted.last().any { it.id == domainSource.id },
            "the novel source was filtered out by the language check because the registry change was never seen",
        )
    }

    /**
     * A scope on the test scheduler, for long-lived collectors.
     *
     * `runTest`'s own `backgroundScope` is not advanced by `advanceUntilIdle()` in this project's coroutines
     * version, so a collector that must survive across emissions lives on an explicit scope sharing
     * `testScheduler`, matching the existing download tests.
     */
    private fun TestScope.collectorScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler))

    private fun newManager(scope: CoroutineScope): SourceManager = AndroidSourceManager(
        scope = scope,
        extensionManager = extensionManager,
        sourceRepository = stubSourceRepository,
        localSource = mockk<LocalSource>(relaxed = true),
        sourceRegistry = registry,
        downloadManager = lazy { mockk(relaxed = true) },
    )

    private fun preferenceWith(value: Set<String>): Preference<Set<String>> = mockk {
        every { changes() } returns flowOf(value)
    }

    private fun preferenceWith(value: Long): Preference<Long> = mockk {
        every { changes() } returns flowOf(value)
    }

    private fun novelSource(id: String, name: String, lang: String) = object : NovelSource {
        override val info = SourceInfo(id, name, "1", lang)
        override suspend fun popular(page: Int) = NovelPage(emptyList(), hasNextPage = false)
        override suspend fun search(query: String, page: Int) = NovelPage(emptyList(), hasNextPage = false)
        override suspend fun getNovelDetails(novel: Novel) = novel
        override suspend fun getChapters(novel: Novel) = emptyList<Chapter>()
        override suspend fun getChapterContent(chapter: Chapter) = ChapterContent(emptyList())
    }
}
