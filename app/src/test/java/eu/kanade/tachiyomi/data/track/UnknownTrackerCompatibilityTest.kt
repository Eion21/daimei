package eu.kanade.tachiyomi.data.track

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.ui.manga.track.TrackChapterSelectorScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackDateRemoverScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackDateSelectorScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackInfoDialogHomeScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackScoreSelectorScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackStatusSelectorScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackerRemoveScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackerSearchScreen
import eu.kanade.tachiyomi.ui.stats.StatsViewModel
import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import kotlin.time.Instant

/** Public model regressions; stats and home are preservation, NOT missing-ID crash reproductions. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnknownTrackerCompatibilityTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val modelJobs = mutableListOf<Job>()
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + dispatcher)

    private abstract class RegisteredTracker : BaseTracker(0L, ""), DeletableTracker

    private val statuses = mapOf(1L to mockk<StringResource>(), 2L to mockk<StringResource>())

    // Fixed registered IDs match TrackerManager: MAL=1, AniList=2; 99 is not registered.
    private val trackers = listOf(1L, 2L).map { id ->
        mockk<RegisteredTracker> {
            every { this@mockk.id } returns id
            every { name } returns if (id == 1L) "MyAnimeList" else "AniList"
            every { isLoggedIn } returns true
            every { getStatusList() } returns listOf(1L, 2L)
            every { getStatus(any()) } answers { statuses[firstArg<Long>()] }
            every { displayScore(any()) } returns "8"
            every { getScoreList() } returns listOf("0", "6", "8", "10")
            every { get10PointScore(any()) } answers { firstArg<Track>().score }
            coEvery { setRemoteStatus(any(), any()) } just runs
            coEvery { setRemoteLastChapterRead(any(), any()) } just runs
            coEvery { setRemoteScore(any(), any()) } just runs
            every { supportsPrivateTracking } returns true
            coEvery { setRemoteStartDate(any(), any()) } just runs
            coEvery { setRemoteFinishDate(any(), any()) } just runs
            coEvery { search(any()) } returns emptyList()
            coEvery { register(any(), any()) } just runs
            coEvery { delete(any()) } just runs
        }
    }
    private val manager = mockk<TrackerManager> {
        every { trackers } returns this@UnknownTrackerCompatibilityTest.trackers
        every { loggedInTrackers() } answers { this@UnknownTrackerCompatibilityTest.trackers }
        every { this@mockk.get(any<Long>()) } answers {
            this@UnknownTrackerCompatibilityTest.trackers.find { it.id == firstArg<Long>() }
        }
    }

    @BeforeEach
    fun installMain() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun releaseModelsBeforeMain() = runTest(dispatcher) {
        try {
            store.clear()
            modelJobs.forEach { it.cancelAndJoin() }
            appJob.cancelAndJoin()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun <T : ViewModel> own(model: T): T = model.also {
        store.put("model-${modelJobs.size}", it)
        modelJobs += it.viewModelScope.coroutineContext.job
    }

    // Each selector has two existing-service preservation cases and one genuine constructor RED candidate.
    @ParameterizedTest(name = "status selector tracker={0}")
    @ValueSource(longs = [1L, 2L, 99L])
    fun `status selector tolerates an absent service and preserves known selections`(id: Long) = runTest(dispatcher) {
        val input = track(id)
        val snapshot = input.copy()
        val model = assertDoesNotThrow<TrackStatusSelectorScreen.Model> {
            own(TrackStatusSelectorScreen.Model(input, id, appScope, manager))
        }
        assertEquals(input.status, model.state.value.selection)
        assertEquals(if (id == 99L) emptyMap() else statuses, model.getSelections())
        model.setSelection(2L)
        assertEquals(2L, model.state.value.selection)
        model.setStatus()
        advanceUntilIdle()
        trackers.forEach { service ->
            coVerify(exactly = if (service.id == id) 1 else 0) { service.setRemoteStatus(any(), any()) }
            if (service.id == id) coVerify(exactly = 1) {
                service.setRemoteStatus(match { it.id == input.id && it.tracker_id == id }, 2L)
            }
        }
        assertEquals(snapshot, input)
    }

    @ParameterizedTest(name = "chapter selector tracker={0}")
    @ValueSource(longs = [1L, 2L, 99L])
    fun `chapter selector tolerates an absent service and preserves chapter range`(id: Long) = runTest(dispatcher) {
        val input = track(id)
        val snapshot = input.copy()
        val model = assertDoesNotThrow<TrackChapterSelectorScreen.Model> {
            own(TrackChapterSelectorScreen.Model(input, id, appScope, manager))
        }
        assertEquals(3, model.state.value.selection)
        assertEquals((0..12).toList(), model.getRange().toList())
        model.setSelection(4)
        assertEquals(4, model.state.value.selection)
        model.setChapter()
        advanceUntilIdle()
        trackers.forEach { service ->
            coVerify(exactly = if (service.id == id) 1 else 0) { service.setRemoteLastChapterRead(any(), any()) }
            if (service.id == id) coVerify(exactly = 1) {
                service.setRemoteLastChapterRead(match { it.id == input.id && it.tracker_id == id }, 4)
            }
        }
        assertEquals(snapshot, input)
    }

    @ParameterizedTest(name = "score selector tracker={0}")
    @ValueSource(longs = [1L, 2L, 99L])
    fun `score selector tolerates an absent service and preserves known scores`(id: Long) = runTest(dispatcher) {
        val input = track(id)
        val snapshot = input.copy()
        val model = assertDoesNotThrow<TrackScoreSelectorScreen.Model> {
            own(TrackScoreSelectorScreen.Model(input, id, appScope, manager))
        }
        assertEquals(if (id == 99L) "" else "8", model.state.value.selection)
        assertEquals(if (id == 99L) emptyList() else listOf("0", "6", "8", "10"), model.getSelections())
        model.setSelection("6")
        assertEquals("6", model.state.value.selection)
        model.setScore()
        advanceUntilIdle()
        trackers.forEach { service ->
            coVerify(exactly = if (service.id == id) 1 else 0) { service.setRemoteScore(any(), any()) }
            if (service.id == id) coVerify(exactly = 1) {
                service.setRemoteScore(match { it.id == input.id && it.tracker_id == id }, "6")
            }
        }
        assertEquals(snapshot, input)
    }

    @Test
    fun `home keeps MAL and AniList items and omits unknown stored IDs without deleting them`() = runTest(dispatcher) {
        val stored = listOf(track(1L), track(2L), track(99L))
        val repository = repository(stored)
        val refresh = mockk<RefreshTracks> { coEvery { await(42L) } returns emptyList() }
        val sources = mockk<SourceManager> { coEvery { getOrStub(7L) } returns mockk(relaxed = true) }
        val model = own(
            TrackInfoDialogHomeScreen.Model(
                mangaId = 42L,
                sourceId = 7L,
                appScope = appScope,
                context = mockk(),
                getTracks = GetTracks(repository),
                getManga = mockk(),
                trackerManager = manager,
                sourceManager = sources,
                refreshTracks = refresh,
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), model.state.value.trackItems.map { it.tracker.id })
        assertEquals(stored.take(2), model.state.value.trackItems.map { it.track })
        assertEquals(stored, GetTracks(repository).await(42L))
        coVerify(exactly = 0) { repository.delete(any(), any()) }
        coVerify(exactly = 0) { repository.upsert(any()) }
        coVerify(exactly = 0) { repository.upsertAll(any()) }
    }

    @Test
    fun `stats excludes unknown-only titles and scores but keeps known tracker counts and mean`() = runTest(dispatcher) {
        val stored = listOf(track(1L), track(2L).copy(score = 6.0), track(99L).copy(mangaId = 43L, score = 100.0))
        val repository = repository(stored)
        val library = mockk<GetLibraryManga> {
            coEvery { await() } returns listOf(42L, 43L).map { id ->
                LibraryManga(Manga.create().copy(id = id, source = 7L), emptyList(), 12L, 3L, 0L, null, null, null)
            }
        }
        val duration = mockk<GetTotalReadDuration> { coEvery { await() } returns 0L }
        val downloads = mockk<DownloadManager> { every { getDownloadCount() } returns 0 }
        val model = own(
            StatsViewModel(
                downloads, library, duration, GetTracksPerManga(repository),
                LibraryPreferences(InMemoryPreferenceStore()), manager,
            ),
        )
        // Stats explicitly uses IO. Wait for the public terminal state with a REAL bounded timeout,
        // not virtual-time polling or advanceUntilIdle pretending to drain Dispatchers.IO.
        val state = withContext(Dispatchers.Default) {
            withTimeout(5_000L) { model.state.filterIsInstance<StatsScreenState.Success>().first() }
        }
        assertEquals(2, state.overview.libraryMangaCount)
        assertEquals(1, state.trackers.trackedTitleCount)
        assertEquals(2, state.trackers.trackerCount)
        assertEquals(7.0, state.trackers.meanScore)
        assertEquals(stored, repository.getTracks())
        coVerify(exactly = 0) { repository.delete(any(), any()) }
        coVerify(exactly = 0) { repository.upsert(any()) }
        coVerify(exactly = 0) { repository.upsertAll(any()) }
    }

    @ParameterizedTest(name = "date selector tracker={0}")
    @ValueSource(longs = [1L, 2L, 99L])
    fun `date selector preserves both date conversions and ignores an absent service`(id: Long) = runTest(dispatcher) {
        val input = track(id).copy(
            startDate = Instant.parse("2020-01-02T12:00:00Z"),
            finishDate = Instant.parse("2020-02-03T12:00:00Z"),
        )
        val snapshot = input.copy()
        val utc = Instant.parse("2020-01-15T12:00:00Z").toEpochMilliseconds()
        val zone = TimeZone.currentSystemDefault()
        val local = utc.convertEpochMillisZone(TimeZone.UTC, zone)
        for (start in listOf(true, false)) {
            val model = assertDoesNotThrow<TrackDateSelectorScreen.Model> {
                own(TrackDateSelectorScreen.Model(input, id, start, appScope, manager))
            }
            val date = if (start) input.startDate!! else input.finishDate!!
            assertEquals(date.toEpochMilliseconds().convertEpochMillisZone(zone, TimeZone.UTC), model.initialSelection)
            val navigator = mockk<Navigator>(relaxed = true)
            model.confirmRemoveDate(navigator)
            verify(exactly = if (id == 99L) 0 else 1) { navigator.push(any<Screen>()) }
            if (id != 99L) verify(exactly = 1) { navigator.push(TrackDateRemoverScreen(input, id, start)) }
            model.setDate(utc)
        }
        advanceUntilIdle()
        trackers.forEach { service ->
            coVerify(exactly = if (service.id == id) 1 else 0) { service.setRemoteStartDate(any(), any()) }
            coVerify(exactly = if (service.id == id) 1 else 0) { service.setRemoteFinishDate(any(), any()) }
            if (service.id == id) coVerify(exactly = 1) {
                service.setRemoteStartDate(match { it.id == input.id && it.tracker_id == id }, local)
                service.setRemoteFinishDate(match { it.id == input.id && it.tracker_id == id }, local)
            }
        }
        assertEquals(snapshot, input)
    }

    @ParameterizedTest(name = "date remover tracker={0}")
    @ValueSource(longs = [1L, 2L, 99L])
    fun `date remover preserves both clear actions and ignores an absent service`(id: Long) = runTest(dispatcher) {
        val input = track(id).copy(
            startDate = Instant.parse("2020-01-02T12:00:00Z"),
            finishDate = Instant.parse("2020-02-03T12:00:00Z"),
        )
        val snapshot = input.copy()
        for (start in listOf(true, false)) {
            val model = assertDoesNotThrow<TrackDateRemoverScreen.Model> {
                own(TrackDateRemoverScreen.Model(input, id, start, appScope, manager))
            }
            assertEquals(trackers.find { it.id == id }?.name.orEmpty(), model.getServiceName())
            model.removeDate()
        }
        advanceUntilIdle()
        trackers.forEach { service ->
            coVerify(exactly = if (service.id == id) 1 else 0) { service.setRemoteStartDate(any(), any()) }
            coVerify(exactly = if (service.id == id) 1 else 0) { service.setRemoteFinishDate(any(), any()) }
            if (service.id == id) coVerify(exactly = 1) {
                service.setRemoteStartDate(match { it.id == input.id && it.tracker_id == id }, 0L)
                service.setRemoteFinishDate(match { it.id == input.id && it.tracker_id == id }, 0L)
            }
        }
        assertEquals(snapshot, input)
    }

    @ParameterizedTest(name = "search tracker={0}")
    @ValueSource(longs = [1L, 2L, 99L])
    fun `search reaches an empty result and absent services cannot register`(id: Long) = runTest(dispatcher) {
        val model = assertDoesNotThrow<TrackerSearchScreen.Model> {
            own(TrackerSearchScreen.Model(42L, null, "", id, appScope, manager))
        }
        assertEquals(id != 99L, model.supportsPrivateTracking)
        assertEquals(null, model.state.value.selected)
        assertEquals(null, model.state.value.queryResult)
        model.trackingSearch("query")
        runCurrent()
        val state = withContext(Dispatchers.Default) {
            withTimeout(5_000L) { model.state.first { it.queryResult != null } }
        }
        assertEquals(emptyList<TrackSearch>(), state.queryResult!!.getOrThrow())
        assertEquals(null, state.selected)
        val item = TrackSearch.create(id).apply {
            title = "Preserved search result"
            tracking_url = "https://example.invalid/manga/10"
        }
        val snapshot = item.toDomainTrack(idRequired = false)
        model.updateSelection(item)
        assertEquals(item, model.state.value.selected)
        model.registerTracking(item)
        advanceUntilIdle()
        trackers.forEach { service ->
            coVerify(exactly = if (service.id == id) 1 else 0) { service.search(any()) }
            coVerify(exactly = if (service.id == id) 1 else 0) { service.register(any(), any()) }
            if (service.id == id) coVerify(exactly = 1) {
                service.search("query")
                service.register(item, 42L)
            }
        }
        assertEquals(snapshot, item.toDomainTrack(idRequired = false))
    }

    @ParameterizedTest(name = "remove tracker={0}")
    @ValueSource(longs = [1L, 2L, 99L])
    fun `remove preserves explicit known deletion and never deletes an absent service`(id: Long) = runTest(dispatcher) {
        val input = track(id)
        val snapshot = input.copy()
        val repository = repository(listOf(input))
        coEvery { repository.delete(any(), any()) } just runs
        val model = assertDoesNotThrow<TrackerRemoveScreen.Model> {
            own(TrackerRemoveScreen.Model(42L, input, id, appScope, DeleteTrack(repository), manager))
        }
        assertEquals(trackers.find { it.id == id }?.name.orEmpty(), model.getName())
        assertEquals(id != 99L, model.isDeletable())
        model.unregisterTracking(id)
        model.deleteMangaFromService()
        advanceUntilIdle()
        coVerify(exactly = if (id == 99L) 0 else 1) { repository.delete(any(), any()) }
        if (id != 99L) coVerify(exactly = 1) { repository.delete(42L, id) }
        trackers.forEach { service ->
            coVerify(exactly = if (service.id == id) 1 else 0) { service.delete(any()) }
            if (service.id == id) coVerify(exactly = 1) { service.delete(input) }
        }
        assertEquals(listOf(input), GetTracks(repository).await(42L))
        assertEquals(snapshot, input)
    }

    private fun repository(stored: List<Track>) = mockk<TrackRepository> {
        coEvery { getTracks() } returns stored
        coEvery { getTracksByMangaId(any()) } answers { stored.filter { it.mangaId == firstArg<Long>() } }
        every { getTracksByMangaIdAsFlow(any()) } answers {
            flowOf(stored.filter { it.mangaId == firstArg<Long>() })
        }
    }

    private fun track(id: Long) = Track(
        id = id + 100L,
        mangaId = 42L,
        trackerId = id,
        remoteId = 10L,
        libraryId = null,
        title = "Preserved title",
        lastChapterRead = 3.0,
        totalChapters = 12L,
        status = 1L,
        score = 8.0,
        remoteUrl = "https://example.invalid/manga/10",
        startDate = null,
        finishDate = null,
        private = false,
    )
}
