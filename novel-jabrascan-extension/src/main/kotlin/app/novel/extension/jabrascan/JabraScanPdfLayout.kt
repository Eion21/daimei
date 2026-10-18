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
 * What [JabraScanPdfLayout.reconstruct] found in a chapter's lines: the reader's paragraphs, the page's
 * typical leading the split was made against, and whether that leading came from a statistic the layout
 * does not trust.
 *
 * The leading and the flag are carried out of the pure reconstruction so the calling layer can reason
 * about *how* a chapter was split and not only about what came out. [statisticUnreliable] is `true` when
 * [modeLeading] hits one of its two degenerate gap populations — one single group of gaps, or a p10 dragged
 * down by an isolated gap — and [JabraScanPdf] turns the flag into a warning that names the work and the
 * chapter.
 */
internal data class ParagraphReconstruction(
    val paragraphs: List<String>,
    val leading: Float,
    val statisticUnreliable: Boolean,
)

/**
 * The text-level half of PDF extraction: the lines an extractor found in, the paragraphs a reader sees
 * out.
 *
 * The rules are the ones measured against real jabrascan.net PDFs, in the order they apply:
 *
 * - a line that is only a page number is not prose and is dropped;
 * - a line that opens or closes several pages is a running header or footer, not prose, and is dropped;
 * - two consecutive lines on the same page belong to the same paragraph unless the lower one sits more
 *   than [PARAGRAPH_GAP] times the page's typical leading below the other, where the leading is the mode
 *   of the same-page gaps that sit at or below their 10th percentile (fewer than [MIN_LEADING_GAPS]
 *   same-page gaps make the leading unmeasurable, and then every line goes into one paragraph). Two
 *   degenerate gap populations make that statistic untrustworthy — one single group of gaps, or a 10th
 *   percentile dragged down by an isolated gap — and are reported through
 *   [ParagraphReconstruction.statisticUnreliable] rather than used silently (see [modeLeading]);
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
     * Calibrated on 35 real files in total: the 14 calibration chapters, the 14 T7 chapters and the extra
     * chapters of the works whose breaks had collapsed. The largest intra-paragraph gap that stayed noise
     * measured 1.20x the leading (chapter CDMNQTMHC, ch.1) and the tightest real paragraph break 1.38x
     * (chapter MushokuTensei), so `1.29` sits at the exact centre of the two.
     *
     * The ceiling carries a caveat worth writing down: it comes only from files whose labels are valid.
     * The three chapters that had collapsed under the old leading (MeCaseConLaDragonaQueMate and
     * TheDemonKingOverrun) do NOT measure a ceiling, because their one-line paragraphs are full width and
     * the short-line oracle cannot label their gaps.
     */
    private const val PARAGRAPH_GAP = 1.29f

    /**
     * Fewer same-page gaps than this and the page's leading is not measurable: [modeLeading] then returns
     * `0f` with the statistic left trusted, which [startsParagraph] reads as "never break".
     */
    private const val MIN_LEADING_GAPS = 4

    /** A line that opens or closes this many pages is a running header or footer. */
    private const val RUNNING_REPEATS = 2

    /**
     * The paragraphs of [lines] in reading order, each a single flowing string, together with the leading
     * they were split against in a [ParagraphReconstruction].
     *
     * An empty input, or an input that is nothing but headers, footers and page numbers, reads as no
     * paragraphs rather than as one empty one, with an unmeasurable leading of `0f`.
     */
    fun reconstruct(lines: List<ExtractedLine>): ParagraphReconstruction {
        val body = withoutFurniture(lines)
        if (body.isEmpty()) return ParagraphReconstruction(emptyList(), 0f, statisticUnreliable = false)

        val statistic = modeLeading(body)
        val paragraphs = ArrayList<String>()
        val current = ArrayList<ExtractedLine>()
        for (line in body) {
            if (current.isNotEmpty() && startsParagraph(current.last(), line, statistic.leading)) {
                paragraphs.add(join(current))
                current.clear()
            }
            current.add(line)
        }
        paragraphs.add(join(current))
        return ParagraphReconstruction(paragraphs, statistic.leading, statistic.unreliable)
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
     * What [modeLeading] measured: [leading] is the baseline-to-baseline distance to split against, and
     * [unreliable] is `true` when the gap population was too degenerate for that statistic to mean
     * anything (see [modeLeading]).
     */
    private data class LeadingStatistic(val leading: Float, val unreliable: Boolean)

    /**
     * The leading [reconstruct] splits against, with the flag that says whether it can be trusted: the
     * mode of the same-page gaps that sit at or below their 10th percentile, unless one of the two
     * degenerate gap populations below is found.
     *
     * The median cannot carry this, and neither could the mode of the gaps at or below the median. In a
     * chapter thick with one-line paragraphs the paragraph-break pitch becomes the *majority* gap, so the
     * median drifts up to the break size, the mode follows it, the threshold rises above every real
     * break, and whole chapters collapse into a handful of blocks. Anchoring the pool at the 10th
     * percentile instead keeps the mode on the small ordinary gaps the wrapped lines contribute: the break
     * pitch, however dominant, is excluded from the pool and cannot set the leading. Measured on 35 real
     * chapters, this recovered all three chapters the median-anchored rule had collapsed while leaving
     * every other chapter's block count identical.
     *
     * Gaps are bucketed to 0.1 pt ([roundToInt]`(gap * 10)`) so near-identical leads land together. On a
     * tie between buckets the smaller bucket wins: an under-estimated leading only adds breaks, while an
     * over-estimated one collapses paragraphs.
     *
     * The p10 anchor can still be fooled by a degenerate gap population, and the two shapes are reported
     * through [LeadingStatistic.unreliable] instead of used silently:
     *
     * - **One single group of gaps.** When the pool of gaps at or below the p10 is at least 90% of all
     *   same-page gaps (`pool.size * 10 >= gaps.size * 9`), the p10 sits on the bulk of the population and
     *   there is no ordinary-versus-break separation to find. The chapter must NOT be split, so the
     *   leading is `0f` — [startsParagraph] then never breaks and the whole input becomes one paragraph —
     *   and the statistic is flagged. The test is deliberately the pool's *share of all gaps* and not a
     *   literal "the p10 is close to the median" comparison: measured against the real corpus, the median
     *   sits within 1% of the p10 in roughly twenty healthy chapters (e.g. Arifureta, 81 blocks, pool 180
     *   of 342 gaps), so a median comparison would have collapsed them, while this pool-share test fires on
     *   no measured chapter (the largest observed share was about 0.53).
     * - **A single stray gap dragging the p10 down.** When the pool has no 0.1 pt bucket holding at least
     *   two gaps — i.e. the p10 landed on an isolated value, as a chapter mixing font sizes can contribute
     *   with one tiny gap — a lone gap must not set the leading, so the dragged value is not returned. The
     *   leading falls back to [modeAtOrBelowMedian], the previous estimator, which a single stray gap
     *   cannot move, and the statistic is flagged.
     *
     * Fewer than [MIN_LEADING_GAPS] same-page gaps is a third shape and is *not* unreliable: there the
     * leading is legitimately "nothing to separate", so it is `0f` with the flag left `false`, and the
     * collapse it causes is already reported as a real failure by the too-few-blocks warning in
     * [JabraScanParagraphWarnings].
     */
    private fun modeLeading(lines: List<ExtractedLine>): LeadingStatistic {
        val gaps = ArrayList<Float>()
        for (i in 1 until lines.size) {
            if (lines[i].page == lines[i - 1].page) gaps.add(abs(lines[i].baseline - lines[i - 1].baseline))
        }
        if (gaps.size < MIN_LEADING_GAPS) return LeadingStatistic(0f, unreliable = false)

        val index = ((gaps.size * 0.1).toInt() - 1).coerceAtLeast(0)
        val p10 = gaps.sorted()[index]
        val pool = gaps.filter { it <= p10 }

        // One single group of gaps: the p10 sits on the bulk of the population, so there is nothing to
        // separate and the chapter must not be split.
        if (pool.size * 10 >= gaps.size * 9) return LeadingStatistic(0f, unreliable = true)

        val counts = bucketCounts(pool)
        // The p10 landed on an isolated value: a lone gap must not set the leading, so the pre-p10
        // estimator answers instead.
        if (counts.values.none { it >= 2 }) {
            return LeadingStatistic(modeAtOrBelowMedian(gaps), unreliable = true)
        }
        return LeadingStatistic(modeBucket(counts), unreliable = false)
    }

    /** The 0.1 pt buckets of [gaps]: each key is [roundToInt]`(gap * 10)`, its value how many gaps it holds. */
    private fun bucketCounts(gaps: List<Float>): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        for (gap in gaps) counts.merge((gap * 10f).roundToInt(), 1, Int::plus)
        return counts
    }

    /**
     * The bucket holding the most gaps, the smaller bucket winning a tie: an under-estimated leading only
     * adds breaks, while an over-estimated one collapses paragraphs.
     *
     * Iterates explicitly rather than using `maxByOrNull`: a tie must resolve to the *smaller* bucket, and
     * the map's iteration order does not guarantee that on its own.
     */
    private fun modeBucket(counts: Map<Int, Int>): Float {
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
     * The pre-p10 estimator, kept only as [modeLeading]'s fallback: the mode of the same-page gaps at or
     * below their median. A single stray gap cannot move it, which is exactly why it answers when the p10
     * lands on one.
     */
    private fun modeAtOrBelowMedian(gaps: List<Float>): Float {
        val median = gaps.sorted()[gaps.size / 2]
        return modeBucket(bucketCounts(gaps.filter { it <= median }))
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
