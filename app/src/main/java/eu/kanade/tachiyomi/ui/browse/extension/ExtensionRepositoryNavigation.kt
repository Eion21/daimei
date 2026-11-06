// Modified for Daimei; see MODIFICATIONS.md.

package eu.kanade.tachiyomi.ui.browse.extension

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.browse.extension.novel.NovelCatalogScreen

fun openExtensionRepositories(navigate: (Screen) -> Unit) {
    navigate(NovelCatalogScreen())
}

fun extensionRepositoryOverflowActions(
    filterLabel: String,
    repositoryLabel: String,
    openFilter: () -> Unit,
    navigate: (Screen) -> Unit,
): List<AppBar.OverflowAction> = listOf(
    AppBar.OverflowAction(filterLabel, openFilter),
    AppBar.OverflowAction(repositoryLabel) { openExtensionRepositories(navigate) },
)

fun rejectLegacyMangaRepositoryLink(scheme: String?, host: String?, notice: () -> Unit): Boolean {
    val legacy = (scheme == "tachiyomi" && host == "add-repo") ||
        (scheme == "mihon" && host == "extension-store")
    if (legacy) notice()
    return legacy
}
