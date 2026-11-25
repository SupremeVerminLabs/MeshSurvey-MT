# Phase 1 — Meshtastic API capabilities and limitations

This is what a client app can and cannot learn from **stock** Meshtastic firmware through the
phone API. It determines what MeshSurvey can measure honestly.

Sources: `meshtastic/protobufs` (`mesh.proto`, `config.proto`, `portnums.proto`,
`telemetry.proto`), the official Python client (`meshtastic/python`: `StreamInterface`,
`SerialInterface`, `TCPInterface`, `BLEInterface`), and the Meshtastic docs on the mesh broadcast
algorithm. Field numbers below are what the core decoder uses.

## Connection methods

| Transport | Framing | Desktop | Android | Notes |
|---|---|---|---|---|
| USB serial | `0x94 0xC3 len_hi len_lo` + protobuf, 115200 8N1 | ✅ jSerialComm | ✅ usb-serial-for-android | Non-framed bytes are the device debug log (shown in the event log). A burst of 32 × `0xC3` wakes a sleeping UART, as the official clients do. An empty heartbeat (nonce 0) is sent every 5 min. Nonce 1 would trigger a NodeInfo broadcast, so it is never used. |
| TCP | Same framing, port 4403 | ✅ | ✅ | Only on nodes with Wi-Fi/Ethernet. Tested here end-to-end against a fake node over a real socket. |
| Bluetooth LE | One FromRadio per read of `FROM_RADIO`; `FROM_NUM` notifies; `TO_RADIO` write | ❌ (see below) | ✅ | Requires pairing (PIN). A node accepts **one** BLE client, so close the official app first. |

**Desktop BLE is not provided.** There is no maintained, cross-platform BLE central library for
the JVM. Per the spec ("do not assume that every connection method exposes identical
functionality"), the desktop app lists BLE as unavailable and explains why.

All three transports carry the same `FromRadio`/`ToRadio` messages, so once connected,
**functionality is identical**.

## Session handshake

`ToRadio.want_config_id (3) = nonce` → node streams `my_info (3)`, `metadata (13)`,
`config (5)` (including `lora (6)`), `node_info (4)` …, then `config_complete_id (7) = nonce`.
Packets arriving before that point may have been queued while the client was disconnected (see
timestamps below).

## Per-packet metadata exposed (MeshPacket)

| Field | # | Type | Available? | Used as |
|---|---|---|---|---|
| from / to | 1 / 2 | fixed32 | ✅ | sender / destination |
| channel | 3 | uint32 | ✅ | channel index (or hash when undecoded) |
| decoded.portnum / payload / request_id | 4 → 1 / 2 / 6 | | ✅ when the node has the channel key | packet type; routing ACK correlation |
| encrypted | 5 | bytes | ✅ when the node lacks the key | status ENCRYPTED, header metadata only |
| id | 6 | fixed32 | ✅ | packet ID |
| rx_time | 7 | fixed32 | ⚠️ depends on node clock | timestamp validation only |
| rx_snr | 8 | float | ✅ | SNR (dB). proto3 omits 0.0; see below |
| hop_limit | 9 | uint32 | ✅ | |
| want_ack | 10 | bool | ✅ | |
| rx_rssi | 12 | int32 | ✅ | RSSI (dBm) |
| via_mqtt | 14 | bool | ✅ | MQTT-injected packets are **excluded** from RF observations |
| hop_start | 15 | uint32 | ✅ on firmware ≥ 2.3 | hops = hop_start − hop_limit |
| next_hop | 18 | uint32 | ⚠️ last byte only, 2.6+ | stored raw, not interpreted |
| relay_node | 19 | uint32 | ⚠️ last byte only, 2.6+ | stored raw as "relay byte" |

### What is *not* available, and how the app handles it

| Wanted | Reality | MeshSurvey behaviour |
|---|---|---|
| **Duplicate receptions** (every rebroadcast copy heard) | The router drops duplicates before they reach the client. Only the first copy is delivered. | Not recorded and **not estimated**. Every session is tagged `duplicates_suppressed = true`. The Monitor view and exports say so. |
| Identity of the relaying node | `relay_node` is only the **last byte** of a 32-bit node number. Many nodes can share it. | Stored and shown as `relay 0x2a`. Never resolved to a node or attributed to a router. |
| Full routing path | Hop count is a number, not a path | Shown as hops; never drawn as a route. |
| Operating frequency | Not reported. Derivable from region + preset + slot + primary channel name (+ override/offset) | Recorded with a source label: `CONFIGURED_OVERRIDE`, `CALCULATED` (firmware formula, see [FREQUENCY.md](FREQUENCY.md)) or `UNKNOWN` |
| SNR of exactly 0.0 dB | proto3 does not serialise 0.0 | If RSSI is present (both are set together by the radio driver), a missing SNR = 0.0 dB. With neither present, both stay unknown. |
| Exact reception time | `rx_time` uses the node's RTC (from GPS, phone or mesh), which may be unset or wrong | The phone clock is the timestamp. `rx_time` only detects queued packets: if it is 30 s–24 h older than the phone clock, the packet is `QUEUED_LATE` and gets no position. Node clocks ahead of the phone, or > 24 h behind, are treated as unsynchronised and ignored. |
| Packets on channels the node has no key for | Delivered without a decoded part (header only), or not at all, depending on firmware path | Logged as `ENCRYPTED` with header metadata when delivered. |

## Controlled tests (A/B)

Semantics are verified against firmware source; details and citations are in
[FIRMWARE_VERIFICATION.md](FIRMWARE_VERIFICATION.md).

* Device A sends a **direct message** on `PRIVATE_APP` (port 256) with an 18-byte payload: `"MST"`,
  version, 8-byte session id, seq, sender time. Stock apps ignore this port.
* **ACK mode**: `want_ack = true`. The firmware makes up to 3 transmissions (initial + 2 retries,
  the last one flooded). Works with a stock Device B; B's firmware sends the ACK.
* **Direct-only mode**: `want_ack = false`, hop limit 0. Exactly one transmission, never relayed,
  no ACK. Only B's MeshSurvey log can confirm reception.
* Status from A's routing reports:

  | Status | Meaning |
  |---|---|
  | `DELIVERED` | ACK from B |
  | `RELAY_OBSERVED` | Retransmission heard (implicit ACK / relay ACK); not delivery |
  | `REJECTED` | B NAKed: its radio heard it but couldn't decode |
  | `NO_ACK` | A gave up after retries; delivery unknown |
  | `NOT_TRANSMITTED` | A refused to transmit (duty cycle etc.) |
  | `UNKNOWN` | No report, or no ACK requested |

* No enrollment: A confirms per target that the user controls it or has its operator's agreement.
  B logs any test addressed to it. Comparison happens afterwards by file exchange.

## Manual test presets (Lite) and preset activity survey (Advanced, future)

Lite can move the radio to a different preset for testing only after a full review and explicit
Apply. It patches only `use_preset`/`modem_preset`, stores the byte-exact original LoRaConfig
first, confirms the change, and restores the original exactly
([FIRMWARE_VERIFICATION.md §3](FIRMWARE_VERIFICATION.md)). The automated multi-preset activity
survey remains Advanced-only and is not started.
