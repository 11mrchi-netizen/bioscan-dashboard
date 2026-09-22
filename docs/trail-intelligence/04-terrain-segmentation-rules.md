# 04 — Terrain segmentation and metric-comparability rules

**Linear:** [DAV-130](https://linear.app/biodashboard/issue/DAV-130) · **Version:** 1.0.0

## Why this exists

DAV-134 needs a deterministic, versioned rule for turning a raw `Trackpoint` stream (DAV-133) into
smoothed elevation, grade, and discrete climb/descent/flat segments — without either fabricating
micro-climbs out of GPS jitter or discarding real terrain structure. This doc is that rule, chosen
from real, citable industry practice rather than invented from scratch (see sources below), and
versioned so a future revision doesn't silently change every already-computed session's numbers.

## Elevation smoothing

**5-point moving average** over the trackpoint sequence (ordered by `cumulativeDistanceM`), applied
before any gain/loss or grade calculation. This is standard practice for consumer-GPS elevation data —
"a five-point moving average" is explicitly the technique cited by GPX elevation-analysis tooling for
reducing point-to-point noise without erasing real climbing.
Source: [raacon.no Route Elevation Analyzer](https://raacon.no/tools/route-elevation-analyzer).

A point with `elevationM == null` is skipped from the average and carries no smoothed value forward —
never defaulted to a neighbor's value or zero, per DAV-133's own "do not silently repair" rule. A
session whose points are mostly null-elevation cannot produce a smoothed profile at all; that's a real
`NoData` result for elevation-derived metrics on that session, not a fabricated flat line.

## Noise threshold (hysteresis climb/descent detection)

**10 meters of consistent net elevation change** in one direction, applied via a hysteresis walk over
the smoothed profile, not a simple accumulate-every-positive-delta sum:

1. Track a running "pivot" elevation (starts at the first smoothed point) and a running direction
   (unknown initially).
2. For each subsequent smoothed point, if it continues the current direction (or direction is
   unknown), extend the current segment.
3. If it reverses direction, only *close* the current segment and start a new one once the reversal
   has accumulated **10m or more** from the local extremum; a reversal smaller than that is absorbed
   into the ongoing segment as noise, not a new descent/ascent.

**Why 10m, not a smaller number**: this matches Strava's own documented non-barometric threshold —
Strava requires 10m of consistent climbing before counting it for GPS-only sources, dropping to 2m
only when real barometric data backs the reading. Neither Health Connect's `ExerciseRoute` nor a
Drive-sourced GPX declares whether its elevation came from a barometer or GPS-only trilateration, so
this app conservatively assumes GPS-only and uses the higher, safer threshold for every session —
avoiding the "excessive micro-climbs" failure mode DAV-134's acceptance criteria names directly, at
the cost of slightly under-crediting real barometric-quality data. Revisit if/when a real
source-quality signal becomes available (e.g. a future Zepp/Amazfit barometric feed).
Source: [Strava: Elevation](https://support.strava.com/hc/en-us/articles/216919447-Elevation).

## Grade bands

No universal standard exists across platforms (confirmed during doc 02's research — this is a genuine
v1 choice, not a claimed external standard). Bands, symmetric for climbs and descents:

| Band | Grade range |
|---|---|
| Flat | −3% to 3% |
| Gentle | 3–8% (climb) / −3 to −8% (descent) |
| Moderate | 8–15% (climb) / −8 to −15% (descent) |
| Steep | >15% (climb) / <−15% (descent) |

Grade for a point-to-point interval is `elevation_delta_m / horizontal_distance_delta_m`, computed on
the smoothed elevation and the trackpoint model's own `cumulativeDistanceM`.

## Stop handling

An interval's implied speed (`horizontal_distance_delta_m / time_delta_s`) below **0.3 m/s** is
treated as stopped/paused — excluded from segment duration and from grade/VAM denominators, so a
water-stop's GPS drift can't fabricate a fake climb or inflate a segment's apparent pace. The distance
and elevation themselves still count toward the session total; only the *time* is excluded.

## Minimum segment length for performance-comparable segments

Two different bars, for two different jobs, matching what DAV-130's acceptance criteria actually asks
for ("comparability rules support repeatable climb-to-climb and early-vs-late analysis" — a narrower
bar than "represent all real terrain"):

- **Structural segments** (feeding grade distribution / climb-descent structure, DAV-136): any
  hysteresis-closed segment, however short, counts — the goal there is representing the course's real
  terrain, not filtering it.
- **Performance-comparable segments** (feeding VAM/efficiency/durability, DAV-138/139/141/142): a
  segment must additionally clear **≥100m horizontal distance and ≥60 seconds of moving time** to be
  eligible. A segment below this bar is real terrain (still counted structurally) but too short for a
  stable per-segment VAM/pace/efficiency number — excluded from those metrics rather than reported
  with fabricated precision.

## Comparability rule (climb-to-climb, early-vs-late)

Two performance-comparable segments are **comparable** when both hold:

- Average grade within **±3 percentage points** of each other.
- Neither segment's horizontal distance exceeds **2×** the other's.

This is what lets DAV-142's durability metrics pick a real "matched early climb vs. matched late
climb" pair instead of comparing, say, a short steep pitch against a long gentle grade and calling the
difference "degradation." A session with no two comparable segments simply has no durability metric
for that pair — `NoData`, not a forced comparison.

## Versioning

v1.0.0 — `algorithmVersion = "1"` for every metric in doc 03's registry that depends on this
segmentation. Changing the 10m threshold, the grade bands, the 0.3 m/s stop cutoff, or the
comparability tolerances is a version bump here, which propagates to every dependent metric's own
`algorithmVersion` (doc 03's convention) — never a silent change to already-computed sessions'
numbers.
