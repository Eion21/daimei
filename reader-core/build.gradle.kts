plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(mihonx.plugins.spotless)
}

dependencies {
    testImplementation(kotlin("test"))
}
