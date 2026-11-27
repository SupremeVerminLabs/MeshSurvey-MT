# Register of unverified implementations, dependencies and hardware assumptions

"Verified" in this project means one of three things:
* **T**: covered by automated tests that ran and passed (76 tests, `tools/verify-core.sh`, including a process-kill durability test);
* **S**: read in firmware source ([FIRMWARE_VERIFICATION.md](FIRMWARE_VERIFICATION.md));
* **H**: validated on hardware. **Nothing is H yet.**

Everything below is not yet verified. Each item names the check that closes it.

## 1. Code that has never been compiled
| ID | Item | Risk | Closes with |
|---|---|---|---|
| U-01 | `ui` module (Compose Multiplatform: Map, Monitor, Test, Settings, map widget) | Compile errors likely on first build; layout/gesture bugs | CI green; HV-02, HV-04 |
| U-02 | `desktopApp` (jSerialComm serial link, NMEA reader, file dialogs, MBTiles opening) | Compile errors; jSerialComm read-timeout behaviour | CI; HV-10 (USB), HV-21 |
| U-03 | `androidApp` (BLE link, USB serial link, foreground service, SAF pickers, permissions) | Compile errors; Android 14 foreground-service/permission edge cases | CI; HV-04, HV-10, HV-14 |
| U-04 | `AndroidSqlDriver` (incl. read-only open, blobs, query binding via `rawQueryWithFactory`) | Binding of numeric args; WAL on some devices | `:core:testDebugUnitTest` covers JDBC only, so needs on-device run (HV-15) |
| U-05 | `JdbcSqlDriver` with real sqlite-jdbc (tests here used a Python sqlite3 bridge with the same SQL) | Driver-specific behaviour, `open_mode` read-only property | `:core:desktopTest` in CI; HV-51 |
| U-07 | Windows self-test (`--self-test`) and MSI options (per-user, bundled runtime, upgrade code) | Never run; first CI run on windows-latest | CI `windows` job; HV-06 |
| U-08 | Committed public test signing key; release APK signed with it | Key generated, build untested | CI `android` job; HV-07 |
| U-09 | GitHub Actions workflow itself (actions versions, paths, PowerShell steps) | Never run | First push |
| U-06 | Gradle build files, version catalog, KMP source-set wiring (`jvmShared` srcDir in two targets) | Plugin/version incompatibilities | CI green |

## 2. Dependencies (pinned in `gradle/libs.versions.toml`, none resolved in development)
| ID | Dependency | Assumption |
|---|---|---|
| U-10 | Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1 | Mutually compatible (documented combination) |
| U-11 | Compose Multiplatform 1.7.3 + material3 | APIs used (FilterChip, HorizontalDivider, AlertDialog, `PointerEventType.Scroll`, `scrollDelta`) exist and are not opt-in-breaking |
| U-12 | kotlinx-coroutines 1.9.0, kotlinx-serialization-json 1.7.3 | Core was tested against 1.6.4 / 1.6.2 runtime jars (API subset used is unchanged) |
| U-13 | sqlite-jdbc 3.47.1.0 | Honours `open_mode=1` for read-only |
| U-14 | jSerialComm 2.11.0 | `TIMEOUT_READ_SEMI_BLOCKING` stream reads return when data is available |
| U-15 | usb-serial-for-android 3.8.1 (JitPack) | Driver probing covers CP210x/CH34x/FTDI/CDC; JitPack availability |
| U-16 | OpenStreetMap tile server | Policy-compliant use: identifying User-Agent, caching, no bulk download |

