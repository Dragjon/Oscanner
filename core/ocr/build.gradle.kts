plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.olaf.oscanner.core.ocr"
    androidResources {
        // traineddata is already compressed; storing it lets us copy it out quickly.
        noCompress += "traineddata"
    }
}

dependencies {
    // Apache-2.0: Tesseract 5 + Leptonica, built for Android.
    implementation(libs.tesseract4android)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
