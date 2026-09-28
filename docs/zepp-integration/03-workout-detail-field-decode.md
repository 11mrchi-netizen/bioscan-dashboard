# 03 — Decoding `detail.json`'s per-second fields

**Linear:** DAV-113, DAV-123, DAV-115 · **Status:** core fields confirmed against a real
GPS run, cross-checked against that same run's own summary numbers

## Method

Every field below was decoded by hypothesis-and-check against one real run: track
`1790121540`, 2026-09-23, a GPS-tracked "800m Repeat" tempo run (`course_title` field),
7.882 km, 2974 s, avg HR 146, real GPS start at Yuanshan Village, Taiwan. The run's own
`sport_history` **summary** entry (`avg_heart_rate`, `max_heart_rate`, `dis`, `avg_pace`,
`max_pace`, `location` geohash, `avgAltitude`/`highestAltitude`/`lowestAltitude`) served
as independent ground truth to check each decoded series against — not guessed, checked.

All per-second fields have 2924–2975 entries for this 2974 s run, i.e. **1 Hz sampling**.

## Confirmed fields

### `heart_rate` — absolute start + per-second signed delta

Format: `"<flag>,<value>;<flag>,<value>;..."`. The **first** entry's value is the
absolute starting HR; every entry after that is a signed integer delta to add to the
running total. `flag` (seen: `7` only on entry 1, then `""`/`0`/`2`) does **not** gate
the decode — every entry's value participates in the same running sum regardless of
its flag.

```
running_hr[0] = value[0]
running_hr[i] = running_hr[i-1] + value[i]   for i > 0
```

Checked: reconstructed series has min=91, max=169, avg=146.0 — an **exact** match to
the summary's `min_heart_rate`/`max_heart_rate`/`avg_heart_rate` for this run.

### `pace` — raw per-second value, seconds per meter

Plain semicolon-separated decimals, no delta encoding, no flag. `0` means paused/no
GPS-derived pace for that second.

Checked: non-zero values range 0.24–6.07; summary's `max_pace` = 0.248 (matches the
series minimum, since pace is inverted — a *smaller* number is *faster*) and
`avg_pace` = 0.3773 (same unit, same order as the series average of 0.415, with the
difference expected since the summary is distance-weighted and the raw average is
simple time-weighted over a session with pauses).

### `speed` — `<flag>,<value>` per second, meters/second

