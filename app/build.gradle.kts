import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.BuildConfigField
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import mihon.gradle.Config
import mihon.gradle.getCurrentTime
import mihon.gradle.getLatestCommitCount
import mihon.gradle.getLatestCommitSha
import mihon.gradle.getLatestCommitTime
import mihon.gradle.tasks.ReplaceShortcutsPlaceholderTask
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.compose)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.metro)
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.androidx.baselineProfile)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.apollo)
}

if (Config.includeTelemetry) {
    pluginManager.apply {
        apply(libs.plugins.google.services.get().pluginId)
        apply(libs.plugins.firebase.crashlytics.get().pluginId)
    }
}

val keystorePropertiesFile = layout.settingsDirectory.file("keystore.properties").asFile

/**
 * Stages the fixture module's apk as `<package>.ext` for the androidTest assets.
 *
 * It has an `@OutputDirectory` because it is wired through the Variant API's `addGeneratedSourceDirectory`, which
 * needs a `DirectoryProperty` to hand to AGP. `fixtureApk` is derived from the settings directory rather than from
 * a cross-project model: with Isolated Projects on, `project(":novel-fixture-unregistered-extension")` at
 * configuration time is rejected, and the `dependsOn` on the producing task's path string is what still orders the
 * build (AGP's SourceSet API cannot take a Provider, so the task dependency cannot be carried by `assets.srcDir`).
 */
abstract class CopyFixtureExtensionExt : DefaultTask() {
    @get:InputFile
    abstract val fixtureApk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun action() {
        outputDir.get().file("app.novel.extension.fixture.unregistered.ext").asFile.apply {
            parentFile.mkdirs()
            fixtureApk.get().asFile.copyTo(this, overwrite = true)
        }
    }
}

