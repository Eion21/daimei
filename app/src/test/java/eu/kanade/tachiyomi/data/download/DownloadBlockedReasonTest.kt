package eu.kanade.tachiyomi.data.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadBlockedReasonTest {

    @Test
    fun `offline blocks with no network even when wifi-only is enabled`() {
        // Offline takes precedence over the Wi-Fi only rule.
        downloadBlockReason(isOnline = false, isWifi = false, downloadOnlyOverWifi = true) shouldBe
            DownloadBlockReason.NO_NETWORK
    }

    @Test
    fun `wifi-only blocks when online but not on wifi`() {
        downloadBlockReason(isOnline = true, isWifi = false, downloadOnlyOverWifi = true) shouldBe
            DownloadBlockReason.ONLY_WIFI
    }

    @Test
    fun `wifi-only disabled does not block on non-wifi`() {
        downloadBlockReason(isOnline = true, isWifi = false, downloadOnlyOverWifi = false) shouldBe null
    }

    @Test
    fun `on wifi never blocks`() {
        downloadBlockReason(isOnline = true, isWifi = true, downloadOnlyOverWifi = true) shouldBe null
    }
}
