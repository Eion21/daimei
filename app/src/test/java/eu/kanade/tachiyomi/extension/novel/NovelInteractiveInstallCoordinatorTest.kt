package eu.kanade.tachiyomi.extension.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class NovelInteractiveInstallCoordinatorTest {
    @TempDir lateinit var root: Path
    private val pkg = "app.novel.example"
    private val signer = "a".repeat(64)
    private val callbackNonce = "b".repeat(64)
    private val metadata get() = NovelArchiveMetadata(
        pkg, 7, "v1", "1.2", listOf("$pkg.Source"), 26, setOf("app.novel.extension"), false,
        NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf(signer)),
    )
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class MemoryJournal : NovelInstallJournal {
        var current: NovelInstallRecord? = null
        var writes = 0
        var failWrite = false
        override fun insert(record: NovelInstallRecord): Boolean {
            if (failWrite || current != null) return false
            current = record
            writes++
            return true
        }
        override fun read(operationId: String): NovelInstallJournalRead = current
            ?.takeIf { it.operationId == operationId }?.let(NovelInstallJournalRead::Found)
            ?: NovelInstallJournalRead.Missing
        override fun replace(expected: NovelInstallRecord, replacement: NovelInstallRecord): Boolean {
            if (failWrite || current != expected) return false
            current = replacement
            writes++
            return true
        }
    }
    private class Bridge : NovelInstallSessionBridge {
        override var controlledTestActivation = true
        var permission = true
        var created = 0
        var submissions = 0
        var abandoned = 0
        var own = true
        var corruptStream = false
        var submissionThrows = false
        override fun hasInstallPermission() = permission
        override fun createSession(record: NovelInstallRecord): Int {
            created++
            return 11
        }
        override fun writeSession(sessionId: Int, source: InputStream, expectedSize: Long): NovelInstallStreamReceipt {
            val bytes = source.readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            return NovelInstallStreamReceipt(bytes.size.toLong(), if (corruptStream) "0".repeat(64) else digest)
        }
        override fun ownsSession(sessionId: Int, packageName: String) = own && sessionId == 11
        override fun submit(record: NovelInstallRecord): NovelInstallDispatch {
            submissions++
            if (submissionThrows) error("uncertain transport")
            return NovelInstallDispatch.SUBMITTED
        }
        override fun abandonSession(sessionId: Int): Boolean {
            abandoned++
            return true
        }
    }
    private inner class Fixture {
        val path = Files.createTempFile(Files.createTempDirectory(root, "s39-"), "artifact-", ".quarantine")
        val bytes: ByteArray
        val expected: NovelDistributionEntry
        val artifact: NovelQuarantinedArtifact
        val journal = MemoryJournal()
        val bridge = Bridge()
        val holdRoot = Files.createTempDirectory(root, "execution-").resolve("holds")
        val holds = NovelExecutionHoldStore(holdRoot.toFile())
        var installed: NovelInstalledRead = NovelInstalledRead.Absent
        var recognized = true
        init {
            ZipOutputStream(Files.newOutputStream(path)).use {
                it.putNextEntry(ZipEntry("AndroidManifest.xml"))
                it.write(NovelBinaryManifestFixture.make())
                it.closeEntry()
            }
            bytes = Files.readAllBytes(path)
            val sha = hash(bytes)
            val json = """{
              "protocol":"daimei-novel-distribution","schemaVersion":2,"repository":"fixture",
              "extensions":[{"packageName":"$pkg","longVersionCode":7,"versionName":"v1",
              "novelApi":"1.2","entryClasses":["$pkg.Source"],"minSdk":26,
              "artifact":{"url":"https://example.com/a.apk","size":${bytes.size},"sha256":"$sha"},
              "currentSigners":["$signer"]}]}"""
            expected = NovelDistributionCatalogCodec.decode(json.byteInputStream()).entries.single()
            artifact = NovelQuarantinedArtifact(
                path,
                NovelArtifactIntegrity(bytes.size.toLong(), bytes.size.toLong(), sha, sha),
            )
        }
        fun coordinator(j: NovelInstallJournal = journal) = NovelInteractiveInstallCoordinator(
            NovelApkInspector(root, 30, NovelArchiveMetadataPort { metadata })::inspect,
            NovelInstalledPackages { installed },
            holds,
            j,
            bridge,
            NovelInstallRecognition { _, _ -> recognized },
            { "op" },
            { callbackNonce },
        )
        fun facts(version: Long = 7, signers: Set<String> = setOf(signer)) = NovelInstalledPackageFacts(
            pkg,
            version,
            "v1",
            NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, signers),
        )
        fun seed(phase: NovelInstallPhase = NovelInstallPhase.COMMITTING): NovelInstallRecord {
            assertTrue(holds.enroll("op", pkg, 7, setOf(signer), expected.sha256))
            return NovelInstallRecord(
                "op", callbackNonce, pkg, 7, setOf(signer), path.toString(), expected.size, expected.sha256,
                sessionId = 11, streamedSha256 = expected.sha256, phase = phase, commitRequested = true,
            ).also { journal.current = it }
        }
        fun callback(status: NovelInstallCallbackStatus, confirmation: NovelInstallUserConfirmation? = null) =
            NovelInstallCallback("op", callbackNonce, 11, status, confirmation)
        fun assertHeld() = assertFalse(holds.permits(pkg, 7, metadata.signers))
    }

    @Test fun `NEW staging streams exact bytes and persists a hold before any session`() {
        val f = Fixture()
        val result = f.coordinator().begin(f.artifact, f.expected, true)
        assertEquals(NovelInstallPhase.STAGED, result.phase)
        assertEquals(f.expected.sha256, f.journal.current!!.streamedSha256)
        assertEquals(1, f.bridge.created)
        assertEquals(0, f.bridge.submissions)
        assertFalse(result.executionGranted)
        f.assertHeld()
    }

    @Test fun `consent and NEW only reject without journal session or trust effects`() {
        val f = Fixture()
        val c = f.coordinator()
        assertEquals(NovelInstallFailure.CONSENT_REQUIRED, c.begin(f.artifact, f.expected, false).failure)
        f.installed = NovelInstalledRead.Present(f.facts())
        assertEquals(NovelInstallFailure.NOT_NEW, c.begin(f.artifact, f.expected, true).failure)
        f.installed = NovelInstalledRead.Private(pkg)
        assertEquals(NovelInstallFailure.NOT_NEW, c.begin(f.artifact, f.expected, true).failure)
        f.installed = NovelInstalledRead.Unavailable
        assertEquals(NovelInstallFailure.NOT_NEW, c.begin(f.artifact, f.expected, true).failure)
        assertEquals(0, f.journal.writes)
        assertEquals(0, f.bridge.created)
    }

    @Test fun `permission refusal persists a recoverable hold and requires fresh explicit consent`() {
        val f = Fixture()
        f.bridge.permission = false
        val c = f.coordinator()
        assertEquals(NovelInstallPhase.PERMISSION_REQUIRED, c.begin(f.artifact, f.expected, true).phase)
        assertEquals(0, f.bridge.created)
        f.assertHeld()
        f.bridge.permission = true
        assertEquals(NovelInstallFailure.CONSENT_REQUIRED, c.resumePermission("op", false).failure)
        assertEquals(NovelInstallPhase.STAGED, c.resumePermission("op", true).phase)
        assertEquals(1, f.bridge.created)
    }

    @Test fun `before submit bytes and installed absence are revalidated`() {
        for (changedBytes in listOf(false, true)) {
            val f = Fixture()
            val c = f.coordinator()
            assertEquals(NovelInstallPhase.STAGED, c.begin(f.artifact, f.expected, true).phase)
            if (changedBytes) {
                Files.write(f.path, byteArrayOf(0))
            } else {
                f.installed = NovelInstalledRead.Present(f.facts())
            }
            val outcome = c.submit("op")
            assertEquals(
                if (changedBytes) NovelInstallFailure.INVALID_ARTIFACT else NovelInstallFailure.STALE_PACKAGE,
                outcome.failure,
            )
            assertEquals(0, f.bridge.submissions)
            assertEquals(1, f.bridge.abandoned)
            f.assertHeld()
        }
    }

    @Test fun `stream receipt mismatch abandons without commit and leaves hold intact`() {
        val f = Fixture()
        f.bridge.corruptStream = true
        assertEquals(
            NovelInstallFailure.STREAM_INTEGRITY,
            f.coordinator().begin(f.artifact, f.expected, true).failure,
        )
        assertEquals(1, f.bridge.abandoned)
        assertEquals(0, f.bridge.submissions)
        f.assertHeld()
    }

    @Test fun `journal failure prevents creating or submitting a session`() {
        val f = Fixture()
        f.journal.failWrite = true
        assertEquals(NovelInstallFailure.JOURNAL, f.coordinator().begin(f.artifact, f.expected, true).failure)
        assertEquals(0, f.bridge.created)
        assertEquals(0, f.bridge.submissions)
    }

    @Test fun `controlled submission is correlated and duplicate submission never dispatches again`() {
        val f = Fixture()
        val c = f.coordinator()
        assertEquals(NovelInstallPhase.STAGED, c.begin(f.artifact, f.expected, true).phase)
        assertEquals(NovelInstallPhase.COMMITTING, c.submit("op").phase)
        assertTrue(f.journal.current!!.commitRequested)
        c.submit("op")
        assertEquals(1, f.bridge.submissions)
        f.assertHeld()
    }

    @Test fun `pending user action waits for another explicit decision and launches only once`() {
        val f = Fixture()
        f.seed()
        val c = f.coordinator()
        var launches = 0
        val action = NovelInstallUserConfirmation {
            launches++
            true
        }
        assertEquals(
            NovelInstallPhase.PENDING_USER_ACTION,
            c.onCallback(f.callback(NovelInstallCallbackStatus.PENDING_USER_ACTION, action)).phase,
        )
        assertEquals(0, launches)
        c.confirmUserAction("op", false)
        assertEquals(0, launches)
        assertEquals(NovelInstallPhase.PENDING_USER_ACTION, c.confirmUserAction("op", true).phase)
        c.confirmUserAction("op", true)
        assertEquals(1, launches)
        f.assertHeld()
    }

    @Test fun `success checks actual package version and whole current signer set`() {
        for (facts in listOf<(Fixture) -> NovelInstalledPackageFacts>(
            { it.facts(8) },
            { it.facts(signers = setOf(signer, "c".repeat(64))) },
        )) {
            val f = Fixture()
            f.seed()
            f.installed = NovelInstalledRead.Present(facts(f))
            assertEquals(
                NovelInstallFailure.INSTALLED_IDENTITY,
                f.coordinator().onCallback(f.callback(NovelInstallCallbackStatus.SUCCESS)).failure,
            )
            f.assertHeld()
        }
    }

    @Test fun `success with exact identity remains held and unrecognized success stays distinct`() {
        for (recognized in listOf(false, true)) {
            val f = Fixture()
            f.seed()
            f.installed = NovelInstalledRead.Present(f.facts())
            f.recognized = recognized
            val result = f.coordinator().onCallback(f.callback(NovelInstallCallbackStatus.SUCCESS))
            assertEquals(
                if (recognized) NovelInstallPhase.INSTALLED_HELD else NovelInstallPhase.INSTALLED_UNRECOGNIZED,
                result.phase,
            )
            assertFalse(result.executionGranted)
            f.assertHeld()
            assertFalse(NovelExecutionHoldStore(f.holdRoot.toFile()).permits(pkg, 7, metadata.signers))
        }
    }

    @Test fun `failure and cancellation callbacks keep persistent isolation`() {
        for (status in listOf(NovelInstallCallbackStatus.FAILURE, NovelInstallCallbackStatus.CANCELLED)) {
            val f = Fixture()
            f.seed()
            val result = f.coordinator().onCallback(f.callback(status))
            assertEquals(
                if (status == NovelInstallCallbackStatus.FAILURE) {
                    NovelInstallPhase.FAILED
                } else {
                    NovelInstallPhase.CANCELLED
                },
                result.phase,
            )
            f.assertHeld()
            assertEquals(0, f.bridge.submissions)
        }
    }

    @Test fun `rejected installation permits a fresh explicitly consented attempt without execution`() {
        val f = Fixture()
        val journal = NovelFileInstallJournal(root.resolve("retry-journal"))
        var attempts = 0
        var nonces = 0
        var recognitions = 0
        val c = NovelInteractiveInstallCoordinator(
            NovelApkInspector(root, 30, NovelArchiveMetadataPort { metadata })::inspect,
            NovelInstalledPackages { f.installed },
            f.holds,
            journal,
            f.bridge,
            NovelInstallRecognition { _, _ -> recognitions++; false },
            { "attempt-${++attempts}" },
            { if (++nonces == 1) "b".repeat(64) else "c".repeat(64) },
        )
        val first = c.begin(f.artifact, f.expected, true)
        assertEquals(NovelInstallOutcome("attempt-1", NovelInstallPhase.STAGED), first)
        assertFalse(first.executionGranted)
        f.assertHeld()
        val submitted = c.submit("attempt-1")
        assertEquals(NovelInstallOutcome("attempt-1", NovelInstallPhase.COMMITTING), submitted)
        assertFalse(submitted.executionGranted)
        var prompts = 0
        val pending = c.onCallback(
            NovelInstallCallback(
                "attempt-1", "b".repeat(64), 11, NovelInstallCallbackStatus.PENDING_USER_ACTION,
                NovelInstallUserConfirmation { prompts++; true },
            ),
        )
        assertEquals(NovelInstallOutcome("attempt-1", NovelInstallPhase.PENDING_USER_ACTION), pending)
        assertFalse(pending.executionGranted)
        assertEquals(0, prompts)
        val confirmed = c.confirmUserAction("attempt-1", true)
        assertEquals(NovelInstallOutcome("attempt-1", NovelInstallPhase.PENDING_USER_ACTION), confirmed)
        assertFalse(confirmed.executionGranted)
        assertEquals(1, prompts)
        val rejected = c.onCallback(
            NovelInstallCallback("attempt-1", "b".repeat(64), 11, NovelInstallCallbackStatus.CANCELLED),
        )
        assertEquals(
            NovelInstallOutcome("attempt-1", NovelInstallPhase.CANCELLED, NovelInstallFailure.ANDROID_FAILURE),
            rejected,
        )
        assertFalse(rejected.executionGranted)
        assertEquals(NovelExecutionConsentOutcome.DENIED, c.authorizeExecution("attempt-1", true))
        f.assertHeld()
        assertFalse(NovelExecutionHoldStore(f.holdRoot.toFile(), NovelExecutionEpoch()).permits(pkg, 7, metadata.signers))
        val rejectedRecord = journal.read("attempt-1")
        val noConsent = c.begin(f.artifact, f.expected, false)
        assertEquals(NovelInstallOutcome(null, NovelInstallPhase.RECOVERY_REQUIRED, NovelInstallFailure.CONSENT_REQUIRED), noConsent)
        assertFalse(noConsent.executionGranted)
        assertEquals(rejectedRecord, journal.read("attempt-1"))
        assertEquals(1, attempts)
        assertEquals(1, f.bridge.created)
        assertEquals(1, f.bridge.submissions)
        f.assertHeld()

        val second = c.begin(f.artifact, f.expected, true)
        assertEquals(NovelInstallOutcome("attempt-2", NovelInstallPhase.STAGED), second)
        assertFalse(second.executionGranted)
        val secondRecord = (journal.read("attempt-2") as NovelInstallJournalRead.Found).record
        assertEquals("c".repeat(64), secondRecord.nonce)
        assertEquals(rejectedRecord, journal.read("attempt-1"))
        assertEquals(2, attempts)
        assertEquals(2, nonces)
        assertEquals(2, f.bridge.created)
        assertEquals(1, f.bridge.submissions)
        assertEquals(1, f.bridge.abandoned)
        assertEquals(0, recognitions)
        val stale = c.onCallback(
            NovelInstallCallback("attempt-1", "b".repeat(64), 11, NovelInstallCallbackStatus.SUCCESS),
        )
        assertEquals(rejected, stale)
        assertEquals(rejectedRecord, journal.read("attempt-1"))
        assertEquals(secondRecord, (journal.read("attempt-2") as NovelInstallJournalRead.Found).record)
        assertEquals(0, recognitions)
        assertEquals(NovelExecutionConsentOutcome.DENIED, c.authorizeExecution("attempt-1", true))
        assertEquals(NovelExecutionConsentOutcome.DENIED, c.authorizeExecution("attempt-2", true))
        f.assertHeld()
        assertFalse(NovelExecutionHoldStore(f.holdRoot.toFile(), NovelExecutionEpoch()).permits(pkg, 7, metadata.signers))
    }

    @Test fun `active enrollment cannot be replaced by another consented request`() {
        val f = Fixture()
        val journal = NovelFileInstallJournal(root.resolve("active-journal"))
        var attempts = 0
        val c = NovelInteractiveInstallCoordinator(
            NovelApkInspector(root, 30, NovelArchiveMetadataPort { metadata })::inspect,
            NovelInstalledPackages { f.installed }, f.holds, journal, f.bridge,
            NovelInstallRecognition { _, _ -> false }, { "active-${++attempts}" },
            { if (attempts == 1) "b".repeat(64) else "c".repeat(64) },
        )
        assertEquals(NovelInstallPhase.STAGED, c.begin(f.artifact, f.expected, true).phase)
        val before = journal.read("active-1")
        assertEquals(NovelInstallFailure.CONSENT_REQUIRED, c.begin(f.artifact, f.expected, false).failure)
        assertEquals(before, journal.read("active-1"))
        val second = c.begin(f.artifact, f.expected, true)
        assertEquals(NovelInstallPhase.FAILED, second.phase)
        assertEquals(NovelInstallFailure.HOLD, second.failure)
        assertEquals(before, journal.read("active-1"))
        assertEquals(1, f.bridge.created)
        assertEquals(0, f.bridge.submissions)
        assertEquals(NovelExecutionConsentOutcome.DENIED, c.authorizeExecution("active-1", true))
        f.assertHeld()
    }

    @Test fun `foreign callbacks never mutate journal session or execution permission`() {
        val f = Fixture()
        val before = f.seed()
        val c = f.coordinator()
        val valid = f.callback(NovelInstallCallbackStatus.SUCCESS)
        for (callback in listOf(
            valid.copy(operationId = "foreign"),
            valid.copy(nonce = "d".repeat(64)),
            valid.copy(sessionId = 12),
        )) {
            assertEquals(NovelInstallFailure.FOREIGN_CALLBACK, c.onCallback(callback).failure)
            assertEquals(before, f.journal.current)
            assertEquals(0, f.journal.writes)
            assertEquals(0, f.bridge.abandoned)
        }
        f.assertHeld()
    }

    @Test fun `duplicate or reordered callbacks cannot undo completed state or grant execution`() {
        val f = Fixture()
        f.seed()
        f.installed = NovelInstalledRead.Present(f.facts())
        val c = f.coordinator()
        assertEquals(
            NovelInstallPhase.INSTALLED_HELD,
            c.onCallback(f.callback(NovelInstallCallbackStatus.SUCCESS)).phase,
        )
        val before = f.journal.current
        val writes = f.journal.writes
        c.onCallback(f.callback(NovelInstallCallbackStatus.SUCCESS))
        c.onCallback(f.callback(NovelInstallCallbackStatus.FAILURE))
        assertEquals(before, f.journal.current)
        assertEquals(writes, f.journal.writes)
        f.assertHeld()
    }

    @Test fun `restart cannot reconcile installed package without correlated success`() {
        val f = Fixture()
        val before = f.seed()
        f.installed = NovelInstalledRead.Present(f.facts())
        val result = f.coordinator().reconcile("op")
        assertEquals(NovelInstallPhase.RECOVERY_REQUIRED, result.phase)
        assertEquals(NovelInstallFailure.PROCESS_RESTART, result.failure)
        assertEquals(before, f.journal.current)
        assertEquals(0, f.journal.writes)
        assertEquals(0, f.bridge.created)
        assertEquals(0, f.bridge.submissions)
        f.assertHeld()
    }

    @Test fun `restart reconciles recorded success without resubmission or restored trust`() {
        val f = Fixture()
        f.seed()
        f.journal.current = f.journal.current!!.copy(androidResult = NovelInstallCallbackStatus.SUCCESS)
        f.installed = NovelInstalledRead.Present(f.facts())
        val result = f.coordinator().reconcile("op")
        assertEquals(NovelInstallPhase.INSTALLED_HELD, result.phase)
        assertEquals(0, f.bridge.created)
        assertEquals(0, f.bridge.submissions)
        f.assertHeld()
    }

    @Test fun `uncertain dispatch and lost session stay recoverable but cannot execute`() {
        val f = Fixture()
        val c = f.coordinator()
        c.begin(f.artifact, f.expected, true)
        f.bridge.submissionThrows = true
        assertEquals(NovelInstallPhase.RECOVERY_REQUIRED, c.submit("op").phase)
        assertEquals(1, f.bridge.submissions)
        f.assertHeld()
    }

    @Test fun `explicit cancellation abandons only the owned session and leaves DENY persisted`() {
        val f = Fixture()
        f.seed(NovelInstallPhase.STAGED)
        assertEquals(NovelInstallPhase.CANCELLED, f.coordinator().cancel("op").phase)
        assertEquals(1, f.bridge.abandoned)
        f.assertHeld()
    }

    @Test fun `full controlled flow uses durable journal explicit action and real loader gate`() {
        val f = Fixture()
        val journalRoot = root.resolve("durable-journal")
        val j = NovelFileInstallJournal(journalRoot)
        val c = f.coordinator(j)
        assertEquals(NovelInstallPhase.STAGED, c.begin(f.artifact, f.expected, true).phase)
        assertEquals(NovelInstallPhase.COMMITTING, c.submit("op").phase)
        var prompts = 0
        c.onCallback(
            f.callback(
                NovelInstallCallbackStatus.PENDING_USER_ACTION,
                NovelInstallUserConfirmation {
                    prompts++
                    true
                },
            ),
        )
        assertEquals(0, prompts)
        c.confirmUserAction("op", true)
        assertEquals(1, prompts)
        f.installed = NovelInstalledRead.Present(f.facts())
        f.recognized = false
        assertEquals(
            NovelInstallPhase.INSTALLED_UNRECOGNIZED,
            c.onCallback(f.callback(NovelInstallCallbackStatus.SUCCESS)).phase,
        )
        val restartedJournal = NovelFileInstallJournal(journalRoot)
        val restarted = f.coordinator(restartedJournal)
        f.recognized = true
        assertEquals(NovelInstallPhase.INSTALLED_HELD, restarted.reconcile("op").phase)
        val before = restartedJournal.read("op")
        restarted.reconcile("op")
        assertEquals(before, restartedJournal.read("op"))
        assertEquals(1, f.bridge.created)
        assertEquals(1, f.bridge.submissions)
        assertEquals(
            eu.kanade.tachiyomi.extension.util.ExtensionGateDecision.UNTRUSTED,
            eu.kanade.tachiyomi.extension.util.decideExtensionGate(
                true,
                true,
                f.holds.permits(pkg, 7, metadata.signers),
                true,
            ),
        )
        f.assertHeld()
    }

    @Test fun `final journal sync failure cannot dispatch and restart cannot resume submission`() {
        val f = Fixture()
        var fail = false
        val journalRoot = root.resolve("sync-journal")
        val j = NovelFileInstallJournal(journalRoot) { point ->
            if (fail && point == "afterMove") error("final directory sync failed")
        }
        val c = f.coordinator(j)
        assertEquals(NovelInstallPhase.STAGED, c.begin(f.artifact, f.expected, true).phase)
        fail = true
        assertEquals(NovelInstallFailure.JOURNAL, c.submit("op").failure)
        assertEquals(0, f.bridge.submissions)
        val restarted = f.coordinator(NovelFileInstallJournal(journalRoot))
        assertEquals(NovelInstallPhase.RECOVERY_REQUIRED, restarted.reconcile("op").phase)
        restarted.submit("op")
        assertEquals(0, f.bridge.submissions)
        f.assertHeld()
    }

    @Test fun `execution requires explicit consent and fresh installed identity after correlated success`() {
        val f = Fixture()
        f.seed()
        val c = f.coordinator()
        f.installed = NovelInstalledRead.Present(f.facts())
        assertEquals(NovelExecutionConsentOutcome.DENIED, c.authorizeExecution("op", true))
        f.assertHeld()
        c.onCallback(f.callback(NovelInstallCallbackStatus.SUCCESS))
        val before = f.journal.current
        val writes = f.journal.writes
        assertEquals(NovelExecutionConsentOutcome.DENIED, c.authorizeExecution("op", false))
        f.installed = NovelInstalledRead.Present(f.facts(8))
        assertEquals(NovelExecutionConsentOutcome.DENIED, c.authorizeExecution("op", true))
        f.installed = NovelInstalledRead.Present(f.facts(signers = setOf("c".repeat(64))))
        assertEquals(NovelExecutionConsentOutcome.DENIED, c.authorizeExecution("op", true))
        val rotated = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf(signer), setOf("c".repeat(64)))
        val legacy = NovelPackageSigners(NovelSignerObservation.LEGACY_UNVERIFIED, legacy = setOf(signer))
        for (invalid in listOf(
            f.facts().copy(signers = rotated),
            f.facts().copy(signers = legacy),
            f.facts().copy(origin = NovelInstallOrigin.PRIVATE),
            f.facts().copy(split = true),
            f.facts().copy(packageName = "app.novel.other"),
        )) {
            f.installed = NovelInstalledRead.Present(invalid)
            assertEquals(NovelExecutionConsentOutcome.DENIED, c.authorizeExecution("op", true))
        }
        assertEquals(before, f.journal.current)
        assertEquals(writes, f.journal.writes)
        f.assertHeld()
        f.installed = NovelInstalledRead.Present(f.facts())
        assertEquals(NovelExecutionConsentOutcome.GRANTED, c.authorizeExecution("op", true))
        assertTrue(f.holds.permits(pkg, 7, metadata.signers))
        assertEquals(before, f.journal.current)
        assertEquals(writes, f.journal.writes)
        assertFalse(NovelExecutionHoldStore(f.holdRoot.toFile(), NovelExecutionEpoch()).permits(pkg, 7, metadata.signers))
    }

    @Test fun `production activation cannot start a session or write journal and holds`() {
        val f = Fixture()
        f.bridge.controlledTestActivation = false
        assertEquals(NovelInstallPhase.PRODUCTION_DISABLED, f.coordinator().begin(f.artifact, f.expected, true).phase)
        assertEquals(0, f.bridge.created)
        assertEquals(0, f.journal.writes)
        assertTrue(f.holds.permits(pkg, 7, metadata.signers))
    }

    @Test fun `update proceeds only from shared nonsplit complete signer facts at a lower version`() {
        val f = Fixture()
        f.installed = NovelInstalledRead.Present(f.facts(version = 6))
        val result = f.coordinator().begin(f.artifact, f.expected, true)
        assertEquals(NovelInstallPhase.STAGED, result.phase)
        assertEquals(1, f.bridge.created)
        assertEquals(0, f.bridge.submissions)
        assertFalse(result.executionGranted)
        f.assertHeld()
    }

    @Test fun `update rejects private split legacy or higher installed facts before any session`() {
        val legacy = NovelPackageSigners(NovelSignerObservation.LEGACY_UNVERIFIED, legacy = setOf(signer))
        val unavailable = NovelPackageSigners(NovelSignerObservation.UNAVAILABLE)
        for (facts in listOf<(Fixture) -> NovelInstalledPackageFacts>(
            { it.facts(version = 6).copy(origin = NovelInstallOrigin.PRIVATE) },
            { it.facts(version = 6).copy(split = true) },
            { it.facts(version = 6).copy(signers = legacy) },
            { it.facts(version = 6).copy(signers = unavailable) },
            { it.facts(version = 8) },
        )) {
            val f = Fixture()
            f.installed = NovelInstalledRead.Present(facts(f))
            val outcome = f.coordinator().begin(f.artifact, f.expected, true)
            assertEquals(NovelInstallFailure.NOT_NEW, outcome.failure)
            assertEquals(0, f.journal.writes)
            assertEquals(0, f.bridge.created)
            assertEquals(0, f.bridge.submissions)
        }
    }

    @Test fun `update with a mismatched signer set is rejected before any session`() {
        val f = Fixture()
        f.installed = NovelInstalledRead.Present(
            f.facts(version = 6, signers = setOf(signer, "c".repeat(64))),
        )
        val outcome = f.coordinator().begin(f.artifact, f.expected, true)
        assertEquals(NovelInstallFailure.NOT_NEW, outcome.failure)
        assertEquals(0, f.journal.writes)
        assertEquals(0, f.bridge.created)
    }
}
