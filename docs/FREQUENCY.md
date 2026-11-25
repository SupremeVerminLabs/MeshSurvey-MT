# Nominal frequency: calculated vs configured

**Answer to "can it be calculated?"** Yes, for firmware using the pre-2.8 region/preset tables,
given region, preset (or custom bandwidth), frequency slot, primary channel name and any override
or offset. The radio never reports its operating frequency over the phone API. MeshSurvey labels
every value with its source:

| `frequency_source` | Meaning |
|---|---|
| `CONFIGURED_OVERRIDE` | The radio's LoRa config has `override_frequency` set. Value = override + `frequency_offset`. Read from config, not measured. |
| `CALCULATED` | Computed by MeshSurvey from the config using the firmware formula below. Not reported by the radio. |
| `UNKNOWN` | Something needed is missing (channel name not received, preset or region not in the table, …). No value is stored. |

None of these is a measurement. An SDR check is part of [HARDWARE_VALIDATION.md](HARDWARE_VALIDATION.md) (HV-40).

## Formula (`RadioInterface::applyModemConfig`, firmware master 2026-11-25)

```
bw, sf, cr  = modemPresetToParams(preset)          // src/mesh/MeshRadio.h; custom: config bandwidth
if region span < bw: fall back to LONG_FAST parameters
numChannels = floor((freqEnd - freqStart) / (spacing + bw/1000))            // float arithmetic
slot        = channel_num ? (channel_num - 1) : djb2(primaryChannelName)
slot        = slot % numChannels
freq        = freqStart + bw/2000 + slot * (bw/1000)
if override_frequency: freq = override_frequency
operating   = freq + frequency_offset
```
* `primaryChannelName`: if the primary channel's name is empty, `Channels::getName()` uses
  `DisplayFormatters::getModemPresetDisplayName(preset, false, use_preset)`: "LongFast",
  "MediumFast", "ShortTurbo", "LongMod", … ("Custom" when `use_preset` is false). **So with a
  default channel name, changing the preset changes the slot and frequency.** The preset-change
  plan shows before/after values.
* `djb2`: `h = 5381; for each byte c: h = h*33 + c` (32-bit unsigned).
* The channel PSK is not needed and is never read by MeshSurvey.

## Preset parameters (`modemPresetToParams`)
| Preset | BW kHz | SF | CR | MeshSurvey test-switch |
|---|---|---|---|---|
| SHORT_TURBO | 500 | 7 | 4/5 | yes (legality warning) |
| SHORT_FAST | 250 | 7 | 4/5 | yes |
| SHORT_SLOW | 250 | 8 | 4/5 | yes |
| MEDIUM_FAST | 250 | 9 | 4/5 | yes |
| MEDIUM_SLOW | 250 | 10 | 4/5 | yes |
| LONG_TURBO | 500 | 11 | 4/8 | yes (legality warning) |
| LONG_FAST | 250 | 11 | 4/5 | yes |
| LONG_MODERATE | 125 | 11 | 4/8 | yes |
| LONG_SLOW (deprecated) | 125 | 12 | 4/8 | no |
| VERY_LONG_SLOW (deprecated) | firmware default branch = LONG_FAST params | | | no |
| LITE_*, NARROW_*, TINY_*, MEDIUM_TURBO (2.8) | unknown | | | no; frequency UNKNOWN |

`wideLora` regions (LORA_24) multiply the bandwidth by 3.25.

## Checked values (unit-tested)
| Region | Preset / name | Slot | MHz | Reference |
|---|---|---|---|---|
| US | LongFast (default) | 20 of 104 | 906.875 | widely published default |
| EU_868 | LongFast | 1 of 1 | 869.525 | Meshtastic docs |
| EU_433 | LongFast | 4 of 4 | 433.875 | Meshtastic docs |
| US | MediumFast | 45 of 104 | 913.125 | computed |
| US | ShortTurbo | 50 of 52 | 926.75 | computed |
| EU_868 | ShortTurbo | fallback | 869.525 | computed; fallback path unverified |

Single-precision rounding was checked for the regions in the table: it changes no slot counts.

## Limitations
* Region table is the pre-2.8 `RDEF` list. 2.8 regions (codes 27–37) → `UNKNOWN`.
* The LONG_FAST fallback for regions narrower than the preset bandwidth (e.g. 500 kHz in EU_868)
  is implemented from a source summary and is **not** hardware-verified.
* The primary channel name comes from `FromRadio.channel` during config download. Until it
  arrives, a default-slot frequency is `UNKNOWN`.
