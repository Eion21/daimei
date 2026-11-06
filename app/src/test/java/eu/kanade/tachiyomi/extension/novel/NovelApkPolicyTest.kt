package eu.kanade.tachiyomi.extension.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/** Synthetic observations exercise policy only, not APK parsing/cryptographic/platform verification. */
class NovelApkPolicyTest {
    private val payload = "isolated test bytes, not an APK".toByteArray()
    private val cert = "a".repeat(64)
    private val otherCert = "b".repeat(64)
    private val entry = NovelCatalogEntry(
        "app.novel.extension.example", "Example", 4L, "1.0.3", "1.3", 26,
        "app.novel.extension.example.Source",
        NovelCatalogArtifact("example.apk", hash(payload), payload.size.toLong()),
        NovelCatalogSigning(listOf(cert)),
    )
    private val apk = NovelApkIdentity(
        entry.id, entry.versionCode, entry.versionName, entry.novelApi, entry.entryClass, entry.minSdk,
        setOf("app.novel.extension"), true, setOf(cert),
    )

    private fun inspect(
        identity: NovelApkIdentity = apk,
        bytes: ByteArray = payload,
        trusted: Boolean = true,
        interactive: Boolean = true,
        context: Boolean = true,
        installed: NovelInstalledIdentity? = null,
        expected: NovelCatalogEntry = entry,
    ) = NovelApkPolicy.inspect(expected, bytes, identity, 35, trusted, interactive, context, installed)

    @Test
    fun `even all matching observations remain blocked by host compatibility and null artifact URL`() {
        val result = inspect()
        assertEquals(setOf(NovelApkFinding.HOST_INCOMPATIBLE, NovelApkFinding.NO_DOWNLOAD_URL), result.findings)
        assertFalse(result.canInstall)
    }

    @Test
    fun `false catalog denies download installation and update regardless of package or trust`() {
        val catalog = NovelCatalog(listOf(entry))
        assertFalse(catalog.hostCompatible)
        for (id in listOf(entry.id, "app.novel.extension.other", "")) {
            val actions = listOf(
                NovelRemoteActions::download,
                NovelRemoteActions::install,
                NovelRemoteActions::update,
            )
            for (action in actions) {
                val error = assertThrows(SecurityException::class.java) { action(id) }
                assertEquals("Remote APK actions are disabled in S38 (hostCompatible: false).", error.message)
            }
        }
        assertEquals(4L, entry.versionCode)
        assertEquals(hash(payload), entry.artifact.sha256)
    }

    @Test
    fun `wrong package altered bytes and missing trust never change inputs or become installable`() {
        val before = payload.copyOf()
        val cases = listOf(
            inspect(identity = apk.copy(packageName = "app.novel.extension.other")) to NovelApkFinding.PACKAGE_IDENTITY,
            inspect(bytes = payload + 1.toByte()) to NovelApkFinding.HASH,
            inspect(bytes = payload + 1.toByte()) to NovelApkFinding.SIZE,
            inspect(trusted = false) to NovelApkFinding.TRUST,
        )
        for ((result, finding) in cases) {
            assertTrue(finding in result.findings, finding.name)
            assertFalse(result.canInstall)
        }
        assertTrue(payload.contentEquals(before))
        assertEquals(entry.id, apk.packageName)
        assertEquals(4L, apk.versionCode)
    }

    @Test
    fun `unsigned unverified and wrong signer observations are rejected independently of user trust`() {
        for (identity in listOf(
            apk.copy(signerSha256 = emptySet()),
            apk.copy(signatureVerified = false),
            apk.copy(signerSha256 = setOf(otherCert)),
        )) {
            val result = inspect(identity)
            assertTrue(NovelApkFinding.SIGNATURE in result.findings)
            assertFalse(result.canInstall)
        }
    }

    @Test
    fun `API manifest type metadata and device SDK must match actual observations`() {
        for ((identity, finding) in listOf(
            apk.copy(novelApi = "9.9") to NovelApkFinding.NOVEL_API,
            apk.copy(novelApi = "1.1") to NovelApkFinding.NOVEL_API,
            apk.copy(entryClass = "other.Class") to NovelApkFinding.METADATA,
            apk.copy(versionName = "unexpected") to NovelApkFinding.METADATA,
            apk.copy(versionCode = 99L) to NovelApkFinding.METADATA,
            apk.copy(minSdk = 36) to NovelApkFinding.METADATA,
            apk.copy(features = setOf("tachiyomi.extension")) to NovelApkFinding.NOVEL_KIND,
            apk.copy(features = apk.features + "tachiyomi.extension") to NovelApkFinding.NOVEL_KIND,
        )) {
            assertTrue(finding in inspect(identity).findings, finding.name)
        }
    }

    @Test
    fun `update cannot change package or signer and must strictly increase versionCode`() {
        val valid = NovelInstalledIdentity(entry.id, 3L, setOf(cert))
        assertEquals(2, inspect(installed = valid).findings.size)
        for ((prior, finding) in listOf(
            valid.copy(packageName = "app.novel.extension.other") to NovelApkFinding.PACKAGE_IDENTITY,
            valid.copy(versionCode = 4L) to NovelApkFinding.VERSION,
            valid.copy(versionCode = 5L) to NovelApkFinding.VERSION,
            valid.copy(signerSha256 = setOf(otherCert)) to NovelApkFinding.INSTALLED_SIGNER,
        )) {
            assertTrue(finding in inspect(installed = prior).findings)
        }
        assertEquals(3L, valid.versionCode)
        assertEquals(setOf(cert), valid.signerSha256)
    }

    @Test
    fun `interactive system install and Jabra style package context cannot be replaced by private loading`() {
        assertTrue(NovelApkFinding.INTERACTIVE_REQUIRED in inspect(interactive = false).findings)
        assertTrue(NovelApkFinding.PACKAGE_CONTEXT_REQUIRED in inspect(context = false).findings)
        assertFalse(inspect(interactive = false, context = false).canInstall)
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
