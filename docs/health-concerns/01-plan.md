# Health concerns: longitudinal injury, symptom and illness intelligence (plan)

Status: built on this branch except deployment (see [README.md](README.md) for what is where and the deploy order, and
[02-validation.md](02-validation.md) for the simulation results). Base: `main` plus every commit of PR #20
(`ccr-b7b17b42-flzlrb`, merged into this branch).
Scope: replace the crude injury/illness logging with a system that shows what repeatedly happens, which factors go
with it, how a problem evolves, how strong the evidence is, and what observation or experiment would clarify it.
It is personal health intelligence, not diagnosis: no diagnosis is required, no injury prediction, no rehab plan.

Contents: 1 audit · 2 data model · 3 analysis rules · 4 pipeline · 5 UX · 6 roadmap · 7 risks, open decisions, deferrals.

---

## 1. Audit (what exists, inspected 21 Oct 2026)

### 1.1 Tables (live project `ugfrglbcoivkprjqvjzz`, Postgres 17)

| Table | Shape (relevant columns) | Rows / coverage | Relevance |
|---|---|---|---|
| `injuries` | `part` text, `type` text, `severity` 0-10 (not null), `status` active/monitoring/resolved, `start_date`, `end_date`, `notes` | 2 (both right knee, both resolved) | Episode-shaped, one row per incident; free-text body part ("Knee (right)" vs "Knee (Right)"); no observations, no recurrence link. **No DDL in `supabase/migrations/`** (created before the folder existed). |
| `illnesses` | `name`, `symptoms` text, `diagnosis`, `medication`, `medications` jsonb, `severity` (nullable, unused), `status`, dates, `doctor_seen` | 2 (resolved, no severity) | Same episode shape. **Read by the analysis core**: `runRelationships.loadFrame` turns every illness span (+1 day) into `frame.excluded`, which the expected bands, relationships, evidence curves, experiments (frozen exclusions) and the typicality scorecard all use. |
| `ostrc_checkins` | `check_date`, free-text `body_area`, OSTRC-H2 q1-q4 with coded values, `severity_score` | 0 | Weekly per-area questionnaire; good functional-impact instrument, never used. |
| `wellbeing_daily` | `energy`, `mood`, `stress`, `soreness` (smallints) | 48 rows; soreness on 45 of the last 90 days | General soreness, not located. Useful context and a fatigue proxy. |
| `evening_checkins` | `drinks`, `caffeine_after_14`, `late_meal`, `stress`, `late_screen`; null = unanswered, 0/false = none | 3 | **The pattern to copy**: explicit zeros vs unanswered. |
| `daily_context` | `utc_offset_min`, `time_zone`, temperature, humidity, daylight | 7 | Local-date conversion; time-zone change (travel) is derivable. |
| `exercise_sessions` | type (run/strength/ride/hike/walk/yoga/other), times, `duration_min`, `distance_km`, `elevation_gain_m`, HR, `rpe`, `details` jsonb (`route_type` road/trail/mixed, `run_type` easy/hills/intervals/long/race/tempo, `exercises`, `exercises_source`) | 6,680 total; 147 in the last 180 days (70 runs, 65 strength); RPE on only 17 ever; elevation on 56 of 70 recent runs; 39 strength sessions with exercises | **Source of truth for exposures.** Duplicates were folded in PR #20 (`exercise_session_tombstones`). No elevation *loss* column. |
| `zepp_workout_detail` | `hr_histogram` (seconds per bpm), `decoded`, `summary` | 412 (411 with histogram) | Intensity distribution per session (zones via `HeartRateZones.kt`). |
| `planned_routes` | GPX terrain (`grade_distribution`, `mountain_index`, elevation loss) | 7, none linked to a session | Terrain for planned events only; not reliable for past sessions yet. |
| `wearable_daily` | `hrv`, `rhr`, `steps`, `spo2_avg`, `hr_timeseries` | HRV and RHR on all of the last 180 days; HRV starts 5 Jan 2026 | Recovery context. |
| `sleep_daily` | `hours`, stages, `bedtime`, `wake_time`, `respiratory_rate` | 180 of the last 180 days | Recovery context; respiratory rate is a possible illness signal. |
| `daily_field_provenance` | origin/writer per daily field | since 12 Oct | Provenance precedent. |
| `analysis_results` | `kind`, `key`, `as_of`, `engine_version`, `inputs_hash`, `state`, `confidence_have/need`, `payload`; unique (user, kind, key, as_of, engine_version); service-role writes | 99 (8 kinds) | **Reuse for derived episodes and findings.** |
| `evidence_ledger` | append-only verdict history per `question_id`; `source` check (catalogue, registered); `verdict` check (clear/negligible/unclear/not_yet) | 78 | Reuse for finding history (needs check constraints widened). |
| `registered_questions` | exposure/outcome check lists (HRV, RHR, sleep only) | 0 | Pattern for "confirm only on new data". |
| `interventions` + `intervention_state_history` | `domain` includes training/sleep/lifestyle; `intended_outcomes` text[]; lifecycle; `evaluate_on` | 0 | **Reuse for trials**; needs a concern link and adherence. |
| `experiments` | randomised designs; `primary_metric` limited to hrv/rhr/sleep/energy/mood/stress/soreness | 0 | Not suitable for injuries in v1 (carry-over, ethics of randomising load into a painful tissue). |
| `notification_rules` / `notification_log` | kinds check list, schedule jsonb, `skip_if_logged` | 2 rules | Reuse for the check-in reminder. |
| `series_break_decisions` | confirmed device/source changes | 0 | Restart points for comparisons. |

pg_cron: `analysis-run-nightly` at 20:30 UTC (04:30 local), plus reminders and the Zepp history pull.

### 1.2 Services and shared code

