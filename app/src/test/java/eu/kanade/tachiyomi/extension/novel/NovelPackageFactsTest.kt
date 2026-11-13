package eu.kanade.tachiyomi.extension.novel

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class NovelPackageFactsTest {
    private fun signature(text: String) = mockk<Signature>().also { signature ->
        every { signature.toByteArray() } returns text.toByteArray()
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun modern(current: List<String>, history: List<String>?): PackageInfo = mockk<PackageInfo>().apply {
        signingInfo = mockk<SigningInfo>().also { signing ->
            every { signing.apkContentsSigners } returns current.map(::signature).toTypedArray()
            every { signing.signingCertificateHistory } returns history?.map(::signature)?.toTypedArray()
            every { signing.hasMultipleSigners() } returns (current.size > 1)
        }
    }

    @Test
    fun `modern current signer set excludes historical certificate`() {
        val result = NovelPackageSigningFacts.read(modern(listOf("new"), listOf("old", "new")), 28)
        assertTrue(result.hasCompleteCurrent)
        assertEquals(setOf(hash("new")), result.current)
        assertEquals(setOf(hash("old"), hash("new")), result.history)
        assertTrue(result.legacy.isEmpty())
    }

    @Test
    fun `modern multiple signers retain the entire current set without requiring history`() {
        val result = NovelPackageSigningFacts.read(modern(listOf("one", "two"), null), 36)
        assertTrue(result.hasCompleteCurrent)
        assertEquals(setOf(hash("one"), hash("two")), result.current)
        assertTrue(result.history.isEmpty())
    }

    @Suppress("DEPRECATION")
    @Test
    fun `API26 and API27 expose typed legacy signers but no complete modern current set`() {
        for (sdk in 26..27) {
            val info = mockk<PackageInfo>().apply { signatures = arrayOf(signature("legacy")) }
            val result = NovelPackageSigningFacts.read(info, sdk)
            assertEquals(NovelSignerObservation.LEGACY_UNVERIFIED, result.observation)
            assertEquals(setOf(hash("legacy")), result.legacy)
            assertTrue(result.current.isEmpty())
            assertFalse(result.hasCompleteCurrent)
        }
    }

    @Test
    fun `missing empty duplicate or unreadable modern signers stay unavailable`() {
        val missing = mockk<PackageInfo>().apply { signingInfo = null }
        val throwing = modern(listOf("one"), listOf("one")).apply {
            every { signingInfo!!.apkContentsSigners } throws SecurityException("fixture")
        }
        for (info in listOf(
            missing,
            modern(emptyList(), emptyList()),
            modern(listOf("same", "same"), null),
            throwing,
        )) {
            val result = NovelPackageSigningFacts.read(info, 36)
            assertEquals(NovelSignerObservation.UNAVAILABLE, result.observation)
            assertFalse(result.hasCompleteCurrent)
            assertTrue(result.current.isEmpty())
        }
    }

    @Test
    fun `observations defensively snapshot current history and legacy independently`() {
        val mutable = mutableSetOf(hash("one"))
        val result = NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, mutable, mutable, mutable)
        mutable.clear()
        assertEquals(setOf(hash("one")), result.current)
        assertEquals(setOf(hash("one")), result.history)
        assertEquals(setOf(hash("one")), result.legacy)
    }
}
