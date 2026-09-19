# 08 — Backtest: competing load and time-dynamics models

**Linear:** [DAV-62](https://linear.app/biodashboard/issue/DAV-62) · **Status:** complete against real data; most outcome associations are too weak/small-n to act on — reported as such, not dressed up

## Method

Every number below came from live SQL against Supabase project `ugfrglbcoivkprjqvjzz`, run
2026-09-19, using the account's real `exercise_sessions`/`wearable_daily`/`sleep_daily`/
`wellbeing_daily` history (2023-05-05 → 2026-09-18, 1,233 days). No synthetic data. The daily load
series reimplements the same TRIMP formula as `EnduranceLoad.kt`'s `trimp()` and the same dual-EWMA
walk as `Stats.kt`'s `dualEwma()` (tau 7/42, matching `TrainingLoadEvaluation.kt`'s real constants) —
this is a backtest of what the app actually runs, not a hypothetical alternative model.

## TRIMP vs. sRPE vs. pace-based — can't actually compare them on this account

167 real run/ride sessions with `duration_min` populated:

| Signal | Computable on | Coverage |
|---|---|---|
| TRIMP (needs `avg_hr`+`max_hr`+same-day `rhr`) | 116 / 167 | 69% |
| sRPE (needs `rpe`) | 13 / 167 | 8% |
| **Both** | **2 / 167** | **1%** |

**Finding**: TRIMP and sRPE are almost never computable on the *same* real session — a direct
correlation between them would be n=2, meaningless. This isn't a modeling question, it's a real
logging-behavior fact: RPE only gets logged on manual entries, HR only exists on Health-Connect-synced
entries, and the two rarely overlap. TRIMP is the only one of the two with enough coverage to backtest
anything else against; sRPE stays as the documented fallback for when TRIMP's inputs are missing
(exactly the degradation order `EnduranceLoad.kt` already implements), not as a candidate to swap in
as primary. Pace-based load was not separately backtested — `derivedPaceMinPerKm`/`effectiveSpeedKmh`
have no fixed threshold to compare against yet ([DAV-126](https://linear.app/biodashboard/issue/DAV-126)
is still open), so there's no third series to correlate.

## Rolling mean vs. EWMA — they agree overall but fail differently, not interchangeably

Built a zero-filled daily TRIMP series over all 1,233 days, then compared a flat rolling mean to an
EWMA at matching windows (7d and 42d, α=2/(τ+1) per the app's real formula):

| Window | corr(rolling, EWMA) | rolling day-to-day volatility (σ) | EWMA day-to-day volatility (σ) |
|---|---|---|---|
| 7-day | 0.917 | 12.1 | 19.7 |
| 42-day | 0.943 | (not separately reported — same convergence pattern) | — |

**Finding, and it's the opposite of the usual assumption**: at a 7-day window, EWMA is *more*
volatile day-to-day than the flat rolling mean, not less. A 7-day EWMA's α=0.25 gives a single new
day 25% of the total weight; a flat 7-day mean gives it 1/7≈14%. Real example: on 2025-07-26, a
single large session (TRIMP≈1,016, roughly 5× a typical day) pushed the 7-day EWMA up **244.8** in one
day (37.0 → 281.7), while the flat 7-day mean only moved **145.2** for the same event. The two models
don't fail the same way:
- **Rolling mean's discontinuity is an edge effect** — a big session's departure from the trailing
  window causes a cliff on some *later*, unrelated day, not the day of the event itself.
- **EWMA's discontinuity is front-loaded** — it reacts hardest on the day of the spike itself, then
  decays smoothly, no delayed cliff.

Neither is strictly "smoother" — they trade one artifact for a different one. At 42 days the two
converge much more closely (corr 0.943 vs 0.917) because a longer window's weights are flatter either
way. This doesn't argue for switching off EWMA (the app's current choice already matches the published
Banister PMC convention DAV-59 implements), but it does mean **a single unusually large session should
be expected to visibly move next-day ATL**, and that's correct model behavior, not a bug to smooth away.

## Multidimensional vs. single-axis representations

Not separately re-litigated here — [DAV-56](https://linear.app/biodashboard/issue/DAV-56)'s
`WorkoutLoadVector.kt` already made this call (keep strength/endurance/regional dimensions separate,
never collapsed into one score) before this backtest ran. Nothing found below argues against it: the
weak, inconsistent outcome associations (next section) are exactly what you'd expect if collapsing
dimensions into one number would just average away whatever real signal exists in each — a reason to
keep dimensions separate, not a reason found independently.

## Association with subsequent outcomes — mostly too weak or too small-n to use

Correlated the endurance ATL/TSB series against next-day change in each outcome. **These are
observational correlations on one account's real history — not causal claims, and most are not even
statistically distinguishable from zero at this sample size:**

| Association | r | n | Read |
|---|---|---|---|
| ATL → next-day ΔRHR | −0.028 | 387 | No detectable linear association |
| ATL → next-day ΔHRV | 0.023 | 256 | No detectable linear association |
| TSB → same-day HRV | 0.072 | 257 | Negligible |
| ATL → next-night sleep hours | 0.119 | 1,131 | Weak positive, largest-n result here, still explains ~1% of variance |
| ATL → next-day soreness | 0.294 | **19** | Plausible direction (more fatigue → more soreness) but n=19 is the entire real history of `wellbeing_daily` — not enough to trust, could easily be noise |

**No "subsequent performance" row**: see [07-performance-anchors.md](07-performance-anchors.md) —
no observed-performance anchor existed to correlate against when this backtest ran; that doc defines
one for a future re-run once enough paired run data accumulates.

**Honest read of this table**: at this account's current data volume, none of the wellness-outcome
correlations are strong enough to justify tuning the model to chase them — the RHR/HRV associations
are indistinguishable from zero, and the one moderate-looking correlation (soreness) has too few
points to mean anything yet. This directly informs [09-parameter-learning.md](09-parameter-learning.md)'s
minimum-data-volume gate: don't let a per-user parameter fit key off signals this weak.
