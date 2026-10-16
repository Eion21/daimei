package eu.kanade.tachiyomi.extension

import app.novel.source.api.NOVEL_API_VERSION
import eu.kanade.tachiyomi.extension.util.SUPPORTED_NOVEL_API_VERSIONS
import org.junit.jupiter.api.Assertions.assertFalse
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

    /**
     * Acceptance criterion 2 / decision D3. The host network was added at 1.1, additively, but an
     * extension already installed at 1.0 must keep loading — otherwise adding a capability silently
     * unloads every extension a user already has. The previous version therefore stays in the list
     * beside the current one.
     */
    @Test
    fun `the loader still accepts the previous contract version`() {
        assertTrue(
            1.0 in SUPPORTED_NOVEL_API_VERSIONS,
            "The loader runs only $SUPPORTED_NOVEL_API_VERSIONS, so a 1.0 extension would stop loading. " +
                "Keep 1.0 in SUPPORTED_NOVEL_API_VERSIONS when raising the contract additively.",
        )
    }

    /**
     * The other half of the same guard: tolerating the previous version must not become tolerating
     * every version. A contract this app cannot run is still refused before anything is instantiated.
     */
    @Test
    fun `a genuinely unsupported contract version is still rejected`() {
        assertFalse(2.0 in SUPPORTED_NOVEL_API_VERSIONS)
        assertFalse(0.9 in SUPPORTED_NOVEL_API_VERSIONS)
    }
}
