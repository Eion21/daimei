plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(mihonx.plugins.spotless)

    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}
