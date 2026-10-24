plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.olaf.oscanner.core.translate"
    androidResources {
        // Already zstd-compressed.
        noCompress += listOf("zst")
    }
}

dependencies {
    // Apache-2.0: serves the engine and the downloaded models to the WebView from the app itself.
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
