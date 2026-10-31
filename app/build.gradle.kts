plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.olaf.oscanner"
    defaultConfig {
        applicationId = "dev.olaf.oscanner"
        versionCode = 34
        versionName = "0.8.9"
    }
    applicationVariants.all {
        // Static shortcuts need the real package name, which differs for debug builds.
        resValue("string", "application_id", applicationId)
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        // F-Droid rejects the encrypted dependency blob Google adds to APKs.
        includeInApk = false
        includeInBundle = false
    }
    // OpenCV's prebuilt native library is ~15–55 MB per CPU type. One APK per ABI, with the
    // library compressed, keeps each download near the 25 MB budget (PLAN.md §5.11) until we
    // build OpenCV from source with only core + imgproc.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":feature:library"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:review"))
    implementation(project(":feature:document"))
    implementation(project(":feature:text"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    // Apache-2.0; uses the platform's biometric and screen-lock prompt, no Play services.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
