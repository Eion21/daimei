plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "app.novel.extension.skynovels"

    defaultConfig {
        applicationId = "app.novel.extension.skynovels"
        versionCode = 3
        versionName = "1.0.2"
    }
}

// The host is a Kotlin application: it already ships the standard library, and DelegateLastClassLoaderCompat
// resolves a class from the APK's own dex *before* falling back to the host. A bundled copy of
// kotlin.coroutines.Continuation therefore shadows the host's, and every `suspend` member of NovelSource then fails
// to link with `LinkageError: ... resolves differently in interface`, naming two different Continuation classes.
// The standard library is compile-time only here for exactly the same reason :novel-api is.
//
// The exclusion is scoped to the app RuntimeClasspath — the one the APK is packaged from — and NOT to the
// `*UnitTestRuntimeClasspath`. A Kotlin test class cannot even be *discovered* without the standard library (JUnit
// resolves `kotlin.coroutines.Continuation` in a `suspend` signature and dies with NoClassDefFoundError), so
// excluding from the test runtime would make the required `testDebugUnitTest` un-runnable. No test configuration
// feeds the APK, so keeping the standard library there does not package anything the host owns: the extension APK
// still ships no stdlib and no coroutines.
configurations.configureEach {
    if (isCanBeResolved && name.endsWith("RuntimeClasspath") && !name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }
}

dependencies {
    // The host provides the :novel-api classes at runtime. Declaring the
    // dependency as compileOnly keeps them out of the APK on purpose: if the
    // APK bundled its own copy of app.novel.source.api.NovelSource, the host
    // ClassLoader would see two distinct NovelSource classes and `is
    // NovelSource` would fail on the loaded instance.
    compileOnly(projects.novelApi)

    // This extension bundles nothing else. SkyNovels serves JSON from its own REST API, and the JSON
    // reader is hand-written (SkyNovelsJson), so no JSON library is added and no HTML library is
    // needed either: the chapter text is markdown-first, and the HTML variant is normalised by the
    // same small hand-written pass. That keeps the APK free of anything the host might own.

    // The tests exercise the real contract types, so they need :novel-api at compile *and* run
    // time. This is a test-only dependency: it never reaches the extension RuntimeClasspath the
    // APK is built from, so the host still owns NovelSource at load time.
    testImplementation(projects.novelApi)

    // JUnit Jupiter, declared explicitly: this fork runs tests on the JUnit platform (see
    // `configureTest` in the build logic), and the bare `kotlin("test")` artifact autodetects
    // JUnit 4, which the platform runner will not pick up.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // The source methods are `suspend`, so the tests drive them through `runBlocking`.
    testImplementation(libs.kotlinx.coroutines.core)
}
