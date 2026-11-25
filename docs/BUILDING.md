# Building (reproducible)

Requirements: **JDK 17**, the Android SDK (platform 35) for the Android app, and internet access
for the first dependency download. All versions are pinned in `gradle/libs.versions.toml`; Gradle
8.11.1 comes through the wrapper.

```bash
# shared core tests (JVM, real SQLite via sqlite-jdbc)
./gradlew :core:desktopTest

# desktop app: run, or build a native installer for the current OS
./gradlew :desktopApp:run
./gradlew :desktopApp:packageDistributionForCurrentOS   # .dmg / .msi / .deb under desktopApp/build/compose/binaries

# Android app (set sdk.dir in local.properties or ANDROID_HOME)
./gradlew :androidApp:assembleDebug                     # androidApp/build/outputs/apk/debug/
```

Windows specifics: [WINDOWS_BUILD.md](WINDOWS_BUILD.md). CI: `.github/workflows/build.yml`.

Opening the folder in Android Studio (Ladybug or newer) or IntelliJ IDEA with the KMP plugin also
works.

## Offline verification of the core (no Android SDK, no network)
```bash
tools/verify-core.sh
```
This uses the Kotlin compiler, kotlinx libraries and JUnit that ship inside a Gradle 8.14
distribution (`GRADLE_LIB=/path/to/gradle/lib` to override), and Python 3's `sqlite3` for storage
tests. This is how the core was verified during development. It also builds the sample-data
generator:

```bash
L=$GRADLE_LIB; java -Dbridge.script=tools/sqlite_bridge.py \
  -cp "build/verify/test:build/verify/main:$L/kotlin-stdlib-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$L/kotlinx-serialization-core-jvm-1.6.2.jar:$L/kotlinx-serialization-json-jvm-1.6.2.jar" \
  meshsurvey.core.testsupport.SampleDataKt samples
```

## Release checklist (Phase 7)
1. `./gradlew :core:desktopTest :core:testDebugUnitTest`
2. Hardware smoke test per transport (USB, TCP, BLE): connect → Logging → receptions appear with position.
3. Two-radio A/B test, export B, import on A, compare.
4. Package desktop installers and a signed Android release.
