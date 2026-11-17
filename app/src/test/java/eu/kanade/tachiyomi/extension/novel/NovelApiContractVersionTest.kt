package eu.kanade.tachiyomi.extension.novel

import app.novel.source.api.NOVEL_API_VERSION
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Local anchor: the novel package's supported-version set must accept the contract version
 * published by `:novel-api`. This is the in-package counterpart to the loader-level test in
 * `eu.kanade.tachiyomi.extension.NovelApiContractVersionTest`.
 */
class NovelApiContractVersionTest {

    @Test
    fun `supported versions include the contract version novel-api publishes`() {
        assertTrue(
            NovelSupportedApiVersions.isSupported("$NOVEL_API_VERSION"),
            "NovelSupportedApiVersions must accept the contract version $NOVEL_API_VERSION",
        )
    }
}
