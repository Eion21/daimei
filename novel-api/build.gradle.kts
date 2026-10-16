plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(mihonx.plugins.spotless)

    id("org.jetbrains.kotlin.plugin.serialization")

    `maven-publish`
}

// The contract version lives in exactly one place: `NOVEL_API_VERSION` in NovelApiVersion.kt. Everything else reads
// it back, so nothing can drift:
//
// - the published artifact version below is parsed out of that file, so the coordinate an author depends on and the
//   value the loader checks are the same value;
// - the loader's SUPPORTED_NOVEL_API_VERSIONS is pinned to it by NovelApiContractVersionTest in :app.
//
// Parsing rather than duplicating a literal is deliberate: a second copy of "1.0" in this build file would be
// exactly the kind of silent divergence the constant exists to remove.
val novelApiVersion: String = providers.fileContents(
    layout.projectDirectory.file("src/main/kotlin/app/novel/source/api/NovelApiVersion.kt"),
).asText.map { source ->
    Regex("""NOVEL_API_VERSION\s*:\s*Double\s*=\s*([0-9.]+)""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: error("NOVEL_API_VERSION not found in NovelApiVersion.kt")
}.get()

group = "app.novel"
// artifactId defaults to the project name, "novel-api", which is what the task requires.
version = novelApiVersion

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}
