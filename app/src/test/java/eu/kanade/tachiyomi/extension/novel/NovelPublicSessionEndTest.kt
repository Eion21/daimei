package eu.kanade.tachiyomi.extension.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

internal class NovelPublicSessionEndTest {
    @TempDir lateinit var temp: Path
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val pkg = "app.novel.extension.matchanovels"
    private fun signers(vararg s: String) = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, s.toSet())
    private fun facts(version: Long = 7, signers: NovelPackageSigners = signers(a, b)) =
        NovelInstalledPackageFacts(pkg, version, "1", signers)
    private fun store() = NovelExecutionHoldStore(temp.resolve("holds").toFile())
    private fun enroll(s: NovelExecutionHoldStore) = s.enroll("op", pkg, 7, setOf(a, b), "c".repeat(64))

    @Test fun `revoking execution removes lease and retains durable DENY`() {
        val s = store()
        assertTrue(enroll(s))
        assertTrue(s.reconcile("op", facts()))
        assertTrue(s.grantExecution("op", facts(), true))
        assertTrue(s.permits(pkg, 7, signers(a, b)))

        assertTrue(s.revokeExecution(pkg, 7))
        assertFalse(s.permits(pkg, 7, signers(a, b)))

        // The durable record is still present and still DENY after a fresh store instance.
        val restarted = store()
        assertFalse(restarted.permits(pkg, 7, signers(a, b)))
    }

    @Test fun `revoke only affects exact package and version`() {
        val s = store()
        assertTrue(enroll(s))
        assertTrue(s.reconcile("op", facts()))
        assertTrue(s.grantExecution("op", facts(), true))

        // Wrong version or wrong package cannot revoke the managed lease.
        assertFalse(s.revokeExecution(pkg, 8))
        assertFalse(s.revokeExecution("app.novel.extension.skynovels", 7))
        assertTrue(s.permits(pkg, 7, signers(a, b)))

        // The exact version revokes.
        assertTrue(s.revokeExecution(pkg, 7))
        assertFalse(s.permits(pkg, 7, signers(a, b)))
    }

    @Test fun `revoked package needs fresh lease after update`() {
        val s = store()
        assertTrue(enroll(s))
        assertTrue(s.reconcile("op", facts()))
        assertTrue(s.grantExecution("op", facts(), true))
        assertTrue(s.revokeExecution(pkg, 7))

        // A newer version requires a fresh enrollment and grant; the old lease must not carry over.
        val update = s.enroll("op2", pkg, 8, setOf(a, b), "d".repeat(64))
        assertTrue(update)
        assertTrue(s.reconcile("op2", facts(8)))
        assertFalse(s.permits(pkg, 8, signers(a, b)))
        assertTrue(s.grantExecution("op2", facts(8), true))
        assertTrue(s.permits(pkg, 8, signers(a, b)))
        assertFalse(s.permits(pkg, 7, signers(a, b)))
    }

    @Test fun `revoke leaves other packages executing`() {
        val s = store()
        val otherPkg = "app.novel.extension.skynovels"
        assertTrue(enroll(s))
        assertTrue(s.reconcile("op", facts()))
        assertTrue(s.grantExecution("op", facts(), true))

        val other = NovelExecutionHoldStore(temp.resolve("other").toFile())
        assertTrue(other.enroll("op2", otherPkg, 3, setOf(a, b), "d".repeat(64)))
        assertTrue(other.reconcile("op2", facts(3).copy(packageName = otherPkg)))
        assertTrue(other.grantExecution("op2", facts(3).copy(packageName = otherPkg), true))

        assertTrue(s.revokeExecution(pkg, 7))
        assertFalse(s.permits(pkg, 7, signers(a, b)))
        assertTrue(other.permits(otherPkg, 3, signers(a, b)))
    }

    @Test fun `revoke on missing record is a no-op`() {
        val s = store()
        assertFalse(s.revokeExecution(pkg, 7))
    }

    @Test fun `higher version enroll cannot rebind to a different signer set`() {
        val s = store()
        assertTrue(enroll(s))
        assertTrue(s.reconcile("op", facts()))
        assertTrue(s.grantExecution("op", facts(), true))
        assertTrue(s.permits(pkg, 7, signers(a, b)))

        // A higher version signed by a different signer set must be rejected...
        assertFalse(s.enroll("op2", pkg, 8, setOf(a), "d".repeat(64)))
        // ...without disturbing the managed lease or the durable record.
        assertTrue(s.permits(pkg, 7, signers(a, b)))
        assertFalse(s.permits(pkg, 8, signers(a)))

        // The same signer set at a higher version still updates monotonically.
        assertTrue(s.enroll("op3", pkg, 8, setOf(a, b), "e".repeat(64)))
        assertFalse(s.permits(pkg, 8, signers(a, b)))
    }
}
