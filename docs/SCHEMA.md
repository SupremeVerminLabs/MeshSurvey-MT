# Local database schema (SQLite, version tracked in `PRAGMA user_version`)

Defined in `core/src/commonMain/kotlin/meshsurvey/core/store/SqlStore.kt` (`Schema.migrations`).
New versions are appended; released migrations are never edited.

Conventions: times are Unix milliseconds unless the name ends in `_s` or `_time`. Node numbers are
unsigned 32-bit values stored as INTEGER. `NULL` always means **unknown**. `source` ∈
{`LOCAL_RF`, `IMPORTED`, `SIMULATED`}. `origin_device` is `''` for rows recorded by this install,
otherwise the exporting install's device id.

## v1 (Lite)

| Table | Purpose | Key columns |
|---|---|---|
| `kv` | app metadata (e.g. `device_id`) | key, value |
| `survey_session` | one passive logging session per radio connection | id, started_ms, ended_ms, status (ACTIVE/ENDED/INTERRUPTED), receiver_node, link_kind, firmware, preset, region, source, **duplicates_suppressed** |
| `observation` | one row per reception delivered by the radio, never merged | rx_ms, node_rx_time, lat, lon, accuracy_m, fix_age_ms, rssi, snr, packet_id, from_node, to_node, channel, portnum, type, status (DECODED/ENCRYPTED/QUEUED_LATE), hop_start, hop_limit, hops_away, relay_byte, payload_len, preset, region, frequency_mhz, summary, source, origin_device |
| `track_point` | receiver path | ms, lat, lon, accuracy_m, **radio_ready**, source, origin_device |
| `logging_window` | intervals when the radio was connected (makes "not received" meaningful) | start_ms, end_ms, origin_device |
| `test_device` | user-registered test devices | node_num, label, added_ms |
| `test_session` | A sessions (manual/auto) and B reference sessions | id, role, mode, peer_node, interval_s, duration_s, end_reason, source, origin_device |
| `test_tx` | controlled transmissions | test_session_id, seq, sent_ms, packet_id, to_node, lat/lon/accuracy_m/fix_age_ms, want_ack, status, status_ms, routing_error |
| `test_rx` | confirmed receptions at Device B | test_session_id, seq, from_node, packet_id, rx_ms, sender_time_s, rssi, snr, hop_start, hop_limit, relay_byte, lat/lon |
| `node_name` | display names (local only, never exported) | node_num, long_name, short_name, role |
| `import_record` | imported files | sha256, imported_ms, origin_device, kind, rows |

Unique constraints prevent duplicate imports: `observation(origin_device, receiver_node, packet_id, rx_ms)`,
`track_point(origin_device, ms, lat, lon)`, `test_tx(origin_device, test_session_id, seq)`,
`test_rx(origin_device, test_session_id, seq, packet_id)`, plus the whole-file SHA-256 in `import_record`.

Crash recovery runs on start: sessions still `ACTIVE` become `INTERRUPTED`, ending at their last
logged row. Unfinished A test sessions get `end_reason = 'interrupted …'`.

## v2 (Lite refinement)
* `observation.frequency_source` (`CONFIGURED_OVERRIDE` / `CALCULATED` / `UNKNOWN`). v1 values become `CONFIGURED_OVERRIDE`.
* `test_session.preset`, `test_session.delivery_mode`
* `test_tx.delivery_mode` (`ACKED` / `DIRECT_ONLY`), `hop_limit`, `preset`, `frequency_mhz`, `frequency_source`, `airtime_ms`
* `test_rx.preset`, `frequency_mhz`, `frequency_source`
* Status names: `IMPLICIT_ACK→RELAY_OBSERVED`, `NOT_CONFIRMED→UNKNOWN`, `NOT_SENT→NOT_TRANSMITTED`,
  `FAILED→NO_ACK` (or `NOT_TRANSMITTED` for local refusal errors)
* `preset_change`: id, node_num, created_ms, **original_raw** (hex of the exact LoRaConfig bytes),
  temp_raw, original_preset, target_preset, status (`APPLYING`, `ACTIVE`, `APPLY_UNCONFIRMED`,
  `RESTORING`, `RESTORED`, `RESTORE_UNCONFIRMED`), status_ms, auto_restore, note. Never deleted by
  "delete all data".

The v1→v2 migration is covered by a test.

## v3 (release readiness)
* `test_session`: `participation` (`CODE` / `OPERATOR_CONTROLS_B`), `ref_code`, `planned_end_ms`,
  `air_util_tx_start/_end`, `ch_util_start/_end` (radio-reported %, null if not reported)
* `test_rx.ref_session_id`: B's reference session that accepted the test
* `observation.fix_source`: GPS / NMEA_SERIAL / MANUAL / SIMULATED / MOCK
* `clock_event`: `TICK_GAP` (app suspended) and `CLOCK_JUMP` (wall clock changed), exported in bundles
* Recovery on startup additionally closes B reference sessions and sets awaiting tests to `UNKNOWN`
  (`routing_error = INTERRUPTED`)
* Preset backups are also written as files outside the database (`preset-backups/*.json`)

## JSON bundle (interchange and backup), `format = "meshsurvey.bundle"`, `version = 1`

```json
{ "format": "meshsurvey.bundle", "version": 1, "app": "0.1.0", "exported_ms": 0,
  "origin_device": "a1b2c3d4e5f6", "contains_simulated": false, "notes": "...duplicates suppressed...",
  "sessions": [], "observations": [], "track": [], "logging_windows": [],
  "test_sessions": [], "test_tx": [], "test_rx": [] }
```

v2 fields are optional additions, so older bundles still import; v1 status names are mapped on
import. Only rows recorded by the exporting install are included (no re-export of imported data). On
import, `source` is preserved and `origin_device` is set to the bundle's origin. Importing your own
bundle, or the same file twice, is refused.
