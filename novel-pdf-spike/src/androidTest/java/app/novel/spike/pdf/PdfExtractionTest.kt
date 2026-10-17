package app.novel.spike.pdf

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * THROWAWAY SPIKE. Extracts the three jabrascan.net sample PDFs with PdfBox-Android on-device and
 * dumps, per PDF and per strategy, the raw text plus char/word/line/paragraph counts. It also times
 * document load and strategy-(b) extraction over several runs.
 *
 * Outputs are written to the app-scoped external dir (targetContext.getExternalFilesDir("pdfspike"))
 * so they can be `adb pull`-ed. Nothing here is production code.
 */
@RunWith(AndroidJUnit4::class)
class PdfExtractionTest {
    @Before
    fun initPdfBox() {
        // One-time initialisation: PdfBox-Android resolves its bundled AFM/glyphlist/fontbox
        // resources through an AssetManager captured here.
        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().context)
    }

    @Test
    fun extractAllPdfs() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val testCtx: Context = instr.context // test APK: holds src/androidTest/assets
        val appCtx: Context = instr.targetContext // app-scoped external dir for the dumps

        val outDir = File(appCtx.getExternalFilesDir(null), "pdfspike").apply { mkdirs() }
        val tmpDir = File(appCtx.cacheDir, "pdfspike_src").apply { mkdirs() }
        val timing = StringBuilder()
        val summary = StringBuilder()

        for (name in PDFS) {
            val id = name.removeSuffix(".pdf")
            Log.i(TAG, "=== $id ===")
            val file = copyAsset(testCtx, name, tmpDir)

            // --- strategy dumps + counts (one shared document) ---
            val counts = LinkedHashMap<String, Stats>()
            PDDocument.load(file).use { doc ->
                val pages = doc.numberOfPages

                val defaultText = PDFTextStripper().getText(doc)
                val sortedText = PDFTextStripper().apply { setSortByPosition(true) }.getText(doc)

                val paragraphStripper = ParagraphTextStripper().apply { setSortByPosition(true) }
                paragraphStripper.getText(doc)
                val paragraphText = paragraphStripper.buildText()

                counts["default"] = stats(defaultText)
                counts["sorted"] = stats(sortedText)
                counts["paragraph"] = stats(paragraphText)

                write(outDir, "${id}_default.txt", defaultText)
                write(outDir, "${id}_sorted.txt", sortedText)
                write(outDir, "${id}_paragraph.txt", paragraphText)

                summary.append("PDF $id pages=$pages\n")
                for ((strategy, s) in counts) {
                    summary.append(
                        "  $strategy: chars=${s.chars} words=${s.words} lines=${s.lines} " +
                            "paragraphs=${s.paragraphs}\n",
                    )
                    Log.i(TAG, "$id $strategy chars=${s.chars} words=${s.words} lines=${s.lines} paragraphs=${s.paragraphs}")
                }

                // --- timing: TIMING_RUNS independent load+extract cycles (strategy b) ---
                timing.append("PDF $id pages=$pages\n")
                for (run in 1..TIMING_RUNS) {
                    val t0 = System.nanoTime()
                    val d = PDDocument.load(file)
                    val t1 = System.nanoTime()
                    val extracted = PDFTextStripper().apply { setSortByPosition(true) }.getText(d)
                    val t2 = System.nanoTime()
                    d.close()
                    val loadMs = (t1 - t0) / 1_000_000.0
                    val extractMs = (t2 - t1) / 1_000_000.0
                    val tag = if (run == 1) "first (cold)" else "warm #$run"
                    timing.append(
                        "  run $run [$tag]: load=%.2f ms extract=%.2f ms chars=%d\n".format(
                            loadMs, extractMs, extracted.length,
                        ),
                    )
                    Log.i(TAG, "$id run$run load=%.2f ms extract=%.2f ms".format(loadMs, extractMs))
                }
                timing.append("\n")
            }

            assertTrue("$id produced no text", counts.values.any { it.chars > 0 })
        }

        write(outDir, "timing.txt", timing.toString())
        write(outDir, "summary.txt", summary.toString())
        Log.i(TAG, "Wrote outputs to ${outDir.absolutePath}")
    }

    private data class Stats(val chars: Int, val words: Int, val lines: Int, val paragraphs: Int)

    private fun stats(text: String): Stats = Stats(
        chars = text.length,
        words = Regex("[A-Za-zÁÉÍÓÚáéíóúñÑ¿¡]{2,}").findAll(text).count(),
        lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1,
        paragraphs = text.split(Regex("\\n[ \\t]*\\n")).count { it.isNotBlank() },
    )

    private fun write(dir: File, name: String, content: String) {
        File(dir, name).writeText(content)
    }

    private fun copyAsset(ctx: Context, name: String, dir: File): File {
        val out = File(dir, name)
        ctx.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    companion object {
        private const val TAG = "PdfSpike"
        private const val TIMING_RUNS = 3
        private val PDFS = listOf("t_Arifureta.pdf", "t_IBecameMarriedMan.pdf", "t_ShadowSlave.pdf")
    }
}

