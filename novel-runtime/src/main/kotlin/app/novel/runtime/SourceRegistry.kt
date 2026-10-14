package app.novel.runtime

import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo

class SourceRegistry {
    private val sources = java.util.concurrent.ConcurrentHashMap<String, NovelSource>()

    fun register(source: NovelSource) {
        sources[source.info.id] = source
    }

    fun get(id: String): NovelSource? = sources[id]

    fun all(): List<NovelSource> = sources.values.sortedBy { it.info.name }
}