- `supabase/functions/analysis-run` (nightly + on demand, user JWT or Vault cron secret) runs the shared TypeScript
  core `_shared/analysis-core` (Node-tested in CI, `test-core.yml`). Directly reusable pieces:
  `evidence.ts` (`mantelHaenszel`, time-stratified case-crossover, simultaneous chance bands), `its.ts` (interrupted
  time series read once on a fixed date), `interventionResponse.ts`, `stats.ts` (Wilson-type helpers are absent but
  `benjaminiHochberg`, `blockBootstrapCi`, `mulberry32` exist), `trainingLoad.ts` (the one TRIMP/sRPE load model shared
  with Android and the console), `expectedBand.ts` (each morning against a forecast: a ready-made "HRV below expected"
  exposure), `hash.ts` (`inputs_hash`), `version.ts` (`CORE_VERSION` 3.0.0-alpha.7), `paging.ts` (past the 1,000-row cap).
- `log-agent` sends free text to Gemini (propose-only). Health notes must not go there unless the person opts in.
- Metric registry (`_shared/metric-registry/metric-registry.json`) holds help text, method, limits and references for
  every published number; new outputs need entries.

### 1.3 UI and integration points

| Where | What | Impact |
|---|---|---|
| Android `HealthEventsRepository`, `HealthEventsScreen`, `InjuryFormSheet`, `IllnessFormSheet` | Merge injuries + illnesses for display; free-text part/type; RESOLVE button | Replaced by concerns. |
| Android `StatusRepository` | Status body-figure pin + FLAGS count from open injuries/illnesses (`isHealthEventOpen`) | Must read concern state. |
| Android Training › INJURY (`TrainingTileScreen.InjuryTab`) | Health events list + OSTRC card with load context "shown adjacent, never combined" | Host for anatomical concerns; keep the no-risk-score stance. |
| Android `AddEntrySheet` (Injury, Illness, OSTRC entries; RECENT), `EveningCheckInSheet` | Grouped add sheet; one-tap explicit-nothing evening check-in | Quick check-in entry point and pattern. |
| Android `BodyHeatMap.kt` | Front/back silhouettes, 14 hand-authored muscle polygons (`BodyZone`), tap to read | Basis for the body map; lacks joints (knee, ankle, foot, wrist, elbow), head, and left/right. |
| Android `domain/RegionalLoad.kt` `BodyRegion` | Muscle vocabulary of `exercise_library` | Maps strength exercises to regions (local exposure). |
| Android Lab › N=1 (`N1Tab`, `EvidenceBoardCard`, `ExperimentsSection`) | Evidence board, case-crossover, experiments | Home for findings and trials. |
| Web `index.html` | Reads both tables, `isHealthEventActive`, RES score penalty, sync diff | Needs a compatibility view or an update. |
| Console `Timeline.tsx`, `Recovery.tsx`, `console.html` | Read injuries/illnesses | Same. |
| Design contracts | `ANALYTICAL_PRESENTATION_CONTRACT.md` R1-R8 (rungs OBSERVED/ASSOCIATED/PREDICTIVE/TESTED, verdict words, no cause verbs or arrows, DATA chip ≠ confidence, ledger); visual contract (neutral `#0A0C0E`/`#11161A`, Health accent rose `#F43F5E`, amber `#F59E0B` = warning state and Log accent) | Findings must speak this vocabulary. |

### 1.4 What the audit means for the design

1. Data volume today is tiny (4 episodes, 0 OSTRC, no located observations). For months the honest output is
   "observed recurrence" and "insufficient evidence". The engine must be built to say so, and capture is the first priority.
2. Exposures are rich and already clean: sessions (de-duplicated), terrain tags, run types, HR histograms, HRV/RHR/sleep
   every day. RPE is nearly absent, so intensity comes from run type and HR zones, not sRPE.
3. Everything derived already goes through `analysis_results` + `evidence_ledger` with versions and input hashes. Reuse
   it instead of new findings infrastructure.
4. `illnesses` is load-bearing for the analysis core; migration must keep `frame.excluded` identical until the core reads
   the new model.

---

## 2. Data model

Principles: raw observations are append-only facts; episodes, states and findings are derived and recomputable;
exposures are linked, never copied; every derived row carries engine version, catalogue version, inputs hash and a
data-quality block. All tables: `user_id default auth.uid()`, RLS own-row, `(select auth.uid())` policies as in
`20261015_evening_checkins.sql`.

### 2.1 Entities

```
health_vocab (codes: regions, symptoms; versioned seed)
      │
health_concerns ──< health_observations ──< health_signals
      │   (persistent issue)   (one check-in)      (one symptom/region in it)
      │                              │
      │                              └── session_id → exercise_sessions (optional link)
      ├──< health_episode_marks (user-asserted boundaries; raw)
      ├──< intervention_concerns >── interventions ──< intervention_adherence
      │
derived (analysis_results): health_concern_state (episodes, metrics, state), health_finding, health_trial_read
history (evidence_ledger): one row per finding per night
context: computed in the TS core from existing tables; v_health_day view for display
```

### 2.2 Tables

**`health_vocab`** (seeded by migration, read-only to users; versioned)

| Column | Notes |
|---|---|
| `code` text PK | e.g. `knee`, `achilles`, `plantar_foot`, `lower_back`, `sym.fatigue`, `sym.sore_throat`, `sym.fever` |
| `kind` | `region` / `symptom` / `quality` |
| `parent` | region hierarchy (`knee` → `lower_limb`), symptom group (`sym.cough` → `respiratory`) |
| `label`, `sided` bool | `sided` = region has left/right |
| `followups` jsonb | which conditional questions apply (joint: swelling, instability, locking; tendon: morning stiffness, warm-up response; systemic: temperature) |
| `vocab_version` | bump on change; codes are never reused |

About 35 regions (joints, tendons, muscle groups mapped to `BodyZone`/`BodyRegion`, head, chest, abdomen) and
about 20 symptoms (pain, ache, stiffness, swelling, instability, numbness/tingling, cramp; fatigue, fever/chills,
sore throat, cough, congestion, headache, GI upset, dizziness, low mood, poor sleep, other). Free text stays allowed
via `other` + note.

**`health_concerns`** (persistent, user-defined; no diagnosis required)

