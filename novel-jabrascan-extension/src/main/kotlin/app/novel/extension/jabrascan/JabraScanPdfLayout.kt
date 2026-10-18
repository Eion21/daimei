package app.novel.extension.jabrascan

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One line of text as the PDF extractor found it: the words of a single visual line already joined,
 * together with the baseline the line sits on and the page it came from.
 *
 * The type is deliberately Android- and PdfBox-free. [JabraScanPdf] builds these out of PdfBox's
 * `TextPosition`s, and [JabraScanPdfLayout] turns them into paragraphs using nothing but the
 * coordinates, so the whole paragraph rule is a pure function a JVM unit test can drive.
 */
internal data class ExtractedLine(val text: String, val baseline: Float, val page: Int)

/**
 * The text-level half of PDF extraction: the lines an extractor found in, the paragraphs a reader sees
 * out.
 *
 * The rules are the ones the spike measured against real jabrascan.net PDFs, in the order they apply:
 *
 * - a line that is only a page number is not prose and is dropped;
 * - a line that opens or closes several pages is a running header or footer, not prose, and is dropped;
 * - two consecutive lines on the same page belong to the same paragraph unless the lower one sits more
 *   than [PARAGRAPH_GAP] times the page's typical leading below the other, where the leading is the mode
 *   of the same-page gaps that sit at or below their median (fewer than [MIN_LEADING_GAPS] same-page gaps
 *   make the leading unmeasurable, and then every line goes into one paragraph);
 * - a page change on its own is *not* a paragraph break: a paragraph that runs off the bottom of a page
 *   continues on the next one;
 * - inside a paragraph the hard line breaks become single spaces, and a word hyphenated across two
 *   lines is sewn back together without its hyphen.
 *
 * Everything here is a pure function of the input list: no PDF, no Android, no dispatcher.
 */
internal object JabraScanPdfLayout {

    /** Nothing but a page number: `12`, `— 12 —`, `12.`, `| 12 |`. */
    private val PAGE_NUMBER = Regex("""^[\s.\-–—|]*\d+[\s.\-–—|]*$""")

    /** The hard hyphen a word is broken at, and the soft hyphen a bad extractor leaves behind. */
    private const val HYPHEN = '-'
    private const val SOFT_HYPHEN = '\u00AD'

    /**
     * A line starts a new paragraph below more than this many times the page's typical leading.
     *
     * Calibrated on 14 real chapters: the largest intra-paragraph gap measured 1.26x the leading (chapter
     * AbsoluteSwordSense) and the tightest real paragraph break 1.38x (chapter MushokuTensei), so `1.32`
     * sits between the two with roughly 0.7 pt of margin below and 0.9 pt above at a ~15 pt leading. The
     * old `1.5f` never reached a real break, while a lower factor would invent breaks inside paragraphs.
     */
    private const val PARAGRAPH_GAP = 1.32f

    /**
     * Fewer same-page gaps than this and the page's leading is not measurable: [modeLeading] then returns
     * `0f`, which [startsParagraph] reads as "never break".
     */
    private const val MIN_LEADING_GAPS = 4

    /** A line that opens or closes this many pages is a running header or footer. */
    private const val RUNNING_REPEATS = 2

    /**
     * The paragraphs of [lines] in reading order, each a single flowing string.
     *
     * An empty input, or an input that is nothing but headers, footers and page numbers, reads as no
     * paragraphs rather than as one empty one.
     */
    fun reconstruct(lines: List<ExtractedLine>): List<String> {
        val body = withoutFurniture(lines)
        if (body.isEmpty()) return emptyList()

        val leading = modeLeading(body)
        val paragraphs = ArrayList<String>()
        val current = ArrayList<ExtractedLine>()
        for (line in body) {
            if (current.isNotEmpty() && startsParagraph(current.last(), line, leading)) {
                paragraphs.add(join(current))
                current.clear()
            }
            current.add(line)
        }
        paragraphs.add(join(current))
        return paragraphs
    }

    /** Drops blank lines, bare page numbers and running headers/footers, keeping the prose in order. */
    private fun withoutFurniture(lines: List<ExtractedLine>): List<ExtractedLine> {
        val prose = lines.filter { it.text.isNotBlank() && !PAGE_NUMBER.matches(it.text.trim()) }
        if (prose.isEmpty()) return prose
        val running = runningFurniture(prose)
        if (running.isEmpty()) return prose
        return prose.filterNot { it.text.trim() in running }
    }

