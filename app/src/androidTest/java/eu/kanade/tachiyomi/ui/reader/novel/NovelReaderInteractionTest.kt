package eu.kanade.tachiyomi.ui.reader.novel

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ActionMode
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.novel.reader.compose.ChapterBody
import app.novel.reader.compose.NovelReaderScreen
import app.novel.reader.core.BlockLayout
import app.novel.reader.core.ReaderSettings
import app.novel.reader.core.ReadingPosition
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Static content and callbacks only: no seeding, preferences, downloads, accounts or progress writes. */
@RunWith(AndroidJUnit4::class)
class NovelReaderInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val settings = mutableStateOf(ReaderSettings())
    private val progress = AtomicReference(ReadingPosition(0, 0f))
    private val progressReported = AtomicBoolean(false)
    private val shared = AtomicReference<String?>(null)
    private var settingsOpened = false
    private var backPressed = false
    private var diagnosticsEnabled = false
    private var diagnosticWindow: Window? = null
    private var geometryProbe: GeometryProbe? = null
    private val content = ChapterContent(
        blocks = List(80) { ContentBlock.Paragraph("Alpha beta gamma. Paragraph $it is isolated test content.") },
    )

    private fun withReader(diagnostics: Boolean = false, geometry: Boolean = false, test: () -> Unit) {
        diagnosticsEnabled = diagnostics
        // No chapter extra: the production host may read the missing-id error, but never gets readable content.
        // Replace its composition with the isolated surface before supplying any progress callback.
        // Own the single UiAutomation connection; never use a concurrent UIAutomator client here.
        val ui = automation
        val originalServiceInfo = checkNotNull(ui.serviceInfo)
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("stream", "\nReader UiAutomation eventTypes before=${originalServiceInfo.eventTypes}\n")
            },
        )
        try {
            ui.serviceInfo = checkNotNull(ui.serviceInfo).apply {
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            assertEquals(AccessibilityEvent.TYPES_ALL_MASK, checkNotNull(ui.serviceInfo).eventTypes)
            ActivityScenario.launch(NovelReaderActivity::class.java).use { scenario ->
                var originalCallback: Window.Callback? = null
                scenario.onActivity { activity ->
                    if (diagnostics) {
                        val window = activity.window
                        diagnosticWindow = window
                        val delegate = checkNotNull(window.callback)
                        originalCallback = delegate
                        // Observe and delegate unchanged: no interception, synthetic handling or consumption.
                        window.callback = object : Window.Callback by delegate {
                            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                                diagnostic("window-dispatch BEGIN ${eventDetails(event)} " +
                                    "callback=${delegate.javaClass.name} decor=${window.decorView.javaClass.name} " +
                                    "accessibilityWindowId=${window.decorView.createAccessibilityNodeInfo()?.windowId} " +
                                    "focus=${window.decorView.hasWindowFocus()}")
                                val handled = delegate.dispatchTouchEvent(event)
                                diagnostic("window-dispatch END action=${MotionEvent.actionToString(event.action)} " +
                                    "handled=$handled")
                                return handled
                            }

                            override fun onWindowFocusChanged(hasFocus: Boolean) {
                                diagnostic("window-focus=$hasFocus")
                                delegate.onWindowFocusChanged(hasFocus)
                            }

                            override fun onActionModeStarted(mode: ActionMode) {
                                diagnostic("action-mode START type=${mode.type} " +
                                    "menu=${(0 until mode.menu.size()).map { mode.menu.getItem(it).title }}")
                                delegate.onActionModeStarted(mode)
                            }

                            override fun onActionModeFinished(mode: ActionMode) {
                                diagnostic("action-mode FINISH type=${mode.type}")
                                delegate.onActionModeFinished(mode)
                            }
                        }
                    }
                    if (geometry) geometryProbe = GeometryProbe(activity.window.decorView)
                    activity.setContent {
                        MaterialTheme {
                            if (geometry && geometryMode == "column-control") {
                                ColumnControl(checkNotNull(geometryProbe))
                            } else NovelReaderScreen(
                                initialContent = content,
                                initialPosition = ReadingPosition(0, 0f),
                                settings = settings.value,
                                errorMessage = null,
                                errorReason = null,
                                retryLabel = "Retry",
                                onRetry = {},
                                onProgress = { progress.set(it); progressReported.set(true) },
                                backLabel = "Reader back",
                                settingsLabel = "Reader settings",
                                controlsLabel = "Reader controls",
                                workTitle = "C3 Fixture Work",
                                chapterTitle = "C3 Fixture Chapter",
                                shareLabel = "Share selection",
                                onBack = { backPressed = true },
                                onSettings = { settingsOpened = true },
                                onShareSelectedText = { shared.set(it) },
                                onDiagnosticListState = geometryProbe?.let { probe -> probe::observeList },
                                onDiagnosticParagraphPositioned = geometryProbe?.let { probe -> probe::observeParagraph },
                            )
                        }
                    }
                }
                awaitUi("isolated reader content") {
                    hasText("Alpha beta gamma. Paragraph 0 is isolated test content.")
                }
                try {
                    test()
                } catch (failure: Throwable) {
                    if (diagnostics) {
                        // Diagnostics must not replace the original assertion or introduce a retry.
                        runCatching { dumpAccessibility("failure") }
                            .onFailure { diagnostic("accessibility-dump failed: $it") }
                        runCatching { instrumentation.runOnMainSync { dumpViews("failure") } }
                            .onFailure { diagnostic("view-dump failed: $it") }
                    }
                    throw failure
                } finally {
                    if (diagnostics) instrumentation.runOnMainSync { diagnosticWindow?.callback = originalCallback }
                    diagnosticWindow = null
                    geometryProbe = null
                }
            }
        } finally {
            ui.serviceInfo = originalServiceInfo
        }
    }

    @Test
    fun upperTapTogglesButLowerTapAndScrollingDoNotAndControlsDispatchActions() = withReader {
        assertFalse(hasText("Reader controls"))
        assertFalse(hasText("Reader settings"))
        val bounds = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
        tap(bounds.centerX().toFloat(), bounds.height() * 0.75f)
        assertFalse(hasText("Reader settings"))
        tap(bounds.centerX().toFloat(), bounds.height() * 0.25f)
        click("Reader settings")
        instrumentation.runOnMainSync { assertTrue(settingsOpened) }
        click("Reader back")
        instrumentation.runOnMainSync { assertTrue(backPressed) }
        tap(bounds.centerX().toFloat(), bounds.height() * 0.25f)
        assertFalse(hasText("Reader settings"))
        assertFalse(hasText("Reader controls"))

        val scrollable = checkNotNull(find(automation.rootInActiveWindow) { it.isScrollable })
        assertTrue(scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        automation.waitForIdle(300, 5_000)
        awaitUi("scroll progress callback") { progress.get().blockIndex > 0 || progress.get().blockFraction > 0f }
        assertFalse(hasText("Reader settings"))
        assertTrue(progress.get().blockIndex > 0 || progress.get().blockFraction > 0f)
        val before = progress.get()
        instrumentation.runOnMainSync { settings.value = settings.value.copy(fontSizeSp = 24f) }
        automation.waitForIdle(300, 5_000)
        assertEquals(before.blockIndex, progress.get().blockIndex)
    }

    @Test
    fun singleFullWidthTopOverlayShowsTitlesWithoutMovingContentOrInsets() = withReader(diagnostics = true, geometry = true) {
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        fun document() = checkNotNull(find(automation.rootInActiveWindow) { it.isScrollable })
        fun insets() = AtomicReference<android.graphics.Insets>().also { result ->
            instrumentation.runOnMainSync {
                result.set(checkNotNull(diagnosticWindow).decorView.rootWindowInsets
                    .getInsets(android.view.WindowInsets.Type.systemBars()))
            }
        }.get()
        val viewport = bounds(document())
        val originalInsets = insets()
        awaitUi("initial logical-position callback") { progressReported.get() }
        val position = progress.get()
        val probe = checkNotNull(geometryProbe)
        val before = probe.snapshot("before")
        assertFalse(hasText("C3 Fixture Work"))
        assertFalse(hasText("C3 Fixture Chapter"))
        repeat(2) {
            tap(viewport.right - 2f, viewport.top + viewport.height() * 0.35f)
            awaitUi("exact work and chapter toolbar titles") {
                hasText("C3 Fixture Work") && hasText("C3 Fixture Chapter")
            }
            fun occurrences(node: AccessibilityNodeInfo?, text: String): Int {
                if (node == null) return 0
                return (if (node.isVisibleToUser && node.text?.toString() == text) 1 else 0) +
                    (0 until node.childCount).sumOf { occurrences(node.getChild(it), text) }
            }
            for (text in listOf("Reader back", "C3 Fixture Work", "C3 Fixture Chapter", "Reader settings")) {
                assertEquals("Exactly one toolbar label: $text", 1, occurrences(automation.rootInActiveWindow, text))
            }
            val back = checkNotNull(findVisibleAction("Reader back"))
            val work = bounds(checkNotNull(findVisibleAction("C3 Fixture Work")))
            val chapter = bounds(checkNotNull(findVisibleAction("C3 Fixture Chapter")))
            val settings = bounds(checkNotNull(findVisibleAction("Reader settings")))
            var bar = checkNotNull(back.parent)
            while (!bounds(bar).contains(work) || !bounds(bar).contains(chapter) || !bounds(bar).contains(settings)) {
                bar = checkNotNull(bar.parent) { "Back, titles and Settings must share one top bar" }
            }
            val barBounds = bounds(bar)
            assertEquals(viewport.left, barBounds.left)
            assertEquals(viewport.right, barBounds.right)
            assertEquals(viewport.top, barBounds.top)
            assertTrue("Toolbar must not span the document", barBounds.bottom < viewport.centerY())
            assertTrue(bounds(back).right <= work.left)
            assertTrue(work.right <= settings.left && chapter.right <= settings.left)
            assertTrue(work.top <= chapter.top)
            assertTrue(settings.centerX() > viewport.centerX())
            assertStationary(before, probe.snapshot("after-show-$it"))
            assertEquals(originalInsets, insets())
            assertEquals(position, progress.get())
            tap(viewport.right - 2f, viewport.top + viewport.height() * 0.35f)
            awaitUi("reader toolbar hidden") {
                !hasText("Reader settings") &&
                    !hasText("C3 Fixture Work") &&
                    !hasText("C3 Fixture Chapter")
            }
            assertFalse(hasText("Reader settings"))
            assertFalse(hasText("C3 Fixture Work"))
            assertFalse(hasText("C3 Fixture Chapter"))
            assertStationary(before, probe.snapshot("after-hide-$it"))
            assertEquals(originalInsets, insets())
            assertEquals(position, progress.get())
        }
    }

    @Test
    fun firstUpperTapWithNativeSelectionClearsOnlyThenNextTapRevealsControls() = withReader {
        val viewport = Rect().also {
            checkNotNull(find(automation.rootInActiveWindow) { node -> node.isScrollable }).getBoundsInScreen(it)
        }
        val paragraph = checkNotNull(findVisibleAction("Alpha beta gamma. Paragraph 0 is isolated test content."))
        val bounds = Rect().also(paragraph::getBoundsInScreen)
        val downTime = SystemClock.uptimeMillis()
        try {
            touch(MotionEvent.ACTION_DOWN, bounds.left + 20f, bounds.top + 15f, downTime)
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        } finally {
            touch(MotionEvent.ACTION_UP, bounds.left + 20f, bounds.top + 15f, downTime)
        }
        val copyLabel = instrumentation.targetContext.getString(android.R.string.copy)
        awaitUi("native selection context menu before clearing") { hasText(copyLabel) }
        assertFalse(hasText("Reader settings"))
        // Outside the selected word and its native menu, still inside the reader's upper half.
        val x = viewport.right - 2f
        val y = viewport.top + viewport.height() * 0.35f
        tap(x, y)
        assertFalse("The first selection-clearing tap must not reveal controls", hasText("Reader settings"))
        assertFalse("Native Copy menu must close with selection", hasText(copyLabel))
        assertFalse(hasText("Share selection"))
        tap(x, y)
        awaitUi("controls after the next unselected upper-half tap") { hasText("Reader settings") }
    }

    @Test
    fun longPressOffersNativeCopyAndSharesOnlyTheSelectedWord() = withReader(diagnostics = true) {
        val paragraph = checkNotNull(find(automation.rootInActiveWindow) { it.text?.startsWith("Alpha") == true })
        val bounds = Rect().also(paragraph::getBoundsInScreen)
        val x = bounds.left + 20f
        val y = bounds.top + 15f
        val copyLabel = instrumentation.targetContext.getString(android.R.string.copy)
        diagnostic("gesture-target paragraph=$bounds screen=($x,$y) " +
            "paragraphLocal=(${x - bounds.left},${y - bounds.top}) copyLabel=$copyLabel " +
            "longPressTimeoutMs=${ViewConfiguration.getLongPressTimeout()} " +
            "tapTimeoutMs=${ViewConfiguration.getTapTimeout()} " +
            "touchSlopPx=${ViewConfiguration.get(instrumentation.targetContext).scaledTouchSlop} " +
            "nativeTouchscreenSource=${InputDevice.SOURCE_TOUCHSCREEN} nativeFingerTool=${MotionEvent.TOOL_TYPE_FINGER}")
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                val xRange = device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHSCREEN)
                val yRange = device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHSCREEN)
                diagnostic("native-touchscreen deviceId=$id sources=${device.sources} " +
                    "xRange=${xRange?.min}..${xRange?.max} yRange=${yRange?.min}..${yRange?.max}")
            }
        }
        val characterArguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 5)
        }
        val characterLocations = paragraph.refreshWithExtraData(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
            characterArguments,
        )
        diagnostic("Alpha-character-locations available=${paragraph.availableExtraData} refreshed=$characterLocations " +
            "rects=${paragraph.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)?.contentToString()}")
        dumpAccessibility("before-gesture")
        instrumentation.runOnMainSync { dumpViews("before-gesture", x, y) }
        fun selectWord() {
            val downTime = SystemClock.uptimeMillis()
            try {
                touch(MotionEvent.ACTION_DOWN, x, y, downTime)
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
            } finally {
                // Release even if injection or selection fails; no held pointer may leak into the next test.
                touch(MotionEvent.ACTION_UP, x, y, downTime)
            }
            awaitUi("native Copy action after long press") { hasText(copyLabel) }
        }
        selectWord()
        assertFalse(hasText("Reader settings"))
        click(copyLabel)
        instrumentation.runOnMainSync {
            val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("Alpha", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        selectWord()
        // Native toolbar actions may overflow; exercise the real menu rather than bypassing selection.
        if (!hasText("Share selection")) click("More options")
        click("Share selection")
        assertEquals("Alpha", shared.get())
    }

    @Test
    fun longPressDragAcrossParagraphsCopiesAndSharesOnlyTheSelectedText() = withReader(diagnostics = true) {
        val copyLabel = instrumentation.targetContext.getString(android.R.string.copy)
        for (paragraphCount in 2..3) {
            val texts = content.blocks.take(paragraphCount).map { (it as ContentBlock.Paragraph).text }
            val expected = texts.joinToString("\n")
            fun selectParagraphs() {
                val first = checkNotNull(findVisibleAction(texts.first()))
                val last = checkNotNull(findVisibleAction(texts.last()))
                val start = Rect().also(first::getBoundsInScreen)
                val end = Rect().also(last::getBoundsInScreen)
                val x = start.left + 20f
                val y = start.top + 15f
                val endX = end.right - 10f
                val endY = end.bottom - 10f
                val downTime = SystemClock.uptimeMillis()
                diagnostic("cross-paragraph gesture count=$paragraphCount from=($x,$y) to=($endX,$endY) expected=$expected")
                try {
                    touch(MotionEvent.ACTION_DOWN, x, y, downTime)
                    SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
                    for (step in 1..20) {
                        touch(MotionEvent.ACTION_MOVE, x + (endX - x) * step / 20,
                            y + (endY - y) * step / 20, downTime)
                        SystemClock.sleep(20)
                    }
                } finally {
                    touch(MotionEvent.ACTION_UP, endX, endY, downTime)
                }
                awaitUi("Copy after dragging across $paragraphCount paragraphs") { hasText(copyLabel) }
            }
            selectParagraphs()
            click(copyLabel)
            instrumentation.runOnMainSync {
                val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val actual = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                diagnostic("cross-paragraph copy count=$paragraphCount expected=$expected actual=$actual")
                assertEquals("Exact copied text across $paragraphCount paragraphs", expected, actual)
            }
            selectParagraphs()
            shared.set(null)
            if (!hasText("Share selection")) click("More options")
            click("Share selection")
            assertEquals("Exact shared text across $paragraphCount paragraphs", expected, shared.get())
        }
    }

    // Only the geometry method reads this argument; normal runs use the actual production surface.
    private val geometryMode: String
        get() = (InstrumentationRegistry.getArguments().getString("c3GeometryLayout") ?: "overlay").also {
            check(it == "overlay" || it == "column-control") { "Unknown c3GeometryLayout: $it" }
        }

    /** Deliberately wrong layout, confined to this fixture: the real list loses space to the bar. */
    @Composable
    private fun ColumnControl(probe: GeometryProbe) {
        val state = rememberLazyListState()
        var visible by remember { mutableStateOf(false) }
        SideEffect { probe.observeList(state) }
        LaunchedEffect(state) {
            snapshotFlow {
                BlockLayout.toReadingPosition(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset,
                    state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }?.size ?: 0)
            }.collect { progress.set(it); progressReported.set(true) }
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            if (visible) Surface(Modifier.fillMaxWidth().semantics {
                isTraversalGroup = true
                paneTitle = "Reader controls"
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { backPressed = true }) { Text("Reader back") }
                    Column(Modifier.weight(1f).padding(8.dp)) {
                        Text("C3 Fixture Work", style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("C3 Fixture Chapter", style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { settingsOpened = true }) { Text("Reader settings") }
                }
            }
            ChapterBody(content = content, settings = settings.value, listState = state,
                contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f).fillMaxWidth(),
                shareLabel = "Share selection", onShareSelectedText = { shared.set(it) },
                onReaderTap = { y, height -> if (y >= 0 && y < height / 2f) visible = !visible },
                onDiagnosticParagraphPositioned = probe::observeParagraph)
        }
    }

    private fun assertStationary(before: LayoutSample, after: LayoutSample) {
        assertTrue("Same actual LazyListState reference", before.state === after.state)
        for (index in before.anchors.indices) {
            val first = before.anchors[index]
            val next = after.anchors[index]
            assertTrue("Same concrete paragraph $index", first.block === next.block && first.coordinates === next.coordinates)
            assertEquals("C3 physical paragraph displacement: block $index root", first.root, next.root)
            assertEquals("C3 physical paragraph displacement: block $index screen", first.screen, next.screen)
        }
        assertEquals("Actual LazyList viewport size", before.viewport, after.viewport)
        assertEquals("Raw first visible item index", before.index, after.index)
        assertEquals("Raw first visible item offset", before.offset, after.offset)
    }

    /** Default-off observation, no cache files/screenshots; read live coordinates on the main thread. */
    private inner class GeometryProbe(private val decor: View) {
        private var listState: LazyListState? = null
        private val paragraphs = mutableMapOf<Int, Pair<ContentBlock.Paragraph, LayoutCoordinates>>()
        private var baseline: LayoutSample? = null
        fun observeList(state: LazyListState) { listState = state }
        fun observeParagraph(index: Int, block: ContentBlock.Paragraph, coordinates: LayoutCoordinates) {
            if (index in 0..1) paragraphs[index] = block to coordinates
        }

        fun snapshot(phase: String): LayoutSample {
            val result = AtomicReference<Result<LayoutSample>>()
            instrumentation.runOnMainSync {
                result.set(runCatching {
                    val state = checkNotNull(listState)
                    val screen = IntArray(2).also(decor::getLocationOnScreen)
                    val window = IntArray(2).also(decor::getLocationInWindow)
                    val anchors = (0..1).map { index ->
                        val (block, coordinates) = checkNotNull(paragraphs[index])
                        check(block === content.blocks[index] && coordinates.isAttached)
                        val root = coordinates.boundsInRoot()
                        check(root.width > 0 && root.height > 0)
                        val origin = coordinates.findRootCoordinates().localToWindow(Offset.Zero) +
                            Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
                        Anchor(block, coordinates, root, root.translate(origin))
                    }
                    LayoutSample(state, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset,
                        state.layoutInfo.viewportSize, anchors)
                })
            }
            val sample = result.get().getOrThrow()
            if (baseline == null) baseline = sample
            fun rect(value: androidx.compose.ui.geometry.Rect) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
            val rows = sample.anchors.mapIndexed { index, anchor ->
                val first = checkNotNull(baseline).anchors[index]
                JSONObject().put("blockIndex", index).put("blockIdentity", System.identityHashCode(anchor.block))
                    .put("sameBlockReference", first.block === anchor.block)
                    .put("sameCoordinatesReference", first.coordinates === anchor.coordinates)
                    .put("root", rect(anchor.root)).put("screen", rect(anchor.screen))
            }
            val raw = JSONObject().put("mode", geometryMode).put("phase", phase)
                .put("listIdentity", System.identityHashCode(sample.state))
                .put("sameListReference", sample.state === checkNotNull(baseline).state)
                .put("index", sample.index).put("offset", sample.offset)
                .put("viewport", JSONArray(listOf(sample.viewport.width, sample.viewport.height)))
                .put("anchors", JSONArray(rows))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nC3_LAYOUT $raw\n") })
            return sample
        }
    }

    private data class Anchor(
        val block: ContentBlock.Paragraph,
        val coordinates: LayoutCoordinates,
        val root: androidx.compose.ui.geometry.Rect,
        val screen: androidx.compose.ui.geometry.Rect,
    )

    private data class LayoutSample(
        val state: LazyListState,
        val index: Int,
        val offset: Int,
        val viewport: IntSize,
        val anchors: List<Anchor>,
    )

    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        try {
            touch(MotionEvent.ACTION_DOWN, x, y, time)
        } finally {
            touch(MotionEvent.ACTION_UP, x, y, time)
        }
        instrumentation.waitForIdleSync()
        automation.waitForIdle(200, 5_000)
    }

    private fun touch(action: Int, x: Float, y: Float, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            diagnostic("inject BEGIN ${eventDetails(event)}")
            val injected = automation.injectInputEvent(event, true)
            diagnostic("inject END action=${MotionEvent.actionToString(action)} accepted=$injected")
            assertTrue(injected)
        } finally {
            event.recycle()
        }
    }

    private fun diagnostic(message: String) {
        if (diagnosticsEnabled) Log.i("NovelReaderDiagnostic", "uptime=${SystemClock.uptimeMillis()} $message")
    }

    private fun eventDetails(event: MotionEvent): String =
        "action=${MotionEvent.actionToString(event.action)} downTime=${event.downTime} eventTime=${event.eventTime} " +
            "durationMs=${event.eventTime - event.downTime} ageMs=${SystemClock.uptimeMillis() - event.eventTime} " +
            "xy=(${event.x},${event.y}) raw=(${event.rawX},${event.rawY}) source=${event.source} " +
            "toolType=${event.getToolType(0)} deviceId=${event.deviceId} pointerId=${event.getPointerId(0)} " +
            "pointerCount=${event.pointerCount} pressure=${event.pressure} size=${event.size} " +
            "flags=${event.flags} buttons=${event.buttonState} classification=${event.classification}"

    private fun dumpViews(label: String, x: Float? = null, y: Float? = null) {
        val window = checkNotNull(diagnosticWindow)
        diagnostic("views $label window=${window.javaClass.name} attributes=${window.attributes}")
        fun visit(view: View, depth: Int) {
            val screen = IntArray(2).also(view::getLocationOnScreen)
            val location = IntArray(2).also(view::getLocationInWindow)
            val global = Rect()
            val visible = view.getGlobalVisibleRect(global)
            val contains = x != null && y != null && global.contains(x.toInt(), y.toInt())
            diagnostic("view $label depth=$depth class=${view.javaClass.name} " +
                "id=${view.id} screen=${screen.contentToString()} window=${location.contentToString()} " +
                "size=${view.width}x${view.height} global=$global visible=$visible containsTarget=$contains " +
                "attached=${view.isAttachedToWindow} windowFocus=${view.hasWindowFocus()} " +
                "accessibilityWindowId=${view.createAccessibilityNodeInfo()?.windowId}")
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
        }
        visit(window.decorView, 0)
    }

    private fun dumpAccessibility(label: String) {
        automation.clearCache()
        diagnostic("accessibility $label service=${automation.serviceInfo}")
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            val bounds = Rect().also(node::getBoundsInScreen)
            diagnostic("node $label depth=$depth windowId=${node.windowId} package=${node.packageName} " +
                "class=${node.className} text=${node.text} description=${node.contentDescription} " +
                "bounds=$bounds visible=${node.isVisibleToUser} focused=${node.isFocused} " +
                "selected=${node.isSelected} selection=${node.textSelectionStart}..${node.textSelectionEnd} " +
                "actions=${node.actionList}")
            for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }
        visit(automation.rootInActiveWindow, 0)
        for (window in automation.windows) {
            diagnostic("accessibility-window $label $window")
            val root = window.root
            if (root?.packageName == instrumentation.targetContext.packageName || root?.packageName == "android") {
                visit(root, 0)
            }
        }
    }

    private fun click(text: String) {
        awaitUi("action '$text'") { hasText(text) }
        val node = checkNotNull(findVisibleAction(text)) { text }
        val bounds = Rect().also(node::getBoundsInScreen)
        tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    /** A rendered tree is the oracle, not arrival of an unrelated accessibility event (which can be absent). */
    private fun awaitUi(description: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            instrumentation.waitForIdleSync()
            // Invalidate cached nodes too: a missing content-change event must not freeze the readiness oracle.
            automation.clearCache()
            if (ready()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("UI not ready: $description; service=${automation.serviceInfo}")
    }

    private fun hasText(text: String) = findVisibleAction(text) != null

    /** Floating selection toolbars can be visible without becoming the active accessibility window. */
    private fun findVisibleAction(text: String): AccessibilityNodeInfo? {
        val matches: (AccessibilityNodeInfo) -> Boolean = {
            it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text)
        }
        find(automation.rootInActiveWindow, matches)?.let { return it }
        for (window in automation.windows) find(window.root, matches)?.let { return it }
        return null
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
}
