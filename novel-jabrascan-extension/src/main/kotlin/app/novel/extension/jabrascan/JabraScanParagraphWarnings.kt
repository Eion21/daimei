package app.novel.extension.jabrascan

/**
 * Which chapter a batch of extracted paragraphs came from, so a warning can name it.
 *
 * The extractor sees only bytes and has no idea which work or chapter it is reading, so the caller that
 * knows hands the identity over. Both fields are free text and are never parsed or trusted here: they
 * only make a log line readable, which is why this is a two-string value type and nothing more.
 */
internal data class ChapterIdentity(val work: String, val chapter: String)

/**
 * The shapes of a JabraScan extraction that mean the paragraph rule has quietly stopped working.
 *
 * The criterion itself is deliberately untouched — a paragraph break is still a vertical gap of 1.5x the
 * median leading, and indent is not used (see [JabraScanPdfLayout]) — so the only honest thing to do when
 * the output looks wrong is to say so loudly. A broken reconstruction still returns "some text", which is
 * why it would otherwise go unnoticed, so two failures are surfaced:
 *
 * - **a single paragraph longer than [LONG_PARAGRAPH_CHARS]** — no real paragraph runs that long, so the
 *   gap rule almost certainly stopped firing and the whole chapter collapsed into one block;
 * - **a long chapter that comes out in fewer than [FEW_BLOCKS] paragraphs** — the same collapse seen from
 *   the chapter's side, and the one that still fires when the collapsed block stayed under the
 *   per-paragraph limit.
 *
 * [warnings] is pure: it takes the already-extracted paragraph texts and returns one message fragment per
 * suspicious shape, so every branch is drivable from a JVM test. Turning a fragment into a `Log.w` line
 * that names the work and the chapter is [JabraScanPdf]'s job, on the device.
 */
internal object JabraScanParagraphWarnings {

    /**
     * A paragraph longer than this is not a paragraph: at roughly 5000-9000 words a chapter, a 3000-char
     * block is a third of a chapter or more, which means the break rule did not fire rather than that the
     * author wrote one enormous paragraph.
     */
    const val LONG_PARAGRAPH_CHARS = 3000

    /**
     * A chapter at least this long (in characters) is a full chapter — the short companion/teaser PDFs
     * that legitimately read as one or two blocks sit under it — and should therefore split into many
     * paragraphs. Measured against the site: collapsed real chapters ran 5.5k-15k chars, while healthy
     * ones of the same length split into dozens of blocks, so 5000 is the floor below which silence is
     * the right answer.
     */
    const val LONG_CHAPTER_CHARS = 5_000

    /**
     * Below this many paragraphs, a chapter of at least [LONG_CHAPTER_CHARS] has failed to split. The
     * same sample produced 1-5 blocks for collapsed chapters and 33-324 for healthy ones, so 6 sits in the
     * middle of that gap; it also fires on a 5k-char chapter split into five 1k paragraphs, which the
     * per-paragraph rule alone would miss.
     */
    const val FEW_BLOCKS = 6

    /**
     * One message per suspicious shape in [paragraphs], each naming what was seen and the threshold it
     * crossed; an empty list means the extraction looks healthy.
     */
    fun warnings(paragraphs: List<String>): List<String> {
        val result = ArrayList<String>()
        for ((index, paragraph) in paragraphs.withIndex()) {
            if (paragraph.length > LONG_PARAGRAPH_CHARS) {
                result += "paragraph ${index + 1} of ${paragraphs.size} is ${paragraph.length} chars " +
                    "(limit $LONG_PARAGRAPH_CHARS): the paragraph rule may have stopped firing"
            }
        }
        val total = paragraphs.sumOf { it.length }
        if (total >= LONG_CHAPTER_CHARS && paragraphs.size < FEW_BLOCKS) {
            result += "$total chars extracted into only ${paragraphs.size} paragraph(s) " +
                "(expected at least $FEW_BLOCKS): a long chapter failed to split"
        }
        return result
    }
}
