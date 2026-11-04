package eu.kanade.tachiyomi.ui.reader.novel

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.novel.reader.compose.NovelReaderScreen
import app.novel.reader.compose.ReaderColorScheme
import app.novel.reader.core.ReaderSettings
import app.novel.reader.core.ReaderTheme
import app.novel.reader.core.ReadingPosition
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

/** Public rendered checks; isolated Activity content never supplies a readable chapter to the production Host. */
@RunWith(AndroidJUnit4::class)
class NovelReaderToolbarTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private val rtl = mutableStateOf(false)
    private val list = AtomicReference<LazyListState>()
    private val backCalls = AtomicInteger()
    private val settingsCalls = AtomicInteger()
    private val chapter = "Capítulo 17 — El despertar y una aventura que cambiaría su destino para siempre ".repeat(4)
    private val work = "Daimei"
    private val content = ChapterContent(
        blocks = List(80) {
            ContentBlock.Paragraph(
                "La luz de la mañana atravesaba las ventanas mientras el protagonista observaba el horizonte. " +
                    "Aquel día comenzaría una aventura que cambiaría su destino para siempre... ($it)",
            )
        },
    )

    private fun withReader(theme: ReaderTheme, block: () -> Unit) {
        val original = checkNotNull(ui.serviceInfo)
        try {
            ui.serviceInfo = checkNotNull(ui.serviceInfo).apply {
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            ActivityScenario.launch(NovelReaderActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        CompositionLocalProvider(
                            LocalLayoutDirection provides if (rtl.value) LayoutDirection.Rtl else LayoutDirection.Ltr,
                        ) {
                            MaterialTheme(
                                colorScheme = if (theme ==
                                    ReaderTheme.DARK
                                ) {
                                    darkColorScheme()
                                } else {
                                    lightColorScheme()
                                },
                            ) {
                                // The chapter paints above this backdrop; compare the same pixel before and after.
                                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                                    NovelReaderScreen(
                                        initialContent = content,
                                        initialPosition = ReadingPosition(0, 0f),
                                        settings = ReaderSettings(theme = theme),
                                        errorMessage = null,
                                        errorReason = null,
                                        retryLabel = "Retry",
                                        onRetry = {},
                                        onProgress = {},
                                        backLabel = "Volver",
                                        settingsLabel = "Ajustes",
                                        controlsLabel = "Reader controls",
                                        chapterTitle = chapter,
                                        workTitle = work,
                                        onBack = { backCalls.incrementAndGet() },
                                        onSettings = { settingsCalls.incrementAndGet() },
                                        onDiagnosticListState = { list.set(it) },
                                    )
                                }
                            }
                        }
                    }
                }
                await("reader document") { document() != null && list.get() != null }
                block()
            }
        } finally {
            ui.serviceInfo = original
        }
    }

    @Test
    fun lightToolbarOverlaysStationaryText() =
        withReader(ReaderTheme.LIGHT) { verifyThemeAndLayout(ReaderTheme.LIGHT) }

    @Test
    fun darkToolbarOverlaysStationaryText() =
        withReader(ReaderTheme.DARK) { verifyThemeAndLayout(ReaderTheme.DARK) }

    private fun verifyThemeAndLayout(theme: ReaderTheme) {
        val hidden = bounds(checkNotNull(document()))
        val initial = onMain { list.get().firstVisibleItemIndex to list.get().firstVisibleItemScrollOffset }
        val beforeImage = screenshot("${theme.name.lowercase()}-hidden")
        toggle(hidden)
        await("toolbar titles") { label(work) != null && label(chapter) != null }
        val bar = toolbarBounds()
        val image = screenshot(theme.name.lowercase())
        try {
            val color = image.getPixel(bar.right - 3, bar.centerY())
            val palette = ReaderColorScheme.colorsFor(theme)
            val surface = lerp(palette.background, palette.text, 0.08f).toArgb()
            val backdrop = beforeImage.getPixel(bar.right - 3, bar.centerY())
            for (channel in listOf<(Int) -> Int>(AndroidColor::red, AndroidColor::green, AndroidColor::blue)) {
                val expected = (channel(surface) * 0.8f + channel(backdrop) * 0.2f).roundToInt()
                assertTrue(
                    "80% reader-theme surface: expected $expected, got ${channel(color)}",
                    abs(channel(color) - expected) <= 3,
                )
            }
        } finally {
            image.recycle()
            beforeImage.recycle()
        }
        val visible = bounds(checkNotNull(document()))
        assertEquals("Overlay must not resize or move the document", hidden, visible)
        assertEquals("Toolbar starts at the inset-safe document top", hidden.top, bar.top)
        assertTrue("Toolbar overlays the document", bar.bottom > visible.top)
        assertEquals(initial, onMain { list.get().firstVisibleItemIndex to list.get().firstVisibleItemScrollOffset })
        val title = bounds(checkNotNull(label(chapter)))
        val back = bounds(checkNotNull(label("Volver")))
        val settings = bounds(checkNotNull(label("Ajustes")))
        assertTrue("Long title remains within the toolbar", bar.contains(title))
        assertTrue(
            "Title cannot push back/settings off-screen",
            back.right <= title.left && title.right <= settings.left,
        )
        click("Ajustes")
        assertEquals(1, settingsCalls.get())
        swipe(visible)
        await("actual touch scrolling") {
            onMain {
                list.get().firstVisibleItemIndex > 0 &&
                    !list.get().isScrollInProgress
            }
        }
        assertTrue("Scrolling must not dismiss the controls", label("Volver") != null)
        val scrolled = onMain { list.get().firstVisibleItemIndex to list.get().firstVisibleItemScrollOffset }
        toggle(bounds(checkNotNull(document())))
        await("toolbar hidden") { label(work) == null }
        assertEquals("All reading space is returned", hidden, bounds(checkNotNull(document())))
        assertEquals(
            "Hiding keeps the same logical item/offset",
            scrolled,
            onMain {
                list.get().firstVisibleItemIndex to
                    list.get().firstVisibleItemScrollOffset
            },
        )
        // Start inside the former toolbar footprint: a hidden overlay must not intercept this swipe.
        touch(hidden.exactCenterX(), bar.centerY().toFloat(), true, hidden.top + hidden.height() * 0.7f)
        await("hidden toolbar permits touch scrolling") {
            onMain {
                !list.get().isScrollInProgress &&
                    (list.get().firstVisibleItemIndex to list.get().firstVisibleItemScrollOffset) != scrolled
            }
        }
    }

    @Test
    fun backIsOnlyAnAccessibleIconMirrorsInRtlAndDispatchesOnce() = withReader(ReaderTheme.DARK) {
        toggle(bounds(checkNotNull(document())))
        await("toolbar") { label(work) != null }
        val back = checkNotNull(label("Volver"))
        assertTrue("Back must not render a text label", back.text.isNullOrEmpty())
        assertEquals("Volver", back.contentDescription?.toString())
        val leftImage = screenshot("icon-ltr")
        val left = glyph(leftImage, bounds(back))
        leftImage.recycle()
        onMain { rtl.value = true }
        await("RTL back at the trailing screen side") {
            val backBounds = label("Volver")?.let(::bounds)
            backBounds != null && backBounds.centerX() > toolbarBounds().centerX()
        }
        val rightImage = screenshot("icon-rtl")
        val right = glyph(rightImage, bounds(checkNotNull(label("Volver"))))
        rightImage.recycle()
        assertEquals(left.size, right.size)
        val side = kotlin.math.sqrt(left.size.toDouble()).roundToInt()
        var union = 0
        var different = 0
        for (y in 0 until side) {
            for (x in 0 until side) {
                val a = left[y * side + side - 1 - x]
                val b = right[y * side + x]
                if (a || b) union++
                if (a != b) different++
            }
        }
        assertTrue("A visible arrow must be painted", union > 50)
        assertTrue("RTL must mirror the actual painted arrow", different.toFloat() / union < 0.30f)
        click("Volver")
        assertEquals("One unchanged navigation callback", 1, backCalls.get())
        assertEquals(0, settingsCalls.get())
    }

    private fun glyph(image: Bitmap, button: Rect): BooleanArray {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val side = (24 * density).roundToInt()
        val x = button.centerX() - side / 2
        val y = button.centerY() - side / 2
        val ink = ReaderColorScheme.colorsFor(ReaderTheme.DARK).text.toArgb()
        return BooleanArray(side * side) { i ->
            val pixel = image.getPixel(x + i % side, y + i / side)
            abs(AndroidColor.red(pixel) - AndroidColor.red(ink)) < 45 &&
                abs(AndroidColor.green(pixel) - AndroidColor.green(ink)) < 45 &&
                abs(AndroidColor.blue(pixel) - AndroidColor.blue(ink)) < 45
        }
    }

    private fun screenshot(name: String): Bitmap {
        ui.waitForIdle(250, 5_000)
        val raw = checkNotNull(ui.takeScreenshot())
        val image = checkNotNull(raw.copy(Bitmap.Config.ARGB_8888, false))
        raw.recycle()
        val phase = InstrumentationRegistry.getArguments().getString("toolbarPhase", "test")
        val file = File(instrumentation.targetContext.cacheDir, "toolbar-$phase-$name.png")
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\ntoolbar proof=${file.absolutePath}\n") })
        return image
    }

    private fun toolbarBounds(): Rect {
        val targets = listOf(work, chapter, "Ajustes").map { bounds(checkNotNull(label(it))) }
        var node = checkNotNull(label("Volver")).parent
        while (node != null) {
            val rect = bounds(node)
            if (targets.all(rect::contains)) return rect
            node = node.parent
        }
        error("One toolbar must contain back, titles and settings")
    }

    private fun document() = find(ui.rootInActiveWindow) { it.isVisibleToUser && it.isScrollable }
    private fun label(text: String) = find(ui.rootInActiveWindow) {
        it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text)
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun find(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null || predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun click(text: String) {
        val rect = bounds(checkNotNull(label(text)))
        touch(rect.exactCenterX(), rect.exactCenterY(), false)
        instrumentation.waitForIdleSync()
    }
    private fun toggle(document: Rect) = touch(document.right - 3f, document.top + document.height() * 0.35f, false)
    private fun swipe(document: Rect) = touch(
        document.exactCenterX(),
        document.top + document.height() * 0.85f,
        true,
        document.top + document.height() * 0.25f,
    )
    private fun touch(x: Float, y: Float, swipe: Boolean, endY: Float = y) {
        val start = SystemClock.uptimeMillis()
        fun inject(action: Int, atY: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, atY, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(ui.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        inject(MotionEvent.ACTION_DOWN, y)
        if (swipe) {
            for (i in 1..15) {
                SystemClock.sleep(20)
                inject(MotionEvent.ACTION_MOVE, y + (endY - y) * i / 15)
            }
        }
        inject(MotionEvent.ACTION_UP, endY)
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("UI not ready: $description")
    }
    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}
