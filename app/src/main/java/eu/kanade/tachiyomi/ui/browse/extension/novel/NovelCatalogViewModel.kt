package eu.kanade.tachiyomi.ui.browse.extension.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.extension.novel.NovelCatalogClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelCatalogViewModel : ViewModel() {
    private val client = NovelCatalogClient()
    private val presentation = NovelCatalogPresentation(client::fetch)
    private var queryJob: Job? = null
    val state = presentation.state

    fun query(url: String) {
        queryJob?.cancel()
        queryJob = viewModelScope.launch { presentation.query(url) }
    }
}
