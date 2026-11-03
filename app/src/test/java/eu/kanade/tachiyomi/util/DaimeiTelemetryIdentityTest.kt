package eu.kanade.tachiyomi.util

import eu.kanade.tachiyomi.BuildConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DaimeiTelemetryIdentityTest {
    @Test
    fun `telemetry remains disabled in the compiled app`() {
        assertFalse(BuildConfig.TELEMETRY_INCLUDED)
    }
}