val copyFixtureExtensionExt = tasks.register<CopyFixtureExtensionExt>("copyFixtureExtensionExt") {
    dependsOn(":novel-fixture-unregistered-extension:assembleDebug")

    fixtureApk.set(
        layout.settingsDirectory.file(
            "novel-fixture-unregistered-extension/build/outputs/apk/debug/" +
                "novel-fixture-unregistered-extension-debug.apk",
        ),
    )

    outputDir.set(layout.buildDirectory.dir("fixture-extension-assets"))
}

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId = "app.daimei"

        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("boolean", "TELEMETRY_INCLUDED", "${Config.includeTelemetry}")
        buildConfigField("boolean", "UPDATER_ENABLED", "${Config.enableUpdater}")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Each instrumented test runs in its own process (see testOptions.execution below) with the app's data
        // cleared first: the context proof and the PdfBox proof cannot share a process, because PdfBox's static
        // resource-loader state is process-wide and a poisoned initializer would take the other test down with it.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    if (System.getenv("MIHON_GITHUB_RELEASE").toBoolean()) {
        signingConfigs {
            named("debug") {
                storeFile = file(System.getenv("storeFile"))
                storePassword = System.getenv("storePassword")
                keyAlias = System.getenv("keyAlias")
                keyPassword = System.getenv("keyPassword")
            }
        }
    } else if (keystorePropertiesFile.exists()) {
        val keystoreProperties = FileInputStream(keystorePropertiesFile).use { Properties().apply { load(it) } }

        signingConfigs {
            named("debug") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val debug = getByName("debug") {
            applicationIdSuffix = ".dev"
            isPseudoLocalesEnabled = true
        }
        val release = getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true

            signingConfig = debug.signingConfig

            isProfileable = true

            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")
        }

        val commonMatchingFallbacks = listOf(release.name)

        create("foss") {
            initWith(release)

            applicationIdSuffix = ".foss"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("nightly") {
            initWith(release)

            applicationIdSuffix = ".debug"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("benchmark") {
            initWith(release)

            versionNameSuffix = "-benchmark"
            applicationIdSuffix = ".benchmark"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }

        if (Config.includeTelemetry) {
            configureEach {
                configure<CrashlyticsExtension> {
                    mappingFileUploadEnabled = Config.uploadCrashlyticsMapping
                }
            }
        }
    }

    sourceSets {
        getByName("nightly").res.directories.add("src/debug/res")
        getByName("benchmark").res.directories.add("src/debug/res")
    }

    testOptions {
        // Run every instrumented test in its own process. PdfBox's static state is process-wide, so a single
        // process cannot run the negative (context) test and the positive (PdfBox) test safely.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = Config.includeDependencyInfo
        includeInBundle = Config.includeDependencyInfo
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

baselineProfile {
    baselineProfileOutputDir = "baselineProfiles"
    mergeIntoMain = true
}

dependencies {
    baselineProfile(projects.baselineProfile)

    implementation(projects.i18n)
    implementation(projects.icons.customMaterialSymbols)
    implementation(projects.icons.materialSymbols)
    implementation(projects.icons.simpleIcons)
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.core.metro)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.novelApi)
    implementation(projects.novelApiAndroid)
    implementation(projects.novelRuntime)
    implementation(projects.novelDemoSource)
    implementation(projects.novelHttpSource)
    implementation(projects.readerCore)
    implementation(projects.readerCompose)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)
    implementation(projects.telemetry)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.animationGraphics)
    debugImplementation(libs.androidx.compose.uiTooling)
    implementation(libs.androidx.compose.uiToolingPreview)
    implementation(libs.androidx.compose.uiUtil)
    implementation(libs.androidx.navigationEvent)

    implementation(libs.androidx.interpolator)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.kotlin.reflect)

    implementation(libs.bundles.kotlinx.coroutines)

    implementation(libs.kotlinx.datetime)

    // AndroidX libraries
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.constraintLayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.coreSplashScreen)
    implementation(libs.androidx.recyclerView)
    implementation(libs.androidx.viewPager)
    implementation(libs.androidx.profileInstaller)

    implementation(libs.bundles.androidx.lifecycle)

    // Job scheduling
    implementation(libs.androidx.work)

    // RxJava
    implementation(libs.rxJava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(libs.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.diskLruCache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.androidx.preference)

    // Dependency injection
    implementation(libs.injekt)
    implementation(libs.metro.runtime)
    implementation(libs.metrox.viewmodel)
    implementation(libs.metrox.viewmodel.compose)

    // Image loading
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingScaleImageView) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexibleAdapter)
    implementation(libs.photoView)
    implementation(libs.directionalViewPager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.composeRichEditor)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.composeMaterialMotion)
    implementation(libs.swipe)
    implementation(libs.composeWebview)
    implementation(libs.composeGrid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // Logging
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // String similarity
    implementation(libs.stringSimilarity)

    // GraphQL generation
    implementation(libs.apollo)
    implementation(libs.apollo.adapters)

    // Tests
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Instrumented tests: the contract-1.3 context proof. They run on a device, because createPackageContext,
    // PackageManager and PdfBox are all Android-only and cannot be exercised on a plain JVM.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // runBlocking drives the loader's suspend entry point from the test.
    androidTestImplementation(libs.kotlinx.coroutines.core)
    // The positive test extracts text out of a bundled PDF, so PdfBox and its glyph-table assets ship in the
    // test apk (not in :app: the host must not bundle PdfBox on behalf of extensions).
    androidTestImplementation("com.tom-roush:pdfbox-android:2.0.27.0")
    androidTestUtil(libs.androidx.test.orchestrator)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakCanary.android)
    implementation(libs.leakCanary.plumber)

    testImplementation(libs.kotlinx.coroutines.test)
}

