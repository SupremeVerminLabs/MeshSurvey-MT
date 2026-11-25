# Lite status (round 3: release readiness)

Scope: **MeshSurvey Lite for Android and Windows only.** No iOS, no Advanced edition, no MQTT, no
community backend. No new features until Lite compiles, installs, and passes the hardware gate.

Legend:
* **T**: automated tests, run and passing (76).
* **S**: confirmed in firmware source.
* **I**: implemented, not yet compiled.
* **CI**: checked by the GitHub workflow (not yet run).
* **H**: hardware-validated (none yet).

| Round-3 requirement | Status | How |
|---|---|---|
| Preset changes vs custom configs, overrides, use_preset | **T** | Refused, nothing sent: use_preset off, frequency override, tx disabled, UNSET/unknown region, preset bandwidth wider than the region (firmware would silently fall back), explicit slot that would wrap, firmware unknown or outside 2.3–2.7, unknown resulting frequency. Firmware altering any other field after apply is detected and named, and tests are blocked |
| Config backups survive crashes, reboots, disconnected radios | **T** | Atomic, fsync'd JSON backup file (with manual restore instructions) written **before** the DB row and before anything is sent; failure to write = nothing sent; reconciled into the DB on startup if the DB lost it; SQLite `synchronous=FULL`; open changes stay tied to their radio and are never applied to another |
| Device B explicitly participates | **T** | B starts a finite reference session that shows a 6-digit code; tests carry the code (payload v2); B records only matching tests during its session; "stock radio I control" mode is ACK-only and labelled *participation not verifiable* |
| Estimated vs actual airtime | **T** | App estimate labelled *estimated, this radio's test packets only*; radio-*reported* air_util_tx / channel_utilization shown separately and snapshotted at session start/end; sessions refused on a busy channel (> 40 %) or above half a duty-cycle limit |
| Late ACKs, queued transmissions, interrupted sessions | **T** | ACKs matched by packet id for 2 h (also after timeout, reconnect or app restart); QueueStatus rejections → NOT_TRANSMITTED, full queue blocks sending; on restart, open sessions closed as interrupted and awaiting tests set to UNKNOWN (INTERRUPTED) |
| GPS freshness and timestamp integrity | **T** | 10 s freshness from monotonic clocks; future-dated fixes rejected; mock fixes tagged; NMEA estimated/manual/simulated fixes and receiver-vs-PC time skew > 30 s refused; app-suspension gaps and wall-clock jumps recorded and exported |
| Survey data survives unexpected termination | **T** | Every row committed immediately; a test kills a writer process with `Runtime.halt` and verifies all rows survive and the session is marked interrupted |
| Android background logging, screen locked | **I** → H needed | Foreground service from Connect to Disconnect + partial wake lock; the app measures and reports its own suspensions (HV-19) |
| Windows install without dev dependencies | **I**, **CI** | Per-user MSI with full bundled runtime; CI installs it, hides the runner's JDK, runs the installed app's `--self-test`, uninstalls |
| Ready-to-install APK + MSI from GitHub Actions | **CI** | `.github/workflows/build.yml`; how to get them: [GET_THE_APP.md](GET_THE_APP.md) |
| Simple first use | **I** | Start panel: Connect radio → GPS → Just listen / Send tests / Receive tests |
| No hardware compatibility claims | Done | [COMPATIBILITY.md](COMPATIBILITY.md) lists targets only |

**Gate:** CI green, then [HARDWARE_VALIDATION.md](HARDWARE_VALIDATION.md). Open items:
[UNVERIFIED.md](UNVERIFIED.md).

## Next round (only after CI is green and the core hardware checks pass on the RAK4631 kits)
Agreed with the user, deliberately deferred for stability:
1. SHORT_TURBO as the default *active test* preset where the region allows 500 kHz; any preset selectable; airtime/spam warnings by preset, strongest when testing on the preset the local mesh uses.
2. Listen-only scan of ≥ 3 presets (a few minutes each, radio reboots per switch, automatic restore) ending in a ranked recommendation list, with the caveat that a quiet period doesn't prove a channel is unused.
3. Position sources: the node's onboard GPS (both apps; default for a fixed Windows station), Windows location service if available, manual fixed position (exists).
4. Keep the screen on during active test sessions on Android.
5. Phone-specific background settings (Samsung, OnePlus) shown in the app.

Test plan for the user's hardware: [RAK4631_TEST_PLAN.md](RAK4631_TEST_PLAN.md).
