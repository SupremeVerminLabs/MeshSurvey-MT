# Building and running on Windows

**End users don't need this page.** Download the ready-made installer as described in
[GET_THE_APP.md](GET_THE_APP.md). It installs per user, needs no admin rights, and bundles its own
Java. This page is for building it yourself.

Tested procedure: **not yet run on Windows** (see HV-01…03). Every step uses standard tooling.

## 1. Install
1. **JDK 17** (64-bit), e.g. Eclipse Temurin 17 from adoptium.net. Tick "Set JAVA_HOME" in the
   installer, then check in a new terminal: `java -version` should say `17.x`.
2. **Git for Windows** (or download the source zip).
3. MSI installer: the Compose Gradle plugin normally downloads WiX 3 itself (`downloadWix` task).
   Only if that download is blocked, install **WiX Toolset v3.x** and put its `bin` folder on `PATH`
   (JDK 17's `jpackage` needs WiX 3, not 4/5). Unverified on this project (HV-03).
4. Only for the Android app: **Android Studio** (it installs the Android SDK). Or set
   `ANDROID_HOME` to an existing SDK with platform 35.
5. USB drivers for your radio's serial chip, if Device Manager shows it without a COM port:
   Silicon Labs **CP210x** (Heltec V3, T-Beam) or WCH **CH340/CH9102** (many LilyGO boards, Station
   G2). nRF52 boards (RAK4631, T-Echo) use the built-in USB CDC driver.

## 2. Build and run (Command Prompt or PowerShell, in the project folder)
```bat
gradlew.bat :core:desktopTest
gradlew.bat :desktopApp:run
```
The first run downloads Gradle 8.11.1 and all dependencies (internet needed once).

## 3. Installer
```bat
gradlew.bat :desktopApp:packageMsi
```
Output: `desktopApp\build\compose\binaries\main\msi\MeshSurvey-0.1.0.msi`. It bundles the full
Java runtime (`includeAllModules`), installs per user, and upgrades in place (fixed upgrade code).
Check an installed copy without a radio: `"%LOCALAPPDATA%\MeshSurvey\MeshSurvey.exe" --self-test report.json`
(exit code 0 = pass; everything it exercises is simulated).

## 4. Android APK from Windows
```bat
gradlew.bat :androidApp:assembleDebug
```
Output: `androidApp\build\outputs\apk\debug\androidApp-debug.apk`. Install it with
`adb install -r androidApp-debug.apk` or copy it to the phone.

## 5. Using it
* The radio appears as `COM3`, `COM4`, … under **Settings → Radio connection**. If opening fails,
  close other programs using the port (official desktop app, CLI, serial monitors).
* The database lives at `%USERPROFILE%\.meshsurvey\meshsurvey.db`, tile cache in
  `%USERPROFILE%\.meshsurvey\tiles`. Override with `-Dmeshsurvey.home=…`.
* A USB GPS receiver appears as another COM port under **Receiver position**.

## Troubleshooting
| Symptom | Fix |
|---|---|
| `JAVA_HOME is set to an invalid directory` | Point `JAVA_HOME` at the JDK 17 folder, then open a new terminal |
| `packageMsi` fails mentioning WiX/candle/light | The automatic WiX download failed: install WiX 3.x and add its `bin` folder to `PATH` |
| COM port missing | Install the CP210x/CH34x driver; try another cable (charge-only cables have no data lines) |
| "Could not open COM5" | Another app holds the port; unplug/replug |
| SmartScreen warns about the MSI | Unsigned build. Use "More info → Run anyway", or sign it with your certificate |
| Corporate proxy blocks the first build | Set `systemProp.https.proxyHost`/`proxyPort` in `%USERPROFILE%\.gradle\gradle.properties` |

## Continuous integration
`.github/workflows/build.yml` runs the core tests and builds the desktop app on `windows-latest`
and `ubuntu-latest`, plus the Android APK, on every push. Its first green run is the
"Lite compiles" gate. It has not been run yet.
