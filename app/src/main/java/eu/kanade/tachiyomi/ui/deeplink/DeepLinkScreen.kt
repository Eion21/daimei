package eu.kanade.tachiyomi.ui.deeplink

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.novel.ChapterOpener
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

class DeepLinkScreen(
    val query: String = "",
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val viewModel = assistedMetroViewModel<DeepLinkViewModel, DeepLinkViewModel.Factory> { create(query = query) }
        val state by viewModel.state.collectAsState()
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.action_search_hint),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when (state) {
                is DeepLinkViewModel.State.Loading -> {
                    LoadingScreen(Modifier.padding(contentPadding))
                }
                is DeepLinkViewModel.State.NoResults -> {
                    navigator.replace(GlobalSearchScreen(query))
                }
                is DeepLinkViewModel.State.Result -> {
                    val resultState = state as DeepLinkViewModel.State.Result
                    if (resultState.chapterId == null) {
                        navigator.replace(
                            MangaScreen(
                                resultState.manga.id,
                                true,
                            ),
                        )
                    } else {
                        // The reader is opened before this screen is popped, not after: popping disposes the
                        // composition, which would cancel the coroutine still reading the chapter row, and the
                        // activity would never start. The effect is keyed so a recomposition cannot open twice.
                        val chapterId = resultState.chapterId
                        LaunchedEffect(chapterId) {
                            ChapterOpener.openChapter(context, resultState.manga.id, chapterId)
                            navigator.pop()
                        }
                    }
                }
            }
        }
    }
}
