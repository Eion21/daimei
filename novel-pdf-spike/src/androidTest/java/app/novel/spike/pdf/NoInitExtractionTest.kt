package app.novel.spike.pdf

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * THROWAWAY SPIKE, part two. Answers one question: does text extraction work when
 * `PDFBoxResourceLoader.init(context)` is NEVER called?
 *
 * It matters because a novel extension apk is instantiated by the host through
 * `Class.forName(...).getDeclaredConstructor().newInstance()` and is handed a `NovelNetwork`, never a
 * `Context`. `PDFBoxResourceLoader` exposes only `init(Context)`, `isReady()` and `getStream(String)`
 * — there is no way to point it at a classpath — so if extraction needs a resource, the extension
 * cannot reach it, and the contract would have to hand one over.
 *
 * Run this class ALONE so the process is fresh and nothing else has called `init` first:
 *   adb shell am instrument -w \
 *     -e class app.novel.spike.pdf.NoInitExtractionTest \
 *     app.novel.spike.pdf.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Then diff the dumps against the with-init ones from [PdfExtractionTest].
 */
@RunWith(AndroidJUnit4::class)
class NoInitExtractionTest {
    @Test
    fun extractWithoutInit() {
        // The premise of the whole measurement. If this ever flips to true, another test in the same
        // process initialised PdfBox and the run proves nothing.
        assertFalse("PdfBox was already initialised; run this class alone", PDFBoxResourceLoader.isReady())

        val instr = InstrumentationRegistry.getInstrumentation()
        val testCtx: Context = instr.context
        val appCtx: Context = instr.targetContext

        val outDir = File(appCtx.getExternalFilesDir(null), "pdfspike_noinit").apply { mkdirs() }
        val tmpDir = File(appCtx.cacheDir, "pdfspike_noinit_src").apply { mkdirs() }

        for (name in PDFS) {
            val id = name.removeSuffix(".pdf")
            val file = copyAsset(testCtx, name, tmpDir)

            // Deliberately NOT calling PDFBoxResourceLoader.init anywhere in this class.
            val text = PDDocument.load(file).use { doc -> PDFTextStripper().getText(doc) }

            File(outDir, "${id}_noinit_default.txt").writeText(text)
            Log.i(TAG, "$id pages-ok chars=${text.length} words=${words(text)}")
        }

        assertTrue("nothing was written", outDir.listFiles()?.isNotEmpty() == true)
        Log.i(TAG, "wrote to ${outDir.absolutePath}")
    }

    private fun words(text: String): Int = Regex("[A-Za-zÁÉÍÓÚáéíóúñÑ¿¡]{2,}").findAll(text).count()

    private fun copyAsset(ctx: Context, name: String, dir: File): File {
        val out = File(dir, name)
        ctx.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    companion object {
        private const val TAG = "PdfSpikeNoInit"
        private val PDFS = listOf("t_Arifureta.pdf", "t_IBecameMarriedMan.pdf", "t_ShadowSlave.pdf")
    }
}
