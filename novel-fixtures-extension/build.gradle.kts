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

dependencies {
    // The host provides the :novel-api classes at runtime. Declaring the
    // dependency as compileOnly keeps them out of the APK on purpose: if the
    // APK bundled its own copy of app.novel.source.api.NovelSource, the host
    // ClassLoader would see two distinct NovelSource classes and `is
    // NovelSource` would fail on the loaded instance.
    compileOnly(projects.novelApi)
}
