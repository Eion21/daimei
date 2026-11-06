package eu.kanade.tachiyomi.ui.browse.extension.novel

import eu.kanade.tachiyomi.extension.novel.NovelCatalog
import eu.kanade.tachiyomi.extension.novel.NovelCatalogClient
import eu.kanade.tachiyomi.extension.novel.NovelCatalogError
import eu.kanade.tachiyomi.extension.novel.NovelCatalogException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface NovelCatalogState {
    data object Initial : NovelCatalogState
    data object Loading : NovelCatalogState
    data class Error(val reason: NovelCatalogError) : NovelCatalogState
    data class Metadata(val origin: String, val entries: List<NovelCatalogRow>) : NovelCatalogState
}

data class NovelCatalogRow(
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val novelApi: String,
) {
    val hostCompatible: Boolean get() = false
}

/** Independent of Android and of the manga repository, installer, and trust state. */
class NovelCatalogPresentation(private val fetch: suspend (String) -> NovelCatalog) {
    private val lock = Any()
    private var generation = 0L
    private val mutableState = MutableStateFlow<NovelCatalogState>(NovelCatalogState.Initial)
    val state: StateFlow<NovelCatalogState> = mutableState.asStateFlow()

    suspend fun query(url: String) {
        val request = synchronized(lock) {
            generation += 1
            mutableState.value = NovelCatalogState.Loading
            generation
        }
        try {
            val endpoint = NovelCatalogClient.catalogUrl(url)
            val origin = endpoint.newBuilder().query(null).fragment(null).build().toString()
            val catalog = fetch(url)
            currentCoroutineContext().ensureActive()
            val rows = catalog.extensions.map {
                NovelCatalogRow(it.id, it.name, it.versionName, it.versionCode, it.novelApi)
            }
            publish(request, NovelCatalogState.Metadata(origin, rows))
        } catch (error: NovelCatalogException) {
            currentCoroutineContext().ensureActive()
            publish(request, NovelCatalogState.Error(error.reason))
        }
    }

    private fun publish(request: Long, state: NovelCatalogState) {
        synchronized(lock) {
            if (generation == request) mutableState.value = state
        }
    }
}
