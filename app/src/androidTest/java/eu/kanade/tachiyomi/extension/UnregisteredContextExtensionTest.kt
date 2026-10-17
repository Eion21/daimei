package eu.kanade.tachiyomi.extension

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import kotlinx.coroutines.runBlocking
import mihon.app.di.HostNovelNetwork
import mihon.app.di.appGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end proof of the contract-1.3 refusal on the private `.ext` path.
 *
 * The fixture apk declares `ContextAwareNovelSource` and contract 1.3, but it is never installed with the
 * package manager — `:app`'s build packages it as an androidTest asset and this test drops it into the app's
 * private extension directory, which is the only place the loader looks for a `.ext`. `createPackageContext` then
 * has no package to resolve, so the loader must refuse the extension with
 * [Extension.NotLoaded.Reason.ContextUnavailable] and must not register it.
 *
 * This drives the real [ExtensionLoader] entry point; it does not re-implement or stub the decision. The loader
 * object is `internal` to `:app`, which is why this test lives in `:app`'s androidTest source set: only a friend
 * of the app module can call the code under test.
 */
@RunWith(AndroidJUnit4::class)
class UnregisteredContextExtensionTest {

    @Before
    fun prepareUnregisteredExtension() {
        // Fail with a plain message rather than let the test pass for the wrong reason: an installed fixture would
        // be resolved by createPackageContext and the private `.ext` path would no longer be under test.
        assertFalse(
            "Premise broken: $FIXTURE_PACKAGE is installed, so the private `.ext` path is not what is being " +
                "exercised. Uninstall it and re-run.",
            isFixtureInstalled(targetContext),
        )

        // The fixture implements NetworkedNovelSource, so the loader binds the host network before it reaches the
        // context step. Real runs get this from ExtensionManager at startup; an isolated test process has not
        // started it, so install the host's network here — otherwise the load would fail earlier, with a different
        // reason, and would never reach the context path this test is about.
        ExtensionLoader.installNovelNetwork(HostNovelNetwork())

        // The loader requires the apk to be signed and *trusted* before it instantiates anything. A `.ext` apk has
        // a real signature (the debug key), so the private path needs trust satisfied here, the same way the UI
        // grants it when a user trusts an extension.
        fixtureFile = installFixtureAsPrivateExtension()
        val pkgInfo = requireNotNull(archivePackageInfo(fixtureFile!!)) {
            "the packaged fixture asset is not a readable signed apk"
        }
        val signatures = requireNotNull(ExtensionLoader.getSignatures(pkgInfo)) {
            "the fixture apk carries no signature"
        }
        targetContext.appGraph.trustExtension.trust(
            pkgName = FIXTURE_PACKAGE,
            versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
            signatureHash = signatures.last(),
        )
    }

    @After
    fun removeUnregisteredExtension() {
        fixtureFile?.delete()
        fixtureFile = null
    }

    @Test
    fun thePrivateExtensionPathIsRefusedWithContextUnavailable() {
        val result = runBlocking { ExtensionLoader.loadExtensionFromPkgName(targetContext, FIXTURE_PACKAGE) }

        assertNotNull("the loader did not return any result for the fixture package", result)
        assertTrue(
            "the fixture must be reported as not-loaded, was ${result!!::class.simpleName}",
            result is Extension.NotLoaded,
        )
        assertEquals(
            "an extension that needs a context the host cannot create must be refused with ContextUnavailable, " +
                "not with a generic reason",
            Extension.NotLoaded.Reason.ContextUnavailable,
            (result as Extension.NotLoaded).reason,
        )

        val loaded = runBlocking { ExtensionLoader.loadExtensions(targetContext) }
        assertFalse(
            "the unregistered fixture must never appear as a Loaded extension",
            loaded.any { it is Extension.Loaded && it.pkgName == FIXTURE_PACKAGE },
        )
    }

    private fun isFixtureInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Copies the fixture apk out of the test assets into `<filesDir>/exts/<pkg>.ext`, where the loader looks. */
    private fun installFixtureAsPrivateExtension(): File {
        val extsDir = File(targetContext.filesDir, "exts").apply { mkdirs() }
        val extFile = File(extsDir, "$FIXTURE_PACKAGE.$PRIVATE_EXTENSION_SUFFIX")
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE_ASSET).use { input ->
            extFile.outputStream().use { input.copyTo(it) }
        }
        extFile.setReadOnly()
        return extFile
    }

    private fun archivePackageInfo(file: File) =
        targetContext.packageManager.getPackageArchiveInfo(file.absolutePath, ARCHIVE_FLAGS)

    private val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var fixtureFile: File? = null

    private companion object {
        const val FIXTURE_PACKAGE = "app.novel.extension.fixture.unregistered"
        const val FIXTURE_ASSET = "$FIXTURE_PACKAGE.ext"
        const val PRIVATE_EXTENSION_SUFFIX = "ext"

        @Suppress("DEPRECATION")
        val ARCHIVE_FLAGS = PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
    }
}
