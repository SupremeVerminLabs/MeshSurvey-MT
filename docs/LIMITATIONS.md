# Known limitations

## Protocol limits (stock firmware; documented, not worked around)
* **Duplicate receptions are invisible.** The firmware drops repeat copies before the client.
  MeshSurvey records only the first copy and never estimates the rest.
* **Relay identity is ambiguous.** `relay_node` is one byte. Shown raw, never attributed.
* **Hop count is not a path.**
* **Frequency is never reported by the radio.** MeshSurvey calculates it with the firmware formula
  (labelled `CALCULATED`) or uses a configured override. 2.8 regions/presets give `UNKNOWN`.
* **SNR 0.0 dB** relies on RSSI being present (proto3 omits zero). See API_CAPABILITIES.md.
* **Node clock dependence.** Queued-packet detection needs a node clock within 24 h of the phone.
  Otherwise only "arrived during config download" is used.
* **RELAY_OBSERVED ≠ delivery, NO_ACK ≠ lost.** Only a destination ACK, or B's own log, confirms delivery.
* **Direct-only tests can't be confirmed by A alone.** B must run MeshSurvey and export its log.
* **A temporary preset takes the radio off its normal mesh** until restored. Both radios must switch.
* **One BLE client per node.** Close other apps before connecting over Bluetooth.

## This build
* **Not compiled for Android/desktop in the development environment.** The shared core is compiled
  and tested (59 tests). The Compose UI, Android app and desktop app need a first Gradle/CI build;
  expect small compile fixes. See [UNVERIFIED.md](UNVERIFIED.md).
* **No hardware testing yet** (see COMPATIBILITY.md). The A/B flow is verified with two simulated
  engines exchanging real bundle files, not over RF.
* Desktop BLE is Windows-only (through the bundled `meshsurvey-ble.exe` helper) and has not yet been tested with a real radio. macOS/Linux desktop builds have no BLE.
* NMEA GPS accuracy is only known if the receiver sends `$GxGST`; otherwise it is recorded as
  unknown (HDOP is not converted to metres).
* MBTiles: raster only (vector `.pbf` refused). On Android the file is copied into app storage.
* Map queries load up to 50,000 observations into memory. Very large databases should be filtered
  by time.
* Imported survey sessions are not merged into the session list; their observations, tests and
  windows are imported with origin attribution.
* Advanced edition (MQTT comparison, preset activity survey, equipment profiles, multi-radio,
  analysis layers) and the community backend are not implemented yet.