| Column | Notes |
|---|---|
| `id` uuid PK | |
| `title` text | user's words: "Right knee (outside)", "Recurring sore throat" |
| `kind` | `anatomical` / `systemic` / `mixed` |
| `onset_type` | `acute` (traumatic event) / `gradual` / `unknown`; acute concerns get no association findings (7.2.5) |
| `regions` jsonb | `[{"code":"knee","side":"right"}]` (empty for systemic) |
| `symptoms` text[] | vocab codes the concern groups |
| `excludes_from_baselines` bool | systemic illness: days in its episodes are left out of HRV/RHR/sleep comparisons (replaces the `illnesses` → `frame.excluded` rule). Default true for systemic. |
| `label_diagnosis` text, `diagnosed_by` text | optional, never required, never used by the engine |
| `resolution_days` smallint | symptom-free span that ends an episode; default 7 anatomical, 3 systemic (editable, recorded in the result) |
| `archived_at`, `created_at`, `updated_at` | archiving hides from quick check-in; history stays |
| `legacy_source`, `legacy_id` | `injuries`/`illnesses` + id for backfilled concerns |

**`health_observations`** (one check-in; append-only)

| Column | Notes |
|---|---|
| `id` uuid PK | client-generated so an offline retry is idempotent |
| `observed_at` timestamptz, `local_date` date, `utc_offset_min` | local date decided by the app (late-night rule as in the evening check-in) |
| `concern_id` uuid null | null = whole-body sweep |
| `scope` | `concern` (about one concern) / `sweep` (anything anywhere) / `session` (prompted by a session) |
| `symptom_free` bool not null | **the explicit zero**: true = "nothing" for the scope; false = signals follow |
| `recall` | `now` / `today` / `since_last` (with `recall_from` date, max 7 days back) — `since_last` + `symptom_free` fills the gap as recalled zeros, flagged lower quality |
| `function_impact` smallint 0-3 null | 0 full participation, 1 reduced, 2 modified/stopped training, 3 daily life affected (an OSTRC-H2 condensation; OSTRC full form remains an option weekly) |
| `change_vs_last` | `better` / `same` / `worse` / null |
| `onset` bool, `mechanism` text null | marks a new acute event ("rolled ankle on descent") — anchors an episode start |
| `session_id` bigint null → `exercise_sessions` | "during / after this session" |
| `timing` | `during_session` / `after_session` / `next_morning` / `at_rest` / `night` / null |
| `care` jsonb | optional: medication taken, saw clinician — description only |
| `note` text | never sent to an LLM unless the person opts in |
| `source` | `app` / `legacy_backfill` / `console` |
| `supersedes` uuid null | edits create a new row pointing at the old one; the engine reads the latest of each chain; nothing is updated in place (trigger refuses updates except `superseded_at`) |
| `created_at` | |

Unobserved = no observation covering that date for that concern (neither concern-scoped, a sweep, nor a recall
range). The engine never treats absence as zero.

**`health_signals`** (many per observation)

| Column | Notes |
|---|---|
| `id`, `observation_id` FK cascade | |
| `concern_id` uuid null | assigned by the app when the person taps an existing concern or region; null until assigned (an unassigned signal shows in "new things" and can start a concern) |
| `symptom` code, `region` code null, `side` null | `left`/`right`/`both`/`midline` |
| `severity` smallint 0-10 | NRS; 0 allowed (present but negligible is not the same as symptom-free) |
| `details` jsonb | answers to conditional follow-ups, validated per `health_vocab.followups` |
| `temperature_c` numeric null | systemic only |

**`health_episode_marks`** (user corrections to derived boundaries; raw input to the engine)

`concern_id`, `kind` (`start` / `end` / `merge_across` / `split_at`), `date`, `note`, `created_at`. The engine applies
marks over its rules, so recalculation never loses a correction.

**`intervention_concerns`** (links a trial to concerns) — `intervention_id`, `concern_id`, `hypothesis` text,
`outcome` (`flare_rate_per_exposure` / `mean_severity` / `symptom_free_share` / `impact_days`),
`exposure_unit` (`run_km`, `run_hours`, `sessions`, `days`), `pre_days` (default = trial length, min 28),
`registered_at`. **`intervention_adherence`** — `intervention_id`, `date`, `adhered` (`yes`/`partial`/`no`), `note`.
Both reuse `interventions` for name, domain (`training`, `sleep`, `lifestyle`), lifecycle, `started_at`, `ended_at`,
`evaluate_on`.

### 2.3 Derived outputs (no new tables)

- `analysis_results` kind `health_concern_state`, key = concern id: current state, episodes (deterministic ids:
  `fnv1a(concern_id + start_date + rule_version)`), per-episode metrics, recurrence metrics, data quality.
- kind `health_finding`, key = `concern:exposure:window`: status, counts, denominators, effect and interval, competing
  explanations, next step, evidence links (episode ids, observation ids, session ids).
- kind `health_trial_read`, key = intervention id: before/after read, frozen at `evaluate_on`.
- `evidence_ledger`: one row per finding per night (`source = 'health'`, widen the check; add `'possible'`,
  `'persistent'`, `'insufficient'`, `'observed'`, `'faded'` to the verdict check, or a separate `status` column).

### 2.4 Context features

No duplicated store. Exposures are built in the TS core from `exercise_sessions` (+ `zepp_workout_detail.hr_histogram`,
`details.route_type/run_type/exercises`), `wearable_daily`, `sleep_daily`, `wellbeing_daily`, `evening_checkins`,
`daily_context`, and the published `expected_band` flags, using the same loaders (`loadFrame`, `trainingLoad.ts`).
Findings link back by session id and date. The daily context the charts need (run km, trail km, load, sleep, HRV
against its band) is published inside each concern's `health_concern_state` payload by the same exposure builders the
findings use, so the chart and the evidence can never disagree. (Built differently from the first draft, which had a
`v_health_day` view: one implementation instead of a SQL copy of the TypeScript rules.)

### 2.5 Migration implications

1. **Capture legacy DDL**: write `20261101_legacy_health_events_baseline.sql` documenting `injuries`/`illnesses`
   as they exist (no-op `create table if not exists`), so the repo describes the schema.
