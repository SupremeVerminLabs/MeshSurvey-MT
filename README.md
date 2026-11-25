# MeshSurvey — RF survey and coverage mapping for Meshtastic

A dedicated RF surveying instrument for **stock** Meshtastic radios, inspired by MeshMapper for
MeshCore. It passively logs what your radio hears, maps it with GPS, and runs consent-based A/B
link tests between radios you control. It needs no servers, accounts, MQTT or custom firmware.

> Working name. "Meshtastic" is a trademark of Meshtastic LLC; this project is not affiliated with
> it. Rename freely.

**Status: Lite release candidate (round 3). Android + Windows only. Not yet hardware-validated.**
* **Get the app without building anything:** [docs/GET_THE_APP.md](docs/GET_THE_APP.md). GitHub
  Actions builds an installable APK and a Windows MSI.
* The shared core passes **76 automated tests**, including real SQLite, a real TCP socket, the full
  preset-change cycle, and a process-kill data-durability test.
* The Compose UI and the apps are compiled by CI only (no local Android/Compose toolchain here), and
  CI hasn't run yet.
* **No hardware compatibility is claimed** until [HARDWARE_VALIDATION.md](docs/HARDWARE_VALIDATION.md)
  passes. Open items: [UNVERIFIED.md](docs/UNVERIFIED.md).

## What it does (Lite)
* **Passive logging**: every reception the radio delivers, with phone time, receiver position, GPS
  accuracy and fix age, RSSI, SNR, packet id/type, preset/region, hop metadata, raw relay byte and
  reception status (decoded / encrypted / queued-late). Message contents are not stored.
  Duplicates are suppressed by the firmware and that is stated, never estimated.
* **Map**: measured SNR/RSSI grid and points, survey track, GPS accuracy circles, test results.
  It distinguishes *no data*, *surveyed but nothing heard*, *test reached B*, *confirmed not
  received* and *no ACK (unknown)*. Offline via tile cache or local raster **MBTiles**. Filters
  for date, preset, signal, GPS accuracy and your own devices. Tap a cell to see every packet
  seen there. Offline tiles. No interpolation.
* **A/B testing without enrollment**: replaces "Test 1-2-3, anyone hear me?".
  * Device A sends private direct test messages to a target the user confirms, in ACK mode or
    direct-only mode (one transmission, no relay).
  * Limits: ≥ 30 s spacing, raised per preset so worst-case test airtime stays ≤ 2 %; finite
    sessions; immediate stop.
  * Device B takes part explicitly: it starts a reference session and gives A a 6-digit code; only
    tests with that code are logged. A stock B (operator-controlled) is ACK-only and labelled
    unverifiable.
  * Logs are compared afterwards by file, with nothing sent over the air for reporting.
  * Delivered, relay-observed, rejected, no-ACK, not-transmitted and unknown are kept separate.
* **Manual test presets** (e.g. SHORT_TURBO): full review before Apply, byte-exact restore,
  confirmation by config re-read, a banner until restored, and every measurement tagged with
  preset + frequency.
* **Frequency**: calculated with the firmware formula and labelled as calculated, never presented
  as reported.
* **Data integrity**: SQLite with migrations, crash recovery, reconnect with backoff, timestamp
  validation, duplicate-import protection, CSV / JSON / GeoJSON export. Every row carries its
  source (`LOCAL_RF`, `IMPORTED`, `SIMULATED`).
* **Simulator** for development, clearly separated from real RF data.

Transports: **Android** BLE, USB-OTG serial, TCP. **Desktop** (Windows/macOS/Linux) USB serial,
TCP. Desktop BLE is unavailable; see [API_CAPABILITIES](docs/API_CAPABILITIES.md).

## Quick start
```bash
./gradlew :desktopApp:run            # desktop
./gradlew :androidApp:assembleDebug  # Android APK
tools/verify-core.sh                 # offline core tests (no SDK needed)
```
Then **Settings → connect radio → pick GPS**, and logging starts. Try **Simulated radio** without
hardware. See [docs/USER_GUIDE.md](docs/USER_GUIDE.md).

## Documentation
| | |
|---|---|
| [API capabilities (Phase 1)](docs/API_CAPABILITIES.md) | What stock firmware exposes, and how each gap is handled |
| [Phase / requirement status](docs/PHASE_STATUS.md) | Verified, implemented, simulated, partial, unavailable |
| [Architecture](docs/ARCHITECTURE.md) | Framework selection and module layout |
| [Database schema](docs/SCHEMA.md) | Tables, constraints, JSON bundle format |
| [User guide](docs/USER_GUIDE.md) | Using Map / Monitor / Test |
| [Compatibility](docs/COMPATIBILITY.md) | Hardware/firmware matrix (hardware untested) |
| [Known limitations](docs/LIMITATIONS.md) | |
| [Building](docs/BUILDING.md) · [Windows](docs/WINDOWS_BUILD.md) | Reproducible build, offline verification, Windows procedure |
| [Firmware verification](docs/FIRMWARE_VERIFICATION.md) | ACK/duplicate/admin semantics from firmware source |
| [Frequency](docs/FREQUENCY.md) | Calculated vs configured frequency, formula, checked values |
| [Hardware validation](docs/HARDWARE_VALIDATION.md) | Checklist and gate |
| [Unverified register](docs/UNVERIFIED.md) | Every unverified implementation, dependency and assumption |
| [Get the app](docs/GET_THE_APP.md) | Download APK/MSI from GitHub; install; first use |
| [RAK4631 test plan](docs/RAK4631_TEST_PLAN.md) | Session-by-session plan for two RAK4631 kits (US, 2.7.x) |
| [Samples](samples/README.md) | Simulated survey data |

## Network etiquette (enforced in code)
Passive by default. No public-channel messages, discovery pings, traceroutes, node-info requests
(the heartbeat never uses the NodeInfo-triggering nonce), role changes, MQTT publishing or routing
changes. The protocol layer refuses to send broadcasts or anything other than test payloads, and
only to a target you confirmed. The only configuration message is the user-approved temporary
preset change and its restore.

## License
MIT. See [LICENSE](LICENSE).
