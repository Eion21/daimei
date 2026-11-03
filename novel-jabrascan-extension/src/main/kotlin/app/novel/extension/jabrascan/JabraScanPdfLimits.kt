package app.novel.extension.jabrascan

/**
 * Chapter processing budgets: 33,554,432 input bytes; PDFBox mixed scratch up to 8,388,608
 * main-memory bytes and 67,108,864 total bytes; 1,000 pages; and 2,000,000 decoded UTF-16 units
 * or glyph positions. Exceeding a budget fails without returning partial paragraphs. Scratch or parse
 * I/O errors identify PDF processing and preserve their cause. fetchBytes has already allocated the
 * complete network response before these checks; parser and font overhead are not a hard heap bound.
 */
internal data class JabraScanPdfLimits(
    val inputBytes: Int = 32 * 1024 * 1024,
    val scratchMainBytes: Long = 8L * 1024 * 1024,
    val scratchTotalBytes: Long = 64L * 1024 * 1024,
    val pages: Int = 1000,
    val textUnits: Int = 2_000_000,
) {
    init {
        require(
            inputBytes > 0 && scratchMainBytes > 0 && scratchTotalBytes >= scratchMainBytes && pages > 0 &&
                textUnits > 0,
        ) {
            "PDF processing budgets must be positive and scratch total must cover main memory"
        }
    }

    fun checkInput(size: Int) = checkLimit("input bytes", size.toLong(), inputBytes.toLong())
    fun checkPages(count: Int) = checkLimit("pages", count.toLong(), pages.toLong())
    fun checkText(count: Long) = checkLimit("decoded text/glyphs", count, textUnits.toLong())

    private fun checkLimit(name: String, actual: Long, maximum: Long) {
        if (actual > maximum) throw IllegalStateException("PDF $name limit $maximum exceeded (observed $actual)")
    }
}
