// Modified for Daimei; see MODIFICATIONS.md.

package eu.kanade.tachiyomi.data.download.model

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Duration.Companion.milliseconds

/**
 * A chapter waiting to be, or being, downloaded.
 *
 * [source] is a [Source] rather than an `HttpSource` on purpose. A novel chapter is downloaded too, and its source
 * is a plain [Source] that has no page pipeline at all — so typing this to `HttpSource` did not restrict the queue
 * to image chapters, it made novel chapters unreachable with no error and no log: the queue simply never accepted
 * them. Typing it to the transport the source actually is leaves the page pipeline where it belongs, at the few
 * places that use it, and lets every source reach the one queue.
 */
data class Download(
    val source: Source,
    val manga: Manga,
    val chapter: Chapter,
) {
    @Transient
    private val _pagesFlow = MutableStateFlow<List<Page>?>(null)

    @Transient
    val pagesFlow = _pagesFlow.asStateFlow()
    var pages: List<Page>?
        get() = _pagesFlow.value
        set(pages) {
            _pagesFlow.value = pages
        }

    val totalProgress: Int
        get() = pages?.sumOf(Page::progress) ?: 0

    val downloadedImages: Int
        get() = pages?.count { it.status == Page.State.Ready } ?: 0

    @Transient
    private val _statusFlow = MutableStateFlow(State.NOT_DOWNLOADED)

    @Transient
    val statusFlow = _statusFlow.asStateFlow()
    var status: State
        get() = _statusFlow.value
        set(status) {
            _statusFlow.value = status
        }

    @Transient
    val progressFlow = _pagesFlow
        .flatMapLatest { pages ->
            if (pages == null) {
                flowOf(0)
            } else {
                combine(pages.map(Page::progressFlow)) { it.average().toInt() }
            }
        }
        .distinctUntilChanged()
        .debounce(50.milliseconds)

    val progress: Int
        get() {
            val pages = pages ?: return 0
            return pages.map(Page::progress).average().toInt()
        }

    enum class State(val value: Int) {
        NOT_DOWNLOADED(0),
        QUEUE(1),
        DOWNLOADING(2),
        DOWNLOADED(3),
        ERROR(4),
    }
}
