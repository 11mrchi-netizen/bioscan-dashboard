pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Home Terminal (:tv): the Google Home APIs SDK is not on Google's Maven. It is downloaded after signing in to
        // Google Home Developers and hosted locally, either unpacked into ~/.m2 or into android-app/home-sdk/ (ignored by
        // git: it is Google's to distribute, not ours). Only its two modules are looked for there.
        // See docs/home-environment/README.md.
        for (repo in listOf(mavenLocal(), maven { url = uri("home-sdk") })) {
            repo.content {
                includeModule("com.google.android.gms", "play-services-home")
                includeModule("com.google.android.gms", "play-services-home-types")
            }
        }
    }
}

rootProject.name = "Field Terminal"
include(":app")
// Home Terminal: the Google TV Streamer app that collects the home's environment (docs/home-environment/README.md).
include(":tv")
