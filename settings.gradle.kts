pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only Tesseract4Android comes from JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.adaptech-cz.Tesseract4Android") }
        }
    }
}

rootProject.name = "Oscanner"

include(":app")
include(":core:design")
include(":core:model")
include(":core:data")
include(":core:imaging")
include(":core:pdf")
include(":core:ocr")
include(":core:translate")
include(":feature:library")
include(":feature:settings")
include(":feature:capture")
include(":feature:review")
include(":feature:document")
include(":feature:text")
