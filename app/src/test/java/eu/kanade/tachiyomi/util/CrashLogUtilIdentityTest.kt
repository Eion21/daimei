package eu.kanade.tachiyomi.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CrashLogUtilIdentityTest {
    @TempDir
    lateinit var cache: File

    @Test
    fun `export uses Daimei filename and keeps body and share handoff`() {
        val context = mockk<Context>(relaxed = true)
        val extensions = mockk<ExtensionManager>()
        val network = mockk<NetworkPreferences>()
        val preferences = mockk<BasePreferences>()
        val uri = mockk<Uri>()
        val intent = mockk<Intent>()
        val process = mockk<Process>()
        val runtime = Runtime.getRuntime()
        val legacy = File(cache, "mihon_crash_logs.txt").apply { writeText("existing export") }
        val exception = IllegalStateException("test crash")
        val util = spyk(CrashLogUtil(context, extensions, preferences, network))

        val sharedFile = slot<File>()
        val logCommand = slot<String>()

        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            mockkObject(runtime)
            mockkStatic(Color::class)
            mockkStatic("eu.kanade.tachiyomi.util.system.ToastExtensionsKt")
            mockkStatic("eu.kanade.tachiyomi.util.storage.FileExtensionsKt")
            mockkStatic("eu.kanade.tachiyomi.util.system.IntentExtensionsKt")
            // ContextExtensionsKt initializes a color before createFileInCacheDir can run.
            every { Color.rgb(any<Int>(), any<Int>(), any<Int>()) } returns 0
            every { context.toast(any<String>(), any(), any()) } answers {
                throw AssertionError("export entered its failure handler: ${secondArg<String>()}")
            }
            every { context.externalCacheDir } returns cache
            every { util.getDebugInfo() } returns "debug info"
            every { extensions.availableExtensionsFlow } returns MutableStateFlow(emptyList())
            coEvery { extensions.getLoadedExtensions() } returns emptyList()
            coEvery { extensions.getNotLoadedExtensions() } returns emptyList()
            every { network.verboseLogging.get() } returns false
            every { runtime.exec(capture(logCommand)) } returns process
            every { process.waitFor() } returns 0
            every { capture(sharedFile).getUriCompat(context) } returns uri
            every { uri.toShareIntent(context, "text/plain") } returns intent

            runBlocking { util.dumpLogs(exception) }

            assertTrue(sharedFile.isCaptured, "export must reach the share boundary")
            val exported = sharedFile.captured
            assertEquals(cache.canonicalFile, exported.canonicalFile.parentFile, "export must stay in the test cache")
            assertTrue(exported.isFile)
            assertEquals("debug info\n\n$exception\n\n", exported.readText())
            assertEquals("logcat *:E -d -v year -v zone -f ${exported.absolutePath}", logCommand.captured)
            verify { uri.toShareIntent(context, "text/plain") }
            verify { context.startActivity(intent) }
            assertEquals("daimei_crash_logs.txt", exported.name)
            assertEquals("existing export", legacy.readText())
        } finally {
            unmockkStatic("eu.kanade.tachiyomi.util.system.IntentExtensionsKt")
            unmockkStatic("eu.kanade.tachiyomi.util.storage.FileExtensionsKt")
            unmockkStatic("eu.kanade.tachiyomi.util.system.ToastExtensionsKt")
            unmockkStatic(Color::class)
            unmockkObject(runtime)
            Dispatchers.resetMain()
        }
    }
}
