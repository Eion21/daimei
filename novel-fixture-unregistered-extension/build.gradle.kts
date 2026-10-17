// Test fixture apk, NOT a real extension.
//
// It is a novel extension built against contract 1.3 (ContextAwareNovelSource) that is never installed with the
// package manager. `:app`'s androidTest packages its apk as an asset and hands it to the loader through the
// private `.ext` path, which is exactly the case that must be refused with ContextUnavailable instead of being
// loaded without a context.
plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "app.novel.extension.fixture.unregistered"

    defaultConfig {
        applicationId = "app.novel.extension.fixture.unregistered"
        versionCode = 1
        versionName = "1.0.0"
    }
}

// The host is a Kotlin application: it already ships the standard library, and DelegateLastClassLoaderCompat
// resolves a class from the APK's own dex before falling back to the host. A bundled kotlin.coroutines.Continuation
// would shadow the host's and break every `suspend` member with a LinkageError. The standard library and
// kotlinx-coroutines are compile-time only here for that reason, exactly as in the real extensions.
configurations.configureEach {
    if (isCanBeResolved && name.endsWith("RuntimeClasspath") && !name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }
}

dependencies {
    // compileOnly: the host owns these classes at runtime. A packaged copy would be a different
    // NovelSource/ContextAwareNovelSource class, so `is NovelSource` and `is ContextAwareNovelSource` would both
    // miss the loaded instance. The second dependency is the Android-only context interface.
    compileOnly(projects.novelApi)
    compileOnly(projects.novelApiAndroid)
}
