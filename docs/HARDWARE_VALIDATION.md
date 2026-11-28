# Hardware validation checklist (gate before Advanced / community work)

Run with **two radios you control** (A and B), stock firmware, and if possible a third
"bystander" node running the official app to watch for unintended traffic. Record firmware
version, hardware, transport and OS for every run. A check passes only when the stated evidence is
recorded. Copy the results table at the bottom into an issue or `docs/validation-results/`.

**Equipment:** 2 × Meshtastic radios (ideally one ESP32 and one nRF52), an Android phone, a
Windows PC, USB data cables. Optional: an SDR (RTL-SDR covers 433/868/915 MHz) for HV-40, and a
USB GPS receiver with NMEA output.

## A. Build and launch
| ID | Check | Pass evidence |
|---|---|---|
| HV-01 | `gradlew.bat :core:desktopTest` passes on Windows | Test report, 0 failures |
| HV-02 | `gradlew.bat :desktopApp:run` opens the window | Screenshot |
| HV-03 | `:desktopApp:packageMsi` produces an installer that installs and starts | MSI file + screenshot |
| HV-04 | `:androidApp:assembleDebug` builds; APK installs and starts on Android 8+ and 14+ | Device models + screenshots |
| HV-05 | Simulated radio: logging, map, A/B session and preset change all run; everything shows SIMULATED | Screenshot of red SIMULATED chip |
| HV-06 | **Clean Windows PC** (no Java, no Android Studio, standard user): MSI from GitHub installs without admin rights, starts from Start menu, upgrades over itself, uninstalls | Screenshots; `java -version` showing "not recognized" |
| HV-07 | APK from GitHub installs on a phone that never had developer tools; a newer CI APK installs over it and keeps data | Screenshots |
| HV-08 | First-run flow: a person who has not read the docs connects a radio, chooses a role and starts logging/testing | Observer notes, time taken |