2. **New tables** (one migration, re-runnable, rollback block like the existing files).
3. **Backfill** (idempotent SQL function `private.backfill_health_concerns()`): each injury → anatomical concern
   (region parsed from `part` with a mapping table; unmatched → `other` + title kept), an onset observation at
   `start_date` (severity from `injuries.severity`, `source = legacy_backfill`), a symptom-free observation at
   `end_date` when resolved. Same for illnesses (systemic, `excludes_from_baselines`, symptoms from text → `other`
   + note). Each legacy row becomes its own concern (decision 7.2.5: the two right-knee injuries were different
   contusions); injuries get `onset_type = acute`. Legacy rows gain nothing; mapping lives in `legacy_id`.
4. **Analysis core**: `loadFrame` reads the union of `illnesses` spans and systemic episodes with
   `excludes_from_baselines` (deduplicated by `legacy_id`). A golden test asserts `frame.excluded` is unchanged on
   today's data. Bump `CORE_VERSION`.
5. **Compatibility view `v_health_events`** (legacy shape: kind, title, status, start, end) for `index.html` and the
   console until they are updated; Android switches directly.
6. **Retire legacy writes** after the new capture ships: drop insert policies on `injuries`/`illnesses`; keep tables
   read-only for a release; `ostrc_checkins` stays (0 rows) as an optional weekly instrument.
7. `notification_rules` kind check gains `health_checkin` and `health_session_prompt`.

---

## 3. Analysis and decision layer (rule engine v1)

Language follows contract R5: findings say "came before", "went with", "followed"; never "caused", "triggers",
"prevents". No arrows. Rung: concern state = OBSERVED; patterns = ASSOCIATED (exploratory until persistent); trials =
OBSERVED (before/after, not randomised).

### 3.1 Daily concern series

For each concern and local date: `observed` (bool), `severity` (max signal severity; 0 when explicitly symptom-free;
null when unobserved), `impact` (max function_impact), `recalled` (filled by a `since_last` sweep), `onset` flag.
A sweep with `symptom_free` covers every active concern; a sweep listing signals covers only the concerns those
signals are assigned to (other concerns stay unobserved that day unless the sweep says "nothing else").
Symptom threshold: severity ≥ 2 counts as symptomatic (1 = barely noticeable; configurable, recorded in output).

### 3.2 Episodes (rule version `ep-1`)

- **Start**: first symptomatic day after the concern was resolved or never active; an `onset` observation forces a start.
- **Continue**: symptom-free days inside an episode do not end it until the resolution span is met.
- **End**: `resolution_days` consecutive *observed* symptom-free days (default 7 anatomical / 3 systemic; recalled days
  count but lower quality); end date = first day of that run. Unobserved days neither extend nor break the run; a run
  with unobserved days needs at least 3 observations across it.
- **Uncertain boundaries**: the start's earliest possible date is the day after the last symptom-free observation;
  the end's latest is the last symptom-free observation before the run completed. Duration is shown as a range
  when they differ ("9-14 days").
- **Gaps**: an unobserved stretch > 14 days inside an episode sets `gap` (duration shown as range); > 28 days with
  symptoms on both sides → two episodes with `boundary_uncertain`.
- **User marks** override rules (`start`, `end`, `merge_across`, `split_at`) and are listed in the payload.

Per episode: duration (range), peak severity and its date, severity-days (area under the curve of observed days,
linear interpolation across ≤ 2-day gaps only), impact days (impact ≥ 2), days from peak to resolution, observation
coverage within the episode, linked sessions.

Per concern: episode count, recurrences in the last 90/365 days, **symptom-free intervals** (median, min between
episodes, with ranges), recurrence within 30 / 90 days of resolution, trend of episode duration and peak (shown as a
list, no slope fitted below 5 episodes).

### 3.3 Current state

Evaluated in this order, with the rule shown in the card's "?":

| State | Rule |
|---|---|
| UNOBSERVED | No observation covering the concern in the last 7 days (3 for an active systemic episode). Shows the last known state and date; never extrapolated. |
| RESOLVED | Not in an episode (resolution span met) and observed within 7 days. |
| ACTIVE | In an episode with fewer than 4 observations, or onset within 3 days. |
| WORSENING | In an episode, ≥ 4 observations; median severity of the last 3 observations (within 7 days) is ≥ 2 points above the median of the 3 before (within the previous 14 days), or impact rose by ≥ 1 level on 2 consecutive observations. |
| IMPROVING | Same comparison, ≥ 2 points lower, or impact fell by ≥ 1 level, and no WORSENING criterion met. |
| STABLE | In an episode, ≥ 4 observations, neither of the above. |

Two points on a 0-10 NRS is the commonly cited minimal clinically important difference; it is a parameter.

### 3.4 Data quality block (on every output)

`coverage_30d`/`coverage_90d` (share of days observed), `symptom_free_observed_days` (explicit zeros in the
analysis window), `recalled_share`, `within_episode_coverage`, exposure coverage (sessions with HR histogram, runs
with route_type, days with HRV), confirmed series breaks in the window, `informative_missingness` (observations per
day during episodes vs outside; > 3× flags that controls are under-observed). Reported as DATA: GOOD / PARTIAL / THIN
per contract R2, never as confidence.

### 3.5 Exposure catalogue (versioned `hx-cat-1`, fixed before any analysis)

Each exposure has one **primary window**; one or two sensitivity windows are shown but never used to promote a status.
Exposures apply only to concern families where they are plausible (anatomical lower limb / anatomical trunk & upper
limb / systemic), which caps the questions per concern at about 8-10.

