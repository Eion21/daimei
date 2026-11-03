package app.novel.extension.jabrascan

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JabraScanPdfLimitsTest {
    @Test
    fun `budgets accept the boundary and reject values above it`() {
        val limits = JabraScanPdfLimits(inputBytes = 10, pages = 2, textUnits = 5)
        assertDoesNotThrow {
            limits.checkInput(10)
            limits.checkPages(2)
            limits.checkText(5)
        }
        val checks = listOf<() -> Unit>(
            { limits.checkInput(11) },
            { limits.checkPages(3) },
            { limits.checkText(6) },
        )
        for (check in checks) {
            val error = assertThrows(IllegalStateException::class.java, check)
            assertTrue(error.message!!.contains("PDF") && error.message!!.contains("limit"))
        }
    }

    @Test
    fun `invalid budgets cannot disable limits`() {
        assertThrows(IllegalArgumentException::class.java) { JabraScanPdfLimits(inputBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { JabraScanPdfLimits(pages = 0) }
        assertThrows(IllegalArgumentException::class.java) { JabraScanPdfLimits(textUnits = 0) }
        assertThrows(IllegalArgumentException::class.java) { JabraScanPdfLimits(scratchMainBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            JabraScanPdfLimits(scratchMainBytes = 2, scratchTotalBytes = 1)
        }
    }
}
