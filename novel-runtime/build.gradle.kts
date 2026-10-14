plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(mihonx.plugins.spotless)
}

dependencies {
    implementation(projects.novelApi)

    testImplementation(kotlin("test"))
}
