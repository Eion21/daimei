package app.novel.extension.jabrascan

import android.util.Log
import app.novel.source.api.ContentBlock
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** The logcat tag every warning about a doubtful extraction carries. */
private const val TAG = "JabraScanPdf"

/**
 * Turns a JabraScan chapter PDF into the paragraphs the reader renders.
 *
 * JabraScan serves every chapter body as a PDF, so this is the only way prose reaches the reader. The
 * extraction is PdfBox-Android's: the spike proved it is correct for accents (100% identical to
 * `pdftotext` on the sample PDFs) and costs 16-24 ms per page. Only the layout half is ours
 * ([JabraScanPdfLayout]).
 *
 * Two things shape the split:
 *
 * - **The PDF call is the load, not a file.** The host hands the chapter over as bytes
 *   (`NovelNetwork.fetchBytes`), so the document is opened from an in-memory `ByteArray`
 *   (`PDDocument.load(byte[])`) and never written to disk.
 * - **The extension owns the offload.** Extraction is CPU-heavy and `GetChapterContent` calls a source
 *   with no `withContext` of its own, so [extract] runs the work on [Dispatchers.IO]. Coroutines arrive
 *   from the host at runtime — they are `compileOnly` in the build file — and the extension APK ships no
 *   copy of them.
 */
internal object JabraScanPdf {

    /**
     * The chapter's paragraphs, extracted from the downloaded PDF [bytes].
     *
     * Returns no paragraphs for an empty body rather than failing: the host already rejects a failed
     * download, so an empty array here means a PDF with no text, which is not something a retry fixes.
     *
     * [chapter] is the caller's identity for what is being read. The extractor cannot know it — it sees
     * only bytes — so when the caller supplies it, a reconstruction that looks wrong is logged with the
     * work and chapter named (see [JabraScanParagraphWarnings]). It is optional so a caller that does not
     * know the identity still extracts, and so the signature does not force the wiring that is a later
     * task to exist now.
     */
    suspend fun extract(
        bytes: ByteArray,
        chapter: ChapterIdentity? = null,
    ): List<ContentBlock.Paragraph> =
        withContext(Dispatchers.IO) {
            val paragraphs = extractPdf(bytes)
            if (chapter != null) warnOnSuspiciousLayout(chapter, paragraphs)
            paragraphs.map { ContentBlock.Paragraph(it) }
        }

    /**
     * The Android half of the safety warnings: turns each message [JabraScanParagraphWarnings] returns
     * into a `Log.w` that names the work and the chapter, so a broken extraction is provable from logcat
     * instead of only inferable from the code.
     */
    private fun warnOnSuspiciousLayout(chapter: ChapterIdentity, paragraphs: List<String>) {
        for (warning in JabraScanParagraphWarnings.warnings(paragraphs)) {
            Log.w(TAG, "work='${chapter.work}' chapter='${chapter.chapter}': $warning")
        }
    }

    /**
     * The synchronous core of [extract]: in-memory PDF bytes in, paragraph texts out.
     *
     * It is split out so the PdfBox load and the pure reconstruction read as one straight line, and so
     * the dispatcher hop in [extract] stays a one-liner. The text-level reconstruction itself lives in
     * [JabraScanPdfLayout], where a plain JVM test drives it.
     */
    internal fun extractPdf(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val lines = PDDocument.load(bytes).use { document ->
            PdfLineStripper().apply { sortByPosition = true }.linesFrom(document)
        }
        return JabraScanPdfLayout.reconstruct(lines)
    }
}

/**
 * The PdfBox pass: `PDFTextStripper` calls [writeString] once per word, and this override records each
 * word with the baseline it sits on; [writePageEnd] marks the page ends. [linesFrom] then groups the
 * words into visual lines and hands [JabraScanPdfLayout] a plain list of [ExtractedLine]s.
 *
 * The word-level record is deliberate. PdfBox 2.0.27 hands the text over word by word, so the baseline
 * is the only honest signal of where a line begins and ends; the reconstruction that matters for
 * paragraphs is done afterwards, on the lines, where it is testable without PdfBox.
 */
private class PdfLineStripper : PDFTextStripper() {

    private data class Word(val text: String, val baseline: Float, val height: Float)

    /** Words in encounter order; a `null` entry marks a page boundary. */
    private val words = ArrayList<Word?>()

    override fun writeString(string: String, textPositions: List<TextPosition>) {
        if (string.isBlank() || textPositions.isEmpty()) return
        val baseline = textPositions.map { it.yDirAdj }.average().toFloat()
        val height = textPositions.maxOf { it.heightDir }
        words.add(Word(string.trim(), baseline, height))
    }

    override fun writePageEnd() {
        words.add(null)
    }

    /**
     * Runs the stripper over [document] and groups the recorded words into lines.
     *
     * Consecutive words whose baselines are within `max(2f, height / 2)` of each other belong to the same
     * visual line; a page boundary always ends one. A line's baseline is the average of its words'.
     */
    fun linesFrom(document: PDDocument): List<ExtractedLine> {
        getText(document)

        val lines = ArrayList<ExtractedLine>()
        var page = 0
        val current = ArrayList<Word>()
        fun flush() {
            if (current.isEmpty()) return
            lines.add(
                ExtractedLine(
                    text = current.joinToString(" ") { it.text },
                    baseline = current.map { it.baseline }.average().toFloat(),
                    page = page,
                ),
            )
            current.clear()
        }
        for (word in words) {
            if (word == null) {
                flush()
                page++
                continue
            }
            val onAnotherLine =
                current.isNotEmpty() && abs(current.last().baseline - word.baseline) > maxOf(2f, word.height * 0.5f)
            if (onAnotherLine) flush()
            current.add(word)
        }
        flush()
        return lines
    }
}
