package eu.kanade.domain.source.interactor

import app.novel.runtime.SourceRegistry
import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.novel.isNovelSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    sourceRegistry: SourceRegistry,
) {

    /**
     * A novel source publishes in the language it publishes in, and the user has no reason to have enabled that
     * language for it, so it is kept regardless. Derived from the registry rather than hardcoded, which is what
     * lets a second novel source show up by being registered.
     */
    private val novelSourceIds = sourceRegistry.all()
        .mapTo(mutableSetOf()) { NovelMapper.generateSourceId(it.info.id) }

    fun subscribe(): Flow<List<Source>> {
        return combine(
            preferences.pinnedSources.changes(),
            preferences.enabledLanguages.changes(),
            preferences.disabledSources.changes(),
            preferences.lastUsedSource.changes(),
            repository.getSources(),
        ) { pinnedSourceIds, enabledLanguages, disabledSources, lastUsedSource, sources ->
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
}
