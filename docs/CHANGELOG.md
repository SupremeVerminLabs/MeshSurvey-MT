# Change log

Verification status keys: **Implemented** · **Compiled** (by a real compiler; says where) · **Simulator tested** ·
**Integration tested** · **Hardware tested** (physical radio; says which) · **Unverified**.

## Unreleased: Community sharing v2 — per-session contributions, receipts, review, 2026-11-27

**Kotlin unit tests (149) and Node script tests pass locally; compiled by CI. Not verified against the live Google deployment.**

* Sharing is per survey session with explicit consent: end-of-session summary prompt (Upload / Keep local only / Don't ask
  again), Upload/Resume/Withdraw for older sessions (Settings → Data, History → Sessions), persistent per-destination status
  (Not uploaded … Withdrawn) with date, accepted squares and receipts. Automatic upload is a separate permission, off by default.
* Default destination = the project's sheet (built in); Custom or Off. Map download is independent of contributing; cached per
  area, hourly refresh or manual Refresh, `since` version avoids re-downloading.
* Session UUIDs (v4) for new sessions; older ids kept. Server-side key = hash of the UUID; stable batch ids; frozen parts;
  duplicate-safe resume and cross-device import; script lock; size and rate limits; backoff 30 min → 24 h.
* Multiple privacy zones (250/500/1000 m), required before the first contribution; optional coarse equipment categories.
* Script schema 2: `contributions`, `reviews` tabs; invalid rows refused to `oddities` with original JSON; flagged rows kept
  with original values and excluded from trusted statistics until reviewed; corrections recorded separately; withdrawal by
  private token (hash only uploaded); public GET returns aggregates only. `setup()` keeps older-layout tabs aside.

## Unreleased: Community sharing (Google Sheet), roomier phone Monitor, 2026-11-27

**Unit tested (145 Kotlin tests + script tests in Node); compiled by CI; not yet run against a real Google Sheet.**

* Opt-in anonymous sharing to a Google Sheet through `tools/community/Code.gs` (Apps Script web app). Rows: ~37 m square ×
  day × preset × heard-from node with counts, SNR/RSSI medians and machine-readable flags; home privacy zone (250 m, on by
  default); never simulated/imported/mock data or IDs of the user's own radios. Manual or automatic (30 min) upload with
  watermark-based retry and duplicate-safe batches; oddity reports; combined squares as a map layer. See docs/COMMUNITY.md.
* Phone Monitor: start panel closes itself once a radio is logging; tighter status card and chips; more rows visible.
* Map cell size option 37 m (⅓ acre).

## Unreleased: GPS accuracy, History tab, tidier Settings, 2026-11-27

**Unit tested (142 local tests), compiled and rendered by CI; not yet checked on hardware.**

* **Radio GPS accuracy:** the radio reports DOP/satellites only in its own position broadcasts (its NodeInfo carries none —
  confirmed in firmware `TypeConversions::ConvertToPosition`), which with default settings come every 15 min to hours. Now the
  last report is kept for 24 h (was 1 h) with its age shown, saved per radio so a restart doesn't lose it, and filled in by a
  second connected radio that hears the broadcast over the air. The top bar says "Radio GPS ≈±8 m", "Radio GPS · 7 sats",
  "Fixed position" or "accuracy pending" instead of "accuracy unknown". Recorded accuracy stays null until a real report exists.
* **History tab:** one entry per local calendar day, named by date and the place mostly covered (offline GeoNames extract,
  CC BY 4.0, `data/resources/places`), with time span, receptions, nodes, best SNR, distance, presets, radios. Opening a day shows
  the map for that day only (all layers, filters and prediction comparison).
* **Settings:** collapsible groups with a one-line summary each; only the active position source shows its explanation.
* **Monitor details:** label/value lines (Radio, Channel, Frequency, Position, Logged, Airtime, Power).

## Unreleased: Dark theme, phone-width layout, map crash fix, 2026-11-27

**Compiled and rendered by CI (screens job, simulated data); not yet checked on a real phone.**

* **Crash fix:** zooming the map could close the app ("maxWidth must be >= minWidth"): node labels near the right edge were drawn
  with a negative width limit. Labels are now measured first and drawn only where they fit; any future drawing error in a map
  layer is written to the event log and that layer skipped for the frame instead of closing the app.
