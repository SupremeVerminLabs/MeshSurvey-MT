# Community sharing (optional)

MeshSurvey can add anonymous coverage summaries to a Google Sheet that you (or someone you trust) own. It is **off by
default**; your own data always stays saved on your device whether you share or not.

## What is shared
One row per **~37 m square (⅓ acre)** × **day** × **preset** × **node heard from**:
`cell_id, lat, lon` (the square's centre, never your real point), `day` (local date, no time), `protocol`, `preset`,
`heard_from` (`mt:!a1b2c3d4`, `mc:a3f1…`, `own_radio`, `unknown`), `n`, `n_direct`, `snr_median`, `snr_best`,
`rssi_median`, `flags`.

Never shared: device ID, your radios' node IDs, exact times or positions, message contents, simulated or imported data,
mock-location data, anything inside your **home privacy zone** (250 m, on by default; uploads wait until you set it).

### Flags (`flags` column, `;`-separated)
| Code | Meaning |
|---|---|
| `gps_poor` | position accuracy worse than 30 m |
| `gps_accuracy_unknown` | GPS did not report accuracy |
| `gps_stationary_stale` | radio's own position, determined more than 30 min earlier |
| `snr_implausible` / `rssi_implausible` | values outside physical limits (excluded from the medians, kept as a flag) |
| `near_noise` | median SNR below −5 dB (RSSI reads high there) |
| `single_reception` | only one packet in the square that day |
| `heard_from_ambiguous` | only a relay byte / short key prefix is known |
| `script_*` | added by the sheet script (e.g. `script_snr_out_of_range`) |

Odd rows are shared **with** their flags, not dropped. The `review` column (`ok` / `suspect` / `rejected`) is for a human or a
scheduled reviewer; `rejected` rows are left out of the combined map, nothing is deleted.

## Set-up (about 10 minutes)
1. Create a new Google Sheet (keep it private).
2. **Extensions → Apps Script**, delete the sample, paste `tools/community/Code.gs`, save.
3. Choose the function **setup** and press **Run** (allow the permissions it asks for: it only touches this Sheet). The tabs
   `uploads`, `cells`, `oddities`, `aggregate`, `meta` appear.
4. Run **installTrigger** once (rebuilds the combined `aggregate` tab every hour; you can also run `rebuildAggregate` by hand).
5. **Deploy → New deployment → Web app**: *Execute as* **Me**, *Who has access* **Anyone**. Copy the web-app URL
   (`https://script.google.com/macros/s/…/exec`).
6. In MeshSurvey: **Settings → Community sharing**: turn on *Share to community anonymously*, paste the URL, set the home zone
   (*Set home zone to the current position*), press **Preview**, then **Upload now** (or turn on automatic upload).
7. On the map: **Layers → Community squares** shows the combined results around the map centre.

Anyone with the URL can submit rows (that is how others contribute), but nobody can read or edit the Sheet except through the
script: the script checks every row, rate-limits each contributor (20 000 rows/day), skips repeated uploads, and only returns
the combined `aggregate` tab.

## Tabs
| Tab | One row per | Notes |
|---|---|---|
| `uploads` | accepted upload part | contributor hash (from a random per-install token), batch, counts |
| `cells` | shared square summary | as above, plus `review`, `review_note` |
| `oddities` | user report or refused row | `source` = `user` or `script` |
| `aggregate` | square × protocol × preset | weighted medians, `contributors`, `days`, `heard_from_top`, `flagged` |
| `meta` | setting | schema version, cell size, limits |

Tested: the script logic runs in CI against an in-memory stand-in for Google Sheets (`tools/community/test.js`). It has not yet
been run on Google's servers.
