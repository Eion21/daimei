package eu.kanade.tachiyomi.ui.browse.extension.novel

import eu.kanade.tachiyomi.extension.novel.NovelExecutionConsentOutcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class NovelDistributionExecutionConsentTest {
    private val pkg = "app.novel.extension.fixtures"
    private val version = 7L

    private inner class Fixture {
        val calls = mutableListOf<String>()
        val trusted = mutableSetOf("$pkg:$version") // An unchanged scoped preference after process restart.
        var grant = NovelExecutionConsentOutcome.GRANTED
        var trustSucceeds = true
        var recognized = true
        val controller = NovelDistributionExecutionConsent(
            grant = { op, name, code ->
                calls += "grant:$op:$name:$code"
                grant
            },
            trust = { name, code ->
                calls += "trust:$name:$code"
                if (trustSucceeds) trusted.add("$name:$code")
                trustSucceeds
            },
            reload = { name, code ->
                calls += "reload:$name:$code"
                recognized
            },
        )
    }

    @Test fun `repeated explicit consent reloads even when scoped trust preference is unchanged`() = runBlocking {
        val f = Fixture()
        val before = f.trusted.toSet()
        repeat(2) {
            assertEquals(
                NovelDistributionExecutionConsent.Result.RECOGNIZED,
                f.controller.authorize("op1", pkg, version, true),
            )
        }
        assertEquals(before, f.trusted)
        assertEquals(
            listOf(
                "grant:op1:$pkg:$version", "trust:$pkg:$version", "reload:$pkg:$version",
                "grant:op1:$pkg:$version", "trust:$pkg:$version", "reload:$pkg:$version",
            ),
            f.calls,
        )
    }

    @Test fun `without explicit consent no grant trust or reload occurs`() = runBlocking {
        val f = Fixture()
        val before = f.trusted.toSet()
        assertEquals(NovelDistributionExecutionConsent.Result.DENIED, f.controller.authorize("op1", pkg, version, false))
        assertEquals(before, f.trusted)
        assertEquals(emptyList<String>(), f.calls)
    }

    @Test fun `coordinator denial does not trust or reload`() = runBlocking {
        val f = Fixture()
        f.grant = NovelExecutionConsentOutcome.DENIED
        val before = f.trusted.toSet()
        assertEquals(NovelDistributionExecutionConsent.Result.DENIED, f.controller.authorize("op1", pkg, version, true))
        assertEquals(before, f.trusted)
        assertEquals(listOf("grant:op1:$pkg:$version"), f.calls)
    }

    @Test fun `stale scoped trust prevents reload even after grant`() = runBlocking {
        val f = Fixture()
        f.trustSucceeds = false
        val before = f.trusted.toSet()
        assertEquals(NovelDistributionExecutionConsent.Result.DENIED, f.controller.authorize("op1", pkg, version, true))
        assertEquals(before, f.trusted)
        assertEquals(listOf("grant:op1:$pkg:$version", "trust:$pkg:$version"), f.calls)
    }

    @Test fun `grant and trust do not imply actual source recognition`() = runBlocking {
        val f = Fixture()
        f.recognized = false
        assertEquals(NovelDistributionExecutionConsent.Result.UNRECOGNIZED, f.controller.authorize("op1", pkg, version, true))
        assertEquals(listOf("grant:op1:$pkg:$version", "trust:$pkg:$version", "reload:$pkg:$version"), f.calls)
    }
}
