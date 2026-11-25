# Test plan: two RAK4631 kits, US, firmware 2.7.x

Setup: **B fixed at home** (Windows, USB). **A mobile** (Android over Bluetooth; Samsung S20 FE and
a OnePlus). Passive surveys use whatever settings the radios already have. Check IDs refer to
[HARDWARE_VALIDATION.md](HARDWARE_VALIDATION.md). Nothing here is verified yet; record what
actually happens.

## Before you start
1. **Flash stock Meshtastic 2.7.x (latest stable) on both kits** with the web flasher
   (flasher.meshtastic.org), or by double-tapping reset and copying the `.uf2` onto the RAK4631
   drive. This replaces the rocket telemetry firmware: keep that firmware's source/UF2 if you want it back.
2. In the official app, on both: region **US**, the **same** preset and primary channel (factory
   default LongFast is fine), and a recognisable node name. ACKs and tests need both radios on the
   same preset and channel. Write down each node ID (`!xxxxxxxx`) and the firmware version.
3. **Bluetooth (A):** a RAK4631 has no screen, so it pairs with the fixed PIN **123456** unless you
   changed it. **Disconnect the official app** (a node allows one Bluetooth client at a time).
4. **USB (B, Windows):** the RAK4631 shows up as a COM port with no driver install. Close the
   Meshtastic web client, CLI or any serial monitor using it.
5. **GPS:** the app doesn't use the RAK GPS module yet (planned). On Windows choose
   **Settings → Receiver position → Fixed position** and enter the home coordinates. Android uses the
   phone's GPS. The node's own position broadcasts are normal node behaviour, not caused by the app.
6. **Phone background settings** (menu names vary by Android version):
   * **Samsung S20 FE:** App info → MeshSurvey → Battery → **Unrestricted**. Settings → Battery →
     Background usage limits → **Never sleeping apps** → add MeshSurvey. Turn off "Put unused apps to
     sleep".
   * **OnePlus:** Settings → Battery → Battery optimisation → MeshSurvey → **Don't optimise**. App info
     → Battery → **Allow background activity**. Optionally lock the app in the recent-apps screen.
   * Allow Notifications: the notification keeps logging alive.

## Session 0: builds (no radio)
Get both files from GitHub ([GET_THE_APP.md](GET_THE_APP.md)). Install the APK on both phones and
the MSI on the Windows PC. Run the simulator once (Connect → Simulated radio). → HV-01…08

## Session 1: desk, both nodes side by side (30–45 min)
1. Windows: connect B over USB, **Just listen**. Android: connect A over Bluetooth, **Just listen**.
2. Compare a few packets' RSSI/SNR with the official app (on a second phone) → HV-10, HV-11.
3. Passivity: each node is the other's bystander. Note every packet the surveyed node sends; it
   should only be its normal NodeInfo/telemetry/position schedule → HV-12, HV-13.

## Session 2: robustness at home
Unplug/replug USB; walk A out of Bluetooth range and back; force-stop the app mid-logging; reconnect
after 2 min away → HV-14, HV-15, HV-16, HV-19c. Change the PC clock by 5 minutes once → HV-20c.

## Session 3: A/B at home
1. **B (Windows):** Test → Receive tests → **Start · 2 h**. Note the 6-digit code.
2. **A (Android):** Test → Send tests → B's node ID + code → **Use as Device B** → **Send one test**
   (ACK mode). Expect DELIVERED → HV-30, HV-36b.
3. Try a wrong code, and a test after stopping B's session: B must ignore both → HV-36b.
4. Unplug B, send: expect RELAY_OBSERVED/NO_ACK, never DELIVERED. Plug back in and look for a late
   ACK → HV-31, HV-36c.
5. **Direct only**: send; check B's log and that nothing rebroadcasts it → HV-32.
6. "Stock radio I control" mode with B's app closed (B still powered): DELIVERED; direct-only refused → HV-36.

## Session 4: temporary preset (at home first)
1. On A: More options → Test on another preset → **SHORT_TURBO**. Read the review, Apply. The radio
   reboots; the banner should say ACTIVE. Do the same on B (Windows app), or set B manually in the
   official app.
2. Send tests; confirm the export tags them SHORT_TURBO → HV-40, HV-44.
3. Restore on both; compare `meshtastic --get lora` (or the official app's LoRa screen) before/after → HV-41.
4. Close the app while ACTIVE, reboot the phone/PC, reopen: banner and backup file present → HV-40c, HV-42.
5. Refusals: set a frequency override on one radio, then try the preset change → refused → HV-40b.

## Session 5: drive test
1. B at home: running a reference session, plugged in, PC set not to sleep.
2. A in the car, phone mounted and **screen on** (automated sessions stop when the screen locks; this
   is intentional for now). Start a **timed session** on LongFast (ACK mode). Spacing is raised to
   about 97 s for LongFast by the airtime limit.
3. Afterwards: B → Export log → import on A → Compare. Check the map: delivered / no ACK / not
   received cells, grey "nothing heard" cells → HV-22, HV-37.

## Session 6: screen-locked passive drive (each phone)
Passive logging only, phone locked for 30 min. Afterwards, Monitor → "app suspended" count and
longest gap. Repeat once with battery saver on → HV-19, HV-19b. Imperfect results are acceptable
for now; record them per phone model.

## After every session
Export the JSON bundle and CSV from both devices, keep screenshots, and fill in the results table
in HARDWARE_VALIDATION.md (date, firmware, hardware, transport/OS, pass/fail, notes).

## Deferred until the basics pass (stability first)
SHORT_TURBO as the default test preset with spam warnings; listen-only scan of 3+ presets with
recommendations; RAK onboard GPS / Windows location as position sources; keep-screen-on during
active sessions. See [PHASE_STATUS.md](PHASE_STATUS.md).
