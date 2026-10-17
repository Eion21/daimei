// THROWAWAY SPIKE MODULE.
//
// This module exists only to measure PdfBox-Android's on-device behaviour against the
// jabrascan.net PDFs: text fidelity (accents), extraction throughput, and the dependency's
// on-disk cost. It is NOT production code and must not be wired into :app.
plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "app.novel.spike.pdf"

    defaultConfig {
        // Instrumentation-test infrastructure. This is the first androidTest source set in the
        // repo, so the runner is declared here rather than inherited from a convention plugin.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // The candidate under evaluation. It is a port of Apache PDFBox 2.0.x, so the 2.0 API applies
    // (PDDocument.load, not the 3.x Loader.loadPDF).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// No `configurations.configureEach` runtime-classpath exclusion here on purpose: unlike the
// extension modules, this is a normal Android library and needs its full transitive runtime
// classpath resolved (pdfbox-android pulls in its own support artifacts).
