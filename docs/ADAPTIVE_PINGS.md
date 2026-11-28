# Adaptive pings — agreed design for the next major update (not implemented)

Recorded 2026-11-28 from the user's decisions. Part of the "test suite" direction (solo → busy mesh, every test ends in a verdict).

## Rules
1. **Fastest interval 15 s**, and only when the sender has moved into a **new ≈37 m (⅓ acre) grid square** in this session.
   A ping fires **immediately on entering an untested square**, so slow traffic still gets pinged, but sitting at a red light
   does not.
2. **US mode first** (EU duty-cycle mode later). The interval drops with **low channel activity** and with **movement into
   untested squares**. Cap (my pick, adjustable later): the app's own test airtime ≤ **3 % of any rolling 10 minutes** and
   **≤ 240 pings per hour**, whichever is stricter; plus the existing rules (RX-only blocks all, finite sessions, tests paused
   above 25 % channel use, no relaying unless the channel is quiet).
3. **Observer / survey node** can receive data and pings from **any** node (not only MeshSurvey peers). As a manual
   acknowledgement for stock senders it may **reply by DM with the last 5 characters of the received DM**, so the sender can
   match the reply to its ping. (User-approved transmission, counted against the same airtime cap.)
4. **Settings:** "Adaptive pings" on/off, with user-set **minimum and maximum interval**.
   * Moving into untested squares: down to the minimum (15 s at the fastest) if allowed.
   * **Not moving, or in a square already tested this session:** never faster than **30 s**, and the interval grows by
     **+50 % per ping** while it stays there, rounded to friendly values (30 s → 45 s → 1 min → 1.5 min → 2.5 min → 4 min →
     6 min → 10 min → 15 min → 20 min → 30 min → 45 min → 1 h → 1.5 h → 2 h), up to a **fixed 2-hour cap that the user cannot
     change**, so a node someone forgot to switch off sends almost nothing. The user's min/max settings apply only to the
     moving / new-square case.
   * Entering a new square resets to an immediate ping.
   * **"Stationary" for a mobile node** = it has slowed to a stop or stopped moving (not just "in a tested square").
5. **Fixed / stationary beacon node** (a node set as a fixed position): **never faster than 60 s**, and a beacon session runs
   for **at most 1 hour**, then stops on its own. Idea to confirm: the session can be **restarted by a DM** from the survey
   node (only from a node the user has approved; still subject to the airtime cap and RX-only lockout).

## Also agreed in discussion
* Two-way results from one ping (forward delivery via ACK or the other end's log; return-link SNR/RSSI from the ACK).
* Either end can be the beacon: fixed home node or the mobile node.
* Activity = max(app-estimated airtime of heard packets, radio-reported channel utilisation).
