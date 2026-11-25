# Firmware behaviour verified against source

**What was read:** `meshtastic/firmware` and `meshtastic/protobufs`, `master` branch, read on
2026-11-25 via raw.githubusercontent.com. Files are listed per finding.

**What this does and does not establish:**
* It establishes what the **current development source** does.
* It does **not** establish behaviour of each released version (2.5.x, 2.6.x, 2.7.x, 2.8.x).
  Release tags were not compared line by line. Behaviour that changed between versions is
  flagged below.
* Source reading is not hardware validation. Every finding has a matching hardware check in
  [HARDWARE_VALIDATION.md](HARDWARE_VALIDATION.md).

## 1. Acknowledgements and routing reports (what Device A's app sees)

Files: `src/mesh/ReliableRouter.cpp`, `src/mesh/NextHopRouter.cpp/.h`, `src/mesh/Router.cpp`

| Situation | Firmware action | Seen by A's app as | MeshSurvey status |
|---|---|---|---|
| B's radio decodes a want_ack DM addressed to it | B: `sendAckNak(NONE, from, id, ch, getHopLimitForResponse(p))` | Routing packet **from B**, `request_id` = test id, error NONE | `DELIVERED` |
| A's radio hears its own packet rebroadcast | A: "Generate implicit ack", `sendAckNak(NONE, getFrom(p), p->id, …)`, stops retransmitting (only if heard over LoRa) | Routing packet from **A's own node** (from may appear as A or 0), error NONE | `RELAY_OBSERVED` (not delivery) |
| A next-hop relay receives the DM | Relay sends a 0-hop ACK (`hopLimit 0`) to stop the previous hop's retries | Routing packet **from the relay**, error NONE | `RELAY_OBSERVED` |
| B receives but cannot decode | B NAKs with `NO_CHANNEL`, or `PKI_UNKNOWN_PUBKEY` for PKI DMs | Routing packet **from B**, error ≠ NONE | `REJECTED`: B's radio heard it |
| A exhausts retries | `if (isFromUs(p)) sendAckNak(MAX_RETRANSMIT, getFrom(p), id, ch)`, addressed to A | Routing packet from A's node, `MAX_RETRANSMIT` | `NO_ACK`: delivery **unknown** |
| A refuses to transmit | `Router::send`: `DUTY_CYCLE_LIMIT` (NAK "only to API, not to the mesh"), `TOO_LARGE`, `NO_INTERFACE`, `BAD_REQUEST` | Routing packet from A's node with that error | `NOT_TRANSMITTED` |
| Nothing within 90 s | — | — | `UNKNOWN` |

Consequences built into the app:
* **Only a routing ACK whose sender is the destination counts as delivery.** This matches the
  official Android app's "received" vs "delivered to mesh" distinction.
* `NO_ACK` is not shown as "failed". B may have received the test and its ACK been lost. A later
  destination ACK upgrades `NO_ACK` to `DELIVERED`, so the app keeps listening until the timeout.
* **Retransmissions:** `NUM_RELIABLE_RETX = 3`. `PendingPacket` stores `numRetransmissions - 1`,
  so with want_ack A makes **up to 3 transmissions** (initial + 2 retries). For DMs the last retry
  resets next-hop and **floods**. The airtime budget assumes 3.
* **Direct-only mode:** `Router::sendLocal` replaces the hop limit with the configured default only
  `if (src == RX_SRC_USER && p->want_ack && p->hop_limit == 0)`. A test sent with **want_ack false
  and hop_limit 0** therefore keeps hop limit 0: one transmission, no relaying, no ACK. Only B's
  log can confirm it.
* `hop_start` is set equal to `hop_limit` for locally originated packets, so hop counts are
  meaningful on firmware that sets `hop_start`.

Not verified:
* `RoutingModule::sendAckNak` internals, including exactly how `from` is filled on locally
  generated reports. MeshSurvey therefore treats any non-destination sender with error NONE as
  `RELAY_OBSERVED`.
* Whether firmware before 2.6 (pre-NextHopRouter) behaves the same. Flooding plus ReliableRouter
  implicit ACKs is expected to give the same statuses except the relay 0-hop ACK.

## 2. Duplicate suppression

Files: `src/mesh/FloodingRouter.cpp`, `src/mesh/Router.cpp`

* `FloodingRouter::shouldFilterReceived` calls `wasSeenRecently(…)`. Ordinary duplicates log
  "Ignore dupe incoming msg", increment `rxDupe`, and are filtered.
