package eu.kanade.tachiyomi.extension

import app.novel.source.api.NOVEL_API_VERSION
import app.novel.source.api.android.BuildConfig
import eu.kanade.tachiyomi.extension.util.SUPPORTED_NOVEL_API_VERSIONS
import eu.kanade.tachiyomi.extension.util.isSupportedNovelApiVersion
import org.junit.jupiter.api.Assertions.assertEquals
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
     * The Kotlin constants agreeing is not enough: an author depends on a published coordinate, and
     * `app.novel:novel-api` and `app.novel:novel-api-android` are published by two build files. If those two
     * coordinates ever name different contract versions, an extension compiled against one of them would run
     * against a loader built for the other.
     *
     * The two published versions are compared through the values each module actually publishes. `:novel-api`'s
     * published version is [NOVEL_API_VERSION] itself — that constant is the only source its build file reads from.
     * `:novel-api-android`'s is surfaced as [BuildConfig.NOVEL_API_ANDROID_PUBLISHED_VERSION], generated from its
     * resolved `project.version`. The generated value is used rather than a cross-project read because this build
     * runs with Isolated Projects enabled, which rejects any project reading another project's `version`.
     */
    @Test
    fun `the android contract artifact publishes the same version novel-api publishes`() {
        assertEquals(
            NOVEL_API_VERSION,
            BuildConfig.NOVEL_API_ANDROID_PUBLISHED_VERSION,
            "app.novel:novel-api publishes $NOVEL_API_VERSION, but app.novel:novel-api-android was published as " +
                "${BuildConfig.NOVEL_API_ANDROID_PUBLISHED_VERSION}. Both coordinates must name the same contract, " +
                "or an extension compiled against one would run against a loader built for the other.",
        )
    }

    /**
     * Acceptance criterion 2 / decision D3. Each capability so far was added additively — the host
     * network at 1.1, the byte fetch at 1.2 — but an extension already installed at an earlier version
     * must keep loading, otherwise adding a capability silently unloads every extension a user already
     * has. Every superseded version therefore stays in the list beside the current one. This covers
     * each one, so the guarantee is pinned for the version the current raise just superseded too.
     */
    @Test
    fun `the loader still accepts the previous contract version`() {
        assertTrue(
            1.0 in SUPPORTED_NOVEL_API_VERSIONS,
            "The loader runs only $SUPPORTED_NOVEL_API_VERSIONS, so a 1.0 extension would stop loading. " +
                "Keep 1.0 in SUPPORTED_NOVEL_API_VERSIONS when raising the contract additively.",
        )
        assertTrue(
            1.1 in SUPPORTED_NOVEL_API_VERSIONS,
            "The loader runs only $SUPPORTED_NOVEL_API_VERSIONS, so a 1.1 extension would stop loading. " +
                "Keep 1.1 in SUPPORTED_NOVEL_API_VERSIONS when raising the contract additively.",
        )
        assertTrue(
            1.2 in SUPPORTED_NOVEL_API_VERSIONS,
            "The loader runs only $SUPPORTED_NOVEL_API_VERSIONS, so a 1.2 extension would stop loading. " +
                "Keep 1.2 in SUPPORTED_NOVEL_API_VERSIONS when raising the contract additively.",
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

    /**
     * Acceptance criterion 2 / decision D3, pinned through the decision the loader actually branches on. The list
     * assert above checks membership; this checks the predicate that turns membership into "runs or not" for the
     * raise this change introduced. The 1.3 context capability is additive, so the current version must be live and
     * 1.0, 1.1 and 1.2 must keep running — an extension already installed at any of them must not stop loading.
     */
    @Test
    fun `the loader predicate accepts the current version and every previous one`() {
        assertTrue(
            isSupportedNovelApiVersion(NOVEL_API_VERSION),
            "the current contract version $NOVEL_API_VERSION must be accepted by the loader predicate",
        )
        for (previous in listOf(1.0, 1.1, 1.2)) {
            assertTrue(
                isSupportedNovelApiVersion(previous),
                "$previous must keep loading after the raise; it is still in $SUPPORTED_NOVEL_API_VERSIONS. " +
                    "Keep it there when raising the contract additively.",
            )
        }
    }
}
