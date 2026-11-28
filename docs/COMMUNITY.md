# Community sharing (optional)

Everything you log is saved on your device. Sharing is a separate, explicit choice per survey session.

## How contributions work
* **Destination** (Settings → Community): *MeshSurvey community* (the project's sheet, built in), *Custom* (a compatible sheet
  you run) or *Off* (no uploads, no downloads). Choosing a destination is **not** permission to upload.
* **When a session ends** the app can show a summary (duration, receptions, eligible squares, what is left out and why,
  quality-flagged squares, equipment details you chose to share, destination) with **Upload**, **Keep local only** and
  **Don't ask again**. Declining changes nothing locally.
* **Older sessions**: Settings → Data → *Community upload status…* or History → a day → *Sessions*: each session shows
  *Not uploaded / Pending / Uploading / Partially uploaded / Uploaded / Failed / Excluded / Withdrawn* for the current
  destination, with date and accepted squares, and **Upload… / Resume… / Withdraw**.
* **Automatic upload** is a separate standing permission, off by default; the app never turns it on.
* **Viewing** community coverage (map → Layers → Community squares) does not require contributing.

## What is shared
Per session, one row per **≈37 m square (⅓ acre)** × **day** × **preset** × **node heard from**: square id and centre, local
date, protocol, preset, heard-from node (`mt:!…`, `mc:…`; your own radios appear as `own_radio`), packets, direct packets,
SNR median/best, RSSI median, flags. Optional categories (off by default): radio model as reported, antenna gain category,
internal/external antenna, installation (indoor/outdoor, vehicle/fixed…), configured transmit power.

**Never shared:** the session's UUID (only a one-way hash of it), exact times or positions, travel paths, device ID, your
radios' IDs, names, callsigns, profile notes, message contents, encryption keys, simulated or unknown-provenance data,
mock-location data, anything inside a **privacy zone**. Zones (several, 250/500/1000 m) are set up before the first
contribution; filtering happens before the upload is built.

### Flags (`flags` column, `;`-separated)
| Code | Meaning |
|---|---|
| `gps_poor` | position accuracy worse than 30 m |
| `gps_accuracy_unknown` | GPS did not report accuracy |
| `gps_stationary_stale` | radio's own position, determined more than 30 min earlier |
| `snr_implausible` / `rssi_implausible` | values outside physical limits (kept as flag, excluded from the medians) |
| `near_noise` | median SNR below −5 dB (RSSI reads high there) |
| `single_reception` | only one packet (descriptive, not a doubt) |
| `heard_from_ambiguous` | only a relay byte / short key prefix is known (descriptive) |
| `script_*` | added by the sheet (e.g. `script_snr_out_of_range`) |

**Outliers vs invalid data.** Rows that are structurally impossible (bad position, date, count, protocol, simulated) are
refused and recorded in `oddities` with their original JSON; they never reach `cells` or the map. Unusual but possible values
are stored with flags; they stay out of the trusted map statistics until reviewed and are counted as *awaiting review*.

## Reliability and duplicates
* Each session has a permanent random UUID (kept through export/import between devices); the sheet knows it only as
  `session_key` = hash of the UUID. Parts carry a stable `batch_id` (`<key>-<n>of<N>`).
* Parts are prepared once and resent unchanged; the sheet answers *already accepted* for parts or sessions it has, under a
  script lock, so retries, resumed uploads and the same session uploaded from another device never double-count. A session
  is *Uploaded* only when every part is acknowledged. Status is tracked per destination.
* Failures wait 30 min, doubling to 24 h; nothing retries in a loop. Without the sheet, surveying works normally.
* Every accepted part returns a receipt (batch id, accepted/refused counts), stored with the session.
* **Withdrawal:** with each contribution the app keeps a private random token and sends only its SHA-256. *Withdraw* sends the
  token; the sheet removes that session's rows and marks it withdrawn. No account needed; nothing public reveals the token.

## Review
`cells` holds the original submitted values and is never edited by scripts. Reviews go to the `reviews` tab
(`row_id`, `review` = ok / suspect / rejected, `reason`, optional corrected SNR/RSSI, reviewer, time); the latest review of a row
decides how `rebuildAggregate` uses it. This suits scheduled or AI-assisted review without overwriting measurements.

## Public responses
`GET ?action=aggregate&lat0&lat1&lon0&lon1[&since=version]` returns only combined squares (protocol, preset, trusted count,
trusted SNR/RSSI medians, best SNR, days, most-heard node, contributing sessions count, awaiting-review count). No session keys,
contributor hashes, batch ids or tokens. The app caches the result per area, refreshes at most hourly (or on *Refresh*), and
asks with `since`, so an unchanged aggregate is not downloaded again.

## Set-up or update of the sheet (owner)
1. Google Sheet (private) → Extensions → Apps Script → replace everything with `tools/community/Code.gs` → save.
2. Run **setup** (allow permissions). It creates `contributions, uploads, cells, reviews, oddities, aggregate, meta`; tabs in an
   older layout are kept as `<name>_old_<time>`.
3. Run **installTrigger** once (hourly aggregate; run `rebuildAggregate` by hand any time).
4. Deploy → **New deployment** (first time) or **Manage deployments → edit → Version: New version** (updates keep the URL):
   *Execute as* **Me**, *Who has access* **Anyone**.
The default destination built into the app is the project's deployment URL; the Sheet itself stays private.

## Limits
An anonymous endpoint cannot prove that a submission came from real hardware: the app refuses simulated data at the
interface, serialiser and sheet, but a modified client could still submit fabricated rows. Review flags and the
"awaiting review" separation limit their effect. Rate limit: 20 000 rows per contributor per day; 500 rows per part; 50 parts
per session.

## Tested
Client logic: Kotlin unit tests (`CommunityTest`) with a stand-in sheet. Script logic: `tools/community/test.js` (Node, in-memory
stand-in for Google Sheets) in CI. **Not yet verified against the live Google deployment.**
