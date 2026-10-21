plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.olaf.oscanner.core.imaging"
}

dependencies {
    api(project(":core:model"))
    // Apache-2.0. Pulled as the official prebuilt AAR for now; F-Droid will need it built from source (PLAN.md §5.10).
    api(libs.opencv)
    implementation(libs.androidx.exifinterface)
    // MIT: runs the page finder (assets/detect).
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
    testImplementation("org.openpnp:opencv:4.9.0-0")
}
