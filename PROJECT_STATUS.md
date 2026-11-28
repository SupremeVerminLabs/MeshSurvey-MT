# Project status — MeshSurvey Lite

Compact handoff file (kept current; details in `docs/`). Last updated 2026-11-27 (evening).

## Version and build
* Versions come from CI: `0.1.<run number>` on `main`, tags `v*` make releases. Unsigned MSI (decision: stay unsigned for now).
* CI (`.github/workflows/build.yml`): core tests (Gradle, real SQLite), Android APK, Windows MSI + installed-app self-test,
  **screens** (renders the real UI with simulated data at phone/desktop sizes, dark/light; PNGs as artifact and base64 in the log).
* Local quick check: `tools/verify-core.sh` (kotlinc + Python SQLite bridge) — 142 tests.

## Implemented
* Passive logging (Meshtastic phone API) over USB serial, TCP, Android BLE, Windows BLE (own C++/WinRT helper); several radios
  at once, each its own engine/session; station position shared.
* Position: phone GPS, NMEA USB GPS, Windows location, manual, radio's own GPS (stationary).
* A/B tests (Meshtastic only) with RX-only switch, airtime limits, participation codes; temporary preset change with byte-exact restore.
* Analysis: confidence classes, pair/equipment comparison, RF health rules; exports CSV/JSON bundle (v2) with import.
* MeshCore (listen-only): protocol probe on USB, NUS service on Windows BLE, PUSH_LOG_RX_DATA logging, contacts/adverts → known nodes.
* Map: coverage tiles (SNR/RSSI), points, track, tests, known nodes, heard-from per node, node picker, filters dialog, layers menu.
* Coverage prediction: Site Planner GeoJSON import, per-cell measured vs predicted (agree/stronger/weaker/heard-not-predicted/
  passed-not-heard), summary with denominators.
* History tab: days named by date + main place (offline GeoNames, CC BY 4.0); a day opens the map limited to that day.
* Radio GPS accuracy: last broadcast DOP kept 24 h, persisted per radio, also taken from a second radio hearing the broadcast.
* UI: dark theme default, Light/System in Settings; phone-width layout below 600 dp (wrapping top bar, compact monitor columns,
  collapsible panels).

## Validation status (hardware)
* Verified by the user: Windows app runs; T-Echo USB + radio GPS; T-Beam Supreme (fw 2.7.15) USB and BLE; two radios at once.
* Not yet verified: Android after the BLE fixes; A/B ACK tests and preset restore on real radios; keep-awake; MeshCore
  (Windows BLE connect to a MeshCore companion stalls — parked at the user's request); prediction comparison with a real export;
  the new layouts on a real phone (only CI renders so far).

## Known issues / limits
* MeshCore BLE on Windows: connection stalls before "connected" (needs a log from the helper's new step messages).
* Map data is loaded on the UI thread every ≤3 s while data changes; fine at current volumes, revisit (single JDBC connection
  shared with writes) if large datasets lag.
* Packet ids/"heard from" for relayed packets are 1-byte prefixes and may be ambiguous; shown as "possibly".

## Pending (agreed order)
1. Live two-radio comparison view. 2. Drive workflow (QR pairing, Start drive, optional Google Sheet sharing with opt-in,
   oddity tags, never simulated data). 3. Real-radio guided test session. Then compare workspace, replay, HTML reports.
* Sidelined: SHORT_TURBO test default, multi-preset listen scan, Android keep-screen-on guidance, session list with delete,
  release checksums, TX power control, Site Planner auto-run, iOS review, GitHub Actions v5 updates.

## Decisions
* Passive-first; app transmits only on explicit user action; RX-only enforced in `RadioClient`. MeshCore: local commands only.
* Simulated data in a separate database; never uploaded.
* Protocol tag on every session and reception; comparisons stay within one protocol.
