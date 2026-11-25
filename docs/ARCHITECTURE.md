# Architecture and framework selection

## Platform choice: Kotlin Multiplatform + Compose Multiplatform

Options considered:

| Option | Desktop | Android | Serial / TCP / BLE | Notes |
|---|---|---|---|---|
| **Kotlin Multiplatform + Compose MP** (chosen) | ✅ JVM (Win/macOS/Linux) | ✅ native | Serial ✅ (jSerialComm, usb-serial-for-android) · TCP ✅ · BLE ✅ Android, ❌ desktop JVM | One language and one UI for both. The Android BLE stack is first-class. Core logic is pure Kotlin and testable anywhere. iOS is a possible later target. |
| Python core + browser UI | ✅ | ❌ (needs a separate app) | Official library covers all three | Fastest to build, but Android would need a rewrite. |
| Tauri (Rust + web UI) | ✅ | ✅ (Tauri 2) | btleplug, serialport | Smaller binaries; two languages; mobile BLE less mature. |
| Flutter | ✅ | ✅ | Plugins of mixed quality on desktop | No official Meshtastic library. |

KMP was chosen because Android is where field surveys happen (phone GPS + BLE radio), desktop
matters for USB/TCP base stations and analysis, and the protocol/logic core can be shared and unit
tested without either platform.

## Modules

```
core/        Kotlin Multiplatform (android + desktop JVM). No UI.
  proto/       protobuf wire codec + Meshtastic message subset (no generated code)
  radio/       RadioLink interface, StreamFramer, RadioClient (handshake, heartbeat, send)
  log/         ReceptionLogger (passive observations, track, timestamp validation)
  abtest/      TestPayload, AbTestController (Device A), ReferenceRecorder (Device B), SessionCompare
  store/       Store interface, SqlStore (schema + migrations) over a tiny SqlDriver
  export/      CSV, GeoJSON, JSON bundle export/import (dedupe by SHA-256 + unique keys)
  map/         Grid + Coverage aggregation + colour scales
  gps/         NMEA parser
  sim/         Simulated radio/world/GPS (protocol-accurate frames, data tagged SIMULATED)
  engine/      SurveyEngine: the single object the UI talks to (StateFlows)
  jvmShared/   StreamLinkBase, TcpLink (compiled into both JVM targets)
  desktopMain/ JdbcSqlDriver (sqlite-jdbc)       androidMain/ AndroidSqlDriver
ui/          Compose Multiplatform shared UI: Map / Monitor / Test + Settings, tile map
desktopApp/  Compose Desktop entry, jSerialComm serial link, NMEA GPS, file dialogs
androidApp/  Activity, Application (engine + 1 Hz tick), BLE link, USB serial link,
             phone GPS, foreground service for passive logging
tools/       verify-core.sh (offline compile + test), sqlite_bridge.py, test shims, sample generator
```

## Design decisions

* **No generated protobuf classes.** About 40 fields are needed. A 100-line wire codec keeps the
  core dependency-free and fully testable, and the field numbers are listed in
  `API_CAPABILITIES.md`. If more of the API is needed later, swap in Wire/protobuf-kotlin
  generated from `meshtastic/protobufs`.
* **Single-threaded engine.** All engine calls happen on the UI/main thread. Links do I/O on their
  own threads and post callbacks to the main thread. This removes locking from the protocol and
  storage code. Writes are small; map queries are capped (50 k rows).
* **Sending is fenced in the protocol layer.** `RadioClient.sendData` refuses broadcast
  destinations and any port other than the test port. Only the A/B controller calls it, and only
  for registered devices.
* **Separate data categories** in separate tables (observations, track, logging windows, test
  sessions, test tx, test rx, imports, node names). Every row has `source` (LOCAL_RF / IMPORTED /
  SIMULATED) and `origin_device` (empty = recorded by this install). Unknown values are `NULL`.
* **Own map widget** (`SlippyMap`), so there's no map SDK, API key or vendor lock-in. Tiles come
  from any `{z}/{x}/{y}` source or a local `file://` folder. Viewed tiles are cached on disk.
  Prefetching is not done, to respect the OSM tile policy.
* **Edition hook.** `Edition.LITE/ADVANCED` lives in the engine. Advanced features will be added
  as extra modules and migrations without forking the Lite app.
