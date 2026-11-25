# Getting MeshSurvey Lite without building anything

GitHub builds the Android app and the Windows installer for you. You don't need Android Studio,
Gradle or Java.

## Option A: download a release (easiest)
1. Open the project's GitHub page → **Releases** (right-hand side).
2. Download:
   * **Android:** `MeshSurvey-Lite-x.y.z.apk`
   * **Windows:** `MeshSurvey-Lite-x.y.z.msi`

A release exists once a maintainer pushes a version tag (see "Publishing a release" below).

## Option B: download the latest build (any commit)
1. GitHub page → **Actions** tab → click the newest green ✔ **build** run.
2. Scroll to **Artifacts** and download **MeshSurvey-Android-APK** or **MeshSurvey-Windows-Installer**
   (you must be signed in to GitHub). Each downloads as a `.zip`; unzip it to get the `.apk` /
   `.msi`.

## Option C: make your own builds from your copy
1. Sign in to GitHub → open the MeshSurvey repository → **Fork**. Or create a new repository and
   upload the project folder contents (drag and drop works in the GitHub web page).
2. In your copy: **Actions** tab → if asked, click **"I understand my workflows, go ahead and
   enable them"**.
3. Click **build** → **Run workflow**. About 10–15 minutes later, the artifacts are under the run
   (as in Option B).

## Installing
**Android (8.0 or newer)**
1. Copy the `.apk` to the phone, or download it on the phone.
2. Open it. Android asks to allow installs from that app (browser / file manager): allow it.
3. Open **MeshSurvey Lite** and allow Location, Nearby devices (Bluetooth) and Notifications.
   Notifications are what keep logging running with the screen locked.
4. Newer builds install over older ones and keep your data, because every build is signed with the
   same public test key.
   * This test key is published in the repository, so it proves nothing about who made the APK. Only
     install APKs from a source you trust.

**Windows 10/11**
1. Double-click the `.msi`.
2. If SmartScreen says "Windows protected your PC" (the installer is not code-signed), click
   **More info → Run anyway**.
3. It installs for your user only; no administrator rights and no Java needed. Start it from the
   Start menu or the desktop shortcut.
4. A newer `.msi` replaces the older version. Your data in `%USERPROFILE%\.meshsurvey` is kept.
5. If your radio has no COM port, install the CP210x or CH340/CH9102 USB driver for it (see
   WINDOWS_BUILD.md).

## First use (no manual needed)
1. **Connect radio**: pick Bluetooth / USB / network from the list.
2. **GPS**: Android uses the phone's GPS automatically. On Windows, choose a USB GPS or a fixed
   position under Settings, or log without positions.
3. **Choose**:
   * **Just listen**: passive logging and the map. Never transmits.
   * **Receive tests (B)**: press Start and read out the 6-digit code to the person testing.
   * **Send tests (A)**: enter B's node ID and code, then **Send one test** or **Start session**.

## Publishing a release (maintainers)
```bash
git tag v0.1.0
git push origin v0.1.0
```
The workflow builds, tests, smoke-tests the installed Windows app, and attaches the APK and MSI to
a GitHub Release named after the tag.

## What the automatic checks do and do not prove
* **They prove:**
  * the code compiles;
  * the 70+ core tests pass;
  * the Windows installer installs silently and the installed app runs its self-test with its own
    bundled Java (database, serial library, map decoding, and a *simulated* survey, test session
    and preset change/restore);
  * it uninstalls cleanly.
* **They do not prove** anything about real radios, Bluetooth, GPS, or Android background
  behaviour. That is what [HARDWARE_VALIDATION.md](HARDWARE_VALIDATION.md) is for.
