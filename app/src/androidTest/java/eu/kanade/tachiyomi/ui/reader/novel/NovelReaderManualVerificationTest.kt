package eu.kanade.tachiyomi.ui.reader.novel

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.novel.runtime.SourceRegistry
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HUMAN-ONLY entry, not an eleventh Reader10 case or an automatic manual-acceptance oracle.
 * Opt in explicitly with s45Manual=human-only. Absent opt-in skips ONLY this new entry.
 * A successful JUnit result means seven explicit case exits and final Finish confirmation, NOT checks passed.
 * Continue/Finish never record human PASS. Safety timeout can still end the session without confirmation.
 * No injected input, installed preferences, or real write services. Controls exist only in this test entry.
 */
@RunWith(AndroidJUnit4::class)
class NovelReaderManualVerificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun humanDrivenSyntheticHostSession() {
        val args = InstrumentationRegistry.getArguments()
        val optIn = args.getString("s45Manual")
        if (optIn == null) {
            stream("NOT ENTERED: missing s45Manual=human-only; skip is NOT manual acceptance")
            assumeTrue("S45 human-only opt-in absent; no manual checks performed", false)
        }
        require(optIn == "human-only") { "s45Manual must be human-only" }
        val seconds = args.getString("s45ScenarioSeconds")?.toLong() ?: 120L
        require(seconds in 30L..120L) { "s45ScenarioSeconds must be 30..120" }
        val started = SystemClock.elapsedRealtime()
        val deadline = started + TimeUnit.SECONDS.toMillis(1800)
        stream("START: explicit progression only; HUMAN observations/sign-off must be recorded separately")
        awaitControl(
            title = "Manual verification: case 1 of ${Case.entries.size}",
            body = "${Case.entries.first().name}: ${Case.entries.first().instructions}",
            actionLabel = "Start first case", seconds = seconds, deadline = deadline,
        )
        for ((index, case) in Case.entries.withIndex()) {
            check(SystemClock.elapsedRealtime() < deadline) { "S50 safety deadline; no case launch" }
            // Every case owns fresh synthetic rows, preferences, repositories, VM and scope.
            val fixture = Fixture(case)
            val destroyed = CountDownLatch(1)
            val backRequested = AtomicBoolean(false)
            val expectedBack = AtomicBoolean(false)
            var scenario: ActivityScenario<ComponentActivity>? = null
            val opened = SystemClock.elapsedRealtime()
            try {
                stream("SCENARIO ${case.name}: ${case.instructions}; Back ends ONLY this case; deadline=${seconds}s")
                // Preserve the actual reader -> dispatcher -> Activity finish path. Do NOT replace reader
                // content on Back: that would hide whether the reader really exits. Compose's own Back
                // callbacks (selection/settings) remain later/higher-priority callbacks, installed by Host.
                val intent = Intent(instrumentation.targetContext, ComponentActivity::class.java)
                ActivityScenario.launch<ComponentActivity>(intent).use { owned ->
                    scenario = owned
                    owned.onActivity { owner ->
                        owner.onBackPressedDispatcher.addCallback(owner, object : OnBackPressedCallback(true) {
                            override fun handleOnBackPressed() {
                                backRequested.set(true)
                                isEnabled = false
                                owner.onBackPressedDispatcher.onBackPressed()
                            }
                        })
                        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_DESTROY) {
                                expectedBack.set(backRequested.get() && owner.isFinishing && !owner.isChangingConfigurations)
                                destroyed.countDown()
                            }
                        })
                        val vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                modelClass.cast(fixture.createViewModel())!!
                        }).get("s45-human-controlled-host", NovelReaderViewModel::class.java)
                        owner.setContent {
                            MaterialTheme {
                                NovelReaderHost(
                                    chapterId = fixture.oldId,
                                    controlledPreferences = fixture.preferences,
                                    controlledViewModel = vm,
                                )
                            }
                        }
                    }
                    awaitBounded(destroyed, seconds, deadline, "reader ${case.name}")
                    check(expectedBack.get()) {
                        "S50 unexpected Activity destruction/recreation in ${case.name}; abort, never advance"
                    }
                }
            } finally {
                // use closes even on timeout/interruption/failure; cancel fixture work even if close throws.
                try {
                    instrumentation.runOnMainSync { fixture.scope.cancel() }
                } finally {
                    stream("CLEANUP ${case.name}: elapsedMs=${SystemClock.elapsedRealtime() - opened} " +
                        "activityDestroyed=${scenario?.state == Lifecycle.State.DESTROYED}; fixture scope cancelled; NOT sign-off")
                }
            }
            val next = Case.entries.getOrNull(index + 1)
            // A separate empty control Activity, never the next fixture, is shown after the reader exits.
            // Destruction wakes a failing wait; ONLY a real button callback permits normal progression.
            awaitControl(
                title = if (next == null) "Final warning: session closure" else "Case ${index + 1} ended",
                body = if (next == null) {
                    "All seven cases have ended. This does not mean any check passed. " +
                        "Finish will end this manual entry; the parent controller may then close the emulator. " +
                        "Confirm only when you are ready to end the session."
                } else {
                    "${case.name} ended. No approval was recorded. Next case ${index + 2}: ${next.name}. " +
                        next.instructions
                },
                actionLabel = if (next == null) "Finish session" else "Continue to next case",
                seconds = seconds, deadline = deadline, isFinal = next == null,
            )
        }
        stream("END: elapsedMs=${SystemClock.elapsedRealtime() - started}; explicit Finish confirmed, MANUAL RESULTS UNASSESSED")
    }

    private fun awaitBounded(latch: CountDownLatch, seconds: Long, deadline: Long, label: String) {
        // Instrumentation thread only. The external owned-SDK deadline starts earlier and remains authoritative.
        val remaining = minOf(TimeUnit.SECONDS.toMillis(seconds), deadline - SystemClock.elapsedRealtime())
        check(remaining > 0 && latch.await(remaining, TimeUnit.MILLISECONDS)) {
            "S50 timeout at $label; safety closure without confirmation; no manual acceptance"
        }
    }

    private fun awaitControl(
        title: String,
        body: String,
        actionLabel: String,
        seconds: Long,
        deadline: Long,
        isFinal: Boolean = false,
    ) {
        // One monotonic deadline includes Activity setup, recomposition and the final dialog.
        // The external owned-SDK deadline still starts earlier and remains authoritative.
        val controlDeadline = minOf(deadline, SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(seconds))
        check(SystemClock.elapsedRealtime() < controlDeadline) { "S50 safety deadline; no control launch" }
        val accepted = CountDownLatch(1)
        val unexpectedDestruction = AtomicBoolean(false)
        val closing = AtomicBoolean(false)
        val intent = Intent(instrumentation.targetContext, ComponentActivity::class.java)
        stream("CONTROL: $title; waiting for explicit $actionLabel; NOT sign-off")
        ActivityScenario.launch<ComponentActivity>(intent).use { owned ->
            try {
                owned.onActivity { owner ->
                    owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_DESTROY && !closing.get()) {
                            unexpectedDestruction.set(true)
                            accepted.countDown() // Wake ONLY to fail below, never to authorize progression.
                        }
                    })
                    // Back on a control screen is not Continue or Finish. Reader Back is untouched above.
                    owner.onBackPressedDispatcher.addCallback(owner, object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() = Unit
                    })
                    owner.setContent {
                        MaterialTheme {
                            var confirming by remember { mutableStateOf(false) }
                            var remainingMillis by remember(controlDeadline) {
                                mutableStateOf((controlDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                            }
                            LaunchedEffect(controlDeadline) {
                                remainingMillis = (controlDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                                while (remainingMillis > 0L) {
                                    delay(minOf(250L, remainingMillis))
                                    remainingMillis = (controlDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                                }
                            }
                            // Ceiling keeps a positive sub-second budget visible as 1, never 0 or negative.
                            val remainingSeconds = (remainingMillis + 999L) / 1000L
                            val countdown = "Time remaining: $remainingSeconds seconds"
                            val timeoutWarning = "Timeout aborts this manual entry; the session/emulator closes without confirmation. This is not PASS."
                            Surface(modifier = Modifier.fillMaxSize()) {
                                Column(
                                    modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        Text(title, style = MaterialTheme.typography.headlineSmall)
                                        Text(body)
                                        Text("Back ends only the current reader case. On this screen use the explicit button; Back does not continue.")
                                        Text("Continue is not PASS. Finish is not PASS. Record observations and approval separately with the operator.")
                                        Text("Each reader/control screen has at most $seconds seconds. The 30-minute safety budget " +
                                            "includes boot, installation and cleanup; the parent may close the emulator sooner.")
                                    }
                                    // Keep the countdown and warning beside the action, outside scrolling instructions.
                                    Text(countdown, style = MaterialTheme.typography.titleLarge)
                                    Text(timeoutWarning)
                                    Button(onClick = {
                                        if (isFinal) confirming = true else accepted.countDown()
                                    }) {
                                        Text(actionLabel)
                                    }
                                }
                            }
                            if (confirming) {
                                AlertDialog(
                                    onDismissRequest = { confirming = false },
                                    title = { Text("End manual session and allow emulator closure?") },
                                    text = { Text("$countdown\n\n$timeoutWarning") },
                                    confirmButton = {
                                        Button(onClick = { accepted.countDown() }) { Text("Finish session now") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { confirming = false }) { Text("Keep session open") }
                                    },
                                )
                            }
                        }
                    }
                }
                val remaining = controlDeadline - SystemClock.elapsedRealtime()
                check(remaining > 0 && accepted.await(remaining, TimeUnit.MILLISECONDS) &&
                    SystemClock.elapsedRealtime() < controlDeadline) {
                    "S50 timeout at control $title; safety closure without confirmation; no manual acceptance"
                }
                check(!unexpectedDestruction.get()) { "S50 control Activity destroyed unexpectedly; abort, never advance" }
                owned.onActivity { owner ->
                    check(!owner.isFinishing && !owner.isDestroyed) { "S50 control no longer active; abort" }
                }
                stream("CONTROL_CONFIRMED: $actionLabel; NOT human PASS")
            } finally {
                closing.set(true)
            }
        }
    }

    private enum class Case(val instructions: String) {
        SAVED_NEXT("Old starts at block 2; scroll to footer, confirm no auto-advance, Next restores block 4; inspect titles/save ordering; Back exits"),
        READ_EQUAL_NEXT("Next already read, identical text; scroll OLD to end, Next must reset to block 0/clear selection and toolbar; inspect title"),
        FAILED_NEXT("First two NEXT loads miss, third succeeds; observe OLD/position/error retained, Return, Next again, Retry; observe save/load trace"),
        SHORT_EQUAL("One block each, equal text; opening either must not mark read/track merely because footer is visible; Next then terminal"),
        LAST("Single chapter: terminal message and no Next/Retry; no false advance"),
        DOWNLOADED_TERMINAL("Only-downloaded in memory, no downloaded destinations: terminal message/no Next"),
        UNKNOWN_TERMINAL("Numeric sort with unknown destination: warning/no Next; never silently choose another order"),
    }

    /** Strict mocks: only named synthetic identities; an unexpected interface never falls through to a real repo. */
    private inner class Fixture(private val case: Case) {
        val oldId = -45_001L
        private val nextId = -45_002L
        private val workId = -45_101L
        private val sourceId = -45_201L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val preferences = NovelReaderPreferences(InMemoryPreferenceStore())
        private val reader = ReaderPreferences(InMemoryPreferenceStore())
        private val short = case == Case.SHORT_EQUAL
        private val oldContent = document("Old", if (short) 1 else 24)
        private val nextContent = if (case == Case.READ_EQUAL_NEXT || short) {
            ChapterContent(oldContent.blocks.toList())
        } else document("Next", 24)
        private val old = Chapter.create().copy(
            id = oldId, mangaId = workId, name = "S45 Old chapter", url = "fixture:s45-old",
            chapterNumber = 1.0, sourceOrder = 2,
        )
        private val next = old.copy(
            id = nextId, name = "S45 Next chapter", url = "fixture:s45-next",
            read = case == Case.READ_EQUAL_NEXT, sourceOrder = 1,
            chapterNumber = if (case == Case.UNKNOWN_TERMINAL) UNRECOGNIZED_NUMBER.toDouble() else 2.0,
        )
        private val rows = if (case == Case.LAST) listOf(old) else listOf(old, next)
        private val work = Manga.create().copy(
            id = workId, source = sourceId, title = "S45 synthetic work", chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
        )
        private val chapters = mockk<ChapterRepository>()
        private val mangas = mockk<MangaRepository>()
        private val contents = mockk<ChapterContentRepository>()
        private val progress = mockk<ReadingProgressRepository>()
        private val history = mockk<HistoryRepository>()
        private val tracker = mockk<TrackChapter>()
        private val downloads = mockk<DownloadManager>()
        private val base = mockk<BasePreferences> {
            every { downloadedOnly } returns InMemoryPreferenceStore().getBoolean(
                "s45_downloaded_only", case == Case.DOWNLOADED_TERMINAL,
            )
        }
        // Captured fake writes live only here. Trace is observation data, never automatic manual PASS.
        private val saved = mutableMapOf(
            oldId to ReadingProgress(if (short) 0 else 2, 0, 0f, 0f),
            nextId to ReadingProgress(if (short) 0 else 4, 0, 0f, 0f),
        )
        private val updates = mutableListOf<ChapterUpdate>()
        private val histories = mutableListOf<HistoryUpdate>()
        private val tracks = mutableListOf<Double>()
        private var attempts = 0

        init {
            for (row in rows) {
                coEvery { chapters.getChapterById(row.id) } returns row
                coEvery { contents.get(row.id) } answers {
                    val miss = row.id == nextId && case == Case.FAILED_NEXT && ++attempts < 3
                    trace("load id=${row.id} miss=$miss attempt=$attempts")
                    if (miss) null else if (row.id == oldId) oldContent else nextContent
                }
                coEvery { progress.get(row.id) } answers {
                    saved[row.id].also { trace("restore id=${row.id} position=$it") }
                }
                coEvery { progress.save(row.id, any()) } answers {
                    val value = secondArg<ReadingProgress>()
                    saved[row.id] = value
                    trace("save id=${row.id} position=$value")
                    Unit
                }
            }
            coEvery { chapters.getChapterByMangaId(workId, true) } returns rows
            coEvery { mangas.getMangaById(workId) } returns work
            coEvery { chapters.update(any()) } answers {
                val value = firstArg<ChapterUpdate>()
                check(rows.any { it.id == value.id })
                updates += value
                trace("fake-read-update id=${value.id} value=$value count=${updates.size}")
                Unit
            }
            coEvery { history.upsertHistory(any()) } answers {
                val value = firstArg<HistoryUpdate>()
                check(rows.any { it.id == value.chapterId })
                histories += value
                trace("fake-history id=${value.chapterId} count=${histories.size}")
                Unit
            }
            coEvery { tracker.await(any(), workId, any(), any()) } answers {
                val number = thirdArg<Double>()
                check(rows.any { it.chapterNumber == number })
                tracks += number
                trace("fake-track chapterNumber=$number count=${tracks.size}")
                Unit
            }
            every { downloads.getDownloadedChapterIds(rows, work) } returns emptySet()
        }

        fun createViewModel() = NovelReaderViewModel(
            getChapterContent = GetChapterContent(contents, chapters, mangas, SourceRegistry()),
            downloadProvider = mockk<DownloadProvider>(),
            getChapter = GetChapter(chapters), getManga = GetManga(mangas),
            sourceManager = mockk<SourceManager>().also { manager -> coEvery { manager.get(sourceId) } returns null },
            getReadingProgress = GetReadingProgress(progress), saveReadingProgress = SaveReadingProgress(progress),
            updateChapter = UpdateChapter(chapters), upsertHistory = UpsertHistory(history), trackChapter = tracker,
            trackPreferences = TrackPreferences(InMemoryPreferenceStore()),
            getIncognitoState = mockk<GetIncognitoState> { coEvery { await(sourceId) } returns false },
            context = instrumentation.targetContext, appScope = scope,
            getChaptersByMangaId = GetChaptersByMangaId(chapters), readerPreferences = reader,
            basePreferences = base, downloadManager = downloads,
        )

        private fun document(prefix: String, count: Int) = ChapterContent(List(count) {
            ContentBlock.Paragraph("$prefix S45 paragraph $it. Alpha beta gamma. Synthetic text for human selection.")
        })

        private fun trace(message: String) = stream("TRACE ${case.name} $message")
    }

    private fun stream(message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nS45 $message\n") })
    }
}
