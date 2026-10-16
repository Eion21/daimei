package eu.kanade.tachiyomi.extension

import app.novel.source.api.NOVEL_API_VERSION
import eu.kanade.tachiyomi.extension.util.SUPPORTED_NOVEL_API_VERSIONS
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the host's loader to the contract `:novel-api` declares.
 *
 * The contract version is written in two places that a human edits independently: the constant in `:novel-api` that
 * the artifact and every extension author move against, and the loader's supported list in `ExtensionLoader.kt`.
 * Nothing in the type system ties them together, and the failure mode of a divergence is invisible at build time:
 * every installed novel extension simply stops loading, on every device, until the lists happen to be reconciled.
 *
 * This test is the only thing that turns that silent breakage into a red build when the contract is bumped and the
 * loader is forgotten. It is deliberately in `:app` because only `:app` sees both values at once.
 */
class NovelApiContractVersionTest {

    @Test
    fun `the loader supports the contract version novel-api declares`() {
        assertTrue(
            NOVEL_API_VERSION in SUPPORTED_NOVEL_API_VERSIONS,
            "The loader runs only $SUPPORTED_NOVEL_API_VERSIONS, but :novel-api declares $NOVEL_API_VERSION. " +
                "Every novel extension built against this contract would fail to load. Add $NOVEL_API_VERSION to " +
                "SUPPORTED_NOVEL_API_VERSIONS in ExtensionLoader.kt.",
        )
    }
}
