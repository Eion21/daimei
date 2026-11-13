package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest

class NovelInstalledPackageReaderTest {
    @TempDir lateinit var temp: Path
    private val pkg = "app.novel.example"
    private val current = setOf("a".repeat(64), "b".repeat(64))
    private val signers = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, current, setOf("c".repeat(64)))
    private val facts = NovelInstalledPackageFacts(pkg, 4294967298L, "1.0", signers, split = true)

    @Test
    fun `only explicit not found without private collision is absent`() {
        val reader = NovelInstalledPackageReader({ NovelPackageLookup.NotFound }, { NovelPrivatePresence.ABSENT })
        assertEquals(NovelInstalledRead.Absent, reader.read(pkg))
    }

    @Test
    fun `private collision takes precedence over shared and absence`() {
        for (lookup in listOf(NovelPackageLookup.NotFound, NovelPackageLookup.Found(facts))) {
            val reader = NovelInstalledPackageReader({ lookup }, { NovelPrivatePresence.PRESENT })
            assertEquals(NovelInstalledRead.Private(pkg), reader.read(pkg))
        }
    }

    @Test
    fun `shared observation preserves long version split and separate complete current and history`() {
        val reader = NovelInstalledPackageReader({ NovelPackageLookup.Found(facts) }, { NovelPrivatePresence.ABSENT })
        val result = reader.read(pkg) as NovelInstalledRead.Present
        assertEquals(facts, result.facts)
        assertEquals(current, result.facts.signers.current)
        assertEquals(setOf("c".repeat(64)), result.facts.signers.history)
        assertEquals(true, result.facts.split)
    }

    @Test
    fun `legacy observation stays typed legacy`() {
        val legacy = NovelPackageSigners(NovelSignerObservation.LEGACY_UNVERIFIED, legacy = setOf("d".repeat(64)))
        val reader =
            NovelInstalledPackageReader({
                NovelPackageLookup.Found(facts.copy(signers = legacy))
            }, { NovelPrivatePresence.ABSENT })
        val result = reader.read(pkg) as NovelInstalledRead.Present
        assertEquals(NovelSignerObservation.LEGACY_UNVERIFIED, result.facts.signers.observation)
        assertEquals(false, result.facts.signers.hasCompleteCurrent)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `android lookup distinguishes visibility permission and non missing errors`() {
        val context = mockk<Context>()
        val pm = mockk<PackageManager>()
        every { context.filesDir } returns temp.toFile()
        every { context.packageManager } returns pm
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
        every { pm.getPackageInfo(pkg, any<Int>()) } throws mockk<PackageManager.NameNotFoundException>()
        assertEquals(NovelInstalledRead.Absent, NovelInstalledPackageReader(context, 30).read(pkg))
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED
        assertEquals(NovelInstalledRead.Unavailable, NovelInstalledPackageReader(context, 30).read(pkg))
        assertEquals(NovelInstalledRead.Absent, NovelInstalledPackageReader(context, 29).read(pkg))
        every { pm.getPackageInfo(pkg, any<Int>()) } throws SecurityException("fixture")
        assertEquals(NovelInstalledRead.Unavailable, NovelInstalledPackageReader(context, 30).read(pkg))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `android lookup reports exact current signer set and separate history`() {
        val context = mockk<Context>()
        val pm = mockk<PackageManager>()
        val info = mockk<PackageInfo>()
        val signing = mockk<SigningInfo>()
        fun signature(
            value: String,
        ) = mockk<Signature>().also { every { it.toByteArray() } returns value.toByteArray() }
        fun hash(
            value: String,
        ) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        every { context.filesDir } returns temp.toFile()
        every { context.packageManager } returns pm
        every { pm.getPackageInfo(pkg, any<Int>()) } returns info
        info.packageName = pkg
        info.versionName = "v1"
        info.splitNames = arrayOf("part")
        info.signingInfo = signing
        every { info.longVersionCode } returns 4294967298L
        every { signing.apkContentsSigners } returns arrayOf(signature("one"), signature("two"))
        every { signing.signingCertificateHistory } returns arrayOf(signature("old"))
        every { signing.hasMultipleSigners() } returns true
        val result = NovelInstalledPackageReader(context, 30).read(pkg) as NovelInstalledRead.Present
        assertEquals(pkg, result.facts.packageName)
        assertEquals(4294967298L, result.facts.longVersionCode)
        assertEquals(true, result.facts.split)
        assertEquals(setOf(hash("one"), hash("two")), result.facts.signers.current)
        assertEquals(setOf(hash("old")), result.facts.signers.history)
    }

    @Test
    fun `unavailable failed or wrong identity lookups never imply absence`() {
        val cases = listOf(
            NovelInstalledPackageReader({ NovelPackageLookup.Unavailable }, { NovelPrivatePresence.ABSENT }),
            NovelInstalledPackageReader({ NovelPackageLookup.NotFound }, { NovelPrivatePresence.UNAVAILABLE }),
            NovelInstalledPackageReader({ throw SecurityException() }, { NovelPrivatePresence.ABSENT }),
            NovelInstalledPackageReader({
                NovelPackageLookup.Found(facts.copy(packageName = "other.package"))
            }, { NovelPrivatePresence.ABSENT }),
        )
        for (reader in cases) assertEquals(NovelInstalledRead.Unavailable, reader.read(pkg))
        assertEquals(NovelInstalledRead.Unavailable, cases.first().read("../bad"))
    }
}
