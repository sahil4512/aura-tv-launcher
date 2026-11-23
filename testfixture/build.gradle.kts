// Emulator-only test fixture: 20 tiny packages (one per flavor) with long names and bright/dark
// banners, so Aura's UI tests can exercise 20+ apps. Aura lists one entry per package, hence flavors.
// Never installed on a real device.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.auratv.testfixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.auratv.testfixture"
        minSdk = 22
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    flavorDimensions += "fx"
    productFlavors {
        create("longname") {
            dimension = "fx"
            applicationIdSuffix = ".longname"
            manifestPlaceholders["label"] = "Aurora Streaming Service with an Extremely Long Name"
            manifestPlaceholders["banner"] = "@null"
        }
        create("projectivy") {
            dimension = "fx"
            applicationIdSuffix = ".projectivy"
            manifestPlaceholders["label"] = "Projectivy Launcher Fixture"
            manifestPlaceholders["banner"] = "@null"
        }
        create("bright") {
            dimension = "fx"
            applicationIdSuffix = ".bright"
            manifestPlaceholders["label"] = "Bright White Banner"
            manifestPlaceholders["banner"] = "@drawable/banner_white"
        }
        create("dark") {
            dimension = "fx"
            applicationIdSuffix = ".dark"
            manifestPlaceholders["label"] = "Night Banner"
            manifestPlaceholders["banner"] = "@drawable/banner_dark"
        }
        create("webcaster") {
            dimension = "fx"
            applicationIdSuffix = ".webcaster"
            manifestPlaceholders["label"] = "Web Video Caster Receiver Fixture"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app01") {
            dimension = "fx"
            applicationIdSuffix = ".app01"
            manifestPlaceholders["label"] = "Fixture App 01"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app02") {
            dimension = "fx"
            applicationIdSuffix = ".app02"
            manifestPlaceholders["label"] = "Fixture App 02"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app03") {
            dimension = "fx"
            applicationIdSuffix = ".app03"
            manifestPlaceholders["label"] = "Fixture App 03"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app04") {
            dimension = "fx"
            applicationIdSuffix = ".app04"
            manifestPlaceholders["label"] = "Fixture App 04"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app05") {
            dimension = "fx"
            applicationIdSuffix = ".app05"
            manifestPlaceholders["label"] = "Fixture App 05"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app06") {
            dimension = "fx"
            applicationIdSuffix = ".app06"
            manifestPlaceholders["label"] = "Fixture App 06"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app07") {
            dimension = "fx"
            applicationIdSuffix = ".app07"
            manifestPlaceholders["label"] = "Fixture App 07"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app08") {
            dimension = "fx"
            applicationIdSuffix = ".app08"
            manifestPlaceholders["label"] = "Fixture App 08"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app09") {
            dimension = "fx"
            applicationIdSuffix = ".app09"
            manifestPlaceholders["label"] = "Fixture App 09"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app10") {
            dimension = "fx"
            applicationIdSuffix = ".app10"
            manifestPlaceholders["label"] = "Fixture App 10"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app11") {
            dimension = "fx"
            applicationIdSuffix = ".app11"
            manifestPlaceholders["label"] = "Fixture App 11"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app12") {
            dimension = "fx"
            applicationIdSuffix = ".app12"
            manifestPlaceholders["label"] = "Fixture App 12"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app13") {
            dimension = "fx"
            applicationIdSuffix = ".app13"
            manifestPlaceholders["label"] = "Fixture App 13"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app14") {
            dimension = "fx"
            applicationIdSuffix = ".app14"
            manifestPlaceholders["label"] = "Fixture App 14"
            manifestPlaceholders["banner"] = "@null"
        }
        create("app15") {
            dimension = "fx"
            applicationIdSuffix = ".app15"
            manifestPlaceholders["label"] = "Fixture App 15"
            manifestPlaceholders["banner"] = "@null"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}
