import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    `maven-publish`
}

// The contract version lives in exactly one place, `NOVEL_API_VERSION` in NovelApiVersion.kt, and this module reads
// it back with the same mechanism `:novel-api` uses, so the coordinate an author depends on and the value the loader
// checks are the same value. The regex below mirrors `novel-api/build.gradle.kts` on purpose: a literal version here
// would be exactly the second copy the constant exists to remove.
//
// The file is resolved through `layout.settingsDirectory` rather than `project(":novel-api")` because this build runs
// with Isolated Projects enabled, which rejects any cross-project model access at configuration time.
val novelApiVersion: String = providers.fileContents(
    layout.settingsDirectory.file("novel-api/src/main/kotlin/app/novel/source/api/NovelApiVersion.kt"),
).asText.map { source ->
    Regex("""NOVEL_API_VERSION\s*:\s*Double\s*=\s*([0-9.]+)""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: error("NOVEL_API_VERSION not found in NovelApiVersion.kt")
}.get()

group = "app.novel"
// artifactId defaults to the project name, "novel-api-android".
version = novelApiVersion

// The published coordinate is surfaced as a generated constant so `:app` can assert that this artifact and the JVM
// `:novel-api` artifact were published at the same contract version (`NovelApiContractVersionTest`). It is derived
// from `project.version` itself, not from `novelApiVersion`, so a hardcoded version in this build file would show up
// as drift instead of hiding behind the parse.
val publishedVersion: String = version.toString()

android {
    namespace = "app.novel.source.api.android"

    buildFeatures {
        // Off by default for libraries; the drift guard below needs the generated BuildConfig.
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("double", "NOVEL_API_ANDROID_PUBLISHED_VERSION", publishedVersion)
    }

    // AGP does not publish `components["java"]` for an Android library. The published component has to be declared
    // explicitly, one variant at a time.
    publishing {
        singleVariant("release")
    }
}

// The same pin as `:novel-api`, for the same reason: the metadata version written into the published AAR's classes
// follows the language version, and a consumer whose Kotlin is older than the one that builds this module cannot
// read it. AGP 9.4.1's built-in Kotlin is 2.2.0, so pinning to 2.2 is what keeps a do-nothing consumer compiling.
// ContextAwareNovelSourceKotlinMetadataTest fails if the published metadata ever climbs above 2.2 again.
kotlin {
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    // compileOnly, never api/implementation: the host supplies `NovelSource` at runtime through its own ClassLoader.
    // Bundling `:novel-api` into this artifact would put a second copy of `NovelSource` on a consumer's runtime
    // classpath and break `is NovelSource` in the host.
    compileOnly(projects.novelApi)

    // Tests only. The metadata check loads `ContextAwareNovelSource` by reflection, and a class cannot be loaded
    // without its supertype: `:novel-api` is `compileOnly` for production and therefore absent from the runtime
    // classpath, so the test runtime needs it explicitly. `libs.bundles.test` is the same JUnit 5 setup the other
    // Android modules in this build use (`android-base` calls `configureTest`, which switches every Test task to
    // `useJUnitPlatform()`).
    testImplementation(projects.novelApi)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

publishing {
    publications {
        // Named "maven" to mirror `:novel-api`, which makes the task `generatePomFileForMavenPublication`.
        create<MavenPublication>("maven") {
            // AGP creates the software component lazily during afterEvaluate, so selecting it has to happen there
            // too. Source: https://developer.android.com/build/publish-library/upload-library
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
