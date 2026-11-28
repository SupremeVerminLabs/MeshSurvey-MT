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
* GitHub Actions run 46087213884 (commit after b84f256): **all jobs green**. The C++ helper compiled (first attempt failed: needed C++20,
  fixed), the desktop and Android apps compiled, the MSI was built, and the installed app's self-test passed on Windows Server 2025, including
  `ble_helper: helper runs; adapter none` (the runner has no Bluetooth adapter). Helper size 426 KB.
* **Not yet tested:** Windows Bluetooth with a real adapter and radio; the Android sync fix on the T-Echo.

### Unverified
U-60 … U-64 (see UNVERIFIED.md); checks HV-60 … HV-63 added to HARDWARE_VALIDATION.md.

### New dependencies
None at runtime. At build time: MSVC + Windows SDK on the GitHub Windows runner (already present).

## Unreleased: position sources (user request, 2026-11-27)
* **Radio's own GPS** (Windows and Android): uses the position the connected radio reports about itself (its GPS, or a
  fixed position set in the radio). Held while the radio is stationary; nothing is asserted while it moves; positions
  blurred beyond ~100 m (e.g. default public-channel precision) and stored GPS positions more than 2 h old at connect
  are refused. **Chosen automatically** when no other position source is active (the Windows base-station case);
  Android keeps the phone GPS by default. New fix source `RADIO`.
* **Windows location** (desktop, Windows): Windows' location service through the existing helper (satellite if the
  PC has GPS, else Wi-Fi). IP-address/obfuscated positions and fixes older than 10 s are refused. New fix source `OS_LOCATION`.
  Lets the Windows app go mobile (secondary use) without extra hardware; a USB NMEA GPS also still works.
* Firmware basis checked in source: 2.7.26 `Router::sendLocal` loops locally sent broadcasts back to the phone;
  `Default.h` smart-broadcast minimum 5 min, broadcast default 60 min. Master (2.8) adds `sendOurPositionToPhone` every 60 s.
* Tests: `RadioGpsTest` (7) and `OsLocationTest` (1). Local core suite **92/92 pass**.
* Unverified: U-65, U-66; hardware checks HV-64, HV-65.

## Unreleased: spec round 5, steps 1–2 (audit; equipment profiles and position confidence), 2026-11-27

### Step 1: audit
Baseline: GitHub Actions run 46090544108 green (core-tests, android, windows incl. installed-app self-test). Local core suite 92/92.

### Step 2: implemented
* **Radio GPS without changing channel settings** (spec §15): firmware 2.7.26 source shows `want_config_id = 69420` (config only)
  returns the radio's own NodeInfo with the full-precision position from its node database and no other nodes. While radio GPS is
  the active source the app sends it once a minute (phone API only, no LoRa). Blurred over-the-air broadcasts are now ignored and
  never replace a good position. Status says "assumed stationary" and shows how long ago the position was determined; each
  observation stores `position_age_ms`. It never claims movement, only that "the position changed between reports".
* **Confirm as fixed reference position**: copies the radio's current position into a user-confirmed fixed position (source
  MANUAL), logged in the event log.
* **Equipment profiles** (optional): all user-entered, unknown stays empty; one profile per session (changing it starts a new
  session); radio-reported model/firmware/region/preset/configured TX power/frequency recorded per session, separately. Soft delete.
  Included in export bundles (v2) and imported with their origin device.
* **Measurement confidence** (derived, never alters data): Valid / Limited / Insufficient / Possibly misconfigured / Invalid for
  aggregation, each with reasons; shown per reception in the map cell list and exported in CSV (`confidence`, `confidence_reasons`).
* Schema v4 (see SCHEMA.md). CSV gains columns at the end (existing column order unchanged).

### Tests that ran
Local core suite (kotlinc, real SQLite incl. v4 migration): **102/102 pass**: new `EquipmentTest` (4), `ConfidenceTest` (4), 3 new radio GPS tests.
UI compiled only on GitHub Actions: run 46093944819 **all jobs green** (core-tests, android, windows incl. installed-app self-test).

### Deviations / not yet done
* Sessions themselves are exported but not yet imported from other devices (needed for multi-device consolidation; step 4).
* Transmit-power control (spec §7) is not started; only the configured value is read and recorded.
