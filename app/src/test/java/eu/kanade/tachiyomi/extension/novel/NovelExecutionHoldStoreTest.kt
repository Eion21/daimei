package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import eu.kanade.tachiyomi.extension.util.ExtensionGateDecision
import eu.kanade.tachiyomi.extension.util.decideExtensionGate
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class NovelExecutionHoldStoreTest {
    @TempDir lateinit var temp: Path
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val pkg = "app.novel.extension.fixture"
    private fun signers(vararg s: String) = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, s.toSet())
    private fun facts(
        version: Long = 7,
        signers: NovelPackageSigners = signers(a, b),
        origin: NovelInstallOrigin = NovelInstallOrigin.SHARED,
        split: Boolean = false,
    ) = NovelInstalledPackageFacts(pkg, version, "1", signers, origin, split)
    private fun store() = NovelExecutionHoldStore(temp.resolve("holds").toFile())
    private fun enroll(s: NovelExecutionHoldStore) = s.enroll("op", pkg, 7, setOf(a, b), "c".repeat(64))

    @Test fun `no record preserves existing execution and enrollment survives restart`() {
        assertTrue(store().permits(pkg, 7, signers(a), false))
        assertTrue(enroll(store()))
        assertFalse(store().permits(pkg, 7, signers(a, b)))
        assertTrue(enroll(store()))
        assertFalse(store().enroll("foreign", pkg, 7, setOf(a, b), "c".repeat(64)))
        assertFalse(store().permits(pkg, 7, signers(a, b)))
    }

    @Test fun `reconciliation is not consent and grants exact current tuple only`() {
        val s = store()
        assertTrue(enroll(s))
        assertTrue(s.reconcile("op", facts()))
        assertFalse(store().permits(pkg, 7, signers(a, b)))
        assertFalse(s.grantExecution("op", facts(), false))
        assertFalse(s.grantExecution("foreign", facts(), true))
        assertTrue(s.grantExecution("op", facts(), true))
        assertTrue(store().permits(pkg, 7, signers(a, b)))
        assertFalse(store().permits(pkg, 8, signers(a, b)))
        assertFalse(store().permits(pkg, 7, signers(a)))
        assertFalse(
            store().permits(pkg, 7, NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf(a), setOf(b))),
        )
        assertFalse(
            store().permits(
                pkg,
                7,
                NovelPackageSigners(NovelSignerObservation.LEGACY_UNVERIFIED, legacy = setOf(a, b)),
            ),
        )
        assertFalse(store().permits(pkg, 7, NovelPackageSigners(NovelSignerObservation.UNAVAILABLE)))
        assertFalse(store().permits(pkg, 7, signers(a, b), false))
    }

    @Test fun `mismatched installed facts cannot reconcile or grant`() {
        val s = store()
        assertTrue(enroll(s))
        for (f in listOf(
            facts(8),
            facts(signers = signers(a)),
            facts(origin = NovelInstallOrigin.PRIVATE),
            facts(split = true),
            facts(signers = NovelPackageSigners(NovelSignerObservation.UNAVAILABLE)),
        )) {
            assertFalse(s.reconcile("op", f))
            assertFalse(s.grantExecution("op", f, true))
        }
        assertFalse(s.permits(pkg, 7, signers(a, b)))
    }

    @Test fun `symlink root lock and record are rejected`() {
        val outside = Files.createDirectory(temp.resolve("outside"))
        val rootLink = temp.resolve("root-link")
        Files.createSymbolicLink(rootLink, outside)
        assertFalse(NovelExecutionHoldStore(rootLink.toFile()).permits(pkg, 7, signers(a)))
        val lockRoot = Files.createDirectory(temp.resolve("lock-root"))
        val target = temp.resolve("target")
        Files.writeString(target, "unchanged")
        Files.createSymbolicLink(lockRoot.resolve(".lock"), target)
        assertFalse(NovelExecutionHoldStore(lockRoot.toFile()).permits(pkg, 7, signers(a)))
        assertEquals("unchanged", Files.readString(target))
        val s = store()
        assertTrue(enroll(s))
        val record = temp.resolve("holds").toFile().listFiles()!!.single { it.name.endsWith(".record") }.toPath()
        Files.delete(record)
        Files.createSymbolicLink(record, target)
        assertFalse(s.permits(pkg, 7, signers(a, b)))
        assertFalse(enroll(s))
        assertEquals("unchanged", Files.readString(target))
    }

    @Test fun `post rename failure keeps enrollment and grant uncertain`() {
        val failing =
            NovelExecutionHoldStore(temp.resolve("holds").toFile()) {
                throw java.io.IOException("simulated sync failure")
            }
        assertFalse(enroll(failing))
        assertFalse(store().permits(pkg, 7, signers(a, b)))
        assertFalse(store().reconcile("op", facts()))
        val otherRoot = temp.resolve("other").toFile()
        val normal = NovelExecutionHoldStore(otherRoot)
        assertTrue(enroll(normal))
        assertTrue(normal.reconcile("op", facts()))
        assertFalse(
            NovelExecutionHoldStore(otherRoot) {
                throw java.io.IOException("simulated sync failure")
            }.grantExecution("op", facts(), true),
        )
        assertFalse(normal.permits(pkg, 7, signers(a, b)))
        assertFalse(normal.grantExecution("op", facts(), true))
    }

    @Test fun `racing instances admit one operation without overwriting consent`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val results = listOf("one", "two").map { op ->
                pool.submit<Boolean> {
                    start.await()
                    store().enroll(op, pkg, 7, setOf(a, b), "c".repeat(64))
                }
            }
            start.countDown()
            val won = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, won.count { it })
            val owner = if (won[0]) "one" else "two"
            val foreign = if (won[0]) "two" else "one"
            assertTrue(store().reconcile(owner, facts()))
            assertTrue(store().grantExecution(owner, facts(), true))
            assertFalse(store().enroll(foreign, pkg, 7, setOf(a, b), "c".repeat(64)))
            assertTrue(store().permits(pkg, 7, signers(a, b)))
            assertFalse(store().permits(pkg, 7, signers(a, b), false))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `invalid version refused and old packages need no modern observation`() {
        val s = store()
        assertTrue(s.permits(pkg, 0, NovelPackageSigners(NovelSignerObservation.UNAVAILABLE)))
        assertTrue(s.permits(pkg, 0, NovelPackageSigners(NovelSignerObservation.LEGACY_UNVERIFIED), false))
        assertFalse(s.enroll("op", pkg, 0, setOf(a, b), "c".repeat(64)))
        assertTrue(s.permits(pkg, 0, NovelPackageSigners(NovelSignerObservation.UNAVAILABLE)))
    }

    @Test fun `failed final sync and failed restoration cannot publish a grant`() {
        val s = store()
        assertTrue(enroll(s))
        assertTrue(s.reconcile("op", facts()))
        val faults = mutableListOf<String>()
        s.onFaultPoint =
            { point ->
                faults.add(point)
                if (point == "finalSync" ||
                    point == "restore"
                ) {
                    throw java.io.IOException(point)
                }
            }
        assertFalse(s.grantExecution("op", facts(), true))
        assertEquals(listOf("beforeHoldWrite", "persist", "finalSync", "restore"), faults)
        assertFalse(
            store().permits(pkg, 7, signers(a, b)),
            "failed operation must not grant after a new store instance",
        )
    }

    @Test fun `failures before and during persistence do not produce authority`() {
        for (point in listOf("beforeHoldWrite", "persist", "finalSync")) {
            val root = temp.resolve(point).toFile()
            val s = NovelExecutionHoldStore(root)
            s.onFaultPoint = { if (it == point) throw java.io.IOException(point) }
            assertFalse(enroll(s))
            // Before hold creation no session may exist; other failures leave a pending marker.
            if (point != "beforeHoldWrite") {
                assertFalse(NovelExecutionHoldStore(root, NovelExecutionEpoch()).permits(pkg, 7, signers(a, b)))
                assertFalse(NovelExecutionHoldStore(root, NovelExecutionEpoch()).reconcile("op", facts()))
            }
        }
    }

    @Test fun `actual barrier and production gate deny class construction and process restart`() {
        fun hash(
            s: String,
        ) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        fun signature(s: String) = mockk<Signature>().also { every { it.toByteArray() } returns s.toByteArray() }
        fun info(version: Long, current: String, history: String): PackageInfo = mockk<PackageInfo>().apply {
            packageName = pkg
            every { longVersionCode } returns version
            signingInfo = mockk<SigningInfo>().also { signing ->
                every { signing.apkContentsSigners } returns arrayOf(signature(current))
                every { signing.signingCertificateHistory } returns arrayOf(signature(history), signature(current))
                every { signing.hasMultipleSigners() } returns false
            }
        }
        val context = mockk<Context>()
        every { context.filesDir } returns temp.toFile()
        val epoch = NovelExecutionEpoch()
        val s = NovelExecutionHoldStore(temp.resolve("s39-execution-holds").toFile(), epoch)
        val observed = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf(hash("new")))
        val actual = NovelInstalledPackageFacts(pkg, 7, "1", observed)
        var constructions = 0
        fun gate(pi: PackageInfo, shared: Boolean, currentEpoch: NovelExecutionEpoch = epoch): ExtensionGateDecision {
            val legacyTrusted = true
            val decision = decideExtensionGate(
                true,
                true,
                legacyTrusted && NovelExecutionBarrier.permits(context, pi, shared, 28, currentEpoch),
                true,
            )
            if (decision == ExtensionGateDecision.ALLOWED) constructions++
            return decision
        }
        val correct = info(7, "new", "old")
        assertEquals(ExtensionGateDecision.ALLOWED, gate(correct, true))
        constructions = 0
        assertTrue(s.enroll("op", pkg, 7, setOf(hash("new")), "c".repeat(64)))
        assertEquals(ExtensionGateDecision.UNTRUSTED, gate(correct, true))
        correct.reqFeatures = arrayOf(mockk<FeatureInfo>().apply { name = "tachiyomi.extension" })
        assertEquals(ExtensionGateDecision.UNTRUSTED, gate(correct, true))
        assertTrue(s.reconcile("op", actual))
        assertTrue(s.reconcile("op", actual))
        assertEquals(ExtensionGateDecision.UNTRUSTED, gate(correct, true))
        s.onFaultPoint = { if (it == "finalSync" || it == "restore") throw java.io.IOException(it) }
        assertFalse(s.grantExecution("op", actual, true))
        val record = temp.resolve("s39-execution-holds").toFile().listFiles()!!.single { it.name.endsWith(".record") }
        assertEquals(1, record.readBytes().last().toInt())
        assertEquals(ExtensionGateDecision.UNTRUSTED, gate(correct, true))
        assertEquals(ExtensionGateDecision.UNTRUSTED, gate(correct, true, NovelExecutionEpoch()))
        s.onFaultPoint = {}
        assertTrue(s.grantExecution("op", actual, true))
        assertEquals(1, record.readBytes().last().toInt())
        assertEquals(ExtensionGateDecision.UNTRUSTED, gate(correct, true, NovelExecutionEpoch()))
        assertEquals(ExtensionGateDecision.UNTRUSTED, gate(info(8, "new", "old"), true))
        assertEquals(ExtensionGateDecision.UNTRUSTED, gate(info(7, "old", "new"), true))
        assertEquals(ExtensionGateDecision.UNTRUSTED, gate(correct, false))
        assertEquals(0, constructions)
        assertEquals(ExtensionGateDecision.ALLOWED, gate(correct, true))
        assertEquals(1, constructions)
    }

    @Test fun `legacy serialized grant cannot authorize new process`() {
        val s = store()
        assertTrue(enroll(s))
        assertTrue(s.reconcile("op", facts()))
        val record = temp.resolve("holds").toFile().listFiles()!!.single { it.name.endsWith(".record") }
        val bytes = record.readBytes()
        bytes[bytes.lastIndex] = 2
        record.writeBytes(bytes)
        assertFalse(
            NovelExecutionHoldStore(
                temp.resolve("holds").toFile(),
                NovelExecutionEpoch(),
            ).permits(pkg, 7, signers(a, b)),
        )
    }

    @Test fun `corruption and filesystem errors refuse while siblings remain`() {
        val s = store()
        assertTrue(enroll(s))
        val sibling = temp.resolve("holds").resolve("unrelated").toFile().apply { writeText("keep") }
        val record = temp.resolve("holds").toFile().listFiles()!!.single { it.name.endsWith(".record") }
        record.writeText("corrupted")
        assertFalse(store().permits(pkg, 7, signers(a, b)))
        assertFalse(enroll(store()))
        assertEquals("keep", sibling.readText())
        val bad = temp.resolve("not-a-directory").toFile().apply { writeText("keep") }
        assertFalse(NovelExecutionHoldStore(bad).permits(pkg, 7, signers(a, b)))
        assertEquals("keep", bad.readText())
    }
}