| Exposure | Definition (existing data) | Families | Primary window (sensitivity) |
|---|---|---|---|
| Any run | run session that day | lower limb, trunk | 0-2 d (0-1, 0-3) |
| Long run | run ≥ 90 min or ≥ own 80th percentile duration (180 d) | lower limb, trunk | 0-2 d |
| Trail / hilly run | `route_type` trail/mixed, or ≥ 25 m/km climb (as `RunCategory.kt`) | lower limb | 0-2 d |
| Quality run | `run_type` intervals/tempo/hills/race, else ≥ 20% HR time in Z4+ | lower limb | 0-2 d |
| Run-volume jump | last 7 d run km > 1.3 × mean weekly km of the previous 28 d (descriptive threshold, not a risk model) | lower limb | 0-3 d |
| Back-to-back run days | runs on both of the two previous days | lower limb | 0-1 d |
| Regional strength | strength session whose exercises load the concern's region (`exercise_library` muscles → region map) | any anatomical | 0-2 d |
| No rest day | ≥ 6 consecutive days with a session | all | 0-1 d |
| Short sleep | sleep < 6 h (or < own 10th percentile) | all | 0-1 d (mean of 3 nights) |
| HRV below expected | `expected_band` below 80% band | all | 0-2 d |
| High weekly load | 7-day load (trainingLoad.ts) > own 80th percentile | all | 0-3 d |
| Alcohol / high stress | `evening_checkins` drinks ≥ 1 / stress ≥ 4 (only on answered evenings) | systemic | 0-2 d |
| Travel | `daily_context.time_zone` changed | systemic | 0-3 d |

Terrain descent, footwear, surfaces and weather are listed as "not available" in the payload, not silently dropped.

### 3.6 Association analyses

**A. Flare onsets against comparable symptom-free days (time-stratified case-crossover).** Case = an episode start
(or a WORSENING transition, analysed separately). Referents = days in the same 28-day stratum, same weekday class
(weekday/weekend), that were *observed symptom-free*, outside any episode and its 3 following days. Exposure measured
in the primary window before each day. Mantel-Haenszel odds ratio with RBG interval (`evidence.mantelHaenszel`).
Because referents sample how often an exposure occurs on ordinary days, a common exposure (most days include a run)
does not look like a trigger merely because it often precedes flares. The card shows the plain version too:
"Before 6 of 8 flare-ups (75%) a trail run came in the 2 days before; on 31% of comparable symptom-free days (41 of 132)."

**B. Exposure-anchored follow-up (session level)**, for frequent exposures: of sessions of type X, the share followed
by symptoms (severity ≥ 2 or worse-than-before) within the window, against other sessions of the same sport, only
where the following days were observed. Risk ratio with a Wilson/Newcombe interval (add to `stats.ts`). "After 9 of 21
trail runs (43%) the knee was symptomatic within 2 days; after 4 of 38 road runs (11%)."

**C. Descriptive episode context** (no inference): for each episode, the 14 days before onset vs the person's 90-day
typical (run km, long runs, load, sleep, HRV band): "This flare followed your highest 7-day run volume since June."

Not in v1: regressions of severity on exposures, recovery-time predictors, multi-exposure models (too few episodes).

### 3.7 Evidence ladder and thresholds

| Status | Requirement | Wording |
|---|---|---|
| INSUFFICIENT EVIDENCE | Any requirement below unmet. Says which and how much is missing ("needs 3 more flare-ups with check-ins in the days before; 14 more symptom-free check-ins") | no pattern words |
| OBSERVED RECURRENCE | ≥ 2 episodes of the concern; no association claim | "has come back 3 times in 6 months; median 34 symptom-free days between" |
| POSSIBLE PATTERN (exploratory) | Concern not `acute` (7.2.5). Analysis A: ≥ 5 cases, ≥ 3 exposed cases, ≥ 20 referent days, DATA not THIN, MH OR lower 95% bound > 1, OR ≥ 1.5, BH-adjusted q < 0.10 across the concern's catalogue questions (primary windows only); or analysis B: ≥ 15 exposed sessions with follow-up, ≥ 3 symptomatic, RR lower bound > 1, same q rule | "went with", "came before", plus EXPLORATORY |
| PERSISTENT PATTERN | Was POSSIBLE; then ≥ 3 *new* cases (onsets after the date it first became possible; the confirmation set, like `registered_questions`) with estimate > 1 on new cases alone and combined lower bound still > 1; holds on the sensitivity windows in direction | "has kept going with … across 4 new flare-ups since it was first noticed" |
| FADED | Was POSSIBLE/PERSISTENT; lower bound ≤ 1 on two consecutive nightly evaluations with new cases | kept in history: "no longer clear after 3 more flare-ups" |

Safeguards against misleading correlation:

1. **Fixed catalogue and windows**, versioned before data; no data-driven window search. Sensitivity windows can only
   weaken a status.
2. **Multiplicity**: BH across the concern's primary questions each night; the ledger stores every evaluation;
   promotion to PERSISTENT needs new data only (no re-use of the discovery set), mirroring phase E's confirmation rule.
3. **Base rates always shown** (exposed share of referent days or of sessions), never "7 of 8 flare-ups followed a run" alone.
4. **Controls must be observed**: unobserved days are never controls; `informative_missingness` > 3× blocks POSSIBLE.
5. **Co-exposure check**: for a POSSIBLE exposure, report how often it co-occurs with each other catalogue exposure
   among cases (trail runs are often long runs) and the MH estimate stratified by the strongest co-exposure when ≥ 3
   discordant cases allow; if the estimate vanishes, the finding lists the co-exposure first.
6. **Competing explanations** (rule-generated, listed on every POSSIBLE/PERSISTENT): co-exposure (5); overall volume
   (more runs = more opportunities: compare with "any run"); reverse direction for systemic (HRV low *before* illness
   is commonly early illness, not a cause); logging bias (5/4); seasonal or block effects (stratified, but named when
   the cases cluster in one training block); a device/source change in the window (series breaks).
7. **Simulation gates in CI** (Node tests with `mulberry32`): on null synthetic histories (exposures independent of
   flares, realistic missingness that rises during flares), POSSIBLE on ≤ 5% of concerns per year of nightly runs,
   PERSISTENT on ≤ 1%; a planted 3× effect with 10 episodes reaches POSSIBLE in ≥ 60% of runs; reports the numbers in
   the doc like phases C-F did.

### 3.8 Every finding answers five questions (payload contract)

