# places.tsv.gz

Place names used offline to name History days ("Sat 27 Nov 2026 · Concord, CA"). Nothing is looked up online.

Source: [GeoNames](https://www.geonames.org) via the `cities.json` package (github.com/lutangar/cities.json, commit e5e759c,
2026-11-01): places with more than 1000 inhabitants or administrative seats. Licence: **Creative Commons Attribution 4.0**
(https://creativecommons.org/licenses/by/4.0/). Converted to one tab-separated line per place: name, latitude, longitude
(4 decimals), country code, region code (kept only when alphabetic, e.g. US state codes). No other changes.
