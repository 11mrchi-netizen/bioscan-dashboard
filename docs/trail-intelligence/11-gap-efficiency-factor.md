# GAP and Efficiency Factor (DAV-272)

Grade-Adjusted Pace, Efficiency Factor (EF) and HR decoupling for Zepp-synced runs. This
replaces DAV-40's "not feasible" verdict: that assumed no per-point elevation was persisted;
`zepp_workout_detail.decoded` now stores per-second altitude, distance and HR.

Computed **server-side** in `supabase/functions/zepp-extract/effort.ts` (pure, no imports;
tests: `node supabase/functions/zepp-extract/effort.test.ts`) and stored as scalars in
`decoded.summary`: `gapMinPerKm`, `efficiencyFactor`, `hrDecouplingPct`, `smoothedAscentM`.
The app reads scalars only. Only sport types 1 (run) and 7 (trail run) get them.

## Method

1. Altitude (metres; the raw field is cm, no-fix samples dropped) is smoothed with a
   +/-15 s moving average.
2. The run is cut into segments of at least 30 m of travel. Segment grade = smoothed altitude
   change / distance, clamped to +/-45 % (Minetti's fit range). Segments with a gap over 120 s
   or speed under 0.5 m/s (pauses) are skipped.
3. Flat-equivalent distance = distance x cost(grade) / cost(0), with Minetti et al. 2002
   `cost(i) = 155.4 i^5 - 30.4 i^4 - 43.3 i^3 + 46.3 i^2 + 19.5 i + 3.6` (identical to
   `minettiCostOfTransport` in `domain/trail/UphillPerformance.kt`).
   GAP = moving time / flat-equivalent distance.
4. EF = GAP speed (m/min) / average HR over the same segments. Stored only when the run is
   aerobic (average HR below Zepp's own `lactateThresholdHr`) and at least 20 min.
5. HR decoupling = (EF first half - EF second half) / EF first half, in %, same eligibility.

## Display

- RUN EFFICIENCY card (Training tab): 28-day rolling median of EF, shown only once
  `EF_MIN_RUNS_28D` = 6 eligible runs exist in the trailing 28 days (DAV-40's gate); a per-run and
  a median line on the shared timeframe switch.
- RUN DYNAMICS card (session detail): that run's GAP, EF and decoupling.

## Validation

- Unit fixtures: a flat run gives GAP = actual pace; a constant 5 % climb gives
  pace x cost(0)/cost(0.05); a slowing second half gives positive decoupling; above-threshold-HR
  and under-20-min runs get GAP but no EF.
- Real data: smoothed ascent should land near Zepp/HC ascent (Sept 23 ~ 20 m). The first
  version mis-read altitude as metres (3003 m "ascent", GAP 2:27/km) which is how the cm unit
  was found.
