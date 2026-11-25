# Sample survey data — SIMULATED

Every file here was produced by the built-in simulator (`tools/testshim/SampleData.kt`). It is
**not RF evidence**: every row has `source = SIMULATED`, and bundles carry `"contains_simulated": true`.

| File | Contents |
|---|---|
| `simulated-device-A.bundle.json` | 45-minute simulated drive: passive observations, track, one automated A/B session (interval raised to the LONG_FAST airtime minimum; every test row carries delivery mode, preset, calculated frequency and airtime). Importable. |
| `simulated-device-A.observations.csv` / `.tests.csv` | Same data as CSV |
| `simulated-device-A.geojson` | Observation points, test points, track lines |
| `simulated-device-B-reference.bundle.json` | A device in the B role: 15-minute reference session with participation code; tests from a simulated peer carrying that code. Importable. |