apollo {
    val schemaBasePath = "src/main/graphql/mihon/graphql"
    service("anilist") {
        packageName.set("mihon.graphql.anilist")
        val srcDir = "$schemaBasePath/anilist"
        srcDir(file(srcDir))

        introspection {
            endpointUrl.set("https://graphql.anilist.co")
            schemaFile.set(file("$srcDir/anilist.graphqls"))
        }

        // ISO 3166-1 alpha-2 country code
        mapScalarToKotlinString("CountryCode")
    }

    service("kitsu") {
        packageName.set("mihon.graphql.kitsu")
        val srcDir = "$schemaBasePath/kitsu"
        srcDir(file(srcDir))
        android.defaultConfig.versionCode

        introspection {
            // Kitsu doesn't like requests without a UA
            headers.put(
                "User-Agent",
                "Mihon v${android.defaultConfig.versionName} (${android.defaultConfig.applicationId})",
            )
            endpointUrl.set("https://kitsu.app/api/graphql")
            schemaFile.set(file("$srcDir/kitsu.graphqls"))
        }

        // A date, expressed as an ISO8601 string
        mapScalarToKotlinString("Date")
        // An ISO 8601-encoded datetime
        mapScalar("ISO8601DateTime", "kotlin.time.Instant", "com.apollographql.adapter.core.KotlinInstantAdapter")
        // A loose key-value map in GraphQL
        mapScalar(
            "Map",
            "kotlin.collections.Map<String, Any?>",
            "mihon.graphql.kitsu.adapter.KitsuMapAdapter",
        )
    }

    service("shikimori") {
        packageName.set("mihon.graphql.shikimori")
        val srcDir = "$schemaBasePath/shikimori"
        srcDir(file(srcDir))

        introspection {
            endpointUrl.set("https://shikimori.io/api/graphql")
            schemaFile.set(file("$srcDir/shikimori.graphqls"))
        }

        // An ISO 8601-encoded date
        mapScalarToKotlinString("ISO8601Date")
    }

    service("suwayomi") {
        packageName.set("mihon.graphql.suwayomi")
        val srcDir = "$schemaBasePath/suwayomi"
        srcDir(file(srcDir))

        introspection {
            endpointUrl.set("http://localhost:4567/api/graphql")
            schemaFile.set(file("$srcDir/suwayomi.graphqls"))
        }
    }
}

val latestCommitCount = getLatestCommitCount()
val latestCommitSha = getLatestCommitSha()
val latestCommitTime = getLatestCommitTime()
val currentTime = getCurrentTime()

fun ApplicationVariant.buildConfigField(type: String, name: String, value: Provider<String>) {
    buildConfigFields?.put(name, value.map { BuildConfigField(type, it, null) })
}

androidComponents {
    onVariants { variant ->
        val isUnstableBuild = variant.buildType == "debug" || variant.buildType == "nightly"
        val buildTime = if (isUnstableBuild) currentTime else latestCommitTime

        variant.buildConfigField("String", "COMMIT_COUNT", latestCommitCount.map { "\"$it\"" })
        variant.buildConfigField("String", "COMMIT_SHA", latestCommitSha.map { "\"$it\"" })
        variant.buildConfigField("String", "BUILD_TIME", buildTime.map { "\"$it\"" })

        if (isUnstableBuild) {
            variant.outputs.forEach { output ->
                val versionName = output.versionName.get()
                output.versionName.set(latestCommitCount.map { "$versionName-$it" })
            }
        }
    }

    onVariants { variant ->
        val resSource = variant.sources.res ?: return@onVariants

        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val replaceShortcutsPlaceholderTask = tasks.register<ReplaceShortcutsPlaceholderTask>(
            "replace${variantName}ShortcutPlaceholder",
        ) {
            applicationId.set(variant.applicationId)
            shortcutsFile.set(projectDir.resolve("src/main/shortcuts.xml"))
        }
        resSource.addGeneratedSourceDirectory(replaceShortcutsPlaceholderTask) { it.outputDir }
    }

    onVariants { variant ->
        // Adds the unregistered fixture apk (as `*.ext`) to this variant's androidTest assets. The Variant API is
        // the only supported route: `assets.srcDir(<provider>)` on the SourceSet API is rejected outright by AGP.
        // It goes on the androidTest component, never on the host app's own assets.
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(copyFixtureExtensionExt) { it.outputDir }
    }

    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}
