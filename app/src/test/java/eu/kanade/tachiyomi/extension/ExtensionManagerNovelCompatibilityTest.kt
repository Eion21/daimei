package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.extension.model.Extension
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionManagerNovelCompatibilityTest {
    private val store = ExtensionStore(
        indexUrl = "https://example.org/index.json",
        name = "Legacy",
        badgeLabel = "Legacy",
        signingKey = "key",
        contact = ExtensionStore.Contact(website = "https://example.org", discord = null),
        isLegacy = true,
        extensionListUrl = null,
    )

    private fun entry(name: String, pkgName: String, libVersion: Double) = Extension.Available(
        name = name,
        pkgName = pkgName,
        versionName = "1.0",
        versionCode = 1,
        libVersion = libVersion,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        sources = mutableListOf(Extension.Available.Source(123, "en", "Source", "https://example.org")),
        apkUrl = "https://example.org/extension.apk",
        iconUrl = "https://example.org/icon.png",
        store = store,
    )

    @Test
    fun `legacy manga catalog entries are excluded without mutating input`() {
        val manga = entry("Manga", "eu.kanade.tachiyomi.extension.en.manga", 1.4)
        val entries = mutableListOf(manga)
        val snapshot = manga.copy(sources = manga.sources.toList())

        val result = novelEntriesFromLegacyMangaCatalog(entries)

        assertEquals(listOf(snapshot), entries)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `novel-like name package and library version do not prove compatibility`() {
        val misleading = entry("Novel Source", "app.novel.extension.en.source", 1.3)
        val entries = mutableListOf(misleading)
        val snapshot = misleading.copy(sources = misleading.sources.toList())

        val result = novelEntriesFromLegacyMangaCatalog(entries)

        assertEquals(listOf(snapshot), entries)
        assertTrue(result.isEmpty())
    }
}
