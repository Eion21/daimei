package eu.kanade.tachiyomi.extension.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path

internal class NovelCoordinatorUpdateTest {
    @TempDir lateinit var root: Path
    private val pkg = "app.novel.extension.matchanovels"
    private val signer = "a".repeat(64)
    private val signers = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf(signer))
    private val url = "https://example.com/app.apk"

    private fun metadata(version: Long) = NovelArchiveMetadata(
        pkg, version, "v$version", "$version", listOf("$pkg.Source"), 26, setOf("app.novel.extension"), false, signers,
    )

    private fun entryJson(version: Long) = """{
        "protocol":"daimei-novel-distribution",
        "schemaVersion":2,
        "repository":"https://example.com",
        "extensions":[{
            "packageName":"$pkg",
            "longVersionCode":$version,
            "versionName":"v$version",
            "novelApi":"1.1",
            "entryClasses":["$pkg.Source"],
            "minSdk":26,
            "artifact":{"url":"$url","size":100,"sha256":"${"c".repeat(64)}"},
            "currentSigners":["$signer"]
        }]
    }""".trimIndent()

    private fun entry(version: Long): NovelDistributionEntry =
        NovelDistributionCatalogCodec.decode(ByteArrayInputStream(entryJson(version).toByteArray())).entries.first()

    private class MemoryJournal : NovelInstallJournal {
        var current: NovelInstallRecord? = null
        override fun insert(record: NovelInstallRecord): Boolean {
            if (current != null) return false
            current = record
            return true
        }
        override fun read(operationId: String): NovelInstallJournalRead = current
            ?.takeIf { it.operationId == operationId }?.let(NovelInstallJournalRead::Found)
            ?: NovelInstallJournalRead.Missing
        override fun replace(expected: NovelInstallRecord, replacement: NovelInstallRecord): Boolean {
            if (current != expected) return false
            current = replacement
            return true
        }
    }

    private class FakeInstalled(private val facts: NovelInstalledPackageFacts?) : NovelInstalledPackages {
        override fun read(packageName: String): NovelInstalledRead =
            if (facts != null && facts.packageName == packageName) NovelInstalledRead.Present(facts)
            else NovelInstalledRead.Absent
    }

    private class FakeBridge : NovelInstallSessionBridge {
        override var controlledTestActivation = true
        override fun hasInstallPermission() = true
        override fun createSession(record: NovelInstallRecord) = 1
        override fun writeSession(session: Int, source: InputStream, expectedSize: Long) =
            NovelInstallStreamReceipt(expectedSize, "c".repeat(64))
        override fun ownsSession(session: Int, packageName: String) = true
        override fun submit(record: NovelInstallRecord) = NovelInstallDispatch.SUBMITTED
        override fun abandonSession(session: Int) = true
    }

    private fun coordinator(installed: NovelInstalledPackageFacts?) = NovelInteractiveInstallCoordinator(
        inspect = { _, _ -> NovelInspectionResult.Observed(metadata(2), NovelStructuralLayout.Base(pkg)) },
        installed = FakeInstalled(installed),
        holds = NovelExecutionHoldStore(root.resolve("holds").toFile()),
        journal = MemoryJournal(),
        bridge = FakeBridge(),
    )

    private fun artifact(): NovelQuarantinedArtifact {
        val path = root.resolve("artifact.apk")
        java.nio.file.Files.createDirectories(path.parent)
        java.nio.file.Files.write(path, ByteArray(100))
        return NovelQuarantinedArtifact(
            path,
            NovelArtifactIntegrity(100, 100, "c".repeat(64), "c".repeat(64)),
        )
    }

    @Test
    fun `update allowed when installed version is lower and signers match`() {
        val installed = NovelInstalledPackageFacts(pkg, 1L, "1", signers)
        val result = coordinator(installed).begin(artifact(), entry(2), true)
        assertEquals(null, result.failure)
    }

    @Test
    fun `new install allowed when package is absent`() {
        val result = coordinator(null).begin(artifact(), entry(2), true)
        assertEquals(null, result.failure)
    }

    @Test
    fun `update rejected when installed signers differ`() {
        val other = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, setOf("b".repeat(64)))
        val installed = NovelInstalledPackageFacts(pkg, 1L, "1", other)
        val result = coordinator(installed).begin(artifact(), entry(2), true)
        assertEquals(NovelInstallFailure.NOT_NEW, result.failure)
    }

    @Test
    fun `update rejected when installed version is same`() {
        val installed = NovelInstalledPackageFacts(pkg, 2L, "2", signers)
        val result = coordinator(installed).begin(artifact(), entry(2), true)
        assertEquals(NovelInstallFailure.NOT_NEW, result.failure)
    }
}
