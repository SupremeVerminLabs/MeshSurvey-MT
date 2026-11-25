plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
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
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        desktopMain.dependencies { implementation(libs.sqlite.jdbc) }
        val desktopTest by getting { dependencies { implementation(libs.sqlite.jdbc) } }
        val androidUnitTest by getting { dependencies { implementation(libs.sqlite.jdbc); implementation(libs.junit) } }
    }
}

android {
    namespace = "meshsurvey.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
