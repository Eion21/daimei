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

dependencies {
    // compileOnly, never api/implementation: the host supplies `NovelSource` at runtime through its own ClassLoader.
    // Bundling `:novel-api` into this artifact would put a second copy of `NovelSource` on a consumer's runtime
    // classpath and break `is NovelSource` in the host.
    compileOnly(projects.novelApi)
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
