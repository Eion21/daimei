plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "app.novel.extension.matchanovels"

    defaultConfig {
        applicationId = "app.novel.extension.matchanovels"
        versionCode = 2
        versionName = "1.0.1"
    }
}

// The host is a Kotlin application: it already ships the standard library, and DelegateLastClassLoaderCompat
// resolves a class from the APK's own dex *before* falling back to the host. A bundled copy of
// kotlin.coroutines.Continuation therefore shadows the host's, and every `suspend` member of NovelSource then fails
// to link with `LinkageError: ... resolves differently in interface`, naming two different Continuation classes.
// The standard library is compile-time only here for exactly the same reason :novel-api is.
//
// The exclusion is scoped to the app RuntimeClasspath — the one the APK is packaged from — and NOT to the
// `*UnitTestRuntimeClasspath`. The fixture has no tests so its blanket rule never had to say so; this module does.
// A Kotlin test class cannot even be *discovered* without the standard library (JUnit resolves
// `kotlin.coroutines.Continuation` in a `suspend` signature and dies with NoClassDefFoundError), so excluding from
// the test runtime would make the required `testDebugUnitTest` un-runnable. No test configuration feeds the APK, so
// keeping the standard library there does not violate D7: the dexdump packaging proof is still zero.
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

    // The extension owns its own HTML parsing, so it bundles Jsoup. Its types never cross the
    // NovelSource signature — the host network hands back a plain String — so bundling it does not
    // shadow anything the host provides (decision D6).
    implementation(libs.jsoup)

    // Jsoup publishes its nullability as org.jspecify annotations but declares that artifact
    // "build time only", so every consumer has to supply it or the Kotlin compiler rejects the
    // inferred types with "type annotation class is inaccessible". :app never noticed because
    // androidx.appcompat drags jspecify in transitively; an extension APK has to declare it.
    // Annotations only: nothing is packaged.
    compileOnly("org.jspecify:jspecify:1.0.0")

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
