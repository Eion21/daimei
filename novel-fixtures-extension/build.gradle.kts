plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "app.novel.extension.fixtures"

    defaultConfig {
        applicationId = "app.novel.extension.fixtures"
        versionCode = 1
        versionName = "1.0.0"
    }
}

// The host is a Kotlin application: it already ships the standard library, and DelegateLastClassLoaderCompat
// resolves a class from the APK's own dex *before* falling back to the host. A bundled copy of
// kotlin.coroutines.Continuation therefore shadows the host's, and every `suspend` member of NovelSource then fails
// to link with `LinkageError: ... resolves differently in interface`, naming two different Continuation classes.
// The standard library is compile-time only here for exactly the same reason :novel-api is.
configurations.configureEach {
    if (isCanBeResolved && name.endsWith("RuntimeClasspath")) {
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
}
