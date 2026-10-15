plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(mihonx.plugins.spotless)
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(projects.novelApi)

    implementation(libs.okhttp.core)
    implementation(libs.jsoup)

    // Jsoup publishes its nullability as org.jspecify annotations but declares that artifact
    // "build time only", so every consumer has to supply it or the Kotlin compiler rejects the
    // inferred types with "type annotation class is inaccessible". :app never had to think about
    // it because androidx.appcompat drags jspecify in transitively; this module has to say so.
    // Annotations only: nothing is packaged, and 1.0.0 is what the rest of the build resolves.
    compileOnly("org.jspecify:jspecify:1.0.0")

    // HttpNovelSource reaches OkHttp through Dispatchers.IO. Mihon's Source api runs on the main
    // dispatcher, so without this the first fetch on a device is a NetworkOnMainThreadException.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
}