* In `Router::perhapsHandleReceived`, a filtered packet is released **before** `handleReceived()`.
  Modules, MQTT and the phone never see it.
* An **upgraded** duplicate (better hop limit) goes through `perhapsHandleUpgradedPacket` →
  `reprocessPacket` (node DB update, traceroute) and possibly a rebroadcast. That does not deliver
  it to the phone either.

Conclusion: stock firmware gives a client **at most one copy** of each packet. MeshSurvey records
that copy and never estimates the others. Unchanged from the Lite v1 design, now source-verified.

## 3. Configuration changes (temporary test presets)

Files: `src/modules/AdminModule.cpp`, `protobufs/meshtastic/admin.proto`, `config.proto`

* `AdminMessage.set_config = 34`, `Config.lora = 6`, `session_passkey = 101`.
* The passkey check runs only `if (mp.from != 0 && !messageIsRequest(r) && !messageIsResponse(r))`.
  Admin messages from the locally connected client (`from == 0`) do **not** need a session passkey.
  Not verified: that the phone API delivers a ToRadio packet to AdminModule with `from == 0`. That
  happens in `MeshService`, which was not read. Hardware check HV-31.
* For a LoRa config change, `requiresReboot` stays true unless every radio field (including
  `use_preset`, `modem_preset`, `bandwidth`) is unchanged. **A preset change reboots the node**
  (`reboot(DEFAULT_REBOOT_SECONDS)` after `service->reloadConfig`).
* AdminModule does not push the new config to the phone. MeshSurvey reconnects after the reboot
  (or re-requests config after 8 s if there was no reboot) and **confirms** the change by
  comparing fields.
* `set_config` replaces the whole `LoRaConfig`. MeshSurvey therefore **patches the received bytes**,
  changing only fields 1 and 2. Fields it doesn't model are kept as received, including `tx_power`,
  `sx126x_rx_boosted_gain`, `ignore_incoming` (103), `ignore_mqtt` (104), `config_ok_to_mqtt`
  (105), `fem_lna_mode` (106) and `serial_hal_only` (107). Restore sends back the exact original
  bytes.
* No region/preset legality check was found in AdminModule (clamping only). Legality of 500 kHz
  presets is left to the user, with a warning.

## 4. Phone API details that affect safety

File: `src/mesh/PhoneAPI.cpp`

* **Heartbeat:** `if (toRadioScratch.heartbeat.nonce == 1)` → `sendOurNodeInfo(NODENUM_BROADCAST, …)`.
  A heartbeat with nonce 1 **transmits a NodeInfo broadcast**. MeshSurvey's heartbeat is an empty
  message (nonce 0), locked by a unit test.
* After `config_complete`, queued packets are delivered (`STATE_SEND_PACKETS`), which is why
  queued-packet detection exists.
* After a heartbeat the device replies with a `queueStatus` (seen in source). MeshSurvey also treats a
  `queueStatus` with non-zero `res` for one of its packet ids as "not queued" (`NOT_TRANSMITTED`,
  `QUEUE_REJECTED_n`), and `free == 0` as "queue full, don't send". **Exactly when the firmware
  emits queueStatus for phone packets, and what `res` values mean, was not verified** (U-32).
* `handleToRadioPacket` applies duplicate and rate checks to phone-originated packets. A
  rate-limited test should come back as an error and be recorded as `NOT_TRANSMITTED` or `NO_ACK`.
  Not verified on hardware.

## 5. Radio parameters and frequency

See [FREQUENCY.md](FREQUENCY.md). Files: `src/mesh/MeshRadio.h`, `src/mesh/RadioInterface.cpp/.h`,
`src/mesh/Channels.cpp`, `src/DisplayFormatters.cpp`.

## Version caveats found during review
* **Protobufs master already defines firmware-2.8 enums**: presets 10–16 (`LITE_*`, `NARROW_*`,
  `TINY_*`, `MEDIUM_TURBO`) and regions 27–37 (`EU_866`, `EU_N_868`, ITU bands, …). Firmware master
  has no display names or parameters for those presets. MeshSurvey labels them but calculates no
  frequency or airtime for them, and does not offer them for test switching.
* The Meshtastic Apple docs say firmware 2.8 reworks regions and presets. Frequency calculation
  for 2.8 radios must be re-verified when 2.8 sources and releases are available.
