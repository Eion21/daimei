package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class NovelDistributionRuntimePathTest {
    @TempDir lateinit var directory: Path

    @Test fun `trusted files dir alias is canonicalized but quarantine leaf symlink is not followed`() {
        val real = Files.createDirectory(directory.resolve("private")).toRealPath()
        val alias = Files.createSymbolicLink(directory.resolve("alias"), real)
        val other = Files.createDirectory(directory.resolve("other"))
        val leaf = Files.createSymbolicLink(real.resolve("s39-quarantine"), other)
        val context = mockk<Context>()
        every { context.filesDir } returns alias.toFile()
        val result = NovelInteractiveInstallRuntime.quarantineRoot(context)
        assertEquals(leaf, result)
        assertTrue(Files.isSymbolicLink(result), "Producer must not canonicalize an untrusted quarantine leaf")
        assertEquals(other.toRealPath(), result.toRealPath())
    }
}
