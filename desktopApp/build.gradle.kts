import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.mp)
}

kotlin { jvmToolchain(17) }

// From the git tag in CI (v0.1.3 -> 0.1.3); MSI versions must be numeric MAJOR.MINOR.BUILD.
val appVersion = (System.getenv("APP_VERSION") ?: "0.1.0").removePrefix("v")

dependencies {
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.coroutines.swing)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jserialcomm)
}

compose.desktop {
    application {
        mainClass = "meshsurvey.desktop.MainKt"
        jvmArgs += listOf("-Dmeshsurvey.version=$appVersion")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            // resources/windows/meshsurvey-ble.exe (Windows Bluetooth helper) is built by CI from native/windows-ble
            // before packaging. Without it the app still works; Bluetooth is then reported as unavailable.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            packageName = "MeshSurvey"
            packageVersion = appVersion
            description = "Passive RF survey and A/B link testing for Meshtastic"
            vendor = "MeshSurvey contributors"
            // Bundle the whole Java runtime: end users install nothing else, and no module can be missing.
            includeAllModules = true
            windows {
                perUserInstall = true      // no administrator rights needed
                menu = true
                menuGroup = "MeshSurvey"
                shortcut = true
                dirChooser = false
                // Fixed upgrade code: a newer MSI replaces the older install instead of installing side by side.
                upgradeUuid = "6f1d1c2e-4b7a-4f5e-9a51-3c2b9d0e7a41"
            }
        }
    }
}