```jsonc
{
  "what": "Right knee flare-ups came after trail runs more often than comparable symptom-free days did.",
  "evidence": { "cases": 8, "exposedCases": 6, "referentDays": 132, "exposedReferents": 41,
                "or": 5.1, "lo": 1.4, "hi": 18.6, "q": 0.06, "window": "0-2 d", "links": { "episodes": [...], "sessions": [...] } },
  "explanations": ["Trail runs were also your long runs in 5 of 6 cases", "Run volume rose in the same weeks"],
  "uncertainty": "Wide interval: 8 flare-ups. EXPLORATORY until 3 more flare-ups are checked.",
  "nextStep": { "kind": "observe", "text": "Check in the day after your next 3 trail runs, symptom-free or not." },
  "status": "possible", "rung": "associated", "dataQuality": "PARTIAL",
  "versions": { "engine": "3.1.0", "catalogue": "hx-cat-1", "episodes": "ep-1", "inputsHash": "…" }
}
```

### 3.9 Next steps (rule table, no prescriptions)

| Situation | Suggested step |
|---|---|
| Unobserved active concern | "Quick check-in: same, better, worse or gone?" |
| Few explicit symptom-free days | "Tap NOTHING on good days too; comparisons need them (12 more)." |
| Observed recurrence, no pattern | "Review the sessions before each flare-up" (links) |
| Possible pattern | "Note how the knee feels after the next trail runs", or "consider a trial that changes trail volume, set up in N=1" |
| Persistent pattern | "A structured trial could clarify it"; "if it limits training, this summary may be useful to a physio" (export) |
| Safety prompts (always on, not findings) | severity ≥ 8; impact 3 for > 3 days; worsening on 3 consecutive observations; fever ≥ 39 °C or systemic episode > 10 days; numbness/weakness, chest pain, or night pain at rest → "consider seeing a clinician". Non-diagnostic wording, no thresholds hidden. |

---

## 4. Pipeline and architecture

```
Android (capture) ──RLS──> health_observations / health_signals / health_concerns / marks / adherence
        │                                           │
        │ after save: POST analysis-run {action:"health"} (user JWT, this user, ~1 s)
        ▼                                           ▼
analysis-run (Edge Function, Deno) ── _shared/analysis-core/health/*.ts (pure, Node-tested)
   nightly via existing pg_cron 20:30 UTC        reads: concerns, observations, signals, marks, sessions(+histograms),
   order: series breaks → frame → expected bands → … → healthConcerns (needs band flags) → healthFindings → healthTrials
        │
        ▼ service role, upsert by (user, kind, key, as_of, engine_version)
analysis_results (health_concern_state, health_finding, health_trial_read) + evidence_ledger (append-only)
        │
        ▼
Android Health › CONCERNS, Training › INJURY, Status figure, Lab › N=1; console Timeline / Evidence; v_health_day for charts
```

- **Pure core modules**: `healthSeries.ts` (daily series, recall fill), `healthEpisodes.ts` (rules 3.2-3.4),
  `healthExposures.ts` (catalogue 3.5 from frame + sessions), `healthAssociations.ts` (A, B, C, BH, co-exposure),
  `healthFindings.ts` (ladder, explanations, next steps, payload), `healthTrials.ts` (4.1), `runHealth.ts` (I/O).
- **SQL only where it is plain**: `v_health_events` (legacy shape). Day series, episodes and context lanes stay in TS
  and are published in the payload (rules, marks and uncertainty are tested there, once).
- **On-demand action** keeps the state fresh after a check-in without a second Kotlin implementation of the rules.
  Android shows the last published state plus "updated just now"; offline it shows the raw entry as pending.
- **Reproducibility**: `inputs_hash` over the latest-of-chain observation ids and `created_at`s, marks, and session
  ids/updated fields; `HEALTH_RULES_VERSION` (`ep-1`, `hx-cat-1`) in every payload; `CORE_VERSION` bump; ledger rows
  never updated; old engine rows stay readable (readers take the latest of the current engine, as phase A fixed).
- **Privacy**: RLS own-row on every table; no LLM call in the pipeline; notes excluded from `log-agent` context;
  the console only reads; export is an explicit user action producing a local file.

### 4.1 Trials (read rules)

A trial = an `interventions` row (e.g. domain `training`, "trail runs ≤ 1 per week") + `intervention_concerns`.
Read once at `evaluate_on` (min 28 days after start; frozen thereafter, as `its.ts`):

- Pre window = the `pre_days` before start; post = start to evaluate_on, adherent periods flagged.
- Outcome per exposure unit where relevant: flare-ups per 100 run-km before vs after (Poisson rate ratio, exact
  interval); mean observed severity; symptom-free share of observed days; impact days. Coverage must be ≥ 60% in
  both windows or the read is INSUFFICIENT.
- Words: OBSERVED rung, "since the change", four outcomes (LOWER / HIGHER / NO CLEAR CHANGE / INSUFFICIENT); listed
  explanations always include natural recovery and regression to the mean (trials started at a flare peak are marked),
  concurrent changes in total volume, and adherence share.
- No automatic proposal to stop or continue (as contract R7's observational rule).

---

## 5. UX and visualisation

Placement (proposal): **Health › CONCERNS** is the home (covers illness and fatigue); **Training › INJURY** shows the
same concern cards filtered to anatomical concerns with the existing load context (adjacent, never combined); the
Status body figure pins regions of ACTIVE/WORSENING/IMPROVING/STABLE concerns; findings and trials live in **Lab ›
N=1** beside the evidence board. Colour: Health's rose accent for the domain; state colours per contract (warning
amber only for WORSENING and safety prompts); neutral gunmetal surfaces; one pill per card (A8).

1. **Quick check-in** (target: ≤ 3 taps on a normal day, ≤ 20 s with one active concern)
   - Entry: Status quick log, Log `+`, a reminder (default 21:00, skipped when logged), and an optional prompt after a
     synced run only while an anatomical lower-limb concern is active (max one per day).
   - Screen: "ANYTHING BOTHERING YOU?" `NOTHING ✓` (one tap, explicit zero for all active concerns) or the list of
     active concerns, each with `GONE · BETTER · SAME · WORSE` and a severity stepper prefilled with the last value;
     `+ NEW` opens the body map. "Missed days? NOTHING SINCE <date>" offers a recall sweep (≤ 7 days).
