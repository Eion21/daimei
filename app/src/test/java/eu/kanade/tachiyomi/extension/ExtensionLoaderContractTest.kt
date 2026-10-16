package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.extension.util.ExtensionGateDecision
import eu.kanade.tachiyomi.extension.util.ExtensionKind
import eu.kanade.tachiyomi.extension.util.contractVersionFromMetadata
import eu.kanade.tachiyomi.extension.util.decideExtensionGate
import eu.kanade.tachiyomi.extension.util.extensionKindOf
import eu.kanade.tachiyomi.extension.util.isExtensionFeature
import eu.kanade.tachiyomi.extension.util.isSupportedNovelApiVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The decisions the extension loader makes before it touches any Android type.
 *
 * `ExtensionLoader` is `internal` and its body needs a real `PackageInfo` and `PackageManager`, so it cannot be
 * driven from a plain JVM test — and a JVM test must not pretend otherwise. What it can do is pin the decisions the
 * loader delegates to, which are the ones with a right and a wrong answer: which apks count as extensions, which
 * kind each is, which contract versions run, and — the security-relevant one — the order in which a load is refused.
 */
class ExtensionLoaderContractTest {

    // Feature detection

    @Test
    fun `a manga extension feature is recognized`() {
        assertTrue(isExtensionFeature("tachiyomi.extension"))
        assertEquals(ExtensionKind.MANGA, extensionKindOf(listOf("tachiyomi.extension")))
    }

    @Test
    fun `a novel extension feature is recognized and classified as novel`() {
        assertTrue(isExtensionFeature("app.novel.extension"))
        assertEquals(ExtensionKind.NOVEL, extensionKindOf(listOf("app.novel.extension")))
    }

    @Test
    fun `an unrelated feature is not an extension`() {
        assertFalse(isExtensionFeature("android.hardware.camera"))
        assertNull(extensionKindOf(listOf("android.hardware.camera")))
    }

    @Test
    fun `a feature with no name is not an extension rather than a crash`() {
        // FeatureInfo.name is a platform type that can be absent. Treating that as a programming error took down
        // the whole extension list on a device, manga included, because the failure happened while filtering
        // packages rather than while examining one. An unnamed feature is "not an extension", nothing more.
        assertFalse(isExtensionFeature(null))
        // The call site filters absent names out before classifying, so an unnamed feature cannot reach the
        // classifier either. listOfNotNull mirrors that `.mapNotNull { it.name }`.
        assertNull(extensionKindOf(listOfNotNull(null, "android.hardware.faketouch")))
    }

    @Test
    fun `a package declaring both features stays a manga extension so the existing path is untouched`() {
        assertEquals(
            ExtensionKind.MANGA,
            extensionKindOf(listOf("app.novel.extension", "tachiyomi.extension")),
        )
    }

    // Contract version

    @Test
    fun `the supported novel contract versions are the declared list`() {
        assertTrue(isSupportedNovelApiVersion(1.0))
    }

    @Test
    fun `an unsupported novel contract version is rejected`() {
        assertFalse(isSupportedNovelApiVersion(2.0))
        assertFalse(isSupportedNovelApiVersion(0.9))
    }

    @Test
    fun `a missing contract version is rejected rather than treated as zero`() {
        assertNull(contractVersionFromMetadata(0f))
        assertFalse(isSupportedNovelApiVersion(null))
    }

    @Test
    fun `a declared contract version is read from the metadata float`() {
        assertEquals(1.0, contractVersionFromMetadata(1.0f))
    }

    // Load gate — the order is the contract

    @Test
    fun `an unsigned novel extension is rejected before anything is instantiated`() {
        // This is the regression test: if the novel branch is ever moved ahead of the signature check, the gate
        // stops being what decides, and this expectation no longer holds.
        assertEquals(
            ExtensionGateDecision.UNSIGNED,
            decideExtensionGate(
                supportedVersion = true,
                hasSignatures = false,
                isTrusted = false,
                contentWarningAllowed = true,
            ),
        )
    }

    @Test
    fun `a signed but untrusted novel extension is rejected`() {
        assertEquals(
            ExtensionGateDecision.UNTRUSTED,
            decideExtensionGate(
                supportedVersion = true,
                hasSignatures = true,
                isTrusted = false,
                contentWarningAllowed = true,
            ),
        )
    }

    @Test
    fun `an unsupported contract version is rejected before the signature is even looked at`() {
        assertEquals(
            ExtensionGateDecision.UNSUPPORTED_VERSION,
            decideExtensionGate(
                supportedVersion = false,
                hasSignatures = true,
                isTrusted = true,
                contentWarningAllowed = true,
            ),
        )
    }

    @Test
    fun `the signature check runs before the content warning filter`() {
        assertEquals(
            ExtensionGateDecision.UNSIGNED,
            decideExtensionGate(
                supportedVersion = true,
                hasSignatures = false,
                isTrusted = false,
                contentWarningAllowed = false,
            ),
        )
    }

    @Test
    fun `an allowed extension only passes when the signature is present and trusted`() {
        assertEquals(
            ExtensionGateDecision.ALLOWED,
            decideExtensionGate(
                supportedVersion = true,
                hasSignatures = true,
                isTrusted = true,
                contentWarningAllowed = true,
            ),
        )
    }

    @Test
    fun `a filtered extension is refused once it is otherwise loadable`() {
        assertEquals(
            ExtensionGateDecision.FILTERED,
            decideExtensionGate(
                supportedVersion = true,
                hasSignatures = true,
                isTrusted = true,
                contentWarningAllowed = false,
            ),
        )
    }

    @Test
    fun `no combination lets an extension load without a present and trusted signature`() {
        // Exhaustive over every other flag: ALLOWED implies supported && signed && trusted. This is what makes the
        // gate the single boundary the novel branch cannot route around.
        for (supportedVersion in listOf(true, false)) {
            for (hasSignatures in listOf(true, false)) {
                for (isTrusted in listOf(true, false)) {
                    for (contentWarningAllowed in listOf(true, false)) {
                        val decision = decideExtensionGate(
                            supportedVersion = supportedVersion,
                            hasSignatures = hasSignatures,
                            isTrusted = isTrusted,
                            contentWarningAllowed = contentWarningAllowed,
                        )
                        if (decision == ExtensionGateDecision.ALLOWED) {
                            assertTrue(
                                supportedVersion && hasSignatures && isTrusted && contentWarningAllowed,
                                "allowed with supportedVersion=$supportedVersion hasSignatures=$hasSignatures " +
                                    "isTrusted=$isTrusted contentWarningAllowed=$contentWarningAllowed",
                            )
                        }
                    }
                }
            }
        }
    }
}
