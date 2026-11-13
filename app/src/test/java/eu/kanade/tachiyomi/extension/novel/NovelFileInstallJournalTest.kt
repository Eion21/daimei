package eu.kanade.tachiyomi.extension.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class NovelFileInstallJournalTest {
    @TempDir lateinit var temp: Path
    private fun record() = NovelInstallRecord(
        "op",
        "b".repeat(64),
        "app.novel.example",
        7,
        setOf("a".repeat(64)),
        temp.resolve("artifact.apk").toString(),
        100,
        "c".repeat(64),
    )

    @Test fun `restart retains exact immutable binding but never an execution grant`() {
        val root = temp.resolve("journal")
        val journal = NovelFileInstallJournal(root)
        val r = record()
        assertTrue(journal.insert(r))
        assertFalse(journal.insert(r))
        assertEquals(NovelInstallJournalRead.Found(r), NovelFileInstallJournal(root).read("op"))
        assertEquals(NovelInstallJournalRead.Missing, journal.read("other"))
    }

    @Test fun `compare and replace rejects stale revision or changed identity and session`() {
        val journal = NovelFileInstallJournal(temp.resolve("journal"))
        val r = record()
        assertTrue(journal.insert(r))
        val bound = r.copy(sessionId = 11, revision = 1)
        assertFalse(journal.replace(r, bound.copy(packageName = "app.novel.foreign")))
        assertTrue(journal.replace(r, bound))
        assertFalse(journal.replace(r, bound))
        assertFalse(journal.replace(bound, bound.copy(sessionId = 12, revision = 2)))
        assertEquals(NovelInstallJournalRead.Found(bound), journal.read("op"))
    }

    @Test fun `malformed records and path traversal stay unavailable and do not overwrite good data`() {
        val root = temp.resolve("journal")
        val journal = NovelFileInstallJournal(root)
        assertTrue(journal.insert(record()))
        val before = journal.read("op")
        assertEquals(NovelInstallJournalRead.Unavailable, journal.read("../outside"))
        assertFalse(journal.insert(record().copy(operationId = "../outside")))
        assertEquals(before, journal.read("op"))
        Files.write(root.resolve("op.journal"), byteArrayOf(0))
        assertEquals(NovelInstallJournalRead.Unavailable, NovelFileInstallJournal(root).read("op"))
    }

    @Test fun `IO failure reports failure and restored journal remains DENY only`() {
        val root = temp.resolve("journal")
        val journal = NovelFileInstallJournal(root) { point -> if (point == "afterMove") error("sync failure") }
        assertFalse(journal.insert(record()))
        val restored = NovelFileInstallJournal(root).read("op")
        assertTrue(restored is NovelInstallJournalRead.Found || restored == NovelInstallJournalRead.Unavailable)
        if (restored is NovelInstallJournalRead.Found) assertEquals(NovelInstallPhase.PREPARING, restored.record.phase)
    }
}