    /**
     * The texts that open or close at least [RUNNING_REPEATS] pages: a header or footer repeats the same
     * words at the same edge page after page, which a paragraph does not.
     */
    private fun runningFurniture(lines: List<ExtractedLine>): Set<String> {
        val openings = HashMap<String, Int>()
        val closings = HashMap<String, Int>()
        for (page in lines.groupBy { it.page }.values) {
            openings.merge(page.first().text.trim(), 1, Int::plus)
            closings.merge(page.last().text.trim(), 1, Int::plus)
        }
        return (openings.keys + closings.keys)
            .filter { (openings[it] ?: 0) >= RUNNING_REPEATS || (closings[it] ?: 0) >= RUNNING_REPEATS }
            .toSet()
    }

    /**
     * The page's typical baseline-to-baseline distance: the mode of the same-page gaps that sit at or
     * below their median.
     *
     * The median alone cannot carry this. In a chapter thick with dialogue, real paragraph breaks are a
     * large fraction of all gaps, so the median itself drifts up to the break size, the threshold rises
     * above every real break, and whole chapters collapse into a handful of blocks. Taking the mode of the
     * gaps at or below the median pins the leading to the most common *ordinary* gap instead and ignores
     * the breaks, which measured the leading correctly in 14 of 14 real chapters.
     *
     * Gaps are bucketed to 0.1 pt ([roundToInt]`(gap * 10)`) so near-identical leads land together. On a
     * tie between buckets the smaller bucket wins: an under-estimated leading only adds breaks, while an
     * over-estimated one collapses paragraphs.
     *
     * It is `0f` when fewer than [MIN_LEADING_GAPS] lines share a page — the leading is then not
     * measurable and [startsParagraph] never breaks, so the whole input becomes ONE paragraph. For a
     * chapter past the thresholds in [JabraScanParagraphWarnings], the consequence the reader sees is that
     * the "long chapter in too few blocks" warning fires.
     */
    private fun modeLeading(lines: List<ExtractedLine>): Float {
        val gaps = ArrayList<Float>()
        for (i in 1 until lines.size) {
            if (lines[i].page == lines[i - 1].page) gaps.add(abs(lines[i].baseline - lines[i - 1].baseline))
        }
        if (gaps.size < MIN_LEADING_GAPS) return 0f
        val median = gaps.sorted()[gaps.size / 2]

        val counts = HashMap<Int, Int>()
        for (gap in gaps) {
            if (gap <= median) counts.merge((gap * 10f).roundToInt(), 1, Int::plus)
        }
        // Iterate explicitly rather than `maxByOrNull`: a tie must resolve to the *smaller* bucket, and
        // the map's iteration order does not guarantee that on its own.
        var bestBucket = 0
        var bestCount = 0
        for ((bucket, count) in counts) {
            if (count > bestCount || (count == bestCount && bucket < bestBucket)) {
                bestBucket = bucket
                bestCount = count
            }
        }
        return bestBucket / 10f
    }

    /**
     * A line opens a new paragraph when, on the same page, it sits more than [PARAGRAPH_GAP] times the
     * typical leading below the previous line. A page change never opens one on its own.
     */
    private fun startsParagraph(previous: ExtractedLine, next: ExtractedLine, leading: Float): Boolean {
        if (previous.page != next.page || leading <= 0f) return false
        return abs(next.baseline - previous.baseline) > PARAGRAPH_GAP * leading
    }

    /**
     * One paragraph's lines as a single string: line breaks become spaces, and a word hyphenated across
     * two lines is sewn back together without the hyphen.
     */
    private fun join(lines: List<ExtractedLine>): String {
        val text = StringBuilder()
        for (line in lines) {
            val part = line.text.trim()
            if (part.isEmpty()) continue
            when {
                text.isEmpty() -> text.append(part)
                isHyphenated(text, part) -> {
                    text.setLength(text.length - 1)
                    text.append(part)
                }
                else -> text.append(' ').append(part)
            }
        }
        return text.toString()
    }

    /**
     * True when [next] continues the last word of [built] across a line break: the built text ends in a
     * hard or soft hyphen that sits between two letters, and [next] starts in lower case, the way the
     * second half of a split word does. A line-final dialogue dash starts no word and never qualifies.
     */
    private fun isHyphenated(built: StringBuilder, next: String): Boolean {
        if (built.length < 2) return false
        val last = built[built.length - 1]
        if (last != HYPHEN && last != SOFT_HYPHEN) return false
        return built[built.length - 2].isLetter() && next.first().isLowerCase()
    }
}
