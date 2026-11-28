# MeshSurvey Lite — LoRa mesh coverage validation and hardware comparison

**Wardriving software, but aimed at answering questions:** *Does my coverage match the prediction? Is this antenna better
than that one? Which preset is busy here? Did that repeater move the needle?* MeshSurvey logs what your radios hear,
maps it, and puts it side by side with propagation predictions, other radios, other antennas and other days.

For **stock Meshtastic radios** (MeshCore listen-only support is in progress). Windows and Android. Open source (MIT).

> "Meshtastic" is a trademark of Meshtastic LLC and "MeshCore" belongs to its authors; this project is not affiliated with
> either.

## Why another mapping app?
Other apps map mesh coverage too. MeshSurvey is built around **validation and hardware testing**, with wardriving as one way
to collect evidence rather than the only point:

* **Compare, don't just collect** — several radios logging at once (each its own session, preset and equipment profile),
  measured coverage vs. a **Meshtastic Site Planner** prediction, per-node "heard from" maps, equipment comparison and RF
  health checks with explicit sample counts. Comparing *software* (firmware versions, MeshCore vs Meshtastic side by side) is
  the next step.
* **No admin rights, no accounts, no servers** — the Windows installer is per-user (no administrator approval needed); the
  Android app installs as an APK. Nothing to sign up for.
* **Completely offline if you want** — all data stays in a local database; maps work from cached or MBTiles tiles; place
  names come from a bundled offline gazetteer. Community sharing is optional and off until you choose to contribute.
* **Passive first** — listening never transmits. Tests are explicit, finite and rate-limited, with an RX-only switch that
  blocks every transmission the app could cause.
* **Honest data** — measured values are never interpolated or invented; unknowns stay unknown; simulated data lives in a
  separate database and can never be shared; questionable values are kept and flagged, not silently dropped.

## What it does
| Area | Features |
|---|---|
| **Logging** | Every reception with time, position (GPS accuracy and age), RSSI/SNR, hops, relay, preset, frequency, protocol. USB serial, Bluetooth (Windows helper and Android), TCP. Several radios at once. Message contents are never stored. |
| **Position** | Phone GPS, USB NMEA GPS, Windows location, the radio's own GPS (stationary), or a fixed position. |
| **Map** | SNR/RSSI tiles (37 m to 2 km), points, tracks, known nodes, per-node "heard from" layers, filters by protocol, preset, time, signal, receiver. |
| **Validation** | Import a Site Planner GeoJSON prediction; per cell: agrees / stronger / weaker / heard where nothing was predicted / passed through without hearing it, with denominators. |
| **Hardware testing** | Consent-based A/B link tests between radios you control (participation codes, ACK vs direct-only), temporary test presets with byte-exact restore, equipment profiles, two-radio comparison, RF health rules. |
| **History** | Every survey day named by date and place (offline), opens the map for just that day. |
| **Community (optional)** | Per-session, explicitly approved contributions of anonymous ≈37 m squares to a Google Sheet; privacy zones; receipts and withdrawal; a community coverage layer you can view without contributing. See [COMMUNITY.md](docs/COMMUNITY.md). |
| **Export** | CSV, GeoJSON, JSON bundle (import on another device without duplicates). |

## Status
**Beta, under active development.** Builds come from GitHub Actions on every push (tests, Android APK, Windows MSI, and
rendered screenshots of the UI).

* Checked on hardware so far: Windows with a LilyGO T-Echo (USB, radio GPS) and a T-Beam Supreme (USB and Bluetooth), two
  radios at once. Android and many newer features are **not yet hardware-validated**; see
  [HARDWARE_VALIDATION.md](docs/HARDWARE_VALIDATION.md) and the open items in [UNVERIFIED.md](docs/UNVERIFIED.md).
* The Windows build is not code-signed yet, so SmartScreen may warn on first run (More info → Run anyway).
* Current state, decisions and pending work: [PROJECT_STATUS.md](PROJECT_STATUS.md). Changes: [CHANGELOG](docs/CHANGELOG.md).

## Get it
Download the latest **Windows MSI** or **Android APK** from the newest green run under
[Actions](../../actions) (Artifacts) — see [GET_THE_APP.md](docs/GET_THE_APP.md). No build tools needed.

Build yourself:
```bash
./gradlew :desktopApp:run            # desktop
./gradlew :androidApp:assembleDebug  # Android APK
tools/verify-core.sh                 # core tests without an Android SDK
node tools/community/test.js         # community sheet script tests
```
First use: **Setup** tab → connect a radio → choose a position source → *Just listen*. Try the **Simulated radio** without
hardware. Guide: [USER_GUIDE.md](docs/USER_GUIDE.md).

## Network etiquette (enforced in code)
Passive by default. No public-channel messages, discovery pings, traceroutes, node-info requests, role changes, MQTT or
routing changes. The radio layer refuses broadcasts and anything but test payloads to a target you confirmed; RX-only mode
blocks even those. MeshCore radios only ever receive local requests that do not transmit. The only configuration change is a
user-approved temporary test preset, which is restored byte-for-byte.

## Documentation
| | |
|---|---|
| [User guide](docs/USER_GUIDE.md) · [Get the app](docs/GET_THE_APP.md) | Using the app; downloading builds |
| [Community sharing](docs/COMMUNITY.md) | What is shared, privacy, the Google Sheet script |
| [Compatibility](docs/COMPATIBILITY.md) · [Hardware validation](docs/HARDWARE_VALIDATION.md) · [Unverified](docs/UNVERIFIED.md) | What is tested and what is not |
| [Architecture](docs/ARCHITECTURE.md) · [Schema](docs/SCHEMA.md) · [Building](docs/BUILDING.md) · [Windows build](docs/WINDOWS_BUILD.md) | Internals |
| [API capabilities](docs/API_CAPABILITIES.md) · [Firmware verification](docs/FIRMWARE_VERIFICATION.md) · [Frequency](docs/FREQUENCY.md) · [Health rules](docs/HEALTH_RULES.md) | Background and method |
| [Limitations](docs/LIMITATIONS.md) | Known limits |

## Credits
Map tiles © OpenStreetMap contributors. Offline place names: GeoNames (CC BY 4.0). Prediction import: Meshtastic Site Planner
GeoJSON. Inspired by MeshMapper for MeshCore.

## License
MIT. See [LICENSE](LICENSE).
