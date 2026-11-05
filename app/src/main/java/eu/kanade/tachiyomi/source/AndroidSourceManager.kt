// Modified for Daimei; see MODIFICATIONS.md.

package eu.kanade.tachiyomi.source

import app.novel.runtime.SourceRegistry
import app.novel.source.api.NovelSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.novel.NovelSourceAdapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import java.util.concurrent.ConcurrentHashMap

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidSourceManager(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val extensionManager: ExtensionManager,
    private val sourceRepository: StubSourceRepository,
    private val localSource: LocalSource,
    private val sourceRegistry: SourceRegistry,
    private val downloadManager: Lazy<DownloadManager>,
) : SourceManager {

    /**
     * One adapter per registered novel source, kept as the registry grows. Caching them by source id rather than
     * rebuilding on each emission means the seed map and the `sources` flow hand out the same instance, so a novel
     * is never equal-and-yet-distinct from itself; a source that is replaced by a new instance replaces its adapter.
     */
    private val novelSources = ConcurrentHashMap<String, NovelSourceAdapter>()

    /**
     * Null until the extensions have loaded, so that nothing observes the empty seed value.
     */
    private val sourcesMapFlow = MutableStateFlow<Map<Long, Source>?>(null)

    private val stubSourcesMap = ConcurrentHashMap<Long, StubSource>()

    override val sources: Flow<List<Source>> = sourcesMapFlow
        .filterNotNull()
        .map { it.values.toList() }

    init {
        scope.launch {
            // Rebuilt from the registry on every registration, not only at construction, so a novel source that is
            // installed (and registered) while the app runs appears without a restart. The extensions themselves
            // are still the other half of the map.
            combine(
                extensionManager.loadedExtensionsFlow,
                sourceRegistry.changes(),
            ) { extensions, _ -> extensions }
                .collectLatest { rebuildSources(it) }
        }

        scope.launch {
            sourceRepository.subscribeAll()
                .collectLatest { sources ->
                    sources.forEach {
                        stubSourcesMap[it.id] = it
                    }
                }
        }
    }

    /**
     * The sources map for the current registry and extension state. The novel sources come from the registry, so a
     * source registered after this manager was constructed is picked up; the extension sources keep being merged
     * exactly as before, so the manga path is untouched.
     */
    private fun rebuildSources(extensions: List<Extension.Loaded>) {
        val mutableMap = ConcurrentHashMap<Long, Source>(
            mapOf(LocalSource.ID to localSource) +
                sourceRegistry.all().map(::adapterFor).associateBy(NovelSourceAdapter::id),
        )
        extensions.forEach { extension ->
            extension.sources.forEach {
                mutableMap[it.id] = it
                registerStubSource(StubSource.from(it))
            }
        }
        sourcesMapFlow.value = mutableMap
    }

    /**
     * The one adapter for [novelSource], reused across rebuilds and replaced only when the registry holds a new
     * instance for the same id.
     */
    private fun adapterFor(novelSource: NovelSource): NovelSourceAdapter =
        novelSources.compute(novelSource.info.id) { _, existing ->
            if (existing?.novelSource === novelSource) existing else NovelSourceAdapter(novelSource)
        }!!

    /**
     * Awaits the extensions to have loaded before returning the sources.
     */
    private suspend fun sourcesMap(): Map<Long, Source> = sourcesMapFlow.filterNotNull().first()

    override suspend fun get(sourceKey: Long): Source? {
        return sourcesMap()[sourceKey]
    }

    override suspend fun getOrStub(sourceKey: Long): Source {
        return sourcesMap()[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            createStubSource(sourceKey)
        }
    }

    override suspend fun getAll(): List<Source> {
        return sourcesMap().values.toList()
    }

    override suspend fun getOnlineSources(): List<HttpSource> {
        return sourcesMap().values.filterIsInstance<HttpSource>()
    }

    override suspend fun getStubSources(): List<StubSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(source: StubSource) {
        scope.launch {
            val dbSource = sourceRepository.getStubSource(source.id)
            if (dbSource == source) return@launch
            sourceRepository.upsertStubSource(source.id, source.lang, source.name)
            if (dbSource != null) {
                downloadManager.value.renameSource(dbSource, source)
            }
        }
    }

    private suspend fun createStubSource(id: Long): StubSource {
        sourceRepository.getStubSource(id)?.let {
            return it
        }
        extensionManager.getSourceData(id)?.let {
            registerStubSource(it)
            return it
        }
        return StubSource(id = id, lang = "", name = "")
    }
}

/**
 * Emits the registry on subscription and again after every change to it.
 *
 * `SourceRegistry` exposes plain callbacks, because `:novel-runtime` has no coroutines dependency and must stay
 * runnable on a plain JVM; this is the one place its changes are bridged into a Flow, so both the source manager
 * and [eu.kanade.domain.source.interactor.GetEnabledSources] react to a source registered after they started.
 */
internal fun SourceRegistry.changes(): Flow<SourceRegistry> = callbackFlow {
    val listener: () -> Unit = { trySend(this@changes) }
    addListener(listener)
    trySend(this@changes)
    awaitClose { removeListener(listener) }
}
