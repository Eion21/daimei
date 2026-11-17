package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class NovelInstallActivationTest {
    private val hostSigner = "e665aa10b5ad8c1d5c3f993a3e733687ff0ef066da465706a69c7635b0652d77"
    private val extensionSigner = "2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408"
    private val otherSigner = "a".repeat(64)
    private val matcha = "app.novel.extension.matchanovels"
    private val sky = "app.novel.extension.skynovels"
    private val jabra = "app.novel.extension.jabrascan"
    private val fixture = "app.novel.extension.fixtures"

    private fun activation(active: Boolean) = NovelInstallActivation(active, extensionSigner, setOf(matcha, sky, jabra))

    private fun record(packageName: String, signers: Set<String>, sha256: String = "a".repeat(64), size: Long = 123) =
        NovelInstallRecord("op", "b".repeat(64), packageName, 1, signers, "/artifact", size, sha256)

    @Test fun `public activation is active only for official host with official signer`() {
        assertTrue(activation(true).active)
        assertFalse(activation(false).active)
    }

    @Test fun `public permits only official packages signed with extensions certificate`() {
        val activation = activation(true)
        assertTrue(activation.permits(record(matcha, setOf(extensionSigner))))
        assertTrue(activation.permits(record(sky, setOf(extensionSigner))))
        assertTrue(activation.permits(record(jabra, setOf(extensionSigner))))
        assertFalse(activation.permits(record(fixture, setOf(extensionSigner))))
        assertFalse(activation.permits(record(matcha, setOf(otherSigner))))
        assertFalse(activation.permits(record(matcha, setOf(hostSigner, extensionSigner))))
    }

    @Test fun `public permits requires explicit consent and NEW absence`() {
        val activation = activation(true)
        val base = record(matcha, setOf(extensionSigner))
        assertFalse(activation.permits(base.copy(explicitInstallConsent = false)))
        assertFalse(activation.permits(base.copy(priorAbsent = false)))
    }

    @Test fun `public permits validates artifact bounds and hash shape`() {
        val activation = activation(true)
        val base = record(matcha, setOf(extensionSigner))
        assertFalse(activation.permits(base.copy(artifactSize = 0)))
        assertFalse(activation.permits(base.copy(artifactSize = NovelDistributionCatalogCodec.MAX_APK_BYTES + 1)))
        assertFalse(activation.permits(base.copy(artifactSha256 = "not-a-hash")))
    }

    @Test fun `inactive activation fails closed`() {
        val activation = activation(false)
        assertFalse(activation.permits(record(matcha, setOf(extensionSigner))))
    }

    @Test fun `official factory requires matching host identity and signer`() {
        // The factory uses Android PackageManager; a relaxed mock with no real host identity is inactive.
        assertFalse(NovelInstallActivation.official(mockk<Context>(relaxed = true)).active)
    }

    @Test fun `host gate accepts only the exact official signer singleton`() {
        val officialHost = "app.daimei"
        val complete = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf(hostSigner))
        assertTrue(NovelInstallActivation.isOfficialHostIdentity(officialHost, complete))
    }

    @Test fun `host gate rejects a host signed by more than the official certificate`() {
        // A multi-signer host that merely includes the official pin must fail: only the exact singleton set is official.
        val multi = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf(hostSigner, otherSigner))
        assertFalse(NovelInstallActivation.isOfficialHostIdentity("app.daimei", multi))
    }

    @Test fun `host gate rejects a wrong or superseded signer`() {
        val wrong = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf(otherSigner))
        assertFalse(NovelInstallActivation.isOfficialHostIdentity("app.daimei", wrong))
        // The pin in the history of a rotated key is not the current identity.
        val rotated = NovelPackageSigners(
            NovelSignerObservation.MODERN_COMPLETE,
            setOf(otherSigner),
            history = setOf(hostSigner),
        )
        assertFalse(NovelInstallActivation.isOfficialHostIdentity("app.daimei", rotated))
    }

    @Test fun `host gate fails closed when signer facts are unavailable`() {
        val unavailable = NovelPackageSigners(NovelSignerObservation.UNAVAILABLE)
        assertFalse(NovelInstallActivation.isOfficialHostIdentity("app.daimei", unavailable))
    }

    @Test fun `host gate fails closed for legacy 26-27 signer observation with no forged complete fallback`() {
        // Android 26/27 expose no modern complete facts; the legacy observation never activates the gate.
        val legacy = NovelPackageSigners(NovelSignerObservation.LEGACY_UNVERIFIED, legacy = setOf(hostSigner))
        assertFalse(NovelInstallActivation.isOfficialHostIdentity("app.daimei", legacy))
    }

    @Test fun `host gate rejects the official signer under a different package name`() {
        val complete = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf(hostSigner))
        assertFalse(NovelInstallActivation.isOfficialHostIdentity("app.daimei.debug", complete))
        assertFalse(NovelInstallActivation.isOfficialHostIdentity("eu.kanade.tachiyomi.debug", complete))
    }
}
