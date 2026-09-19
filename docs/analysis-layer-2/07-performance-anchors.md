# 07 — Performance and adaptation anchors

**Linear:** [DAV-61](https://linear.app/biodashboard/issue/DAV-61) · **Status:** anchors defined, 3 of 5 blocked on real data gaps already filed

## Observed performance vs. modeled capacity

Two different things get called "performance" and this project keeps them separate everywhere below:

- **Observed performance** — a directly measured result on a real session: pace at a given HR, a
  completed set's weight×reps, a race time. Ground truth. Never derived from the load model itself.
- **Modeled capacity** — an estimate a model produces: estimated VO2max, Epley-estimated 1RM
  (`StrengthLoad.kt`'s `estimatedOneRepMax`), CTL as a fitness proxy. Useful, but circular as a
  *validation anchor* — you can't backtest the load model against a number the load model (or a
  vendor's own opaque model) produced.

Only observed performance qualifies as an anchor for [DAV-62](https://linear.app/biodashboard/issue/DAV-62)-style
backtesting. Modeled capacity is a downstream *output*, not a ground truth to check against.

## Candidate anchors, checked against real data

| Anchor | Observed or modeled | Real data today | Verdict |
|---|---|---|---|
| Running pace/HR relationship | Observed (pace, HR both measured) | 131 HC runs + 19 manual, `avg_hr` 95% on HC | **Usable, with confounders below** |
| Power benchmarks | Observed | `avg_power_w` 0/6,558 (per [01-data-inventory.md](01-data-inventory.md)) | **Blocked** — no data exists, not a modeling gap |
| VO2max trend | Modeled (vendor-side estimate, opaque algorithm) | 57/1,279 (4%) | **Contextual only, not an anchor** — see below |
| Strength rep/load progression | Observed | 1 of 6,558 rows has structured `details` (per 01-data-inventory) | **Blocked** — same root gap as DAV-54's linkage backlog |
| Movement-pattern-specific anchors | Observed | `RegionalLoad.kt`/DAV-65 classifies sessions, but same 1-row structured-data gap applies | **Blocked**, same cause |

### Running pace/HR — the one anchor with enough real data to use

Defined as: for two runs close enough in time that fitness hasn't materially shifted, is pace at a
given HR getting faster (efficiency improving) or slower (fatigue/detraining)? This needs **paired**
sessions, not just a count of 150 runs.

**Minimum data requirement**: at least 3 runs within a rolling 21-day window with both `avg_hr` and
`avg_speed_kmh` (or `distance_km`+`duration_min`) populated, to fit even a crude pace-vs-HR line. Real
account coverage today (167 run/ride sessions, `avg_hr` 158/167) makes this *possible* but not
*normalized* — see confounders.

**Confounders, real ones found in this account's own data, not textbook ones**:
- No route/terrain field exists anywhere in `exercise_sessions` — two runs at the same pace/HR could
  be flat vs. hilly, and `elevation_gain_m` is 26% populated on HC runs / 0% on manual, so it can't
  reliably normalize this away.
- No weather/temperature field — heat alone shifts HR at a given pace by real, non-trivial amounts.
- Pacing strategy (even effort vs. negative split) isn't captured, and changes what "avg HR" means
  for a given "avg pace."
- **Circularity risk with TSB**: if this anchor is later used to validate the CTL/ATL/TSB model
  (DAV-60), the anchor itself is partly a function of the athlete's current fatigue — a slow run on a
  high-ATL day isn't necessarily model error, it may be the model correctly predicting reduced
  performance. Anchors used for validation need to control for TSB at anchor time, not just report a
  bare pace/HR trend.

### VO2max — contextual signal, not a validation anchor

Health Connect's VO2max estimate is itself a vendor model (likely already incorporating recent pace/HR
history) — using it to validate this app's own training-load model would be validating one opaque
model against another, not against ground truth. Kept as a **contextual trend indicator** only
(already what `wearable_daily.vo2max` is used for), never as a DAV-62 backtest anchor.

### Strength and movement-pattern anchors — structurally blocked today

[01-data-inventory.md](01-data-inventory.md) already found only 1 of 6,558 sessions has the
structured `exercises[].sets[]` payload DAV-54 defines. `StrengthLoad.kt`/`RegionalLoad.kt`
(DAV-57/65) are fully implemented and will activate the moment real structured strength data exists,
but there is currently exactly one real data point — not enough to define an anchor, let alone
backtest against one. No new issue filed; this is the same gap DAV-54's own linkage backlog already
tracks.

## What this unblocks and what it doesn't

DAV-62's backtest (see [08-backtest-results.md](08-backtest-results.md)) could not evaluate load
models against "subsequent performance" for exactly this reason — no anchor existed yet when that
backtest ran. It evaluated against HRV/RHR/sleep/soreness instead, which are observed but are
wellness proxies, not performance anchors. Once real paired run sessions accumulate with a
terrain/effort-normalization strategy, a pace/HR anchor can be added to that backtest — this doc
defines it, it doesn't yet make it retroactively available.
