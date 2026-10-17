package app.novel.source.api.android

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the Kotlin metadata version of the *published* `app.novel:novel-api-android` AAR.
 *
 * `kotlin.Metadata` has runtime retention, so the `mv` value read off `ContextAwareNovelSource` here is the same
 * number written into the classes that ship inside the AAR's `classes.jar` — this is not a proxy for the real thing.
 *
 * Why it exists: this module pins `languageVersion`/`apiVersion` to 2.2 in `build.gradle.kts` precisely so the
 * published metadata stays readable by a consumer whose Kotlin is older than the one that builds it here (AGP
 * 9.4.1's built-in Kotlin is 2.2.0). A Kotlin plugin upgrade silently raises the metadata version unless that pin is
 * kept, and the failure lands on third parties, not on this build: they get
 *
 *     binary version of its metadata is 2.4.0, expected version is 2.2.0
 *
 * and cannot compile at all. This test turns that silent drift into a red build here.
 *
 * The check is per module rather than one test over both artifacts: `:novel-api-android` is an AAR and `:novel-api`
 * is a plain-JVM jar, so no single module's test classpath holds both as shipped artifacts, and reading the other
 * one from `~/.m2` would make the check inert on any normal CI run that never publishes. This test reads the class
 * this module actually compiles and ships.
 */
class ContextAwareNovelSourceKotlinMetadataTest {
    @Test
    fun `published metadata version stays at or below the pinned 2_2`() {
        assertMetadataAtMostPinned(ContextAwareNovelSource::class.java)
    }
}

private fun assertMetadataAtMostPinned(type: Class<*>) {
    val metadata = requireNotNull(type.getAnnotation(Metadata::class.java)) {
        "${type.name} carries no kotlin.Metadata annotation, so it is not a Kotlin class"
    }
    // `metadataVersion` is the Kotlin-visible name of the annotation element whose JVM name is `mv` — the exact
    // number the Kotlin compiler reads out of a shipped class file.
    val mv = metadata.metadataVersion
    val version = mv.joinToString(".") { it.toString() }

    assertTrue(
        mv[0] < 2 || (mv[0] == 2 && mv[1] <= 2),
        "${type.name} ships kotlin.Metadata mv=$version, above the pinned 2.2.0. A consumer compiled with an " +
            "older Kotlin then fails with \"binary version of its metadata is $version, expected version is " +
            "2.2.0\". Keep the languageVersion/apiVersion pin in build.gradle.kts, or raise the contract's " +
            "documented minimum Kotlin instead.",
    )
}
