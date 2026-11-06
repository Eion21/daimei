package eu.kanade.tachiyomi.extension.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NovelS39ReadOnlyRegressionTest {
    @Test
    fun `S38 stays read only and cannot be promoted to a distribution`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/s38/local-test.json")).use { it.readBytes() }
        val catalog = NovelCatalogCodec.decode(bytes.inputStream())
        assertFalse(catalog.hostCompatible)
        assertFalse(catalog.canInstall)
        val promoted = bytes.toString(
            Charsets.UTF_8,
        ).replace("\"downloadUrl\": null", "\"downloadUrl\": \"https://example.invalid/a.apk\"")
        assertThrows(NovelCatalogException::class.java) { NovelCatalogCodec.decode(promoted.byteInputStream()) }
        assertThrows(NovelDistributionException::class.java) {
            NovelDistributionCatalogCodec.decode(bytes.inputStream())
        }
        for (entry in catalog.extensions) {
            for (action in listOf(
                NovelRemoteActions::download,
                NovelRemoteActions::install,
                NovelRemoteActions::update,
            )) {
                val error = assertThrows(SecurityException::class.java) { action(entry.id) }
                assertEquals("Remote APK actions are disabled in S38 (hostCompatible: false).", error.message)
            }
        }
    }
}
