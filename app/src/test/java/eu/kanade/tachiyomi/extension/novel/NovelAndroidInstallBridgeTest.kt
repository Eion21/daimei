package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

internal class NovelAndroidInstallBridgeTest {
    private fun record() = NovelInstallRecord(
        "op", "b".repeat(64), "app.novel.example", 7, setOf("a".repeat(64)),
        "/controlled/artifact.apk", 9, "c".repeat(64), sessionId = 11,
    )
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `real bridge is cold on every supported API and submit never touches Android`() {
        val context = mockk<Context>()
        for (sdk in listOf(26, 30, 31, 36)) {
            val bridge = NovelAndroidInstallBridge(context, sdk)
            assertFalse(bridge.controlledTestActivation)
            assertEquals(sdk >= 31, bridge.requiresUserAction())
            assertEquals(NovelInstallDispatch.DISABLED, bridge.submit(record()))
        }
        verify { context wasNot Called }
    }

    @Test fun `decoder retains exact operation nonce session and status without launching anything`() {
        val context = mockk<Context>()
        val intent = mockk<Intent>()
        val uri = mockk<Uri>()
        every { intent.action } returns NovelAndroidInstallBridge.RESULT_ACTION
        every { intent.data } returns uri
        every { uri.scheme } returns "daimei-s39-install"
        every { uri.authority } returns "result"
        every { uri.query } returns null
        every { uri.fragment } returns null
        every { uri.pathSegments } returns listOf("op", "b".repeat(64))
        every { intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) } returns 11
        every {
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        } returns PackageInstaller.STATUS_SUCCESS
        every { intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) } returns null
        val callback = NovelAndroidInstallBridge.decodeCallback(context, intent)!!
        assertEquals("op", callback.operationId)
        assertEquals("b".repeat(64), callback.nonce)
        assertEquals(11, callback.sessionId)
        assertEquals(NovelInstallCallbackStatus.SUCCESS, callback.status)
        verify { context wasNot Called }
    }

    @Test fun `malformed callback URI is rejected before any Android or execution side effect`() {
        val context = mockk<Context>()
        val intent = mockk<Intent>()
        val uri = mockk<Uri>()
        every { intent.action } returns NovelAndroidInstallBridge.RESULT_ACTION
        every { intent.data } returns uri
        every { uri.scheme } returns "daimei-s39-install"
        every { uri.authority } returns "foreign"
        assertNull(NovelAndroidInstallBridge.decodeCallback(context, intent))
        verify { context wasNot Called }
    }

    @Test fun `streaming receipt hashes exactly the bytes emitted`() {
        val bytes = "apk bytes".toByteArray()
        val output = ByteArrayOutputStream()
        val receipt = NovelInstallSessionStreaming.copy(bytes.inputStream(), output, bytes.size.toLong())
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(NovelInstallStreamReceipt(bytes.size.toLong(), hash(bytes)), receipt)
    }

    @Test fun `overlong short and failed output do not produce an accepted receipt`() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            NovelInstallSessionStreaming.copy(byteArrayOf(1, 2).inputStream(), output, 1)
        }
        assertEquals(0, output.size())
        assertThrows(IOException::class.java) {
            NovelInstallSessionStreaming.copy(byteArrayOf(1).inputStream(), output, 2)
        }
        assertArrayEquals(byteArrayOf(1), output.toByteArray())
        val broken = object : java.io.OutputStream() {
            override fun write(value: Int) {
                throw IOException("disk failure")
            }
        }
        assertThrows(IOException::class.java) {
            NovelInstallSessionStreaming.copy(byteArrayOf(1).inputStream(), broken, 1)
        }
    }

    @Test fun `actual bridge writer fsyncs open output and closes output and session before returning`() {
        val context = mockk<Context>()
        val manager = mockk<PackageManager>()
        val installer = mockk<PackageInstaller>()
        val session = mockk<PackageInstaller.Session>()
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val bytes = "apk bytes".toByteArray()
        every { context.packageManager } returns manager
        every { manager.packageInstaller } returns installer
        every { installer.openSession(11) } returns session
        every { session.openWrite("base.apk", 0, bytes.size.toLong()) } returns output
        every { session.fsync(output) } answers {
            assertFalse(closed)
            Unit
        }
        every { session.close() } returns Unit
        val receipt = NovelAndroidInstallBridge(context, 31).writeSession(11, bytes.inputStream(), bytes.size.toLong())
        assertEquals(hash(bytes), receipt.sha256)
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue(closed)
        verifyOrder {
            session.openWrite("base.apk", 0, bytes.size.toLong())
            session.fsync(output)
            session.close()
        }
        verify(exactly = 0) { session.commit(any()) }
    }
}
