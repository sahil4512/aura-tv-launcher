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
rootProject.name = "AuraTV"
include(":app")
// Emulator-only UI-test fixture (see tools/ui-check.sh); not part of the launcher APK.
include(":testfixture")
