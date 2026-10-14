plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.compose)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "app.novel.reader.compose"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    // The reader surface exposes reader-core types and ChapterContent in its public signatures.
    api(projects.readerCore)
    api(projects.novelApi)

    // Compose BOM and tooling preview come from `mihonx.plugins.compose`.
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)

    // Coil 3, for images embedded in a chapter.
    implementation(libs.bundles.coil)

    implementation(libs.bundles.kotlinx.coroutines)
}
