package eu.kanade.domain.source.interactor

import app.novel.runtime.SourceRegistry
import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.changes
import eu.kanade.tachiyomi.source.novel.isNovelSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tachiyomi.data.novel.NovelMapper
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Pins
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.source.local.isLocal

@Inject
class GetEnabledSources(
    private val repository: SourceRepository,
    private val preferences: SourcePreferences,
    private val sourceRegistry: SourceRegistry,
) {

    fun subscribe(): Flow<List<Source>> {
        return combine(
            preferences.pinnedSources.changes(),
            preferences.enabledLanguages.changes(),
            preferences.disabledSources.changes(),
            preferences.lastUsedSource.changes(),
            combine(repository.getSources(), sourceRegistry.novelSourceIdsChanges()) { sources, novelSourceIds ->
                sources to novelSourceIds
            },
        ) { pinnedSourceIds, enabledLanguages, disabledSources, lastUsedSource, (sources, novelSourceIds) ->
            sources
                .filter { it.lang in enabledLanguages || it.isLocal() || it.isNovelSource(novelSourceIds) }
                .filterNot { it.id.toString() in disabledSources }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .flatMap {
                    val flag = if ("${it.id}" in pinnedSourceIds) Pins.pinned else Pins.unpinned
                    val source = it.copy(pin = flag)
                    val toFlatten = mutableListOf(source)
                    if (source.id == lastUsedSource) {
                        toFlatten.add(source.copy(isUsedLast = true, pin = source.pin - Pin.Actual))
                    }
                    toFlatten
                }
        }
            .distinctUntilChanged()
    }

    /**
     * The novel source ids the registry holds now, re-emitted on every registry change.
     *
     * A novel source publishes in the language it publishes in, and the user has no reason to have enabled that
     * language for it, so it is kept regardless. Derived from the registry rather than snapshotted at construction,
     * which is what lets a novel source installed as an extension — it registers after this interactor exists — be
     * kept exactly like one this build ships with.
     */
    private fun SourceRegistry.novelSourceIdsChanges(): Flow<Set<Long>> =
        changes().map { registry ->
            registry.all().mapTo(mutableSetOf()) { NovelMapper.generateSourceId(it.info.id) }
        }
}