* **Dark theme by default**; Light and System in Settings → Appearance (stored). Text colours chosen for ≥ 4.5:1 contrast; SNR
  badges pick black or white text by background.
* **Phone-width layout below 600 dp:** top-bar chips wrap, idle radio chip hidden, Monitor shows a compact column set (time, from,
  type, RSSI, SNR, hops) and a collapsible status card, wide tables scroll sideways instead of running off screen, map controls
  regrouped (Layers menu, Filters dialog), legend and prediction summary collapsible.
* **Prediction cells** with no reception are drawn as outlines (weak evidence).
* **Sessions…** in Settings: list of recent sessions with per-session delete (active session refused).
* CI **screens** job renders the real UI (phone/desktop, dark/light) with simulated data on every push.

## Unreleased: Coverage prediction vs measurement, 2026-11-27

**Implemented, unit tested (136 local tests); compiled by CI; not yet tried with a real Site Planner export.**

* Map → **Prediction…**: import a Meshtastic Site Planner **GeoJSON** export (nested iso-bands, `dbm` property; other GeoJSON
  polygons with `dbm`/`rssi`/`signal_dbm`/`value` work too). PNG/KML exports are refused with the reason (colours only).
  Predictions are stored in the database (schema v8) and listed for show/hide/delete.
* Choose the **transmitter** the prediction is for, from nodes heard directly (Meshtastic: exact node at zero hops; MeshCore:
  key-prefix group). Only direct receptions are compared: relayed copies say nothing about that transmitter's own signal.
* Per cell: predicted range [band, next band) vs median measured RSSI → agrees (± tolerance, default 6 dB), stronger, weaker,
  heard where nothing was predicted, passed through predicted coverage without hearing it (labelled weak evidence), passed
  through with neither. Cells with median SNR < −5 dB are outlined (RSSI there reads high).
* Summary card with explicit denominators and the median difference; bands drawn on the map; cell details show predicted vs measured.

## Unreleased: MeshCore listening and map layers, 2026-11-27

**Implemented, unit tested (local `tools/verify-core.sh`, 132 tests); compiled by CI; not hardware tested.**

* **MeshCore radios, listen-only.** USB serial: the app first sends a MeshCore DEVICE_QUERY (a Meshtastic radio ignores it) and only
  continues with Meshtastic if no MeshCore reply comes within 2.5 s (waiting out ESP32 boot output). Windows Bluetooth: the helper
  looks for the Meshtastic service, then the Nordic UART service MeshCore uses, and reports which it found. MeshCore radios get
  APP_START, DEVICE_QUERY, GET_CONTACTS and GET_BATT_AND_STORAGE only (all local; enforced in `RadioClient.sendMc`); messages are
  never synced, so they stay for the user's MeshCore app. Every received packet (PUSH_LOG_RX_DATA) is logged with SNR/RSSI, route,
  payload type and path; contents are never decrypted.
* **Protocol tag** on every session and reception (`protocol`), shown in the top bar, Monitor preset column ("MC 910.525/BW62.5/SF7")
  and map cell details. Map "Both protocols / Meshtastic / MeshCore" selector; mixed views are labelled coverage-only.
* **Heard from** (`heard_id`): the node each reception was heard from directly (MeshCore: last repeater in the flood path, or the
  sender at zero hops; Meshtastic: the sender at zero hops, else the relay byte). Existing Meshtastic rows are back-filled.
* **Known nodes**: positions from MeshCore contacts and adverts, and from the Meshtastic node database.
* **Map layers**: Tile colours (on/off), Known nodes (▲ repeaters, ■ others, coloured once heard), Heard from (one colour per node;
  with one node chosen, lines from each place it was heard to its advertised position), and a Nodes picker that filters tiles,
  points and layers to chosen repeaters.
* Tests and preset changes stay Meshtastic-only; the Test tab explains this for a MeshCore radio.
* Schema v7, bundle and CSV carry `protocol` and `heard_id`.

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

