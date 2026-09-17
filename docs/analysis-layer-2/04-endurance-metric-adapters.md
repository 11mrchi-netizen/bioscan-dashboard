# 04 — Endurance metric adapters and source hierarchy

**Linear:** [DAV-55](https://linear.app/biodashboard/issue/DAV-55) · **Design only — no implementation in this doc**

## Scope

"Adapter" here means: a documented, deterministic mapping from whatever raw fields a given
`exercise_sessions` row actually has (run/ride/swim/walk/hike, either source) onto the canonical
endurance features in the [metric registry](02-metric-registry.md) — HR/TRIMP, zone exposure,
pace/speed, threshold-relative intensity, power, sRPE. Per this ticket's own instruction, this
deliberately does **not** collapse those into one universal "load" score — the three models below
(TRIMP-based, pace/speed-based, sRPE-based) stay parallel, named series. This mirrors a precedent
that already exists in this codebase today, currently as an accident of platform migration rather
than a design choice: the Android app's `TrainingLoadEvaluation.kt` runs Banister CTL/ATL/TSB while
the separate web dashboard (`index.html`) still runs the older ACWR model, unreconciled. This
registry makes coexistence of multiple models the intended behavior rather than a migration
artifact.

## Real field coverage by type × source

(Full detail in [01-data-inventory.md](01-data-inventory.md); summarized here for what each
adapter needs.)

| type/source | n | HR avg/max | distance | speed | elevation | power | RPE |
|---|---|---|---|---|---|---|---|
| walk / HC | 4,655 | 90% | 99% | 97% | 0% | 0% | 0% |
| run / HC | 131 | 95% | 92% | 95% | 26% | 5% | 0.8% |
| run / manual | 19 | avg 100%, **max 0%** | 100% | 0% (derivable) | 0% | 0% | 58% |
| ride / HC | 16 | 88% | 63% | 75% | 19% | 0% | 0% |
| hike / HC | 2 | **0%** | 100% | 100% | 0% | 0% | 0% |

Two extremes worth designing for explicitly, not just handling generically: manual runs (no max HR,
ever) and hikes (no HR at all, only 2 real sessions).

## Adapters

### HR/TRIMP

Needs: session `avg_hr`, a max-HR reference (session `max_hr`, or a fallback), and resting HR
(`wearable_daily.rhr`, 42% daily coverage) for HR-reserve normalization.

Degradation ladder when `max_hr` is missing (true for 100% of manual runs and 100% of hikes):
1. Session `max_hr` if present (HC-sourced sessions, 88–98% of the time).
2. Age-estimated max HR (220−age or a chosen formula) as an **inferred** fallback — explicitly
   labeled `inferred` in the provenance tag, never silently presented as measured.
3. If neither exists and `avg_hr` is also absent (hikes: 0% HR coverage) — skip TRIMP for that
   session entirely and fall through to sRPE. Do not synthesize an HR value from RPE; that's what
   the sRPE model is for, kept as its own separate series rather than smuggled into "TRIMP."

### Duration / zone exposure

Session `duration_min` is reliably present. Per-session HR-zone *exposure* (minutes in each zone)
cannot be derived from current data — only a sparse **daily** aggregate exists
(`wearable_daily.zone_minutes`, 3% coverage, and it's a whole-day number, not session-scoped). This
adapter is therefore duration-only for now; zone exposure stays a documented gap, not solved by
inventing a per-session estimate from a daily number that predates or postdates the session.

### Pace / speed

HC-sourced sessions carry `avg_speed_kmh` natively (75–97% coverage depending on type). Manual runs
never store speed (0/19) but always store `distance_km` and `duration_min` — derive pace at query
time (`distance_km / duration_min`) rather than requiring speed to be stored; this is a **derived**
metric per the registry, computed on demand, not backfilled into the row.

### Threshold-relative intensity

Requires a per-user threshold profile (threshold HR, threshold pace per sport, FTP) that **does not
exist in any table today** — real gap, filed as
[DAV-126](https://linear.app/biodashboard/issue/DAV-126) and explicitly out of scope for this
adapter design (the adapter's math assumes the profile exists; populating it is separate work).
Until that profile exists, this adapter cannot run — document the formula now (session avg relative
to threshold, expressed as % of threshold pace/HR/power) so it activates the day the profile lands,
without needing this design revisited.

### Power

`avg_power_w` exists on the schema and is **populated on 0 of 6,558 sessions** — currently
100% unavailable, not merely sparse. Spec the adapter (power zones relative to FTP, once
[DAV-126](https://linear.app/biodashboard/issue/DAV-126) exists) so it's ready the moment any
future source (a power meter, a smart trainer) populates the column — but build no UI for it now;
there is nothing real to show.

### sRPE fallback

`rpe × duration_min` — the universal fallback when HR, power, and pace are all unusable for a
session. Real coverage is thin today (58% of manual runs, under 1% of HC-sourced sessions of any
type) but it's the correct fallback model regardless of how often it fires, and manual entry
already prompts for RPE, so its coverage will improve as more sessions are logged manually.

### Source precedence and conflict handling

No real conflicts exist today — `source` (`manual` vs. `health_connect`) is already
mutually exclusive per session (a given real workout is represented by exactly one row from exactly
one source). This becomes a real question only once a second device pipeline exists (e.g. a future
direct Amazfit sync, per [DAV-124](https://linear.app/biodashboard/issue/DAV-124)'s provenance-column
proposal) and could report the same real-world session Health Connect also saw.

Proposed precedence for that future case, stated now so it's not designed under time pressure
later: **device-measured** (HR/power/pace from any direct sync) > **structured manual entry**
(distance/duration/RPE typed by the user) > **estimated/inferred** (e.g. pace estimated from a
GPS-less manual distance guess). Never blend two sources' numbers into one value for the same
feature — record the winning source in that feature's provenance tag (per the metric registry) and
discard the losing source's value for *that feature only* (a losing source might still win on a
different feature of the same session — e.g. Amazfit wins on HR, a manual note wins on RPE, both
recorded, neither blended).

### Incomplete sessions — worked examples

- **Hike** (0% HR, 100% distance/speed): TRIMP unavailable → falls through to pace/speed-based
  intensity, which is fully available. No sRPE needed since pace-based load already has data.
- **Manual run** (100% HR-avg, 0% max-HR, 0% stored speed, 58% RPE): TRIMP falls back to
  age-estimated max HR (inferred, labeled as such); pace is derived from distance/duration; sRPE
  fills the remaining 42% of sessions with no RPE at all by... having no load value for those
  sessions on that model — not a fabricated one. A session with genuinely no usable signal for a
  given model simply produces no data point for that model, rather than an interpolated guess.

## Explicitly not implemented here

No single universal training-load score. TRIMP, pace/speed-based intensity, and sRPE-based load
remain three parallel, independently labeled series per session (where each has enough real data to
compute) — matching DAV-53's core principle and the existing CTL/ATL/TSB-vs-ACWR precedent, now
made an intentional design choice rather than a migration side effect.
