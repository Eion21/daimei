package eu.kanade.tachiyomi.data.backup

import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager

class BackupFileValidatorTest {

    private val uri = mockk<Uri>()
    private val backupDecoder = mockk<BackupDecoder>()
    private val sourceManager = mockk<SourceManager>()
    private val installedSource = mockk<Source>()
    private val betaStub = mockk<Source>()
    private val deltaStub = mockk<Source>()

    init {
        coEvery { sourceManager.get(any<Long>()) } returns null
        coEvery { sourceManager.get(1L) } returns installedSource
        coEvery { sourceManager.getOrStub(4L) } returns betaStub
        coEvery { sourceManager.getOrStub(5L) } returns deltaStub
        every { betaStub.toString() } returns "Beta Stub"
        every { deltaStub.toString() } returns "Delta Stub"
    }

    @Test
    fun `a backup without tracking validates sources without resolving TrackerManager`() = runTest {
        val trackerManager = lazy<TrackerManager> {
            error("Should not resolve TrackerManager for backup without tracking")
        }
        every { backupDecoder.decode(uri) } returns backup()

        val result = validator(trackerManager).validate(uri)

        assertEquals(
            BackupFileValidator.Results(
                missingSources = listOf("Alpha Source", "Beta Stub", "Delta Stub", "Zulu Source"),
                missingTrackers = emptyList(),
            ),
            result,
        )
        assertFalse(trackerManager.isInitialized())
        verify(exactly = 1) { backupDecoder.decode(uri) }
    }

    @Test
    fun `tracking IDs resolve TrackerManager once and preserve sorted missing trackers and sources`() = runTest {
        val loggedIn = mockk<BaseTracker> {
            every { isLoggedIn } returns true
            every { name } returns "Logged In Tracker"
        }
        val alpha = mockk<BaseTracker> {
            every { isLoggedIn } returns false
            every { name } returns "Alpha Tracker"
        }
        val zulu = mockk<BaseTracker> {
            every { isLoggedIn } returns false
            every { name } returns "Zulu Tracker"
        }
        val manager = mockk<TrackerManager> {
            every { get(10L) } returns loggedIn
            every { get(20L) } returns alpha
            every { get(40L) } returns zulu
            every { get(999L) } returns null
        }
        var resolutions = 0
        val trackerManager = lazy {
            resolutions++
            manager
        }
        val backup = backup().apply {
            backupManga[0].tracking = listOf(40, 999, 10, 20, 40).map {
                BackupTracking(syncId = it, libraryId = 0L)
            }
            backupManga[1].tracking = listOf(20, 999).map {
                BackupTracking(syncId = it, libraryId = 0L)
            }
        }
        every { backupDecoder.decode(uri) } returns backup

        val result = validator(trackerManager).validate(uri)

        assertEquals(
            BackupFileValidator.Results(
                missingSources = listOf("Alpha Source", "Beta Stub", "Delta Stub", "Zulu Source"),
                missingTrackers = listOf("Alpha Tracker", "Zulu Tracker"),
            ),
            result,
        )
        assertEquals(1, resolutions)
        verify(exactly = 1) { manager.get(10L) }
        verify(exactly = 1) { manager.get(20L) }
        verify(exactly = 1) { manager.get(40L) }
        verify(exactly = 1) { manager.get(999L) }
        verify(exactly = 4) { manager.get(any()) }
        verify(exactly = 1) { backupDecoder.decode(uri) }
    }

    private fun backup() = Backup(
        backupManga = listOf(
            BackupManga(source = 1L, url = "/sample-one", title = "Sample One"),
            BackupManga(source = 2L, url = "/sample-two", title = "Sample Two"),
        ),
        backupSources = listOf(
            BackupSource(name = "Installed Source", sourceId = 1L),
            BackupSource(name = "Zulu Source", sourceId = 2L),
            BackupSource(name = "Alpha Source", sourceId = 3L),
            BackupSource(name = " ", sourceId = 4L),
            BackupSource(name = "5", sourceId = 5L),
            BackupSource(name = "Alpha Source", sourceId = 6L),
        ),
    )

    // Passing the Lazy itself keeps tracker construction behind validation of actual tracking IDs.
    private fun validator(trackerManager: Lazy<TrackerManager>) = BackupFileValidator(
        sourceManager = sourceManager,
        trackerManager = trackerManager,
        backupDecoder = backupDecoder,
    )
}