/**
 * Paragraph-oriented text stripper.
 *
 * PdfBox 2.0.27 invokes `writeString(String, List<TextPosition>)` once *per word* (see
 * `PDFTextStripper.writeLine`), and `writeWordSeparator()` between words of the same line. So the
 * override below only records each word with its baseline; the line/paragraph assembly happens in
 * [buildText] afterwards, which keeps the grouping logic testable and simple.
 *
 * Heuristic, documented for the spike:
 *  1. Consecutive words whose baselines (`getYDirAdj()`) are within `max(2f, height/2)` belong to the
 *     same visual line; a page break (from [writePageEnd]) always ends a line.
 *  2. Words of a line are joined with a single space.
 *  3. The "line height" is taken as the document's typical baseline-to-baseline leading, i.e. the
 *     median same-page gap between consecutive line baselines (a paragraph break is the minority and
 *     so does not move the median). A line starts a new paragraph when its baseline sits more than
 *     1.5x that leading below the previous baseline, or when a page break intervenes. Paragraphs are
 *     separated by a blank line; ordinary line breaks inside a paragraph are single newlines.
 *     NOTE: using the glyph bounding-box height (`getHeightDir()`) as the reference instead is wrong
 *     for these PDFs: their leading exceeds 1.5x the glyph height, so every line would become its own
 *     paragraph.
 */
class ParagraphTextStripper : PDFTextStripper() {
    private data class Word(val text: String, val y: Float, val height: Float)
    private data class Line(val text: String, val baseline: Float, val page: Int)

    /** Words in encounter order; a null entry marks a page boundary. */
    private val items = ArrayList<Word?>()

    override fun writeString(string: String, textPositions: List<TextPosition>) {
        if (string.isBlank() || textPositions.isEmpty()) return
        val y = textPositions.map { it.yDirAdj }.average().toFloat()
        val height = textPositions.maxOf { it.heightDir }
        items.add(Word(string.trim(), y, height))
    }

    override fun writePageEnd() {
        items.add(null)
    }

    fun buildText(): String {
        // --- 1. group words into visual lines (page-scoped) ---
        val lines = ArrayList<Line>()
        var page = 0
        var current = ArrayList<Word>()
        fun flush() {
            if (current.isNotEmpty()) {
                lines.add(
                    Line(
                        current.joinToString(" ") { it.text },
                        current.map { it.y }.average().toFloat(),
                        page,
                    ),
                )
                current = ArrayList()
            }
        }
        for (item in items) {
            if (item == null) {
                flush()
                page++
                continue
            }
            val tolerance = maxOf(2f, item.height * 0.5f)
            if (current.isNotEmpty() && kotlin.math.abs(current.last().y - item.y) > tolerance) {
                flush()
            }
            current.add(item)
        }
        flush()

        // --- 2. typical leading = median same-page baseline gap ---
        val gaps = ArrayList<Float>()
        for (i in 1 until lines.size) {
            if (lines[i - 1].page == lines[i].page) gaps.add(lines[i].baseline - lines[i - 1].baseline)
        }
        val leading = if (gaps.isEmpty()) 0f else gaps.sorted()[gaps.size / 2]

        // --- 3. emit, inserting a blank line at paragraph boundaries ---
        val out = StringBuilder()
        for (i in lines.indices) {
            if (i > 0) {
                val prev = lines[i - 1]
                val gap = lines[i].baseline - prev.baseline
                val paragraphBreak = prev.page != lines[i].page || (leading > 0f && gap > 1.5f * leading)
                out.append('\n')
                if (paragraphBreak) out.append('\n')
            }
            out.append(lines[i].text)
        }
        return out.toString()
    }
}