Format: `"<flag>,<value>;..."`, flag meaning not yet determined (doesn't gate the
value). Checked: max speed 4.03 m/s → 1000/4.03 = 248 s/km, an **exact** match to the
summary's `max_pace` = 248 s/km-equivalent — the two fields agree via `pace = 1000 /
speed`.

### `altitude` — raw per-second value, CENTIMETRES (corrected 2026-09-25)

Plain semicolon-separated integers, no delta encoding. **Unit is cm, not m** — the first
decode assumed metres because the Sept 23 run read 922–2054, which sat near the
summary's `lowestAltitude`/`highestAltitude` (1075/1869); the same summary fields are
also cm. Real ascent that day was ~20 m (HC `elevation_gain_m` = 20), i.e. a 9–21 m
span, and the 12 Sep trail run tops out at 30663 = 307 m. Samples with no fix are
written as about −2,000,000 and must be dropped. `zepp-extract` (v15+) divides by 100
and skips those samples; older rows decoded before v15 hold wrong `altitudeM` until
re-synced. Consumed by GAP/EF: see `docs/trail-intelligence/11-gap-efficiency-factor.md`.

### `longitude_latitude` — absolute first point + per-second delta, ×1e8

Format: `"<lat_or_dlat>,<lon_or_dlon>;..."`. The **first** pair is absolute
`(lat × 1e8, lon × 1e8)`; every pair after that is `(Δlat × 1e8, Δlon × 1e8)` to
cumulatively sum against the previous absolute position.

```
lat[0], lon[0] = value[0] / 1e8
lat[i] = lat[i-1] + value[i].dlat / 1e8   for i > 0  (same for lon)
```

Checked two ways: (1) the first point (25.0685946, 121.5227088) matches the summary's
`location` geohash (`wsqqt7d5h9rb` decodes to 25.068576, 121.522726 — same point to
4 decimal places); (2) summing haversine distance between consecutive reconstructed
points over the whole run gives 7770 m, within 1.4% of the summary's own `dis`
(7882 m) / `highPrecisionDistance` (7882.21 m) — the real GPS track.

### `gait` — per-second cadence and vertical stride ratio

Format: `"<f1>,<f2>,<vertRatio×10>,<cadence_spm>;..."`. `f1`/`f2` are low-integer
state flags (0 before the run starts, settling to small values once steady) with
no summary field to check them against, so left undecoded.

Checked against this run's summary: avg of the 4th field (excluding the pre-run
zeros) = 175.5 vs `avg_frequency` 173.0; max = 200 vs `max_frequency` 201 — cadence,
steps/min. Avg of the 3rd field ÷10 = 8.89% vs `avgVertStrideRatio`/10 = 8.6% —
vertical stride ratio, a percentage with one implied decimal place.

No ground-contact-time or stride-length **per-second** series was found in `gait`
or any other field for this device — those exist only as this workout's own
summary averages (below).

### Workout summary fields (not per-second, from `sport_history`'s list entry)

These live on the *summary* entry, not `detail.json` -- carried through
`WorkoutRef` at extraction time rather than decoded from the detail payload:

| Field | Meaning | Unit |
|---|---|---|
| `avg_frequency` / `max_frequency` | avg/max cadence | steps/min (string on avg, number on max) |
| `avg_stride_length` | avg stride length | cm |
| `averageGct` | avg ground contact time | ms |
| `avgVertStrideRatio` | avg vertical stride ratio | ×10 (divide by 10 for %) |
| `lactateThresholdHr` / `lactateThresholdPace` | Zepp's own lactate threshold estimate | bpm / sec-per-km |

Lactate threshold is **not a dedicated test result tied to one event** — Zepp
recomputes it per qualifying run (confirmed: `lactateThresholdUpdateFlag` field
present alongside it). "Current" is whichever run was synced most recently, not a
fixed historical event.

## Not yet decoded

- **`lap`** — 11 entries for this run (matches summary's `total_group: 11`, the
  "800m Repeat" template's segment count), so this is **per-lap**, not per-second.
  ~65 comma-separated fields per entry, order undocumented; some positions look like
  lap distance/duration/avg-HR by inspection but nothing here is checked the way the
  fields above are — don't trust a guessed field order from this section alone.
- **`heart_range`** (in the summary, not `detail.json`'s per-second data) is a
  time-in-zone histogram: `"<seconds>,<bpm_bound>;..."` — e.g. this run's
  `"1,94;25,112;438,131;1260,150;1242,169;0,188"` sums to ~2966 s (≈ the run's
  2974 s), giving seconds spent below each bpm boundary. Useful independent of the
  per-second `heart_rate` decode above.
- `runningPower`/`cadence` were empty strings for this run (device `MILI_Y_GENEVA_W` /
  Helio Strap) even though the **summary** carries `average_power: 271`,
  `max_power: 383` — this device reports power as a summary stat only, not a
  per-second series, at least for this run. Whether the Active Max (the project's
  primary device, DAV-110/123) reports a real per-second power series is still open —
  needs a run recorded on that specific watch to check.
- `sport_type` code mapping is tracked separately: DAV-268.

## Sources

- Real payload: `zepp_workout_detail`/`zepp_raw_extracts` rows for track `1790121540`
  (Supabase project `ugfrglbcoivkprjqvjzz`), captured via the app's real Zepp sync.
- Cross-checks run directly against Postgres (`execute_sql`): per-field entry counts,
  cumulative-sum reconstruction, haversine path length, geohash decode.
