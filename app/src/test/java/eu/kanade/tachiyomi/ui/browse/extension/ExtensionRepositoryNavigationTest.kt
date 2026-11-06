package eu.kanade.tachiyomi.ui.browse.extension

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.browse.extension.novel.NovelCatalogScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionRepositoryNavigationTest {
    @Test
    fun menuPreservesFilterAndOpensOneNovelRepositoryScreen() {
        var filters = 0
        val screens = mutableListOf<Screen>()
        val actions = extensionRepositoryOverflowActions("Filter", "Repositories", { filters++ }, { screens.add(it) })
        assertEquals(listOf("Filter", "Repositories"), actions.map { it.title })
        actions[0].onClick()
        assertEquals(1, filters)
        assertTrue(screens.isEmpty())
        actions[1].onClick()
        assertEquals(1, screens.size)
        assertEquals(1, filters)
        assertTrue(screens.single() is NovelCatalogScreen)
        openExtensionRepositories { screens.add(it) }
        assertEquals(2, screens.size)
        assertEquals(1, filters)
        assertTrue(screens.last() is NovelCatalogScreen)
    }

    @Test
    fun rejectsBothLegacyPairsAndLeavesUnrelatedInputsAlone() {
        var notices = 0
        val notice: () -> Unit = { notices++ }
        assertTrue(rejectLegacyMangaRepositoryLink("tachiyomi", "add-repo", notice))
        assertTrue(rejectLegacyMangaRepositoryLink("mihon", "extension-store", notice))
        assertEquals(2, notices)
        assertFalse(rejectLegacyMangaRepositoryLink("https", "add-repo", notice))
        assertFalse(rejectLegacyMangaRepositoryLink("tachiyomi", "extension-store", notice))
        assertFalse(rejectLegacyMangaRepositoryLink(null, null, notice))
        assertEquals(2, notices)
    }
}
