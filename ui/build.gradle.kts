plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.mp)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        // JVM code shared by the Android and desktop targets (java.net/java.io), compiled into both.
        androidMain { kotlin.srcDir("src/jvmShared/kotlin") }
        val desktopMain by getting { kotlin.srcDir("src/jvmShared/kotlin") }
        commonMain.dependencies {
            api(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.coroutines.core)
        }
    }
}

android {
    namespace = "meshsurvey.ui"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
