package app.novel.runtime

import app.novel.source.api.NovelSource
import java.util.concurrent.CopyOnWriteArrayList

class SourceRegistry {
    private val sources = java.util.concurrent.ConcurrentHashMap<String, NovelSource>()

    /**
     * The same sources indexed by the numeric id Mihon persists, so a stored row can be resolved back to the source
     * it came from. Derived with [NovelSourceId] rather than stored, because the registry never learns an id from
     * outside: it only knows sources, and the mapping is the whole point.
     */
    private val sourcesBySourceId = java.util.concurrent.ConcurrentHashMap<Long, NovelSource>()

    /**
     * Called after the registry's content changes, so a consumer can rebuild from the current state instead of the
     * snapshot it saw when it started. Plain callbacks rather than a flow on purpose: this module has no coroutines
     * dependency and must stay runnable on a plain JVM, so the Android layer bridges these to a Flow where it needs
     * one.
     */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun register(source: NovelSource) {
        val id = source.info.id
        val previous = sources.put(id, source)
        sourcesBySourceId[NovelSourceId.generate(id)] = source
        // Re-registering the same instance is not a change: notifying would only make every listener rebuild the
        // state it already has, and a source that arrives from an install event is registered on every reload.
        if (previous !== source) {
            notifyChanged()
        }
    }

    /**
     * Removes the source [id] identifies, if any.
     *
     * A source whose apk was uninstalled must stop resolving: the numeric id it derived is already written into
     * `manga.source_id`, so leaving it here would keep answering for rows whose real source is gone. Only notifies
     * when there was something to remove.
     */
    fun unregister(id: String) {
        if (sources.remove(id) == null) return
        sourcesBySourceId.remove(NovelSourceId.generate(id))
        notifyChanged()
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

    /** Registers [listener] to be called after every change; see [listeners]. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }
}
