# Comparison and RF health rules

Everything here is deterministic code in `core/src/commonMain/kotlin/meshsurvey/core/analysis/` (no LLM, no network,
no transmissions). Thresholds are named constants in `Health.kt` / `EquipmentCompare.kt` / `PairCompare.kt`.
Findings point at *possible* problems; RSSI/SNR alone never establish a defective antenna, damaged receiver,
impedance mismatch or high SWR, and every finding says so where relevant.

## Which receptions count
`ConfidenceRules` (quality/Confidence.kt) labels each reception. Only **Valid** and **Limited** receptions with a position
and an RSSI or SNR value enter statistics. Nothing is deleted; excluded counts are always reported.

## Paired comparison (two receivers' logs)
* A packet is identified by **sender + packet id**. Meshtastic keeps both the same across relays, and each receiver's
  firmware drops duplicate copies, so a match means both receivers heard the packet by some path.
* Matching tolerates device clock offsets up to **120 s**; the same id further apart, or appearing twice in one log, is
  **Ambiguous**.
* Excluded, and counted separately: packets sent by either receiver's own radio; packets delivered late from the radio's
  queue (reception time unknown); packets heard while the other receiver was not logging (**Other receiver unavailable**,
  from logging windows); packets heard on different preset/region (**Incompatible settings**).
* **Comparable** = both + reference-only + mobile-only.
* "Mobile logged X% of the reference's packets" uses the denominator **both + reference-only**. It is an overlap of logs,
  **not a packet delivery rate**: the receivers may be in different places, and the firmware filters packets.
* Positions for reference-only packets come from the mobile's recorded track, **nearest point within 10 s**, otherwise none
  (not placed on the map). Positions are never interpolated.
* Clock offset = median (mobile − reference) over matched packets.

## Equipment comparison (two groups of measurements)
* Only receptions of the **same sender in the same 100 m cell** are compared (a "stratum"), with at least 3 receptions per
  side. Differences are medians over strata of (A − B), in **dB**. Percentages are never used for dB quantities.
* 95% interval: deterministic bootstrap (fixed seed) over strata. At least **5 strata** are required; otherwise the result
  says no fair comparison is possible.
* The plain overall-median difference is shown separately and labelled as mixing places and senders.
* Limitations listed automatically: different presets, regions, firmware or radio models; > 30 days apart; mixed profiles;
  small samples; many low-confidence receptions.

## Health findings
Each finding has: supporting measurements, baseline, sample count, confidence (low/medium/high), alternative explanations,
suggested inspection, and caveats. "Not enough evidence" results are shown as such, never as a score.

| Rule | Level | Fires when | Notes |
|---|---|---|---|
| SOLO-CONFIG | notice | sessions used more than one preset/region/frequency | |
| SOLO-INTERRUPT | notice | interrupted sessions, timer pauses or clock changes | gaps are not "no reception" |
| SOLO-DATA | info (insufficient) | < 30 usable receptions | no signal conclusions drawn |
| SOLO-QUALITY | notice | > 30% of receptions excluded as low-confidence | lists top reasons |
| SOLO-VARIABILITY | notice | receiver stationary; a sender with ≥ 20 readings has SNR IQR > 10 dB | low confidence unless ≥ 3 senders |
| SOLO-MOVING | info (insufficient) | receiver moved | variability not assessed |
| SOLO-OK | info | nothing else fired | "finding nothing is not proof of good equipment" |
| HIST-NODATA | info (insufficient) | < 5 matched strata with the earlier survey | |
| HIST-DECLINE / HIST-IMPROVE | warning / info | matched median diff ≤ −3 dB / ≥ +3 dB **and** 95% interval excludes 0 | |
| HIST-STABLE | info | |diff| < 1.5 dB | |
| HIST-UNCLEAR | info (insufficient) | otherwise | |
| PAIR-SKEW | notice | clocks differ > 30 s | |
| PAIR-INSUFFICIENT | info (insufficient) | < 50 comparable packets | |
| PAIR-LOCATION | info | receivers not within 100 m (median) or distance unknown | no equipment judgement |
| PAIR-WEAKER | warning | side by side; one logged ≥ 30% fewer of the shared packets **and** matched SNR ≥ 3 dB lower | "consider inspecting…" |
| PAIR-CONTRADICTORY | notice | fewer packets but not weaker signals | points away from the antenna |
| PAIR-SIMILAR | info | neither condition | |

Tests: `AnalysisTest` covers valid comparisons, contradictory evidence, incomplete observations (late/unpositioned),
incompatible configurations, ambiguous ids, interrupted recording and insufficient samples.