## B. Connection and passive logging (per transport: Windows USB, Windows TCP, Android BLE, Android USB, Android TCP)
| ID | Check | Pass evidence |
|---|---|---|
| HV-10 | Connects; status reaches **Logging**; node id, firmware, preset, region shown | Screenshot |
| HV-11 | Receptions appear with RSSI/SNR matching the official app's values for the same packets (±1 dB SNR, ±2 dBm RSSI) | Side-by-side screenshots |
| HV-12 | **Passivity**: 30 min logging on a busy channel → bystander node sees **zero** packets from the survey radio beyond its own normal NodeInfo/telemetry schedule | Bystander packet log |
| HV-13 | Heartbeat (serial/TCP): leave connected 15 min; no NodeInfo broadcast is caused by the app (compare with the radio's broadcast interval) | Bystander log |
| HV-14 | Unplug/replug USB, walk out of BLE range and back: reconnects automatically; logging session closes and reopens | Event log |
| HV-15 | Kill the app mid-session, restart: previous session marked INTERRUPTED | Session list / export |
| HV-16 | Disconnect for 2 min while packets are received, reconnect: queued packets are marked **late** with no position | Monitor screenshot |
| HV-17 | Encrypted traffic (channel A doesn't have) appears as ENCRYPTED, if the firmware forwards it | Monitor screenshot, or "not forwarded" noted |
| HV-18 | Duplicate suppression: with B and a relay in range, one packet heard directly and via relay appears **once** | Monitor + firmware debug log |
| HV-19 | **Screen locked, Android:** connect over BLE, lock the phone for 30 min while walking/driving. Afterwards: receptions and track continued (compare reception count with the official app on another phone, or the node's own packet counters); Monitor shows "app suspended" count/longest gap — must be 0 or short | Screenshots of Monitor + export; phone model/Android version; battery-optimisation setting used |
| HV-19b | Same with the phone's battery saver ON and with "Optimised" battery setting: record the gaps the app reports | Monitor timing line |
| HV-19c | Force-stop or swipe away the app mid-session; reopen: all rows logged up to that moment are present, session marked interrupted | Export before/after |

## C. GPS
| ID | Check | Pass evidence |
|---|---|---|
| HV-20 | Android GPS: positions and accuracy shown; fix age goes stale when GPS is blocked; receptions then have no position | Screenshots |
| HV-20b | Android mock-location app: fixes are tagged MOCK in the export | CSV excerpt |
| HV-20c | Change the phone/PC clock by 5 min while logging: a clock-change event appears and is exported | Monitor + bundle `clock_events` |
| HV-21 | Windows NMEA GPS: fix detected; accuracy from GST if supported, otherwise "unknown" | Screenshot + receiver model |
| HV-22 | Drive test: track recorded, grey "surveyed, nothing heard" cells appear where nothing was heard | Map screenshot + GeoJSON |

## D. Controlled tests (A/B)
| ID | Check | Pass evidence |
|---|---|---|
| HV-30 | ACK mode, B in range: status **DELIVERED**; routing report sender = B | Test list + firmware debug log |
| HV-31 | ACK mode, B powered off: **RELAY_OBSERVED** (if relays exist) then **NO_ACK (MAX_RETRANSMIT)**, never DELIVERED | Test list |
| HV-32 | Direct-only mode: B's MeshSurvey logs the test with `hop_start = 0`; bystander/relays do **not** rebroadcast it; A shows UNKNOWN | B log, bystander log |
| HV-33 | B on a different channel/PSK: NAK → **REJECTED** (or NO_ACK if no NAK), documented which | Test list |
| HV-34 | Spacing: Send is disabled until the computed minimum spacing has passed; timed session interval is raised to the preset minimum | Screenshot |
| HV-35 | Timed session stops on its own at duration, on STOP, on BLE drop, and when the Android app is backgrounded | Event log |
| HV-36 | B running **no app** (stock node only), "stock radio I control" mode, ACK mode: DELIVERED still works; direct-only is refused | Test list |
| HV-36b | **Participation:** B not in a reference session → B records nothing (ignored count rises); wrong code → ignored; correct code → recorded; after B's session ends → ignored again | B screenshots |
| HV-36c | **Late ACK:** A sends to B at the edge of range / while B is briefly off; an ACK arriving after 90 s, or after A reconnects, turns the test into DELIVERED (marked late) | Test list |
| HV-36d | **Queued transmission:** on a busy channel or near the EU duty-cycle limit, record whether QueueStatus rejections occur and appear as NOT_TRANSMITTED | Test list + firmware log |
| HV-36e | **Airtime:** compare the app's *estimated* per-test airtime with an SDR burst length, and note the radio-*reported* air_util_tx before/after a session | SDR capture + session export |
| HV-37 | Export B log → import on A → Compare: received-at-B rows match B's list; measured delivery ratio shown | Screenshot + both bundles |
| HV-38 | EU regions: tests eventually hit DUTY_CYCLE_LIMIT if forced; recorded as NOT_TRANSMITTED | Test list (only if legally possible) |

## E. Temporary preset change
| ID | Check | Pass evidence |
|---|---|---|
| HV-39 | Review dialog lists before/after preset, frequency and warnings; **nothing is sent until Apply** (bystander and radio unchanged after Cancel) | Screenshot |
| HV-40 | Apply SHORT_TURBO (where legal): radio reboots, app reconnects, banner says **ACTIVE**; device screen/official app shows the new preset; **SDR shows a carrier at the calculated frequency** (±10 kHz) | Screenshots + SDR waterfall |
| HV-40b | Refusals: radio with custom LoRa settings (use_preset off), with a frequency override, and on EU_868 with SHORT_TURBO → each refused with an explanation, nothing sent (official app shows unchanged config) | Screenshots |
| HV-40c | Kill the app and reboot the PC/phone while a temporary preset is ACTIVE; reopen: banner reappears; backup file present in app storage contains manual restore instructions | Screenshots + backup file |
| HV-41 | Restore: radio back on the original preset; **every LoRa setting** (tx power, boosted gain, ignore list, slot, override) identical in the official app/CLI (`meshtastic --get lora`) before vs after | CLI output before/after |
| HV-42 | Kill the app while ACTIVE, restart and reconnect: banner reappears and Restore works | Screenshots |
| HV-43 | Admin from the local client works with no session passkey error on the tested firmware versions | Event log |
| HV-44 | Measurements taken during the temporary preset are tagged with it in the CSV export | CSV excerpt |

## F. Maps
| ID | Check | Pass evidence |
|---|---|---|
| HV-50 | Online tiles cached; in offline-only mode cached tiles still show | Screenshot |
| HV-51 | Raster MBTiles (PNG and JPEG) displays correctly aligned (TMS flip) on Windows and Android | Screenshot over a known landmark |
| HV-52 | Vector MBTiles is refused with a clear message | Screenshot |

## G. Bluetooth (Windows helper; Android fix)
| ID | Check | Evidence |
|---|---|---|
| HV-60 | Windows: Connect → Search again lists the radio by name (and "paired" if Windows already paired it); with Bluetooth off, the reason is shown instead | Screenshot of both states |
| HV-61 | Windows: first connection with the correct PIN pairs and connects; a wrong PIN fails with the PIN hint; the radio appears in Windows' Bluetooth list afterwards | Screenshots |
| HV-62 | Windows: status reaches "Logging"; packets appear in Monitor with RSSI/SNR matching the official app within a few dB; switching the radio off shows "link lost" and reconnects when it returns; no test is resumed automatically | Screenshot + export |
| HV-63 | Android: a radio that previously stalled at "Syncing…" (T-Echo) now shows "N items received" and reaches "Logging", or fails with the 3-try message instead of hanging | Screenshot of the status line |

## H. Position sources (radio GPS, Windows location)
| ID | Check | Evidence |
|---|---|---|
| HV-64 | Windows base, radio with GPS fix (or a fixed position set in the radio), no other position source chosen: the app switches to "Radio's own GPS" by itself and receptions get the radio's position. On a public default channel (reduced precision) it refuses with the "too coarse" message instead | Screenshot of the status line; export showing source RADIO |
| HV-65 | Windows laptop: "Windows location" gives positions with plausible accuracy while driving; with location turned off for desktop apps, the clear refusal message appears | Screenshot + exported track |

## Results table
| ID | Date | Firmware | Hardware | Transport/OS | Result (pass/fail/n.a.) | Evidence link | Notes |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

**Gate:** HV-01…08, HV-10…16, HV-19, HV-19c, HV-20, HV-30…37 (incl. 36b, 36c), HV-39…44 (incl. 40b, 40c) must pass on at least one ESP32 and one
nRF52 radio, on Windows and on Android, before Advanced or community features begin.
