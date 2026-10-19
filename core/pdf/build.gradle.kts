plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.olaf.oscanner.core.pdf"
}

dependencies {
    testImplementation(libs.junit)
}
