# MeshSurvey Lite: user guide

MeshSurvey is a survey instrument, not a messaging client. It **listens** by default. It transmits
only when you explicitly run a controlled test to a device you registered.

## 0. The start panel
When the app opens, it shows three steps: **Connect radio**, **GPS**, and what you want to do:
**Just listen**, **Send tests (A)** or **Receive tests (B)**. Reopen it with **Start** in the top bar.

## 1. Connect and start logging
1. **Settings → Radio connection.** Pick your radio:
   * Android: Bluetooth (pair it first in Android settings; close the official Meshtastic app),
     USB serial (OTG cable), or TCP.
   * Desktop: USB serial or TCP (`host` or `host:port`, default 4403).
2. **Settings → Receiver position.** Pick a GPS source: phone GPS (Android), USB NMEA receiver or
   fixed position (desktop).
3. When the chip in the top bar shows **Logging**, every packet your node delivers is recorded
   with your position. If the link drops, the app reconnects automatically (5 s, backing off to
   60 s) until you press Disconnect.

On Android, logging continues with the **screen locked** while a radio is connected (a notification
shows it). For best results, set the app's battery usage to *Unrestricted* (Settings → Background
logging). If Android still pauses the app, the Monitor reports how often and for how long, and those
gaps are saved with your data. Automated tests stop as soon as the app leaves the screen.

## 2. Monitor
Live list of receptions: time, sender, packet type, RSSI, SNR, hops (hop_start − hop_limit),
the raw **relay byte**, and whether a position was attached. The top card shows radio, preset,
LoRa parameters, frequency (marked *calculated* or *configured override*), GPS status and counters.

* **Late** means the node queued the packet while the app was disconnected. It is logged without
  a position, because you were somewhere else when it was heard.
* **Relay byte** is the last byte of the relaying node's number, as the firmware reports it. It
  does not identify a node, and the app never claims it does.
* Duplicate copies are filtered by the firmware and are not visible to any app.

