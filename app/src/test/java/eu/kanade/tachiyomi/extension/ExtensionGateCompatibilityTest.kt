package eu.kanade.tachiyomi.extension

import app.novel.source.api.NOVEL_API_VERSION
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionGateDecision
import eu.kanade.tachiyomi.extension.util.contextUnavailableReason
import eu.kanade.tachiyomi.extension.util.decideExtensionGate
import eu.kanade.tachiyomi.extension.util.isSupportedNovelApiVersion
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The boundary the 1.2 -> 1.3 contract raise must not cross: nothing that used to load stops loading, and nothing
 * that must be refused starts loading.
 *
 * `ExtensionLoader` is `internal` and its body needs a real `PackageInfo`/`PackageManager` and an Android `Context`
 * — the 1.3 context injection in particular cannot run on a plain JVM, so this test does not pretend to drive a load.
 * What it does reach are the real decisions the loader branches on, [isSupportedNovelApiVersion] feeding
 * [decideExtensionGate], and the value type the refusal returns when the gate says [ExtensionGateDecision].
 */
class ExtensionGateCompatibilityTest {

    @Test
    fun `the 1-2 to 1-3 raise keeps every previously supported version loadable`() {
        for (version in listOf(1.0, 1.1, 1.2, NOVEL_API_VERSION)) {
            assertTrue(
                isSupportedNovelApiVersion(version),
                "$version must pass the version gate after the raise",
            )
            assertTrue(
                decideExtensionGate(
                    supportedVersion = isSupportedNovelApiVersion(version),
                    hasSignatures = true,
                    isTrusted = true,
                    contentWarningAllowed = true,
                ) != ExtensionGateDecision.UNSUPPORTED_VERSION,
                "$version must not be refused for its version; the gate said UNSUPPORTED_VERSION",
            )
        }
    }

    @Test
    fun `a version this app cannot run is refused before anything is instantiated`() {
        val unsupported = 2.0
        assertFalse(isSupportedNovelApiVersion(unsupported))
        assertEquals(
            ExtensionGateDecision.UNSUPPORTED_VERSION,
            decideExtensionGate(
                supportedVersion = isSupportedNovelApiVersion(unsupported),
                hasSignatures = true,
                isTrusted = true,
                contentWarningAllowed = true,
            ),
        )
    }

    @Test
    fun `an unsupported version is reported as a not-loaded extension, not a thrown failure`() {
        // The loader's UNSUPPORTED_VERSION branch returns a value rather than throwing, so the rejection reaches the
        // UI as data that carries its own reason. That reason is UnsupportedLibVersion and must never be the generic
        // Failed: Failed means the extension threw while being loaded, and blaming an apk for the host's loader being
        // too old would point at the wrong party.
        val rejected = Extension.NotLoaded(
            name = "Unsupported Novel",
            pkgName = "app.novel.unsupported",
            versionName = "1.0.0",
            versionCode = 1L,
            isShared = true,
            contentWarning = ContentWarning.SAFE,
            signatures = listOf("0".repeat(64)),
            libVersion = 2.0,
            reason = Extension.NotLoaded.Reason.UnsupportedLibVersion,
        )

        // It is a usable Installed extension, not an exception that a caller has to catch.
        val installed: Extension.Installed = rejected
        assertEquals("app.novel.unsupported", installed.pkgName)
        assertEquals(Extension.NotLoaded.Reason.UnsupportedLibVersion, rejected.reason)
        assertFalse(rejected.reason is Extension.NotLoaded.Reason.Failed)
    }

    @Test
    fun `a context the host could not create is reported as ContextUnavailable, not Failed`() {
        // A private .ext extension that implements ContextAwareNovelSource is refused by design: it is never
        // registered with PackageManager, so createPackageContext throws NameNotFoundException and there is no
        // context to hand over. This calls the real mapping the loader's catch uses. The refusal must name itself: it
        // must not collapse into the generic Failed (which means the extension's own code threw) nor into
        // UnsupportedLibVersion (a different cause with a different fix), so a regression cannot quietly merge them.
        val reason = contextUnavailableReason()

        assertEquals(Extension.NotLoaded.Reason.ContextUnavailable, reason)
        assertFalse(reason is Extension.NotLoaded.Reason.Failed)
        assertNotEquals(Extension.NotLoaded.Reason.UnsupportedLibVersion, reason)
    }
}
