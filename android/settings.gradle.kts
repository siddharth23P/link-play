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
rootProject.name = "LinkPlay"

// The desktop app (macOS .dmg, Windows .exe) lives in ../desktop and shares the phone's link-play code.
// Desktop-only builds (CI without an Android SDK) pass -PdesktopOnly=true.
val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"
if (!desktopOnly) include(":app")
include(":desktop")
project(":desktop").projectDir = file("../desktop")
