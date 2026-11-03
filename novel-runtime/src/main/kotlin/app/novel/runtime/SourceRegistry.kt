package app.novel.runtime

import app.novel.source.api.NovelSource
import java.util.concurrent.CopyOnWriteArrayList

class SourceRegistry {
    private data class State(
        val sources: Map<String, NovelSource> = emptyMap(),
        val sourcesBySourceId: Map<Long, NovelSource> = emptyMap(),
    )

    private val writerLock = Any()

    // Both indexes are published together; maps in a published state are never mutated.
    @Volatile
    private var state = State()

    /**
     * Called after the registry's content changes, so a consumer can rebuild from the current state instead of the
     * snapshot it saw when it started. Plain callbacks rather than a flow on purpose: this module has no coroutines
     * dependency and must stay runnable on a plain JVM, so the Android layer bridges these to a Flow where it needs
     * one.
     */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun register(source: NovelSource) {
        val id = source.info.id
        val numericId = NovelSourceId.generate(id)
        val changed = synchronized(writerLock) {
            val current = state
            val previous = current.sources[id]
            state = State(current.sources + (id to source), current.sourcesBySourceId + (numericId to source))
            previous !== source
        }
        // Callbacks rebuild the latest state and must not run under the writer lock.
        if (changed) notifyChanged()
    }

    /**
     * Removes the source [id] identifies, if any.
     *
     * A source whose apk was uninstalled must stop resolving: the numeric id it derived is already written into
     * `manga.source_id`, so leaving it here would keep answering for rows whose real source is gone. Only notifies
     * when there was something to remove.
     */
    fun unregister(id: String) {
        val numericId = NovelSourceId.generate(id)
        val changed = synchronized(writerLock) {
            val current = state
            if (id !in current.sources) {
                false
            } else {
                state = State(current.sources - id, current.sourcesBySourceId - numericId)
                true
            }
        }
        if (changed) notifyChanged()
    }

    fun get(id: String): NovelSource? = state.sources[id]

    /**
     * The source [sourceId] identifies, or null when nothing registered derives that id.
     *
     * `manga.source_id` is the only thing a stored row keeps about where it came from, so this is how a chapter that
     * is already in the library gets back to the source that can fetch it. Null rather than an error: a source that
     * was removed or is not enabled yet is a normal state, not a failure.
     */
    fun getBySourceId(sourceId: Long): NovelSource? = state.sourcesBySourceId[sourceId]

    fun all(): List<NovelSource> = state.sources.values.sortedBy { it.info.name }

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