## Unreleased: hardware-first round (T-Beam Supreme prep), 2026-11-27
* Recorded first user hardware report: T-Echo on Windows over USB logs packets and supplies radio GPS (COMPATIBILITY.md, HARDWARE_VALIDATION.md).
* ESP32 USB: checked against the official Python client: it opens the port with DTR/RTS asserted on Windows, which is
  also jSerialComm's default, so no change to port handling. New: if the radio prints boot messages before answering
  (many ESP32 boards restart when the port opens), the app re-requests its configuration 3 s after the output stops instead
  of waiting 20 s. Test `radioThatRebootsOnPortOpenIsAskedAgainAfterBootOutput`. Core suite 103/103.
* Windows signing: user chose to stay unsigned for now (options documented in WINDOWS_BUILD.md).

## Unreleased: comparison + RF health engine (additional spec, step 1 of 4), 2026-11-27
Order agreed with the user: engine → Windows Compare workspace (map, replay) → HTML reports → themes (with CI-rendered screenshots).
Data path: files first (export/import), live sync later.
* **Sessions import from other devices** (schema v5: `survey_session.origin_device`); crash recovery and session ending
  only touch this device's own sessions.
* **Paired comparison** (`PairCompare`): matches by sender + packet id; classes Both / Reference only / Mobile only /
  Other receiver unavailable / Incompatible settings / Ambiguous; explicit denominators; positions only from recorded
  track points within 10 s; clock offset estimate; receiver separation.
* **Equipment comparison** (`EquipmentCompare`): same-sender, same-100 m-cell strata; medians, percentiles, IQR; dB
  differences with deterministic 95% intervals; automatic limitations.
* **RF health** (`Health`): solo, historical and paired rules with evidence, baseline, sample count, confidence,
  alternatives, inspection and caveats. Rules documented in HEALTH_RULES.md.
* Tests: `AnalysisTest` (11) + session import checks. Core suite 114/114.
* Not yet: UI for any of this (next step), HTML report, themes.

## Unreleased: several radios at once + radio GPS accuracy, 2026-11-27
User reports recorded: T-Beam Supreme (!33601b74, ESP32-S3, firmware 2.7.15) on Windows over **USB** and **Bluetooth**
(PIN from its screen): connects, logs, radio GPS used (accuracy unknown). See COMPATIBILITY.md.
* **Multiple radios**: `Station` holds one `SurveyEngine` per connected radio; each logs as its own receiver (own
  sessions, logging windows, preset, equipment profile), so radios on different presets survey side by side.
  The station position (GPS / Windows location / fixed / one radio's GPS) applies to all. Only the first engine does
  crash recovery, backup reconciliation and suspension records. Schema v6: logging windows per receiver.
  UI: "Connect another", one chip per radio in the top bar (tap to view it), per-radio Disconnect, receiver filter on the map;
  the same connection cannot be opened twice.
* **Radio GPS accuracy**: the precise position comes from the radio's stored copy; the accuracy is estimated from the PDOP
  and satellite count in the radio's latest position broadcast (default firmware flags send PDOP, not HDOP), used for up to 1 h.
* Tests: `StationTest` (3), new radio GPS accuracy test. Core suite 119/119.
* Not yet: a live two-radio comparison view (the engine exists: PairCompare/EquipmentCompare; the screen comes next).

## Unreleased: base-station essentials, 2026-11-27
Decisions (user): build base essentials → live two-radio comparison → seamless drive workflow → real-radio test session;
Google Sheet + Apps Script for optional sharing (opt-in checkboxes, local saves kept, odd readings shared with a tag);
simulation in its own database; ACK tests kept with a spam warning; base station: keep PC awake only.
* **RX-only mode** (Settings switch + always-visible top-bar chip, remembered): blocks every app transmission on every radio,
  enforced in `RadioClient.sendData` (lowest level), A/B controller and preset apply; turning it on stops running test sessions.
  Restoring a radio's original configuration stays allowed. Firmware's own transmissions are explained as not the app's.
* **App transmission counter** per radio with estimated airtime (top bar + Settings).
* **Keep PC awake while logging** (Windows): helper `awake on|off` (SetThreadExecutionState), only while a radio is connected.
* **Simulation isolated**: simulated radios write to `simulation.db`, never to the survey database; real exports report
  `contains_simulated: false`.
* Test screen: spam warning for active tests.
* Tests: RxOnlyTest (2), SimulationIsolationTest (1). Core suite 122/122.
