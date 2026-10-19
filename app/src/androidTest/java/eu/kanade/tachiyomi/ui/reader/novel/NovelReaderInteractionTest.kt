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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.novel.reader.compose.NovelReaderScreen
import app.novel.reader.core.ReaderSettings
import app.novel.reader.core.ReadingPosition
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Static content and callbacks only: no seeding, preferences, downloads, accounts or progress writes. */
@RunWith(AndroidJUnit4::class)
class NovelReaderInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val settings = mutableStateOf(ReaderSettings())
    private val progress = AtomicReference(ReadingPosition(0, 0f))
    private val shared = AtomicReference<String?>(null)
    private var settingsOpened = false
    private var backPressed = false
    private var diagnosticsEnabled = false
    private var diagnosticWindow: Window? = null
    private val content = ChapterContent(
        blocks = List(80) { ContentBlock.Paragraph("Alpha beta gamma. Paragraph $it is isolated test content.") },
    )

    private fun withReader(diagnostics: Boolean = false, test: () -> Unit) {
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
                    activity.setContent {
                        MaterialTheme {
                            NovelReaderScreen(
                                initialContent = content,
                                initialPosition = ReadingPosition(0, 0f),
                                settings = settings.value,
                                errorMessage = null,
                                errorReason = null,
                                retryLabel = "Retry",
                                onRetry = {},
                                onProgress = { progress.set(it) },
                                backLabel = "Reader back",
                                settingsLabel = "Reader settings",
                                controlsLabel = "Reader controls",
                                shareLabel = "Share selection",
                                onBack = { backPressed = true },
                                onSettings = { settingsOpened = true },
                                onShareSelectedText = { shared.set(it) },
                            )
                        }
                    }
                }
                awaitUi("isolated reader content and controls") {
                    hasText("Reader controls") && hasText("Alpha beta gamma. Paragraph 0 is isolated test content.")
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
                }
            }
        } finally {
            ui.serviceInfo = originalServiceInfo
        }
    }

    @Test
    fun upperTapTogglesButLowerTapAndScrollingDoNotAndControlsDispatchActions() = withReader {
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
