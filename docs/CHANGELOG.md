# Change log

Verification status keys: **Implemented** · **Compiled** (by a real compiler; says where) · **Simulator tested** ·
**Integration tested** · **Hardware tested** (physical radio; says which) · **Unverified**.

## Unreleased: Phase A (regression audit), 2026-11-27

### Repository state vs. working copy
| Item | Repo (`main` @ f97c4a8) | Working copy | Action |
|---|---|---|---|
| Bluetooth sync fix (`BleLink.kt`, `RadioClient.kt`, `ProtocolTest.kt`) | Uploaded to the **repo root** by mistake, so not compiled; the real files are unchanged | In place | Move into their folders, delete the root copies |
| `.gitignore`, `.gitattributes` | Missing (the web uploader skips dot-files) | Present | Add |
| CI hardening (`build.yml`: time limits, installer uploaded before smoke test) | Old version | New | Replace |
| Windows crash log (`desktopApp/.../Main.kt`) | Missing | Present | Add |
| Map clipping fix (`MapView.kt`) | Present | Present (plus a comment) | none |
| Compile fixes from first CI runs (`TestScreen.kt`, `AndroidSqlDriver.kt`) | Present | Present | none |

### Tests that actually ran (this phase)
* Core tests, compiled with kotlinc 2.0.21 on Linux against a local test shim and real SQLite:
  repo code **76/76 pass**; working copy **77/77 pass** (adds `stalledHandshakeIsRetriedThenFails`).
* Not run here: Android unit tests, UI compilation, MSI packaging. These only run on GitHub Actions.

### Status of the last build reports
| Component | Compiled | Simulator tested | Hardware tested |
|---|---|---|---|
| core | Yes (local + GitHub Actions, 2026-11-25) | Yes (unit tests + CI self-test) | No |
| Windows app + MSI | Yes (GitHub Actions run on b3e1993, reported green by user) | CI self-test PASS on Windows Server 2025 | Opens on user's Windows PC; no radio connected yet |
| Android app + APK | Yes (GitHub Actions, reported green by user) | No | Installs and opens; BLE connects to a LilyGO T-Echo but stalls at "Syncing…" (unresolved) |
| RAK4631 (target hardware) | — | — | Not tested |
| Latest commit f97c4a8 | **Unknown**: the stray root files do not affect the build, but no result has been seen | | |

### Known open defects
1. Android BLE: stuck at "Syncing…" with a T-Echo. A fix exists in the working copy and is not yet in the repo. **Unverified.**
2. Windows: no Bluetooth (USB serial and TCP only). Planned for Phase B.

## Unreleased: Phase B (Windows Bluetooth LE), 2026-11-27

**Approved approach:** our own small helper program (`meshsurvey-ble.exe`, MIT, C++/WinRT), which the desktop app
runs as a child process and talks to over stdin/stdout. No new libraries; only Windows' built-in Bluetooth API.

### Added
* `desktopApp/native/windows-ble/meshsurvey-ble.cpp` + `build.ps1`: the helper. Scan (Meshtastic service UUID + already-paired
  radios), PIN pairing, connect, FromNum notifications plus a 2 s poll fallback, read-until-empty, write-with-response, link-loss events.
  Static C runtime, no console window, exits when the app does.
* `core/src/jvmShared/.../radio/HelperBle.kt`: `BleHelperProcess`, `BleHelperScan`, `HelperBleLink` (a normal `RadioLink`, so the same
  transmission rules apply as for USB/TCP/Android).
* `desktopApp/.../WindowsBle.kt`: finds the bundled helper; clear reasons when unavailable (not Windows, adapter off/missing, helper missing).
* Connect screen: radios found over Bluetooth on Windows, plus a **Bluetooth PIN** field (default 123456) shown only when needed.
* Installed-app self-test: new `ble_helper` check (helper starts without extra runtimes and answers; CI runners have no adapter → "adapter none").
* CI: builds the helper before packaging the MSI.
* Tests: `HelperBleTest` (7 tests) against `tools/fake-ble-helper.py`, which tests the app side of the protocol only.

### Also included (from Phase A)
* Android BLE "Syncing…" fix: progress count, FromRadio polling, 3 config retries, connect retry on status 133 (`BleLink.kt`, `RadioClient.kt`), with test.
* Windows crash log (`%USERPROFILE%\.meshsurvey\crash.log`), hardened CI workflow, `.gitignore`/`.gitattributes`.

### Preserved
USB serial, TCP, Android BLE, simulator, all safeguards. No defaults changed (Bluetooth is only used if you pick a Bluetooth radio).

### Tests that actually ran
* Core + protocol tests locally (kotlinc 2.0.21, Linux): **84/84 pass** (77 before + 7 new).
* **Not compiled yet:** the C++ helper, `WindowsBle.kt`, the Connect/SelfTest/DesktopPlatform changes. These are compiled for the first time on GitHub Actions after the push.

### Unverified
U-60 … U-64 (see UNVERIFIED.md); checks HV-60 … HV-63 added to HARDWARE_VALIDATION.md.

### New dependencies
None at runtime. At build time: MSVC + Windows SDK on the GitHub Windows runner (already present).