## 3. Map
* **Grid** cells are coloured by the best SNR (or RSSI) measured in them.
  * Grey: you passed through with the radio connected and heard nothing.
  * Blue: a test reached B (ACK, B's NAK, or B's log).
  * Purple: a test was **confirmed not received** (needs B's imported log).
  * Orange: no ACK after retries (delivery unknown, not proven lost).
  * No colour: no data.
  * A dark outline means at least one packet came straight from its sender (0 hops).
* **Points**: individual receptions. **Track**: your path. **Tests**: diamonds, same colours (light
  grey = unknown). **GPS accuracy**: circles at the reported accuracy radius.
* Filters: time range, preset, minimum SNR, maximum GPS accuracy, and "my test devices only".
  When filtering by signal, preset or device, grey "nothing heard" cells are hidden because they
  would be misleading.
* **Tap a cell** for its statistics and every reception logged in it.
* Colour thresholds: SNR ≥ 5 / ≥ 0 / ≥ −7 / ≥ −12 / < −12 dB; RSSI ≥ −90 / ≥ −105 / ≥ −115 / ≥ −122 /
  < −122 dBm. These are display bands, not pass/fail limits.
* Offline:
  * Tiles you have viewed are cached, and **Offline only** stops all network use.
  * **Use MBTiles file…** loads a raster `.mbtiles` map (PNG/JPEG/WebP) made with tools such as
    QGIS ("Generate XYZ tiles (MBTiles)") or TileMill. Vector MBTiles are refused. On Android the
    file is copied into app storage.
  * You can also point the template at your own tile server or a `file:///…/{z}/{x}/{y}.png`
    folder.
  * Bulk downloading from openstreetmap.org is not permitted.

## 4. Test (A/B link testing): replaces "Test 1-2-3, anyone hear me?"
There's no enrollment, account, server or internet. Tests are private direct messages on a port
that chat apps ignore, so nobody's chat fills with "test" spam.

**Device B takes part explicitly.**
* **B runs MeshSurvey (recommended):**
  1. **Receive tests (B)** → **Start** (30 min / 2 h / 8 h). B shows a **6-digit participation code**.
  2. B's operator tells A's operator the code and B's node ID (in person, phone, text).
  3. B records only tests carrying that code, and only while its session runs. Everything else is
     ignored and counted.
* **B is a stock radio you control:** A's operator ticks "I own or control this radio". Only ACK
  mode is possible, and B's participation can't be verified: its firmware acknowledges
  automatically.

**Device A** (survey):
1. **Device B**: enter B's node ID (or pick a heard node), then either B's 6-digit code or "stock
   radio I control". Press **Use as Device B**.
2. **Delivery mode**:
   * **ACK requested**: works with a stock B. Up to 3 transmissions per test (firmware retries),
     relays may rebroadcast. Hop limit selectable.
   * **Direct only**: one transmission, never relayed, no ACK. Least airtime, and it measures the
     direct A↔B link only. Needs B's MeshSurvey log to know the result.
3. **Airtime, two separate figures**:
   * *Estimated*: calculated airtime of your test packets only (per transmission, worst case per
     test), which sets the minimum spacing (≥ 30 s) and the per-session cap. Relays' rebroadcasts are
     not included.
   * *Reported by your radio*: its own transmit share and how busy it hears the channel (all
     traffic), when the radio reports them. Automated sessions are refused when the channel is
     reported over 40 % busy, or above half a regional duty-cycle limit.
4. **Send one test**, or a **timed session** (≤ 30 min). It stops by itself, on disconnect, when the
   app is backgrounded or minimised, or with **STOP NOW**.
5. "Sent" means handed to your radio. On a busy channel the radio may transmit later, or reject it
   (shown as *not transmitted*). An ACK that arrives late, even after a reconnect or an app
   restart, still counts and is marked late.
   Status per test on A:
   * **delivered**: ACK from B
   * **relay observed**: a retransmission was heard, not proof of delivery
   * **rejected by B**: B's radio heard it but couldn't decode it (different channel/key)
   * **no ACK**: firmware gave up; delivery unknown
   * **not transmitted**: e.g. duty-cycle limit
   * **unknown**
6. Afterwards: on B **Export my log**, on A **Import Device B log** → **Compare**. This tells
   received-at-B apart from not-received-while-B-was-listening, and gives a measured delivery ratio.

### Testing on another preset (e.g. SHORT_TURBO)
**Test preset → Test on another preset…** opens a review that shows:
* current vs new preset, LoRa parameters, calculated frequency before/after, airtime limits;
* warnings: the radio leaves your normal mesh, B must switch too, the radio reboots, 500 kHz
  legality, duty-cycle regions, frequency-slot change with a default channel name.

Nothing is sent until you tick "I understand…" and press **Apply**. The app saves your exact
original LoRa settings first, changes only the preset, waits for the radio to confirm, and tags
every measurement with the preset and frequency. A banner stays up until the radio is confirmed
back on its original settings. **Restore original now** sends them back byte-for-byte, and it can
happen automatically when the timed session ends. Device B must be switched to the same preset;
the official app works for that too, and MeshSurvey records whatever preset each radio reports.

## 5. Data
**Settings → Data**: export observations CSV, tests CSV, GeoJSON (points, tests, track) or the
full JSON bundle; import a bundle; delete all logged data. Message contents are not stored.
Optional decoded summaries for telemetry/node info can be enabled under **Logging**. Text
messages are never stored.

## 6. Simulator
**Settings → Radio connection → Simulated radio** runs a synthetic mesh for trying the app without
hardware. Everything it produces is tagged **SIMULATED** (red chip in the top bar, `source` column
in exports). Use "Sim Reference B" (`!51a000b2`) as the target to try A/B tests and preset changes.
The simulator applies preset changes with a simulated reboot.
