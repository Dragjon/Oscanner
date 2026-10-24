plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.olaf.oscanner.core.data"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:imaging"))
    implementation(project(":core:pdf"))
    api(project(":core:ocr"))
    api(project(":core:translate"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
