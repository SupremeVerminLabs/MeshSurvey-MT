plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.mp)
}

// Version comes from the git tag in CI (e.g. v0.1.3 -> 0.1.3, versionCode = run number); defaults for local builds.
val appVersion = (System.getenv("APP_VERSION") ?: "0.1.0").removePrefix("v")
val appVersionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()

android {
    namespace = "meshsurvey.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "meshsurvey.lite"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
    }
    signingConfigs {
        // PUBLIC test key committed to the repository so every CI build has the same signature and
        // installs as an update over the previous one. Not suitable for app-store distribution.
        create("ciTest") {
            storeFile = file("ci-test.keystore")
            storePassword = "meshsurvey-test"
            keyAlias = "meshsurvey-test"
            keyPassword = "meshsurvey-test"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("ciTest") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ciTest")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Bundled data shared with desktop (offline place names for the History tab).
    sourceSets["main"].assets.srcDir(rootProject.file("data/resources"))
}

dependencies {
    implementation(project(":ui"))
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.usb.serial)
    implementation(compose.material3)
}