## 3. Firmware behaviour assumptions
| ID | Assumption | Evidence so far | Closes with |
|---|---|---|---|
| U-20 | Only one copy of each packet reaches the client | S (FloodingRouter/Router) | HV-18 |
| U-21 | Destination ACK = routing packet from B, error NONE; implicit ACK and relay ACKs come from other senders | S (ReliableRouter); RoutingModule internals not read | HV-30, HV-31 |
| U-22 | Up to 3 transmissions with want_ack; last retry floods | S (NextHopRouter) | HV-31 (firmware debug log) |
| U-23 | want_ack=false + hop_limit 0 stays 0 (direct-only) | S (Router::sendLocal) | HV-32 |
| U-24 | Local-client admin needs no session passkey; ToRadio admin packets reach AdminModule with `from == 0` | S (AdminModule); MeshService not read | HV-43 |
| U-25 | Preset change reboots the node; config is re-sent on reconnect | S (AdminModule) | HV-40 |
| U-26 | Restoring the exact original LoRaConfig bytes restores all settings | T (byte-exact patch/restore), S (set_config replaces whole message) | HV-41 |
| U-27 | Heartbeat without nonce causes no broadcast | S (PhoneAPI) + T | HV-13 |
| U-28 | Undecryptable packets are forwarded to the client as `encrypted` | **Not established**: may never be forwarded | HV-17 |
| U-29 | Behaviour is the same on released 2.5/2.6/2.7 firmware as on master | Not compared per release | HV gate on ≥ 2 firmware versions |
| U-30 | rx_snr and rx_rssi are set together (absent SNR with RSSI present = 0 dB) | Reasoned from proto3 + driver behaviour | HV-11 |
| U-31 | Node clocks are usually synced (for queued-packet detection) | Assumption | HV-16 |
| U-32 | QueueStatus is emitted for phone-sent packets; non-zero `res` = not queued | Partly S (heartbeat path only) | HV-36d |
| U-33 | The radio reports its own DeviceMetrics (air_util_tx, channel_utilization) to the phone via node info / telemetry | Field numbers S (protobufs); delivery to phone assumed | HV-36e |
| U-34 | Routing reports for earlier packets are queued by the node and delivered after reconnect (late-ACK matching) | Assumption; matching logic T | HV-36c |

## 4. RF / calculation assumptions
| ID | Assumption | Evidence | Closes with |
|---|---|---|---|
| U-40 | Calculated frequency matches the radio | T against 3 published defaults; S formula | HV-40 (SDR) |
| U-41 | Region and preset tables are pre-2.8 | S (master); 2.8 changes them | Re-verify when 2.8 releases |
| U-42 | LONG_FAST fallback in regions narrower than the preset bandwidth | S (summary only) | HV-40 in EU_868 (only where legal) |
| U-43 | Airtime model: 16-symbol preamble, 16-byte header, ≤ 25-byte test data + 12-byte PKI overhead | S (RadioInterface.h), formula T | SDR burst-length check (optional) |
| U-44 | 2 % worst-case duty and 60 s per-session airtime are "conservative" | Design choice, not a regulatory figure | Community review |

## 5. Hardware / platform assumptions
| ID | Assumption | Closes with |
|---|---|---|
| U-50 | BLE: MTU 512 accepted; FROM_RADIO read-until-empty + FROM_NUM notify works on ESP32 and nRF52 | HV-10 (Android BLE, both MCU families) |
| U-51 | Serial: 115200 baud; 32 × 0xC3 wake burst is harmless | HV-10 (USB) |
| U-52 | Android `Location.accuracy` is a usable horizontal accuracy | HV-20 |
| U-53 | NMEA receivers emit GGA; accuracy only if GST is sent | HV-21 |
| U-54 | MBTiles TMS row flip and raster formats display correctly | T (flip logic); HV-51 |
| U-55 | A minimised desktop window counts as "background" for stopping automated tests | HV-35 (desktop) |
| U-56 | Android foreground service + partial wake lock keeps BLE, GPS and the 1 Hz tick running with the screen locked; vendor battery managers (Samsung, Xiaomi, …) may still kill it | HV-19, HV-19b — the app records suspensions itself |
| U-57 | Android `Location.isMock` / `isFromMockProvider` detects mock providers | HV-20b |
| U-58 | `synchronous=FULL` + WAL makes committed rows survive power loss on the tested devices | HV-19c, HV-40c (reboot) |
| U-59 | Atomic rename (ATOMIC_MOVE) is supported on the app's storage (falls back to plain replace) | HV-40c |
| U-60 | Windows BLE helper (C++/WinRT) compiles with the runner's Visual Studio + Windows SDK and runs without a VC++ redistributable | CI build + installed-app self-test (`ble_helper`) |
| U-61 | Windows finds Meshtastic radios by their advertised service UUID during an active scan, and lists already-paired radios | HV-60 |
| U-62 | Custom pairing with a PIN (`DeviceInformationCustomPairing`, ProvidePin) works for Meshtastic radios from a desktop (non-packaged) app | HV-61 |
| U-63 | WinRT GATT: FromNum notifications arrive, FromRadio reads return one message each and empty when drained, ToRadio write-with-response works | HV-62 |
| U-64 | Android BLE handshake now completes on radios that previously stalled at "Syncing…" (polling + retry fix) | HV-63 (T-Echo), HV-10 (RAK4631) |
