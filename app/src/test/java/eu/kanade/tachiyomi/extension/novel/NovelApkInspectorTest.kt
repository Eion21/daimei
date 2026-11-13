package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NovelApkInspectorTest {
    @TempDir lateinit var root: Path
    private val metadata =
        NovelArchiveMetadata(
            "app.novel.example", 7, "v1", "1.2",
            listOf(
                "app.novel.example.Source",
            ),
            26, setOf("app.novel.extension"), false, NovelPackageSigners(NovelSignerObservation.UNAVAILABLE),
        )
    private fun hash(
        bytes: ByteArray,
    ) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fixture(
        manifest: Boolean = true,
        invalidZip: Boolean = false,
        xml: ByteArray = NovelBinaryManifestFixture.make(),
        expectation: NovelArchiveMetadata = metadata,
    ): Triple<Path, NovelQuarantinedArtifact, NovelDistributionEntry> {
        val folder = Files.createTempDirectory(root, "s39-")
        val path = Files.createTempFile(folder, "artifact-", ".quarantine")
        if (invalidZip) {
            Files.write(path, byteArrayOf(1, 2, 3))
        } else {
            ZipOutputStream(Files.newOutputStream(path)).use {
                it.putNextEntry(ZipEntry(if (manifest) "AndroidManifest.xml" else "other"))
                it.write(xml)
                it.closeEntry()
            }
        }
        val bytes = Files.readAllBytes(path)
        val digest = hash(bytes)
        val classes = expectation.entryClasses.joinToString(",") { "\"$it\"" }
        val json = """{"protocol":"daimei-novel-distribution","schemaVersion":2,"repository":"fixture","extensions":[{"packageName":"${expectation.packageName}","longVersionCode":${expectation.longVersionCode},"versionName":"${expectation.versionName}","novelApi":"${expectation.novelApi}","entryClasses":[$classes],"minSdk":${expectation.minSdk},"artifact":{"url":"https://example.com/a.apk","size":${bytes.size},"sha256":"$digest"},"currentSigners":["${"a".repeat(
            64,
        )}"]}]}"""
        val expected = NovelDistributionCatalogCodec.decode(json.byteInputStream()).entries.single()
        return Triple(
            path,
            NovelQuarantinedArtifact(
                path,
                NovelArtifactIntegrity(bytes.size.toLong(), bytes.size.toLong(), digest, digest),
            ),
            expected,
        )
    }
    private fun inspect(
        artifact: NovelQuarantinedArtifact,
        expected: NovelDistributionEntry,
        value: NovelArchiveMetadata = metadata,
        sdk: Int = 30,
    ) =
        NovelApkInspector(root, sdk, NovelArchiveMetadataPort { value }).inspect(artifact, expected)

    @Test fun `valid inert fixture remains pending and bytes unchanged`() {
        val (path, artifact, expected) = fixture()
        val before = Files.readAllBytes(path)
        val result = inspect(artifact, expected)
        assertTrue(result is NovelInspectionResult.Observed)
        assertEquals(
            NovelSignatureVerification.PENDING,
            (result as NovelInspectionResult.Observed).signatureVerification,
        )
        assertEquals(NovelStructuralLayout.Base("app.novel.example"), result.layout)
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun `compiled fixture manifest proves base independently of metadata`() {
        val xml = NovelBinaryManifestFixture.compiledManifest()
        assertEquals("637a7938fcbe176958860b9a7a6f83fe3d1d0e5d6b0dda525636f1e7d889190b", hash(xml))
        val compiledMetadata = metadata.copy(
            packageName = "app.novel.extension.fixtures",
            longVersionCode = 1,
            versionName = "1.0.0",
            novelApi = "1.1",
            entryClasses = listOf(
                "app.novel.extension.fixtures.FixtureNovelSource",
                "app.novel.extension.fixtures.HttpFixtureNovelSource",
            ),
        )
        val (path, artifact, expected) = fixture(xml = xml, expectation = compiledMetadata)
        val before = Files.readAllBytes(path)
        assertEquals(NovelStructuralLayout.Base("app.novel.extension.fixtures"), NovelApkLayoutInspector.inspect(path))
        val result = inspect(artifact, expected, compiledMetadata) as NovelInspectionResult.Observed
        assertEquals(NovelStructuralLayout.Base("app.novel.extension.fixtures"), result.layout)
        assertEquals(NovelSignatureVerification.PENDING, result.signatureVerification)
        assertEquals(
            NovelInspectionResult.Rejected(NovelInspectionError.IDENTITY_CONFLICT),
            inspect(artifact, expected, metadata),
        )
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun `compiled resource id alias and unknown id stay unavailable`() {
        for (badId in listOf(0x01010003, 0x0101ffff)) {
            val xml = NovelBinaryManifestFixture.compiledManifest()
            val buffer = java.nio.ByteBuffer.wrap(xml).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var p = 8
            while (buffer.getShort(p).toInt() and 0xffff != 0x180) p += buffer.getInt(p + 4)
            buffer.putInt(p + 8, badId) // Map "label" to a different or unknown platform ID.
            val (path, artifact, expected) = fixture(xml = xml)
            val before = Files.readAllBytes(path)
            assertEquals(NovelStructuralLayout.Unknown, NovelApkLayoutInspector.inspect(path))
            assertEquals(
                NovelInspectionResult.Rejected(NovelInspectionError.UNKNOWN_LAYOUT),
                inspect(artifact, expected),
            )
            assertArrayEquals(before, Files.readAllBytes(path))
        }
    }

    @Test fun `standalone split is rejected despite empty pm split fields`() {
        val (path, artifact, expected) = fixture(xml = NovelBinaryManifestFixture.make(split = "config.en"))
        val before = Files.readAllBytes(path)
        assertEquals(NovelInspectionResult.Rejected(NovelInspectionError.SPLIT), inspect(artifact, expected))
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun `malformed binary manifest and text manifest are rejected`() {
        val inputs =
            listOf(
                NovelBinaryManifestFixture.make().dropLast(1).toByteArray(),
                "not binary xml".toByteArray(),
                NovelBinaryManifestFixture.make(duplicatePackage = true),
            )
        for (xml in inputs) {
            val (path, artifact, expected) = fixture(xml = xml)
            val before = Files.readAllBytes(path)
            assertEquals(
                NovelInspectionResult.Rejected(NovelInspectionError.MALFORMED_MANIFEST),
                inspect(artifact, expected),
            )
            assertArrayEquals(before, Files.readAllBytes(path))
        }
    }

    @Test fun `contradictory binary package is rejected`() {
        val (path, artifact, expected) = fixture(xml = NovelBinaryManifestFixture.make(pkg = "app.other.identity"))
        val before = Files.readAllBytes(path)
        assertEquals(
            NovelInspectionResult.Rejected(NovelInspectionError.IDENTITY_CONFLICT),
            inspect(artifact, expected),
        )
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun `resource valued layout signal is insufficient`() {
        val (path, artifact, expected) = fixture(xml = NovelBinaryManifestFixture.make(resourceFlag = true))
        val before = Files.readAllBytes(path)
        assertEquals(NovelInspectionResult.Rejected(NovelInspectionError.UNKNOWN_LAYOUT), inspect(artifact, expected))
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun `uses split is not standalone`() {
        val (path, artifact, expected) = fixture(xml = NovelBinaryManifestFixture.make(usesSplit = true))
        val before = Files.readAllBytes(path)
        assertEquals(NovelInspectionResult.Rejected(NovelInspectionError.SPLIT), inspect(artifact, expected))
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun `metadata mismatches and non novel features reject without changing bytes`() {
        val (path, artifact, expected) = fixture()
        val before = Files.readAllBytes(path)
        assertEquals(
            NovelInspectionResult.Rejected(NovelInspectionError.IDENTITY_CONFLICT),
            inspect(artifact, expected, metadata.copy(packageName = "app.other.name")),
        )
        val variants =
            listOf(
                metadata.copy(longVersionCode = 8),
                metadata.copy(versionName = "v2"),
                metadata.copy(novelApi = "1.1"),
                metadata.copy(entryClasses = listOf("app.other.Source")),
                metadata.copy(minSdk = 27),
                metadata.copy(features = emptySet()),
                metadata.copy(features = setOf("app.novel.extension", "tachiyomi.extension")),
            )
        for (value in variants) {
            assertEquals(
                NovelInspectionResult.Rejected(NovelInspectionError.MISMATCH),
                inspect(artifact, expected, value),
            )
        }
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun `unsupported api or device reject`() {
        val (path, artifact, expected) = fixture()
        val before = Files.readAllBytes(path)
        assertEquals(
            NovelInspectionResult.Rejected(NovelInspectionError.UNSUPPORTED),
            inspect(artifact, expected, sdk = 25),
        )
        assertEquals(
            NovelInspectionResult.Rejected(NovelInspectionError.UNSUPPORTED),
            inspect(artifact, expected, metadata.copy(novelApi = "2.0")),
        )
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun `split metadata rejects`() {
        val (path, artifact, expected) = fixture()
        val before = Files.readAllBytes(path)
        assertEquals(
            NovelInspectionResult.Rejected(NovelInspectionError.SPLIT),
            inspect(artifact, expected, metadata.copy(split = true)),
        )
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun `missing manifest and malformed zip reject`() {
        for ((path, artifact, expected) in listOf(fixture(manifest = false), fixture(invalidZip = true))) {
            val before = Files.readAllBytes(path)
            assertEquals(NovelInspectionResult.Rejected(NovelInspectionError.INVALID_ZIP), inspect(artifact, expected))
            assertArrayEquals(before, Files.readAllBytes(path))
        }
    }

    @Test fun `unowned path rejects unchanged`() {
        val (path, artifact, expected) = fixture()
        val before = Files.readAllBytes(path)
        val result = NovelApkInspector(
            path.parent,
            30,
            NovelArchiveMetadataPort {
                metadata
            },
        ).inspect(artifact, expected)
        assertEquals(NovelInspectionResult.Rejected(NovelInspectionError.UNOWNED), result)
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun `symlink and altered bytes reject without modifying target`() {
        val (path, artifact, expected) = fixture()
        val link = path.resolveSibling("artifact-link.quarantine")
        Files.createSymbolicLink(link, path.fileName)
        val original = Files.readAllBytes(path)
        val linked = NovelQuarantinedArtifact(link, artifact.integrity)
        assertEquals(NovelInspectionResult.Rejected(NovelInspectionError.UNOWNED), inspect(linked, expected))
        assertArrayEquals(original, Files.readAllBytes(path))
        val changed = "different".toByteArray()
        Files.write(path, changed)
        assertEquals(NovelInspectionResult.Rejected(NovelInspectionError.INTEGRITY), inspect(artifact, expected))
        assertArrayEquals(changed, Files.readAllBytes(path))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `context archive adapter observes pm declarations without loading code`() {
        val (path, artifact, expected) = fixture()
        val context = mockk<Context>()
        val pm = mockk<PackageManager>()
        val info = mockk<PackageInfo>()
        val app = mockk<ApplicationInfo>()
        val bundle = mockk<Bundle>()
        val feature = mockk<FeatureInfo>()
        every { context.packageManager } returns pm
        every { pm.getPackageArchiveInfo(path.toString(), any<Int>()) } returns info
        info.packageName = "app.novel.example"
        info.versionName = "v1"
        info.applicationInfo = app
        info.splitNames = emptyArray()
        info.signingInfo = null
        info.reqFeatures = arrayOf(feature)
        feature.name = "app.novel.extension"
        app.metaData = bundle
        app.minSdkVersion = 26
        app.splitSourceDirs = null
        every { info.longVersionCode } returns 7L
        every { bundle.getFloat("app.novel.extensionApi") } returns 1.2f
        every { bundle.getString("app.novel.extension.class") } returns ".Source"
        val result = NovelApkInspector(context, root, 30).inspect(artifact, expected)
        assertTrue(result is NovelInspectionResult.Observed)
        assertEquals(
            NovelSignatureVerification.PENDING,
            (result as NovelInspectionResult.Observed).signatureVerification,
        )
        assertEquals(NovelSignerObservation.UNAVAILABLE, result.metadata.signers.observation)
    }

    @Test fun `metadata callback mutation rejects without restoring bytes`() {
        val (path, artifact, expected) = fixture()
        val modified = "changed by fake port".toByteArray()
        val result = NovelApkInspector(
            root,
            30,
            NovelArchiveMetadataPort {
                Files.write(it, modified)
                metadata
            },
        ).inspect(artifact, expected)
        assertEquals(NovelInspectionResult.Rejected(NovelInspectionError.INTEGRITY), result)
        assertArrayEquals(modified, Files.readAllBytes(path))
    }
}
