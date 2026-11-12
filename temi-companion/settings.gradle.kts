// The MapLens companion app for the temi robot: a separate build, installed on temi only.
// Not part of the phone app's build (see ../settings.gradle.kts), so the team's CI is unchanged.
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

rootProject.name = "maplens-temi-companion"
include(":app")
