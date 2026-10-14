package app.novel.runtime

import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo

class SourceRegistry {
    private val sources = java.util.concurrent.ConcurrentHashMap<String, NovelSource>()

    /**
     * The same sources indexed by the numeric id Mihon persists, so a stored row can be resolved back to the source
     * it came from. Derived with [NovelSourceId] rather than stored, because the registry never learns an id from
     * outside: it only knows sources, and the mapping is the whole point.
     */
    private val sourcesBySourceId = java.util.concurrent.ConcurrentHashMap<Long, NovelSource>()

    fun register(source: NovelSource) {
        sources[source.info.id] = source
        sourcesBySourceId[NovelSourceId.generate(source.info.id)] = source
    }

    fun get(id: String): NovelSource? = sources[id]

    /**
     * The source [sourceId] identifies, or null when nothing registered derives that id.
     *
     * `manga.source_id` is the only thing a stored row keeps about where it came from, so this is how a chapter that
     * is already in the library gets back to the source that can fetch it. Null rather than an error: a source that
     * was removed or is not enabled yet is a normal state, not a failure.
     */
    fun getBySourceId(sourceId: Long): NovelSource? = sourcesBySourceId[sourceId]

    fun all(): List<NovelSource> = sources.values.sortedBy { it.info.name }
}
