plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.olaf.oscanner.core.ocr"
    androidResources {
        // traineddata is already compressed; storing it lets us copy it out quickly.
        noCompress += listOf("traineddata", "onnx")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:imaging"))
    // Apache-2.0: Tesseract 5 + Leptonica, built for Android.
    implementation(libs.tesseract4android)
    // MIT: runs the PP-OCR text models (Apache-2.0, in assets/paddle). Prebuilt AAR for now,
    // like OpenCV; F-Droid will need it built from source (PLAN.md §5.10).
    implementation(libs.onnxruntime.android)
    // Apache-2.0: box finding and line crops around the models.
    implementation(libs.opencv)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