2. **Body-map logger**: extend `BodyHeatMap` into `BodyMap` (front/back, joints and tendons added as tap targets,
   side taken from the tapped half, mirrored labels "R KNEE"); a SYSTEMIC row of chips (fatigue, fever, sore throat,
   cough, congestion, headache, GI, other). After a tap: severity 0-10 stepper → impact (4 options) → at most 2
   conditional follow-ups from `health_vocab.followups` → optional session link ("during/after today's run?") → save.
   Tapping a region that has a concern assigns the signal to it; otherwise CREATE CONCERN (title prefilled "Right knee").
3. **Concern page** (pushed screen, A4): header pill (state word), last check-in, DATA line in "?".
   - **Timeline**: severity line (gaps where unobserved; explicit zeros as small ticks on the baseline; recalled zeros
     hollow), episode spans shaded with duration ranges, onset markers, user marks, session ticks below (shape by
     type, trail filled) — one time axis, 3M/6M/1Y switch.
   - **Episodes table**: start, duration (range), peak, severity-days, impact days, symptom-free before it.
   - **Recurrence strip**: episodes per quarter + symptom-free intervals as bars.
   - **Recovery overlay**: small multiples (FTSmallMultiples) of severity against days since onset, one per episode.
   - **Impact chart**: stacked days by impact level per episode.
4. **Unified view** (concern page tab CONTEXT): lanes on one axis — concern severity; daily run km (trail stacked),
   strength sessions loading the region; 7-day load; HRV distance from expected (band); sleep hours. Tap a flare →
   WHAT CAME BEFORE: the 14 days listed against the person's typical (analysis C), each session opening Session Detail.
5. **Findings**: one card per finding, status word (INSUFFICIENT / RECURRING / POSSIBLE · EXPLORATORY / PERSISTENT /
   FADED), the plain-count sentence with base rate, interval drawn against no-effect (OR 1), "WHAT ELSE COULD
   EXPLAIN IT", "NEXT STEP", "EVIDENCE" (episodes and sessions, tappable), ledger history ("POSSIBLE since 3 Dec").
6. **Trial tracker**: concern-linked card in N=1: hypothesis, dates, adherence calendar (tap yes/partial/no), interim
   numbers labelled interim with no verdict, the frozen read at the end.
7. **Console**: Timeline workspace reads concerns/episodes; Evidence tab lists health findings with the same payload.

Design work: one Figma pass on the check-in and concern page before M2/M3 code (optional; contracts are precise
enough to build from).

---

## 6. Roadmap (milestones → issues; acceptance criteria)

Dependencies: M0 → M1 → (M2 ∥ M3) → M4 → M5 → M6 → M7. M3's engine can start on synthetic data while M2 builds capture.

Done on this branch: H-01 to H-28, H-30 (registry and contract R9). Partly: H-29 (the app no longer writes the legacy
tables and the web dashboard and console read `v_health_events`; dropping the legacy insert policies waits one release,
owner decision 7.2.7). Not done here: deployment, and compiling the Android UI (no Android SDK in the build container).

### M0 · Decisions and vocabulary (small)
- **H-01** Confirm the open decisions in §7.2 (placement, reminder time, thresholds, legacy merge). *AC*: answers recorded in this doc.
- **H-02** Region and symptom vocabulary v1 with follow-ups and region→`BodyZone`/`BodyRegion`/exercise-muscle map. *AC*: JSON + seed SQL; every `BodyZone` and `exercise_library` muscle maps to a region; Node test checks the map is total.

### M1 · Data model and migration
- **H-03** Baseline DDL for `injuries`/`illnesses` in migrations. *AC*: `create table if not exists` matches live columns and checks (diffed with information_schema).
- **H-04** Tables `health_vocab`, `health_concerns`, `health_observations`, `health_signals`, `health_episode_marks`, `intervention_concerns`, `intervention_adherence`; RLS; supersede trigger; client-id idempotency. *AC*: applied on a branch DB; advisors clean; update on `health_observations` refused except supersede; rollback block present.
- **H-05** View `v_health_events` (legacy shape for the web dashboard and console). Daily series and context lanes are in the published payload instead of SQL views (§2.4). *AC*: legacy view returns the 4 existing events; unobserved days are null in the payload, explicit zeros 0.
- **H-06** Backfill function. *AC*: re-runnable (no duplicates on second run); 4 legacy events → 4 concerns with onset/resolution observations; the two knee contusions stay separate.
- **H-07** `loadFrame` reads systemic episodes with `excludes_from_baselines` ∪ `illnesses`. *AC*: golden test — `frame.excluded` identical on fixtures and on today's data; CORE_VERSION bumped.

### M2 · Capture (Android)
- **H-08** `HealthConcernsRepository` (observations with supersede chains, offline-safe ids). *AC*: unit tests for latest-of-chain and local-date rule.
- **H-09** Quick check-in sheet (NOTHING, per-concern GONE/BETTER/SAME/WORSE, recall sweep). *AC*: ≤ 3 taps no-symptom path; explicit zero stored; saved-state survives process death (Savers.kt).
- **H-10** `BodyMap` component (joints, sides, systemic chips) + conditional follow-ups. *AC*: every vocab region tappable at 360 dp; accessibility labels; snapshot test.
- **H-11** Concern management (create, rename, archive, merge, marks). *AC*: archive hides from check-in only.
- **H-12** Reminders `health_checkin` and post-run prompt. *AC*: skipped when logged; ≤ 1 post-run prompt per day; only with an active lower-limb concern.
- **H-13** Replace Injury/Illness forms and HealthEvents list; Status figure reads concern state. *AC*: figure pin clears when RESOLVED; old forms removed from the add sheet.

