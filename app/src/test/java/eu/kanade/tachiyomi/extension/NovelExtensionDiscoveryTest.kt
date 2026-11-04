package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.extension.util.ExtensionGateDecision
import eu.kanade.tachiyomi.extension.util.decideExtensionGate
import eu.kanade.tachiyomi.extension.util.isEligibleExtensionForDiscovery
import eu.kanade.tachiyomi.extension.util.isSupportedNovelApiVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Exercises the feature eligibility used by both scans and package-name lookup, before the load gate. */
class NovelExtensionDiscoveryTest {

    @Test
    fun `manga packages are not eligible for novel discovery`() {
        assertFalse(isEligibleExtensionForDiscovery(listOf("tachiyomi.extension")))
    }

    @Test
    fun `novel feature remains eligible for MatchaNovels SkyNovels and JabraScan`() {
        // Eligibility is based on the declared feature, not on package or source names.
        for (source in listOf("MatchaNovels", "SkyNovels", "JabraScan")) {
            assertTrue(isEligibleExtensionForDiscovery(listOf("app.novel.extension")), source)
        }
    }

    @Test
    fun `dual feature packages are classified as manga and excluded`() {
        assertFalse(isEligibleExtensionForDiscovery(listOf("app.novel.extension", "tachiyomi.extension")))
        assertFalse(isEligibleExtensionForDiscovery(listOf("tachiyomi.extension", "app.novel.extension")))
    }

    @Test
    fun `missing and unrelated feature names are safe and excluded`() {
        assertFalse(isEligibleExtensionForDiscovery(emptyList()))
        assertFalse(isEligibleExtensionForDiscovery(listOf(null, "android.hardware.camera")))
        assertTrue(isEligibleExtensionForDiscovery(listOf(null, "app.novel.extension")))
    }

    @Test
    fun `unsupported novel remains eligible and is refused by the version gate`() {
        assertTrue(isEligibleExtensionForDiscovery(listOf("app.novel.extension")))
        assertEquals(
            ExtensionGateDecision.UNSUPPORTED_VERSION,
            decideExtensionGate(
                supportedVersion = isSupportedNovelApiVersion(2.0),
                hasSignatures = true,
                isTrusted = true,
                contentWarningAllowed = true,
            ),
        )
    }

    @Test
    fun `untrusted novel remains eligible and is refused by the trust gate`() {
        assertTrue(isEligibleExtensionForDiscovery(listOf("app.novel.extension")))
        assertEquals(
            ExtensionGateDecision.UNTRUSTED,
            decideExtensionGate(
                supportedVersion = isSupportedNovelApiVersion(1.0),
                hasSignatures = true,
                isTrusted = false,
                contentWarningAllowed = true,
            ),
        )
    }

    @Test
    fun `supported signed trusted novel remains eligible and passes the gate`() {
        assertTrue(isEligibleExtensionForDiscovery(listOf("app.novel.extension")))
        assertEquals(
            ExtensionGateDecision.ALLOWED,
            decideExtensionGate(
                supportedVersion = isSupportedNovelApiVersion(1.0),
                hasSignatures = true,
                isTrusted = true,
                contentWarningAllowed = true,
            ),
        )
    }
}
