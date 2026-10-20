package app.novel.extension.fixtures

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FixtureClasspathTest {

    @Test
    fun `unit test runtime supports Kotlin stdlib and coroutines`() {
        val result = runBlocking {
            listOf(3, 1, 2).sorted().joinToString("-")
        }

        assertEquals("1-2-3", result)
    }
}