### M3 · State and episode engine
- **H-14** `healthSeries.ts`, `healthEpisodes.ts` with rules 3.2-3.4. *AC*: tests for start/continue/end, gaps, ranges, recall, marks; deterministic episode ids.
- **H-15** `runHealth.ts` + `analysis-run` action `health` and nightly hook; `health_concern_state` published. *AC*: re-run same inputs → same hash, no new row; on-demand returns < 2 s.
- **H-16** Concern page: header, timeline, episodes table, recurrence, recovery overlay, impact. *AC*: unobserved gaps visible; ranges shown; chart rules R4 respected (DateTrendLine/ChartRules).
- **H-17** Registry entries (`health_concern_state`, episode metrics). *AC*: `metricRegistry.test.ts` passes.

### M4 · Context and unified view
- **H-18** `healthExposures.ts` (catalogue `hx-cat-1`, region applicability, unavailable list). *AC*: each exposure tested on fixtures; parity of load with `trainingLoad.ts`.
- **H-19** Descriptive analysis C (what came before each episode). *AC*: published in the state payload; no inferential words.
- **H-20** CONTEXT tab lanes + WHAT CAME BEFORE panel linking to Session Detail. *AC*: one time axis; tap-through works.

### M5 · Findings
- **H-21** `healthAssociations.ts` (A, B; Wilson/Newcombe helpers; BH; co-exposure). *AC*: matches hand-computed 2×2 and MH golden values.
- **H-22** Ladder, explanations, next steps, safety prompts (`healthFindings.ts`). *AC*: every payload has the five answers; no cause verbs (lint test over strings).
- **H-23** Simulation gates (§3.7, safeguard 7). *AC*: numbers within gates, recorded in `docs/health-concerns/02-validation.md`.
- **H-24** Ledger integration (source `health`, statuses) + confirmation set. *AC*: PERSISTENT only from post-flag onsets; ledger append-only verified.
- **H-25** Findings cards (Android N=1 + concern page), console Evidence. *AC*: base rate always shown; evidence links open episodes/sessions.

### M6 · Trials
- **H-26** Concern-linked interventions + adherence UI. *AC*: hypothesis and outcome required before start; adherence one tap per day.
- **H-27** `healthTrials.ts` read at `evaluate_on`, frozen. *AC*: exposure-normalised rate ratio; INSUFFICIENT under 60% coverage; null-simulation false "LOWER" ≤ 5%.
- **H-28** Trial card. *AC*: interim labelled, no verdict before evaluate_on.

### M7 · Retire and document
- **H-29** Web `index.html` and console read concerns (or `v_health_events`); drop legacy insert policies. *AC*: no client writes to `injuries`/`illnesses`.
- **H-30** Docs: README for the folder, contract amendment (health statuses and wording), registry methodology. *AC*: reviewed against R5-R8.

Size guide: M1 ~3-4 days, M2 ~5-7, M3 ~4-5, M4 ~3, M5 ~5-7, M6 ~3-4, M7 ~2.

---

## 7. Risks, open decisions, deferrals

### 7.1 Key risks

| Risk | Mitigation |
|---|---|
| Too little data for months (4 episodes, no located history) | The ladder makes INSUFFICIENT the default and says what is missing; value early comes from the timeline, recurrence and WHAT CAME BEFORE. |
| Logging fatigue; check-ins only when it hurts (informative missingness) | One-tap NOTHING, recall sweep, reminder skipped when logged, post-run prompt only when relevant; missingness ratio blocks patterns. |
| Many exposures × windows → false discoveries | Fixed catalogue, one primary window, BH, confirmation on new data only, simulation gates. |
| Reverse causation and prodromes (illness lowers HRV before symptoms) | Named as a competing explanation; systemic findings on recovery metrics never go past POSSIBLE in v1. |
| Confounding by co-exposure and volume | Co-exposure table and stratified estimate; "any run" always shown beside specific run types. |
| Misread as medical advice | No diagnosis fields required; safety prompts generic; no prescriptions; contract wording. |
| Breaking the analysis core's illness exclusion | Dual-read with a golden test before switching (H-07). |
| Exposure data gaps (RPE nearly absent, no elevation loss, terrain only by tag) | Intensity from run type/HR zones; unavailable exposures listed; descent deferred. |
| Privacy of free-text notes | No LLM path; RLS; export only on request. |

### 7.2 Design decisions (owner, 21 Oct 2026)

1. Home: **Health › CONCERNS**; Training › INJURY shows the same cards filtered to anatomical concerns.
2. Accent: **Health rose** for the domain; amber only for WORSENING and safety prompts (contract unchanged).
3. Check-in: **its own reminder at 21:00** (`health_checkin`), skipped when logged.
4. Thresholds: symptomatic at **severity ≥ 2**; resolution spans **7 days** anatomical, **3 days** systemic.
5. Legacy knees: **not merged**. They were two different contusions, so each legacy row becomes its own concern
   (one episode each). Consequence for the model: concerns carry `onset_type` (`acute` = a traumatic event such as a
   contusion or a sprain, `gradual`, `unknown`). Acute concerns get the timeline, recovery metrics and WHAT CAME BEFORE,
   but no exposure-association findings (a knock is not an overuse pattern); a new knock to the same knee is a new
   concern unless the person says it is the same problem coming back.
6. OSTRC-H2: **kept** as an optional weekly instrument; the table stays.
7. Illness exclusion: **dual-read for one release** (`illnesses` ∪ systemic episodes), then switch.

### 7.3 Explicitly deferred

- Injury/illness prediction, risk scores, ACWR-style warnings, any ML model.
- Rehabilitation plans, exercise prescriptions, return-to-run protocols.
- Randomised injury experiments (carry-over, harm); trials stay before/after observational.
- Severity regressions, recovery-time predictors, multi-exposure models, pooling across concerns.
- Footwear/gear tracking, surface, descent from GPX for past sessions, weather as an exposure.
- Photo logging of swelling/rashes, voice logging, LLM summaries of notes.
- Wear-OS or widget check-ins; web dashboard capture (read-only there).
- Clinician-facing export beyond a plain summary file.
