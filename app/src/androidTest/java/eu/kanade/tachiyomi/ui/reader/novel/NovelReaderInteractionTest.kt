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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import app.novel.runtime.SourceRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.novel.UNRECOGNIZED_NUMBER
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.novel.interactor.GetChapterContent
import tachiyomi.domain.novel.interactor.GetReadingProgress
import tachiyomi.domain.novel.interactor.SaveReadingProgress
import tachiyomi.domain.novel.repository.ChapterContentRepository
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgressRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Five unchanged C3 surface cases plus isolated actual-Host cases; no installed data or backend writes. */
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
    fun topOverlayKeepsViewportParagraphsAndInsetsStationary() = withReader(diagnostics = true, geometry = true) {
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
                return (if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) 1 else 0) +
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
            val shown = probe.snapshot("after-show-$it")
            assertStationary(before, shown)
            assertEquals("Document viewport remains fixed beneath overlay", viewport, bounds(document()))
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

    // S16/S28-S32/S35-S37: actual Host + lifecycle-owned REAL VM. The five C3 cases above are surface-only.
    @Test
    fun actualHostNextSavesBeforePendingLoadRestoresTitlesAndActivityBackFinishes() {
        val gate = CompletableDeferred<ChapterContent?>()
        val f = HostFixture()
        f.nextLoader = { gate.await() }
        withActualHost(f) { scenario, activity ->
            awaitUi("OLD restored rather than overwritten at start") {
                hasText(f.paragraph("Old", 2)) && hostMain { f.saves.any { it.first == f.oldId && it.second.blockIndex == 2 } }
            }
            hostToolbar(f, "Old chapter")
            hostScrollTo(f.nextLabel)
            awaitUi("OLD completed once through Host") { hostMain { f.tracks.size == 1 } }
            val boundary = hostMain { f.events.size }
            hostActivate(f.nextLabel)
            awaitUi("NEXT loader suspended") { hostMain { f.loads.count { it == f.nextId } == 1 } }
            // Loader entry can precede the next Compose frame; wait for the public disabled state,
            // rather than sampling the still-enabled accessibility tree immediately after suspension.
            awaitUi("pending real footer is disabled") {
                var button = findVisibleAction(f.nextLabel)
                while (button != null && !button.isClickable && button.parent != null) button = button.parent
                button != null && button.isClickable && !button.isEnabled
            }
            val pending = checkNotNull(findVisibleAction(f.nextLabel))
            var button = pending
            while (!button.isClickable && button.parent != null) button = checkNotNull(button.parent)
            assertFalse("Pending real footer is disabled", button.isEnabled)
            val bounds = Rect().also(pending::getBoundsInScreen)
            repeat(2) { tap(bounds.exactCenterX(), bounds.exactCenterY()) }
            hostMain {
                assertEquals(1, f.loads.count { it == f.nextId })
                assertEquals(listOf("save:${f.oldId}", "load:${f.nextId}"), f.events.drop(boundary))
                assertEquals(ReadingProgress(23, 0, 1f, 1f), f.saves.last().second)
                assertEquals(listOf(f.oldId), f.updates.map { it.id })
                assertEquals(listOf(f.oldId), f.histories.map { it.chapterId })
                gate.complete(f.nextContent)
            }
            awaitUi("NEXT restored and progress belongs to NEXT") {
                hasText(f.paragraph("Next", 4)) && hostMain { f.saves.any { it.first == f.nextId } }
            }
            assertFalse(hasText(f.paragraph("Old", 2)))
            hostToolbar(f, "Next chapter")
            hostMain {
                val transition = f.events.drop(boundary)
                assertTrue(transition.indexOf("save:${f.oldId}") < transition.indexOf("load:${f.nextId}"))
                assertTrue(transition.indexOf("restore:${f.nextId}") < transition.indexOf("history:${f.nextId}"))
                val firstNextSave = f.saves.first { it.first == f.nextId }.second
                assertEquals(4, firstNextSave.blockIndex)
                assertEquals(listOf(f.oldId, f.nextId), f.histories.map { it.chapterId })
                assertEquals(listOf(f.oldId), f.updates.map { it.id })
                assertEquals(listOf(1.0), f.tracks)
                activity.onBackPressedDispatcher.onBackPressed()
            }
            awaitUi("actual Activity finished, not a per-chapter back stack") {
                hostMain { activity.isFinishing || activity.isDestroyed } && scenario.state == Lifecycle.State.DESTROYED
            }
            assertFalse(hasText(f.paragraph("Old", 2)))
        }
    }

    @Test
    fun actualHostEqualContentReadNextResetsScrollToolbarAndNativeSelection() {
        val f = HostFixture(equalContent = true, nextRead = true)
        withActualHost(f) { _, _ ->
            hostToolbar(f, "Old chapter")
            hostScrollTo(f.nextLabel)
            val paragraph = checkNotNull(findVisibleAction(f.paragraph("Old", 23)))
            val bounds = Rect().also(paragraph::getBoundsInScreen)
            val downTime = SystemClock.uptimeMillis()
            try {
                touch(MotionEvent.ACTION_DOWN, bounds.left + 20f, bounds.top + 15f, downTime)
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
            } finally {
                touch(MotionEvent.ACTION_UP, bounds.left + 20f, bounds.top + 15f, downTime)
            }
            val copy = instrumentation.targetContext.getString(android.R.string.copy)
            awaitUi("OLD native selection") { hasText(copy) }
            // Invoke the actual enabled Button without a pointer clearing selection first.
            hostActivate(f.nextLabel)
            awaitUi("equal document has a NEW top-of-chapter session") {
                hasText(f.paragraph("Old", 0)) && !hasText(copy) &&
                    hostMain { f.saves.any { it.first == f.nextId } }
            }
            assertFalse(hasText(f.endLabel("Old chapter")))
            assertFalse(hasText(f.label(MR.strings.action_settings)))
            hostToolbar(f, "Next chapter")
            hostMain {
                assertEquals(ChapterContent(f.oldContent.blocks.toList()), f.nextContent)
                assertEquals(0, f.saves.first { it.first == f.nextId }.second.blockIndex)
                assertEquals(0f, f.saves.first { it.first == f.nextId }.second.blockFraction, 0f)
                assertFalse("Already-read NEXT ignores its saved position", "restore:${f.nextId}" in f.events)
                assertEquals(listOf(f.oldId, f.nextId), f.histories.map { it.chapterId })
                assertTrue(f.updates.all { it.id == f.oldId })
                assertTrue(f.tracks.size <= 1)
            }
        }
    }

    @Test
    fun actualHostEqualShortNextDoesNotCompleteOrTrackWithoutUserProgress() {
        val f = HostFixture(equalContent = true, short = true)
        withActualHost(f) { _, _ ->
            awaitUi("short OLD initial end position reported") { hostMain { f.saves.any { it.first == f.oldId } } }
            hostMain { f.assertNoCompletion(); assertEquals(listOf(f.oldId), f.histories.map { it.chapterId }) }
            hostActivate(f.nextLabel)
            awaitUi("short equal NEXT has rendered and emitted its initial end position") {
                hasText(f.endLabel("Next chapter")) && hostMain { f.saves.any { it.first == f.nextId } }
            }
            assertFalse(hasText(f.endLabel("Old chapter")))
            assertTrue(hasText(f.label(MR.strings.novel_no_more_chapters)))
            hostMain {
                f.assertNoCompletion()
                // Opening writes ONE history row by contract; initial end visibility must not add another.
                assertEquals(listOf(f.oldId, f.nextId), f.histories.map { it.chapterId })
                assertEquals(listOf(f.oldId, f.nextId), f.loads)
                assertTrue(f.saves.filter { it.first == f.nextId }.all { it.second.blockIndex == 0 })
            }
        }
    }

    @Test
    fun actualHostFailedNextReturnAndRetryRetainOldContentAndSaveIdentity() {
        val f = HostFixture(short = true)
        var attempts = 0
        f.nextLoader = { if (++attempts < 3) null else f.nextContent }
        withActualHost(f) { _, activity ->
            hostActivate(f.nextLabel)
            val error = f.label(MR.strings.novel_next_chapter_load_error)
            val retry = f.label(MR.strings.action_retry)
            awaitUi("NEXT failure retains OLD footer") { hasText(error) && hasText(f.endLabel("Old chapter")) }
            assertTrue(hasText(f.paragraph("Old", 0)))
            hostToolbar(f, "Old chapter")
            hostActivate(f.label(MR.strings.novel_return_to_chapter))
            awaitUi("Return only dismisses the error") { !hasText(error) && hasText(f.nextLabel) }
            hostMain {
                f.assertNoCompletion()
                assertEquals(listOf(f.oldId), f.histories.map { it.chapterId })
                assertTrue(f.saves.all { it.first == f.oldId })
                assertEquals(1, attempts)
                assertFalse(activity.isFinishing)
            }
            hostActivate(f.nextLabel)
            awaitUi("second controlled miss exposes actual Retry") { hasText(error) && hasText(retry) }
            hostActivate(retry)
            awaitUi("Retry opens NEXT") {
                hasText(f.endLabel("Next chapter")) && hostMain { f.saves.any { it.first == f.nextId } }
            }
            hostMain {
                f.assertNoCompletion()
                assertEquals(3, attempts)
                assertEquals(listOf(f.oldId, f.nextId), f.histories.map { it.chapterId })
                val loads = f.events.withIndex().filter { it.value == "load:${f.nextId}" }
                assertEquals(3, loads.size)
                loads.forEach { assertEquals("save:${f.oldId}", f.events[it.index - 1]) }
                assertEquals(1, f.loads.count { it == f.oldId })
            }
            hostToolbar(f, "Next chapter")
        }
    }

    @Test
    fun actualHostTerminalDownloadedAndUnknownFootersNeverOfferFalseAdvance() {
        for (status in listOf(NovelNavigationStatus.LAST_CHAPTER, NovelNavigationStatus.NO_MORE_DOWNLOADED,
            NovelNavigationStatus.UNKNOWN_NUMBER)) {
            val f = HostFixture(short = true, terminal = status)
            withActualHost(f) { _, activity ->
                val message = f.label(when (status) {
                    NovelNavigationStatus.LAST_CHAPTER -> MR.strings.novel_no_more_chapters
                    NovelNavigationStatus.NO_MORE_DOWNLOADED -> MR.strings.novel_no_more_downloaded
                    else -> MR.strings.novel_next_unknown
                })
                awaitUi("terminal $status rendered with initial progress") {
                    hasText(message) && hostMain { f.saves.isNotEmpty() }
                }
                assertTrue(hasText(f.endLabel("Old chapter")))
                assertFalse(hasText(f.nextLabel))
                assertFalse(hasText(f.label(MR.strings.novel_next_downloaded, "Next chapter")))
                assertFalse(hasText(f.label(MR.strings.action_retry)))
                hostToolbar(f, "Old chapter")
                hostMain {
                    assertEquals(listOf(f.oldId), f.loads)
                    assertEquals(listOf(f.oldId), f.histories.map { it.chapterId })
                    assertTrue(f.saves.all { it.first == f.oldId })
                    f.assertNoCompletion()
                    assertFalse(activity.isFinishing)
                }
            }
        }
    }

    /** Only mocked boundaries, real use cases/VM, and memory preference handles. No global Main substitution. */
    @Test
    fun s31HostRecreationAndRotationKeepLivePositionWithoutReload() {
        val f = HostFixture(long = true)
        withS31Host(f) { bridge ->
            val originalVm = hostMain { bridge.vm }
            val expected = hostMain {
                kotlinx.coroutines.runBlocking { bridge.list.scrollToItem(8, 12) }
                val sample = bridge.sample()
                bridge.owner.recreate()
                sample
            }
            awaitUi("S31 Activity recreated with the retained VM") {
                hostMain { bridge.creations >= 2 && bridge.list.firstVisibleItemIndex == expected.blockIndex }
            }
            hostMain {
                assertTrue(originalVm === bridge.vm)
                assertEquals(listOf(f.oldId), f.loads)
                assertPosition(expected, bridge.sample())
            }
            val beforeRotation = hostMain { bridge.creations }
            hostMain {
                bridge.owner.requestedOrientation = if (bridge.owner.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            awaitUi("S31 real orientation change restored logical position") {
                hostMain { bridge.creations > beforeRotation && bridge.list.firstVisibleItemIndex == expected.blockIndex }
            }
            hostMain {
                assertTrue(originalVm === bridge.vm)
                assertEquals(listOf(f.oldId), f.loads)
                assertPosition(expected, bridge.sample())
            }
        }
    }

    @Test
    fun s31NavigatedChapterSurvivesActivityRecreationAndPreviousButtonWorks() {
        val f = HostFixture(long = true)
        withS31Host(f) { bridge ->
            hostScrollTo(f.nextLabel)
            hostActivate(f.nextLabel)
            awaitUi("S31 NEXT published") { hostMain { bridge.vm.activeChapterId.value == f.nextId } }
            hostMain { bridge.owner.recreate() }
            awaitUi("S31 recreation keeps NEXT rather than route OLD") {
                hostMain { bridge.creations >= 2 && bridge.vm.activeChapterId.value == f.nextId }
            }
            hostMain { assertEquals(listOf(f.oldId, f.nextId), f.loads) }
            val previous = f.label(MR.strings.novel_previous_chapter, "Old chapter")
            hostScrollTo(previous)
            hostActivate(previous)
            awaitUi("S31 actual Previous footer publishes OLD") {
                hostMain { bridge.vm.activeChapterId.value == f.oldId }
            }
            hostMain { assertEquals(listOf(f.oldId, f.nextId, f.oldId), f.loads) }
        }
    }

    @Test
    fun s31ImmediateExitAndDatabaseReopenRestoreTheActualScrollPosition() = kotlinx.coroutines.runBlocking {
        val root = java.io.File(instrumentation.targetContext.cacheDir, "s31-db-${java.util.UUID.randomUUID()}")
        assertTrue(root.mkdir())
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "S31 owned database: ${root.path}\n") })
        var database = S31Database(root)
        try {
            database.open()
            val ids = database.seed()
            database.progress.save(ids.first, ReadingProgress(2, 0, 0f, 0f))
            val first = HostFixture(backingProgress = database.progress, ids = ids, long = true)
            var expected = ReadingPosition.START
            withS31Host(first) { bridge ->
                hostMain {
                    kotlinx.coroutines.runBlocking { bridge.list.scrollToItem(9, 17) }
                    expected = bridge.sample()
                    // Finish in the very same main-thread turn: no 200 ms debounce can run first.
                    bridge.owner.finish()
                }
                awaitUi("S31 final sample really stored after Activity finish") {
                    hostMain { first.saves.any { it.first == ids.first && it.second.blockIndex == expected.blockIndex &&
                        kotlin.math.abs(it.second.blockFraction - expected.blockFraction) < 0.04f } }
                }
            }
            val before = checkNotNull(database.progress.get(ids.first))
            assertPosition(expected, ReadingPosition(before.blockIndex, before.blockFraction))
            database.close()
            database = S31Database(root)
            database.open()
            val after = checkNotNull(database.progress.get(ids.first))
            assertEquals(before, after)
            val reopened = HostFixture(backingProgress = database.progress, ids = ids, long = true)
            withS31Host(reopened) { bridge ->
                awaitUi("S31 fresh reader resumes after SQLite driver reopen") {
                    hostMain { bridge.list.firstVisibleItemIndex == expected.blockIndex }
                }
                hostMain {
                    assertPosition(expected, bridge.sample())
                    assertEquals(listOf(ids.first), reopened.loads)
                }
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "S31 durable position: $after; database reopened and fresh reader restored\n") })
        } finally {
            database.close()
        }
    }

    @Test
    fun s31RelativeInlineImageIsFetchedDecodedAndPaintedByCoil() {
        val image = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.GREEN)
        val bytes = java.io.ByteArrayOutputStream().also {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        image.recycle()
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val worker = kotlin.concurrent.thread(name = "s31-inline-image") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (closed: java.net.SocketException) { break }
                socket.use {
                    it.soTimeout = 5_000
                    val input = it.getInputStream().bufferedReader()
                    val path = input.readLine().split(' ')[1]
                    while (!input.readLine().isNullOrEmpty()) { /* HTTP header boundary */ }
                    requests += path
                    val body = if (path == "/novel/chapter.html") {
                        """<div class="chapter-content"><img src="../images/figure.png" alt="S31 inline illustration"><p>S31 image after text.</p></div>""".toByteArray()
                    } else bytes
                    val type = if (path.endsWith(".html")) "text/html" else "image/png"
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}/"
            val source = app.novel.source.http.HttpNovelSource(base, okhttp3.OkHttpClient())
            val content = kotlinx.coroutines.runBlocking {
                source.getChapterContent(app.novel.source.api.Chapter("s31", "s31", "s31", "S31", url = "${base}novel/chapter.html"))
            }
            assertEquals("${base}images/figure.png", (content.blocks.first() as ContentBlock.Image).url)
            val f = HostFixture(short = true, imageContent = content)
            var painted = 0
            withS31Host(f) {
                awaitUi("S31 relative inline image is actually decoded and painted") {
                    val bitmap = automation.takeScreenshot()
                    if (bitmap == null) false else {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        painted = pixels.count { pixel -> pixel == android.graphics.Color.GREEN }
                        if (painted > 500) {
                            val file = java.io.File(instrumentation.targetContext.cacheDir, "s31-image-${java.util.UUID.randomUUID()}.png")
                            file.outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
                            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "S31 painted=$painted image proof=${file.path} requests=$requests\n") })
                        }
                        bitmap.recycle()
                        painted > 500
                    }
                }
                assertTrue(requests.contains("/images/figure.png"))
                assertFalse(requests.contains("/novel/images/figure.png"))
                assertTrue(painted > 500)
            }
        } finally {
            server.close()
            worker.join(1_000)
        }
    }

    private inner class S31Database(private val root: java.io.File) {
        private lateinit var driver: app.cash.sqldelight.db.SqlDriver
        private val repository = mihon.data.database.DatabaseRepositoryImpl(lazy { driver })
        private val context = object : android.content.ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getDatabasePath(name: String): java.io.File {
                check(name == "tachiyomi.db")
                return java.io.File(root, name)
            }
            override fun getCacheDir(): java.io.File = root
        }
        init { driver = tachiyomi.data.DatabaseBindings.providesSqlDriver(context, repository) }
        private val database = tachiyomi.data.DatabaseBindings.providesDatabase(driver)
        val progress = tachiyomi.data.novel.ReadingProgressRepositoryImpl(database)
        private val content = tachiyomi.data.novel.ChapterContentRepositoryImpl(
            database, tachiyomi.data.novel.NovelContentDir(java.io.File(root, "content")),
        )
        private val chapters = tachiyomi.data.chapter.ChapterRepositoryImpl(database, content, progress)
        private val mangas = tachiyomi.data.manga.MangaRepositoryImpl(database)
        suspend fun open() = repository.awaitOpen()
        suspend fun seed(): Pair<Long, Long> {
            val novel = app.novel.source.api.Novel("s31-isolated", "s31-novel", "S31 isolated work")
            val manga = mangas.insertNetworkManga(listOf(tachiyomi.data.novel.NovelMapper.toManga(novel))).single()
            val rows = chapters.updateFromRemote(emptyList(), (1..2).map { number ->
                tachiyomi.data.novel.NovelMapper.toChapter(
                    novel, app.novel.source.api.Chapter(novel.sourceId, novel.id, "s31-$number", "S31 chapter $number", number.toFloat()),
                    sourceOrder = (3 - number).toLong(),
                ).copy(mangaId = manga.id)
            }, emptyList())
            return rows[0].id to rows[1].id
        }
        fun close() = driver.close()
    }

    private class S31Bridge {
        lateinit var owner: NovelReaderActivity
        lateinit var vm: NovelReaderViewModel
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        var creations = 0
        fun sample(): ReadingPosition {
            val index = list.firstVisibleItemIndex
            val height = list.layoutInfo.visibleItemsInfo.first { it.index == index }.size
            return ReadingPosition(index, list.firstVisibleItemScrollOffset.toFloat() / height)
        }
    }

    private fun assertPosition(expected: ReadingPosition, actual: ReadingPosition) {
        assertEquals(expected.blockIndex, actual.blockIndex)
        assertEquals(expected.blockFraction, actual.blockFraction, 0.04f)
    }

    /** Reinstall the SAME controlled Host after real onCreate on every recreated Activity. */
    private fun withS31Host(f: HostFixture, test: (S31Bridge) -> Unit) {
        assertEquals("app.daimei.dev.s28", instrumentation.targetContext.packageName)
        assertTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val application = instrumentation.targetContext.applicationContext as android.app.Application
        val bridge = S31Bridge()
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                if (activity !is NovelReaderActivity) return
                bridge.owner = activity
                hostWindow = activity.window
                bridge.vm = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(f.createViewModel())!!
                }).get("s31-controlled-reader", NovelReaderViewModel::class.java)
                bridge.creations++
                activity.setContent {
                    MaterialTheme {
                        NovelReaderHost(
                            chapterId = f.oldId,
                            controlledPreferences = f.preferences,
                            controlledViewModel = bridge.vm,
                            onDiagnosticListState = { bridge.list = it },
                        )
                    }
                }
            }
            override fun onActivityCreated(a: android.app.Activity, state: Bundle?) = Unit
            override fun onActivityStarted(a: android.app.Activity) = Unit
            override fun onActivityPaused(a: android.app.Activity) = Unit
            override fun onActivityStopped(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        }
        val ui = automation
        val original = checkNotNull(ui.serviceInfo)
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            ui.serviceInfo = checkNotNull(ui.serviceInfo).apply {
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val intent = NovelReaderActivity.newIntent(instrumentation.targetContext, Long.MIN_VALUE)
            ActivityScenario.launch<NovelReaderActivity>(intent).use {
                awaitUi("S31 actual Host and list ready") {
                    hostMain { bridge.creations > 0 && bridge.vm.content.value != null &&
                        runCatching { bridge.list.layoutInfo.visibleItemsInfo.isNotEmpty() }.getOrDefault(false) }
                }
                test(bridge)
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            // Final saves start undispatched. Do not cancel the app-lifetime scope while a storage
            // callback is pending; fixture callers wait for their actual persistence oracle first.
            hostMain { f.appScope.cancel(); hostWindow = null }
            ui.serviceInfo = original
        }
    }

    private inner class HostFixture(
        equalContent: Boolean = false,
        short: Boolean = false,
        nextRead: Boolean = false,
        terminal: NovelNavigationStatus? = null,
        val backingProgress: ReadingProgressRepository? = null,
        ids: Pair<Long, Long> = -10_001L to -10_002L,
        val imageContent: ChapterContent? = null,
        long: Boolean = false,
    ) {
        val oldId = ids.first
        val nextId = ids.second
        private val workId = -20_001L
        val oldContent = imageContent ?: ChapterContent(List(if (short) 1 else if (long) 80 else 24) { ContentBlock.Paragraph(paragraph("Old", it)) })
        val nextContent = if (equalContent) ChapterContent(oldContent.blocks.toList()) else
            ChapterContent(List(if (short) 1 else if (long) 80 else 24) { ContentBlock.Paragraph(paragraph("Next", it)) })
        private val old = Chapter.create().copy(id = oldId, mangaId = workId, name = "Old chapter",
            url = "fixture:old", chapterNumber = 1.0, sourceOrder = 2)
        private val next = old.copy(id = nextId, name = "Next chapter", url = "fixture:next", read = nextRead,
            chapterNumber = if (terminal == NovelNavigationStatus.UNKNOWN_NUMBER) UNRECOGNIZED_NUMBER.toDouble() else 2.0,
            sourceOrder = 1)
        private val rows = if (terminal == NovelNavigationStatus.LAST_CHAPTER) listOf(old) else listOf(old, next)
        private val work = Manga.create().copy(id = workId, title = "C5 controlled work", source = -30_001L,
            chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        val preferences = NovelReaderPreferences(InMemoryPreferenceStore())
        private val reader = ReaderPreferences(InMemoryPreferenceStore())
        private val base = mockk<BasePreferences> {
            every { downloadedOnly } returns InMemoryPreferenceStore().getBoolean("c5_host_downloaded_only",
                terminal == NovelNavigationStatus.NO_MORE_DOWNLOADED)
        }
        private val chapters = mockk<ChapterRepository>()
        private val mangas = mockk<MangaRepository>()
        private val contents = mockk<ChapterContentRepository>()
        private val progressStore = mockk<ReadingProgressRepository>()
        private val history = mockk<HistoryRepository>()
        private val tracker = mockk<TrackChapter>()
        private val downloads = mockk<DownloadManager>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val saves = mutableListOf<Pair<Long, ReadingProgress>>()
        val updates = mutableListOf<ChapterUpdate>()
        val histories = mutableListOf<HistoryUpdate>()
        val tracks = mutableListOf<Double>()
        val loads = mutableListOf<Long>()
        val events = mutableListOf<String>()
        var nextLoader: suspend () -> ChapterContent? = { nextContent }
        val nextLabel get() = label(MR.strings.novel_next_chapter, "Next chapter")

        init {
            coEvery { chapters.getChapterById(any()) } answers { rows.find { it.id == firstArg<Long>() } }
            coEvery { chapters.getChapterByMangaId(workId, true) } returns rows
            coEvery { chapters.update(any()) } answers { updates += firstArg<ChapterUpdate>(); Unit }
            coEvery { mangas.getMangaById(workId) } returns work
            coEvery { contents.get(any()) } coAnswers {
                val id = firstArg<Long>()
                loads += id
                events += "load:$id"
                if (id == oldId) oldContent else { check(id == nextId); nextLoader() }
            }
            coEvery { progressStore.get(any()) } coAnswers {
                val id = firstArg<Long>()
                events += "restore:$id"
                if (backingProgress != null) backingProgress.get(id) else
                    ReadingProgress(if (short) 0 else if (id == oldId) 2 else 4, 0, 0f, 0f)
            }
            coEvery { progressStore.save(any(), any()) } coAnswers {
                backingProgress?.save(firstArg<Long>(), secondArg<ReadingProgress>())
                saves += firstArg<Long>() to secondArg<ReadingProgress>()
                events += "save:${firstArg<Long>()}"
                Unit
            }
            coEvery { history.upsertHistory(any()) } answers {
                val update = firstArg<HistoryUpdate>()
                histories += update
                events += "history:${update.chapterId}"
                Unit
            }
            coEvery { tracker.await(any(), any(), any(), any()) } answers { tracks += thirdArg<Double>(); Unit }
            every { downloads.getDownloadedChapterIds(any(), any()) } returns emptySet()
        }

        fun createViewModel() = NovelReaderViewModel(
            getChapterContent = GetChapterContent(contents, chapters, mangas, SourceRegistry()),
            downloadProvider = mockk<DownloadProvider>(),
            getChapter = GetChapter(chapters), getManga = GetManga(mangas),
            sourceManager = mockk<SourceManager>().also { manager -> coEvery { manager.get(any()) } returns null },
            getReadingProgress = GetReadingProgress(progressStore), saveReadingProgress = SaveReadingProgress(progressStore),
            updateChapter = UpdateChapter(chapters), upsertHistory = UpsertHistory(history), trackChapter = tracker,
            trackPreferences = TrackPreferences(InMemoryPreferenceStore()),
            getIncognitoState = mockk<GetIncognitoState> { coEvery { await(anyNullable()) } returns false },
            context = instrumentation.targetContext, appScope = appScope,
            getChaptersByMangaId = GetChaptersByMangaId(chapters), readerPreferences = reader,
            basePreferences = base, downloadManager = downloads,
        )

        fun paragraph(prefix: String, index: Int) = "$prefix isolated Host paragraph $index. Alpha beta gamma."
        fun endLabel(chapter: String) = label(MR.strings.novel_chapter_end, chapter)
        fun label(resource: dev.icerock.moko.resources.StringResource, vararg args: Any) =
            instrumentation.targetContext.stringResource(resource, *args)
        fun assertNoCompletion() { assertTrue(updates.isEmpty()); assertTrue(tracks.isEmpty()) }
    }

    private fun withActualHost(f: HostFixture, test: (ActivityScenario<NovelReaderActivity>, NovelReaderActivity) -> Unit) {
        val ui = automation
        val original = checkNotNull(ui.serviceInfo)
        try {
            ui.serviceInfo = checkNotNull(ui.serviceInfo).apply {
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            // Never launch chapter 1. This sentinel cannot be allocated by chapter.sql insertReturningId,
            // which omits id (SQLite allocates positive rowids); restoration remaps IDs through that API.
            // Initial onCreate may run BEFORE setContent below: its missing row/cache can only yield an error.
            val intent = NovelReaderActivity.newIntent(instrumentation.targetContext, Long.MIN_VALUE)
            ActivityScenario.launch<NovelReaderActivity>(intent).use { scenario ->
                val activity = AtomicReference<NovelReaderActivity>()
                scenario.onActivity { owner ->
                    activity.set(owner)
                    hostWindow = owner.window
                    // A distinct lifecycle-store key must NOT return the Activity's default Metro VM.
                    val vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(f.createViewModel())!!
                    }).get("c5-controlled-host", NovelReaderViewModel::class.java)
                    owner.setContent {
                        MaterialTheme {
                            NovelReaderHost(chapterId = f.oldId, controlledPreferences = f.preferences, controlledViewModel = vm)
                        }
                    }
                }
                awaitUi("actual controlled Host publication") { hasText(f.endLabel("Old chapter")) ||
                    hasText(f.paragraph("Old", 2)) || hasText(f.paragraph("Old", 0)) }
                test(scenario, activity.get())
            }
        } finally {
            hostMain { f.appScope.cancel(); hostWindow = null }
            ui.serviceInfo = original
        }
    }

    private fun <T> hostMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private var hostWindow: Window? = null

    /** Visible bounds of THIS Activity, independent of document length and other accessibility windows. */
    private fun hostViewport(): Rect = hostMain {
        val decor = checkNotNull(hostWindow).decorView
        val origin = IntArray(2).also(decor::getLocationOnScreen)
        val visible = Rect()
        check(decor.getLocalVisibleRect(visible)) { "Host Activity is not visible" }
        visible.offset(origin[0], origin[1])
        val insets = checkNotNull(decor.rootWindowInsets).getInsets(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
        )
        val content = Rect(origin[0] + insets.left, origin[1] + insets.top,
            origin[0] + decor.width - insets.right, origin[1] + decor.height - insets.bottom)
        check(content.intersect(visible) && !content.isEmpty) { "No visible Host content viewport" }
        content
    }

    private fun hostRoot(): AccessibilityNodeInfo {
        instrumentation.waitForIdleSync()
        automation.clearCache()
        val id = hostMain { checkNotNull(checkNotNull(hostWindow).decorView.createAccessibilityNodeInfo()).windowId }
        return checkNotNull((listOfNotNull(automation.rootInActiveWindow) +
            automation.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }).firstOrNull {
            it.windowId == id && it.packageName?.toString() == instrumentation.targetContext.packageName
        }) { "No public tree for the Host Activity window" }
    }

    private fun hostContentViewport(root: AccessibilityNodeInfo): Rect {
        val viewport = hostViewport()
        val controls = instrumentation.targetContext.stringResource(MR.strings.novel_reader_controls)
        find(root) { it.isVisibleToUser && it.paneTitle?.toString() == controls }?.let { toolbar ->
            val bounds = Rect().also(toolbar::getBoundsInScreen)
            if (Rect.intersects(viewport, bounds)) viewport.top = maxOf(viewport.top, bounds.bottom)
        }
        check(!viewport.isEmpty) { "Toolbar covers Host content" }
        return viewport
    }

    /** A clipped footer label is not yet a usable target; never match another window's text. */
    private fun hostTarget(text: String): AccessibilityNodeInfo? {
        val root = hostRoot()
        val viewport = hostContentViewport(root).apply { inset(1, 1) }
        val label = find(root) {
            val bounds = Rect().also(it::getBoundsInScreen)
            it.isVisibleToUser && !bounds.isEmpty && viewport.contains(bounds) &&
                (it.text?.toString() == text || it.contentDescription?.toString() == text)
        } ?: return null
        var target = label
        while (true) {
            if (target.windowId != root.windowId || target.packageName != root.packageName ||
                !target.isVisibleToUser || !target.isEnabled) return null
            // Stop at the nearest public clickable control, never bypass a disabled intended target.
            if (target.isClickable) break
            target = target.parent ?: return null
        }
        val bounds = Rect().also(target::getBoundsInScreen)
        if (bounds.isEmpty || !viewport.contains(bounds) ||
            !target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return null
        return target
    }

    private fun hostActivate(text: String) {
        awaitUi("enabled Host action '$text'") { hostTarget(text) != null }
        val target = checkNotNull(hostTarget(text)) { "No public Host click target: $text" }
        assertTrue("Enabled clickable Host target: $text", target.isEnabled && target.isClickable)
        assertTrue("Host target supports ACTION_CLICK: $text",
            target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        assertTrue("Host target accepted ACTION_CLICK: $text", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        hostRoot() // Fresh public tree after activation; scenario-specific oracles still decide success.
    }

    private fun hostToolbar(f: HostFixture, title: String) {
        hostRoot()
        if (!hasText(title)) {
            val viewport = hostViewport()
            tap(viewport.right - 2f, viewport.top + viewport.height() * 0.35f)
        }
        awaitUi("actual Host metadata $title") { hasText(title) && hasText("C5 controlled work") }
        hostRoot()
    }

    private fun hostScrollTo(text: String) {
        // At most 30 public drags. Success is a fully visible footer, never an action return value.
        for (attempt in 0..30) {
            if (hostTarget(text) != null) return
            if (attempt == 30) break
            val viewport = hostContentViewport(hostRoot())
            val x = viewport.exactCenterX()
            val start = viewport.top + viewport.height() * 0.8f
            val end = viewport.top + viewport.height() * 0.55f
            val downTime = SystemClock.uptimeMillis()
            try {
                touch(MotionEvent.ACTION_DOWN, x, start, downTime)
                for (step in 1..20) {
                    // Gesture cadence only (320 ms drag), not a blind wait for application readiness.
                    SystemClock.sleep((downTime + step * 16 - SystemClock.uptimeMillis()).coerceAtLeast(0))
                    touch(MotionEvent.ACTION_MOVE, x, start + (end - start) * step / 20, downTime)
                }
            } finally {
                touch(MotionEvent.ACTION_UP, x, end, downTime)
            }
            instrumentation.waitForIdleSync()
            automation.waitForIdle(200, 5_000)
            hostRoot()
        }
        throw AssertionError("Footer not fully visible within 30 public drags: $text")
    }
}
