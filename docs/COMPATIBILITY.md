# Hardware and firmware compatibility

**Status: no physical radio has been tested yet.** The matrix below shows what the code targets
and what each part depends on. Please report results (device, firmware, transport, OS) so this
table can be filled with measured entries.

## Firmware
| Firmware | Targeted | Why |
|---|---|---|
| 2.8.x | Partial (targeted) | New regions (EU_866, EU_N_868, ITU…) and presets (LITE/NARROW/TINY/MEDIUM_TURBO): labelled, but frequency/airtime UNKNOWN and not offered for test switching |
| 2.6.x – 2.7.x | Targeted | `hop_start` and `relay_node` present; next-hop routing; routing ACKs; behaviour read from master source |
| 2.3 – 2.5 | Works; relay byte unknown | `relay_node` (#19) added in 2.6 → stored as NULL |
| < 2.3 | Works; hops unknown | No `hop_start` → hops stored as NULL |
| Custom/modified firmware | Not required, not assumed | |

## Radios (by connection path)
| Hardware family | USB serial chip | Desktop USB | Android USB | Android BLE | TCP |
|---|---|---|---|---|---|
| Heltec V3/V4, T-Beam, T-LoRa (ESP32-S3) | CP210x / CH9102 / native USB | targeted | targeted | targeted | if Wi-Fi enabled |
| RAK4631 (nRF52) | native USB CDC | targeted | targeted | targeted | with RAK13800 Ethernet |
| T-Echo, Heltec T114 (nRF52) | native USB CDC | targeted | targeted | targeted | — |
| Station G2 | CH9102 | targeted | targeted | targeted | if Wi-Fi enabled |
| Linux-native (meshtasticd) | — | — | — | — | targeted |

**No hardware is listed as compatible.** "Targeted" means the transport is implemented with the
same API the official clients use; it does not mean it works. Entries change to "verified (firmware
x.y, date, report link)" only after the matching hardware checks pass.

## Host platforms
| Platform | Minimum | Transports |
|---|---|---|
| Android | 8.0 (API 26) | BLE, USB-OTG serial, TCP |
| Windows / macOS / Linux | JDK 17 runtime (bundled in native packages) | USB serial, TCP. BLE unavailable on desktop |
| iOS | Not supported (possible future KMP target) | |
