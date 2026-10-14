plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

android {
    namespace = "tachiyomi.domain"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.novelApi)
    implementation(projects.core.common)
    implementation(projects.core.metro)

    // `api` and not `implementation` because `:novel-runtime` types are genuinely part of this module's public
    // surface: `GetChapterContent` takes a `SourceRegistry` in its constructor, so Metro has to be able to resolve
    // it from `:app`, and `:data` needs the same module for `NovelMapper` to delegate the source-id derivation
    // instead of keeping a second copy of the hash that could drift.
    api(projects.novelRuntime)

    implementation(libs.metro.runtime)
    implementation(libs.bundles.kotlinx.coroutines)
    implementation(libs.bundles.serialization)

    implementation(libs.kotlinx.datetime)

    implementation(libs.unifile)

    api(libs.androidx.paging.runtime)

    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtimeAnnotation)

    testImplementation(libs.bundles.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
