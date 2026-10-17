plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "app.novel.extension.jabrascan"

    defaultConfig {
        applicationId = "app.novel.extension.jabrascan"
        versionCode = 1
        versionName = "1.0.0"
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

    // Coroutines are compileOnly for the same reason :novel-api is. The host is a Kotlin application
    // and already ships kotlinx-coroutines at runtime, so the extension only needs the types to
    // compile: T5 wraps the CPU-heavy PdfBox work in `withContext(Dispatchers.IO)`, while
    // GetChapterContent calls this source with no offload of its own. Packaging coroutines would ship
    // a second copy of kotlinx.coroutines.Continuation and break every `suspend` member with a
    // LinkageError, exactly as a bundled stdlib would. The runtime classpath excludes
    // kotlinx-coroutines-core anyway (see above), and the guard is what keeps the packaging proof
    // holding even if this ever drifts to `implementation`.
    compileOnly(libs.kotlinx.coroutines.core)

    // JabraScan's chapter bodies are PDFs, so the extension has to turn text out of them on the
    // device. PdfBox-Android is a port of Apache PDFBox 2.0.x and is the extractor the spike measured:
    // correct accents through CID/Identity-H fonts with a ToUnicode CMap. None of its types cross the
    // NovelSource signature — the host hands back a plain ByteArray — so bundling it shadows nothing
    // the host provides, and being a Java library it drags in no Kotlin runtime.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // This extension bundles nothing else. The catalogue (obras.xml), the index (capitulos.json) and
    // the chapter lists (books/{clave}.json) are small hand-written formats, and T4 parses them by
    // hand, so no JSON or HTML library is added.

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
