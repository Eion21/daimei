package eu.kanade.tachiyomi.extension

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import eu.kanade.tachiyomi.extension.util.ExtensionPackageContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The positive half of the contract-1.3 proof: a package [Context] wrapped by [ExtensionPackageContext] is enough
 * for PdfBox to initialise and extract accented text.
 *
 * In production the loader wraps the value of `createPackageContext(extensionPackage, 0)`. That raw context is
 * meant to expose only the extension's own assets — it is not the host application — and it has no
 * `Application` behind it in the host process, so `getApplicationContext()` returns null. `PDFBoxResourceLoader.init`
 * reads `context.getApplicationContext().getAssets()`, so without the wrapper it can never reach the glyph tables
 * the pdfbox AAR ships under `assets/`. [ExtensionPackageContext] overrides `getApplicationContext()` to return
 * itself, which is the entire fix.
 *
 * This test exercises the real host wrapper (it is `internal` to `:app`, hence the test lives here) against a real
 * package context built the same way the loader builds it, then proves the consequence: a specific Spanish sentence
 * from the repository's own synthetic `synthetic_spanish_novel.pdf` comes out with its accents intact.
 *
 * The fixture is self-authored and lives in `app/src/androidTest/assets`. It replaces the third-party
 * `t_Arifureta.pdf` sample, which is no longer tracked by git and must not be depended on; the
 * `synthetic_spanish_novel.pdf` copies in `app`, `novel-pdf-spike` and `novel-jabrascan-extension` are byte-identical
 * (a JVM test in `novel-jabrascan-extension` pins that).
 */
@RunWith(AndroidJUnit4::class)
class ExtensionPackageContextPdfBoxTest {

    @Test
    fun theWrappedPackageContextLetsPdfBoxExtractAccentedText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext

        // A package context for an apk that is not the host application's own package, created exactly the way the
        // loader creates the extension's. The test apk carries the pdfbox assets (it depends on the library), so
        // this context has the glyph tables PdfBox needs and — being a package the host process does not run —
        // no application behind it.
        val rawPackageContext = target.createPackageContext(instrumentation.context.packageName, 0)
        Log.i(TAG, "raw package context applicationContext=${rawPackageContext.applicationContext}")
        assertNull(
            "precondition: a raw package context must have no application context in the host process, otherwise " +
                "this test proves nothing about the wrapper",
            rawPackageContext.applicationContext,
        )

        val wrapped = ExtensionPackageContext(rawPackageContext)

        // The wrapper's whole contract: getApplicationContext() is usable, and it is the wrapper itself.
        assertNotNull("the wrapper must expose an application context", wrapped.applicationContext)
        assertSame(
            "getApplicationContext() must return the wrapper, so a library that asks for the application context " +
                "keeps the wrapper's package assets",
            wrapped,
            wrapped.applicationContext,
        )

        PDFBoxResourceLoader.init(wrapped)

        val pdf = copyTestAsset(
            "synthetic_spanish_novel.pdf",
            File(target.cacheDir, "context-contract-pdf").apply { mkdirs() },
        )
        val text = PDDocument.load(pdf).use { document ->
            PDFTextStripper().apply { sortByPosition = true }.getText(document)
        }

        val normalized = text.replace('\u00AD', ' ').replace(Regex("\\s+"), " ")
        assertTrue(
            "the extracted text must contain the accented sentence; got ${text.length} chars: " +
                normalized.take(200),
            normalized.contains(EXPECTED_SENTENCE),
        )
    }

    private fun copyTestAsset(name: String, dir: File): File {
        val out = File(dir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    private companion object {
        const val TAG = "ContextContract"

        // The opening sentence of the synthetic fixture, kept verbatim (four accented words: señora, jardín,
        // mañana, pájaros). Compared after whitespace is collapsed so line wrapping cannot break the match.
        const val EXPECTED_SENTENCE = "La señora del jardín anotaba cada mañana los nombres de los pájaros"
    }
}
