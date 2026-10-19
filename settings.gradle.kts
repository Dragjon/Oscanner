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
    }
}

rootProject.name = "Oscanner"

include(":app")
include(":core:design")
include(":core:model")
include(":core:data")
include(":core:imaging")
include(":core:pdf")
include(":feature:library")
include(":feature:settings")
include(":feature:capture")
include(":feature:review")
include(":feature:document")
