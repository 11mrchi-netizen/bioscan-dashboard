# Analysis Layer 3

Design and status notes for the layer that supersedes the Analysis Layer 2 *contracts* (docs `06`, `19` of `docs/analysis-layer-2`) while keeping the Layer 1 per-metric evaluations (`domain/*Evaluation.kt`) as inputs. Origin: `docs/wellness-project-teardown/14-integration-report-for-field-terminal.md` (what to take from a competitor and why). Approved plan: `C:\Users\ddema\.claude\plans\compiled-moseying-naur.md` (decisions listed there).

Documents: [contract-v3.md](contract-v3.md) (the published result shapes), [interaction-registry.md](interaction-registry.md), [healthspan.md](healthspan.md). The engines are described below.

## Architecture in one paragraph

A pure TypeScript core in `supabase/functions/_shared/analysis-core/` (erasable TypeScript, so Node 24, Deno and Vite all import it unchanged) holds the statistics and engines. The Edge Function `analysis-run` reads raw rows, runs the core and upserts versioned results into `analysis_results` (service-role writes, own-row reads). The console and the Android app read those rows. Existing on-device Layer 1 evaluations are unchanged; they gain optional `windowStart` parameters so a baseline can restart after a *confirmed* series break.

## Tables (migrations in `supabase/migrations/`, applied 2026-10-12)

| Table | Purpose |
| -- | -- |
| `analysis_results` | `(user_id, kind, key, as_of, engine_version)` unique; `state`, `confidence_have/need`, `payload jsonb`, `inputs_hash`, `calculated_at`. Select-only for users. |
| `daily_field_provenance` | Which origin wrote each daily field. Side table because the Health Connect sync upserts each metric separately. History before 2026-10-12 is `unknown`, not backfilled. |
| `series_break_decisions` | The user's answer for a break candidate: `source_change` (restarts baselines), `real_change`, `ignore`. |
| `experiments` | A pre-registered supplement N-of-1 plan (supplement, primary and at most three secondary outcomes, design, phases, washout, minimum adherence, the preview shown at registration). Own rows only; a trigger blocks changes to everything except `status`. |
| `interaction_records` | Curated supplement and drug pairs (mechanism, effect, advice, minimum gap, severity, citation, `review_status`). Read-only for signed-in users; a CHECK refuses `reviewed` without a citation. See `interaction-registry.md`. |
| `log_agent_runs` | One row per typed message: utterance, raw model entries, proposals, and (after the app saved) `committed_count`. `unique(user_id, client_turn_id)` makes a retry return the stored proposals. Own rows only, written with the caller's JWT. |

## Status

| Phase | State |
| -- | -- |
| 0 Foundations | **Done.** Core stats + fixtures, `test-core.yml` CI, console schema repair (pushed to `main` as `cdfda5e`), `analysis_results`, `analysis-run` deployed (v4). The nightly job `analysis-run-nightly` (20:30 UTC = 04:30 UTC+8) is **live and verified**: a manual `select private.run_analysis()` authenticated, ran for the account and stored 7 results. |
| 1 Provenance and series breaks | **Code complete and deployed except the Android build.** Core detector/result builder/I-O tested (35 Node tests); `analysis-run` deployed and run live. `zepp-extract` **v14 deployed** (verified byte-identical to the repo file): per-field provenance in `daily_field_provenance`, 2 h sleep floor, on top of the already-live lap-status fix (v13, which had never been committed). Android: `windowStart` on the SWC evaluators and `computeSleepIndex`, wired at every call site through `AnalysisRepository.loadSeriesBreakStarts()` (no effect until a break is confirmed); the Health Connect sync records per-field origin (HRV, RHR, SpO2, VO2max, sleep fields); `IntegrityCategory.SERIES_BREAK`; 421 unit tests pass. **Not yet true on the phone:** the app change is unit-tested and compiles, but no build has been installed, so `daily_field_provenance` stays empty until you install one and sync (Zepp rows will start appearing from the next `zepp-extract` run). Still open: wiring `DataIntegrityValidator` into a Settings card (no production callers today). |
| 2 Capture layer | **Built (2a-2e); live checks open.** 2a: alcohol is recorded as grams of ethanol (`volume_ml x ABV% / 100 x 0.789`) from a strength field with beer/wine/spirits shortcuts; a drink with no strength is refused. 2b: Edge Function `log-agent` v1 deployed (propose-only, see below). 2c: a "SAY IT" entry in the add-entry sheet shows the proposals for review. 2d: two new reminder kinds, `wellbeing_checkin` (10:00, skipped once any of energy/mood/stress/soreness is logged today) and `evening_log` (19:30, skipped once a "say it" message was saved today); migration `20261012_notification_capture_kinds.sql` widened the kind CHECK and seeded both rules (enabled) for the existing account. 2e: Settings > User > DATA CAPTURE shows days logged in the last 28 per stream. 435 Android unit tests and 49 Node tests pass. **Not yet exercised:** the live model call needs a signed-in user (the function boots and rejects anonymous calls with 401), the screens and notifications need an installed build, and the "~50 utterances" golden run against the real model is still to do. |
| 7 Methodology as data | **Done (Android wiring partial).** `metric-registry.json` has an entry for every doc-02 metric plus Sleep Index, Dynamic Recovery, alcohol and series break; a Node test fails when a doc-02 row lacks an entry or a field. Android packages it as an asset and `FTCard(methodology = key)` shows it behind the help button: HRV, RHR, sleep duration, Sleep Index, Today's Recovery, SRI, sleep phases, respiratory rate, training load, Today's Readiness. Other cards still use their own help text; the console does not read the registry yet. 438 Android unit tests pass. |
| 3 Confirmatory relationships | **Done and live.** Engine, nine-hypothesis catalogue v1.0.0, publisher (`analysis-run` v5, nightly), console Relationship Lab "Confirmatory" tab (committed in the console worktree, **not yet pushed to `main`**) and a read-only PATTERNS card on the Android Daily Readiness page. Details and the backtest below. 440 Android unit tests and 66 Node tests pass. |
| 4 Personal Normal as typicality | **Engine done and live; gate missed on real data, so it ships as a quiet drill-down.** `typicality.ts` + publisher in `analysis-run` (v7, nightly), tests on synthetic data pass, Android "IS TODAY TYPICAL?" card (read-only, Daily Readiness). It is **not** a Today's Recovery contributor and there is no push prompt, per the plan's fallback. Details below. 445 Android unit tests and 69 Node tests pass. |
| 5 Supplement N-of-1 experiments | **Done and live; not yet used.** `experiments` table with a trigger that locks the pre-registered protocol, `experiment.ts` + publisher in `analysis-run` (v8, nightly) and an `experiment_preview` action, Android "TEST IT (EXPERIMENT)" on each active supplement (registers the plan and shows what the design could detect first) and an EXPERIMENTS card, console Relationship Lab "Experiments" tab (committed in the console worktree, **not pushed**). No experiment has been registered yet, so nothing has run end to end on a device. Details below. 450 Android unit tests and 95 Node tests pass. |
| 6 Interaction registry | **Done and live; every entry still unreviewed.** `interaction_records` seeded with 11 cited pairs (applied 2026-10-13), pure `detectInteractions` (same slot, too close, spaced, any timing, missing partner, as-needed, helpful pairing), an INTERACTIONS card on the Supplements screen and a live check in the add/edit sheet. The app prints "(unreviewed)" beside every effect until you mark a row reviewed. Citations and what to decide: `interaction-registry.md`. 464 Android unit tests and 95 Node tests pass. |
| 8 Healthspan grade | **Built; Android build not installed.** `Healthspan.kt` (eight factors, trust gate, provisional letter bands), `HealthspanRepository`, a HEALTHSPAN card at the top of the User profile page, and a test that no other screen may use it. The profile page's sample data is gone: the running level is now the real VO2max placement, the other domains read "Not enough data yet", achievements are real only. Strength is the median level of your main barbell lifts over six months (lifter standards, labelled as such); handgrip is a loggable entry (GRIP) shown but not scored. Cut-offs, sources, gate and deviations (TyG dropped): `healthspan.md`. 481 Android unit tests and 77 core Node tests pass. |
| 9 Supersede and retire | **Done for what is gated; the rest is listed below.** Contract v3 written (`contract-v3.md`), docs `06` and `19` carry a "partly superseded" note, the Layer 2 README points here, and the table below says what is retired, demoted, kept or still to do. |

## Running the tests

```bash
node --test "supabase/functions/_shared/analysis-core/*.test.ts" "supabase/functions/_shared/log-core/*.test.ts" "console/src/lib/*.test.ts"
```

(`node --test <directory>` does not work on Node 24; use the glob.) The golden values in `stats.test.ts` come from the independent Python implementation `docs/wellness-project-teardown/tools/replicate_pattern_engine.py`.

## Deploying `analysis-run`

Via the Supabase MCP `deploy_edge_function` (or the CLI) with the entrypoint `index.ts` plus every file under `../_shared/analysis-core/` that it imports (names relative to the function folder, e.g. `../_shared/analysis-core/stats.ts`). Verified 2026-10-12: shared files bundle and run in Deno; calls without a valid user get 401.

## Nightly schedule: one request per engine group (2026-10-29)

A single `analysis-run` request running every engine was killed with HTTP 546 "CPU Time exceeded" after about 10-12 s, so the engines at the end of the order (health) never finished. `analysis-run` now accepts `group` (`core`, `bands`, `interventions`, `fitness`, `health`; `runGroups.ts`; no group means all, the old behaviour, still used by the console's refresh buttons) and returns per-engine `timings` in ms. Migration `20261114_analysis_run_groups.sql` makes `private.run_analysis(p_group)` post one group with a 60 s `pg_net` timeout (so `net._http_response` keeps the status and timings) and schedules the groups in dependency order: `core` 20:30 (job `analysis-run-nightly`), `bands` 20:33 (reads the series breaks `core` confirms), `interventions` 20:36, `fitness` 20:39, `health` 20:42 UTC. Measured on 2026-10-29: core 4.9 s of engine time (breaks 2.4 s, relationships 1.8 s), bands 3.8 s (evidence 2.3 s), interventions 0.4 s, fitness 0.6 s, all HTTP 200. `health` alone was killed (546) until the exercise-library lookup was indexed (see below): `musclesOf` recomputed every library row's movement key on each miss, about 540,000 key computations per run (1.8 s of the 2.5 s of compute); with a name and movement-key index built once per library the health group returns 200 (46 rows published; the 46 sequential upserts take about 2.7 s of waiting, not CPU). The `health-timing` log lines (step names by position or catalogue id, never a concern's title) stay in, so a regression shows in the function logs.

### Batched publishing (2026-10-29)

Each engine used to publish one row at a time (an upsert, then a delete of the key's older copies), about 60 ms per row of sequential round trips. `analysis-core/publish.ts` (`publishResults`) now sends up to 10 rows per upsert and one delete per kind (and day) per batch; the newest row per key still wins, older copies of the same engine version are removed, and a failed upsert stops the run with the rows already published reported. Used by health, evidence, relationships (catalogue and registered questions), expected bands and series breaks (which also reads its seven tables in parallel). Engines that publish one or a few rows (typicality, fitness-fatigue, integrity, experiments, intervention response/ITS, event evidence) still use their own calls: batching would gain nothing. Measured engine wall time before -> after, the same data: health 3,145 -> 867 ms, relationships 1,776 -> 414 ms, evidence 2,327 -> 533 ms, expected bands 626 -> 166 ms, series breaks 2,431 -> 1,523 ms; all five groups return 200. The evidence engine's own compute is only about 0.4 s (`engine-timing` log lines, steps by catalogue id); what dominated it was publishing. `engine-timing` and `health-timing` log lines stay in (`trace.ts`).

### Lag curves: six days judged together (2026-10-29, engine alpha.12)

Each of the six lag intervals used to be a separate 95% interval, so with no real effect 16% of 200 simulated histories showed at least one "clear" day (intended 5%); the week total (lags 1 to 6 as one contrast) was already near nominal (6%). Now the week total is the headline, and a day counts as clear only through a Bonferroni-widened interval (`simLo`/`simHi`, the fit's own critical value scaled by t(0.05/6)/t(0.05)); the per-lag `lo`/`hi` stay as descriptive. Under no effect a clear day now appears in about 3.5% of histories; a planted next-morning effect is still found. Payload gains `simultaneous: { family, individualCritical, criticalValue }`; `clearLags`/`lastClearLag` use the simultaneous interval; the summary says when a day was clear only on its own. `calibration.test.ts` keeps both null checks (lag curves; the 23-question catalogue, where a "clear" verdict needs the whole widened interval beyond the no-effect zone and none appeared in 150 simulated null histories).

### Series-break engine: where its 1.9 s went (2026-10-29)

`engine-timing` log lines (steps by metric id) showed: the provenance read 849 ms (4,439 rows, five sequential pages), the seven table reads 227 ms (parallel), break detection about 620 ms in total (20 to 160 ms per metric; binary segmentation, left alone: a rewrite would have to reproduce its results exactly for a saving of well under a second), per-metric provenance filtering about 1 ms, publish 157 ms. The provenance read now asks only for the seven fields the engine uses and fetches the pages after the first together (`fetchAllWaves`, same rows in the same order): the engine went from 1,872 to 1,181 ms with identical results (tested).

## Series-break detector: what was measured

Method and thresholds are in the header of `seriesBreak.ts`. Calibration on synthetic series with a planted noise break (60 seeds): critical value 6 gives a false-alarm rate of 0.7 % (150 stationary AR(1) series) and power 0.90–0.97 with 30–40 readings after the break. A 7-metric run over a 420-day lookback takes about 0.7 s.

Replays on the real history are run privately and are not recorded here (this repository is public and holds no personal health data). The detector found the regime changes it was built for: an HRV change that coincides with a drop in the share of non-integer readings, and shifts in the sleep-stage minutes.

**Design correction found on real data:** the detector also flags genuine physiological changes, not only device or source changes. So a break is only a **candidate**. It restarts a baseline when confirmed by an origin change in `daily_field_provenance` within ±3 days or by a user decision. Device-sensitive metrics are HRV and the sleep-stage minutes; the others are reported but never auto-confirmed.

## Scheduler authentication (corrected 2026-10-12)

The first design reused a `service_role_key` Vault secret on the assumption that the reminder cron jobs already did; the Vault was in fact **empty** (those jobs have no push subscribers, so they never failed visibly, and still do nothing). The final design needs no human-held secret: migration `20261012_analysis_cron_secret.sql` generates a random secret inside Vault, `private.run_analysis()` sends it in `x-analysis-cron` (plus the public anon key to satisfy the gateway), and `analysis-run` checks it through `public.analysis_cron_secret_ok()` (service role only). No Edge secret was set and none is needed. (Side note: `private.send_daily_reminder` still reads `service_role_key` from the empty Vault, so the daily push reminders would silently skip if they ever had subscribers.)

## Relationship engine (Phase 3)

Replaces scanning every lag of every pair and labelling anything with |r| >= 0.1 "Moderate" (no significance needed). A fixed, versioned catalogue (`hypotheses.ts`, v1.0.0: alcohol to next-day HRV / resting heart rate / sleep, sleep to HRV and to energy, training day to HRV, recovery sessions to HRV, afternoon caffeine to sleep, steps to HRV) is tested once each:

1. Both series are adjusted for day of week (weekday means removed), so a weekly routine cannot pass as an effect. A cause that follows a fixed weekly schedule (for example training on set days) is reported as **not testable**, not as a finding.
2. p-value: weekly-block permutation (whole weeks of the cause are shuffled, 2,000 times), so day-of-week alignment and within-week dependence survive. Resolution is 1/2001; an exhaustive circular shift cannot get below 1/(n-13), which no correction across a catalogue survives.
3. Benjamini-Hochberg across the hypotheses that had enough data (family size is stored in the payload).
4. Effect in natural units (percent for ln-HRV) with a seeded block-bootstrap 95 % interval, plus the smallest effect detectable at 80 % power, so "not distinguishable from noise" says how large an effect had to be to show.
5. Days without a record are left out, except under each hypothesis's stated exposure model (zero on days with a food log for alcohol and caffeine; zero on days with a watch record for training; zero only after the first logged session for recovery). Sessions are never assumed from a schedule, and illness days (and the day after) are excluded.

Supplement start to outcome is deliberately not in this catalogue: a step exposure against a trending series is confounded by time, so supplements are tested as pre-registered N-of-1 experiments (Phase 5).

**Backtest on synthetic data with planted effects** (`relationship.test.ts` runs a smaller version of this on every push): over 60 planted datasets, alcohol to next-day HRV (true effect about -13 %) was found in 90 % at 61 days and 100 % at 120 days or more, with 92-95 % interval coverage; sleep to HRV 80 % / 100 %; the true +4 ms sauna effect needs far more data (13 % at 61 days, 47 % at 120, 77 % at 240), which is exactly what the reported smallest-detectable effect says; the planted null (alcohol to sleep) was called supported in 0-5 % of datasets (nominal 5 %); the confounded training card (fixed weekly schedule) is not testable. Calibration on autocorrelated nulls: false-positive rate within the nominal 5 %.

Published as `analysis_results` rows of kind `relationship`, one per hypothesis, states `supported` / `not_distinguishable` / `insufficient`. There is no "Moderate" tier anywhere.

## Typicality: "is today typical for you?" (Phase 4)

Replaces fixed-threshold personal-normal bands (a competitor's version called a quarter of days unusual against about 9 % expected). `typicality.ts` (+ `linalg.ts`):

1. Five daily signals: ln HRV, resting heart rate, sleep hours, deep + REM minutes, steps (today's steps are ignored, the day is not over).
2. Baseline = up to 120 past days that **end before the 14 days being judged** (so an illness in progress cannot make itself look normal), skip logged illness days, start at the last confirmed series break, and need 45 complete days.
3. Robust standardisation (median, MAD), a mean and a Ledoit-Wolf shrunk covariance, so correlated signals are judged jointly and a short history still gives an invertible matrix.
4. Distance = Mahalanobis over the signals present (at least three) divided by their number. Thresholds are the person's own **leave-one-out** distance quantiles (90 % = elevated, 97.5 % = unusual), not a chi-square.
5. Attribution: up to three signals with |z| >= 1. Strain = elevated or worse with resting heart rate up (and HRV down); two strain days in a row with no illness logged set `suggestIllnessLog`. Lookalikes: the three past days nearest to today, illness days included.
6. A device-sensitive signal (deep + REM) is left out while a detected series break for it lies inside the window, and the result says so.

**Gate on synthetic data** (`typicality.test.ts`, 300 simulated 200-day histories): on stationary data 3.8 % of judged days are unusual (nominal 2.5 %) and 11.5 % are elevated or worse (nominal 10 %), and a false "log an illness" suggestion appears in 0.7 % of histories; a planted illness (resting heart rate +8 bpm, HRV -30 %, steps halved) is flagged on 99.8 % of its days and prompts in 96 % of histories (it cannot prompt on its first day).

**Gate on the real history (private rolling-origin replay, figures not recorded here): missed.** Replaying the engine one day at a time over the real months, the share of ordinary days flagged was far above the nominal share, and the share of logged illness days flagged was only moderate. Real signals drift and change regime (a sensor change in HRV noise, sleep-stage classifier shifts), which a window of the recent past cannot absorb; leaving out deep + REM helped but not enough. Per the plan's rule ("if specificity misses, ship as quiet drill-down only") the card is a drill-down labelled experimental, it does not feed Today's Recovery, and no notification is attached.

To revisit: a regime-aware baseline (restart on unconfirmed but detected breaks), robust per-signal dispersion models, or per-signal one-sided tests; re-run the replay before promoting it.

## Supplement N-of-1 experiments (Phase 5)

A supplement is tested on you, pre-registered:

1. **Plan, locked.** In the app a person picks one supplement, the design (AB, or ABAB to see whether the effect repeats), days per phase (14 to 60), an optional washout (days at the start of B set aside) and **one primary outcome** (HRV, resting heart rate, sleep duration, deep + REM sleep, energy, mood, stress, soreness). Phase A is without the supplement, B with it; day 1 of A is the day of registration. The row goes into `experiments` and a trigger refuses any later change except `status`, so the analysis cannot be re-chosen after seeing data. Before registering, the app shows for every outcome the smallest difference this design could detect on the person's own recent data (`experiment_preview` action), flags outcomes whose series changed regime recently, and warns that smaller effects will not show.
2. **Analysis, as registered.** B days against A days by phase (intention to treat), adjusted for day of week; illness days, alcohol days and the morning after (when the plan says so) and washout days are left out. The difference of means is reported in natural units (percent for HRV) with a 95 % interval from a Student t on the effective sample size implied by day-to-day autocorrelation (lag-1 estimate with its small-sample bias removed). The smallest detectable difference (80 % power) is printed next to every estimate, and "no detectable effect" says what size was out of sight.
3. **Guards.** A drifting A phase is checked and flagged (a step in a drifting series looks like an effect); ABAB reports whether both cycles agree; adherence comes from the real supplement log and low adherence or slips in A are noted, never assumed; secondary outcomes (at most three) are corrected together, the registered primary is not. While the experiment is running the numbers are labelled interim and carry no verdict; the verdict appears when the last phase ends.
4. **Published** as `analysis_results` kind `experiment`, key = experiment id, with the protocol and the registration-time preview attached; `status` moves planned, running, completed on its own.

**Backtest on synthetic data** (`experiment.test.ts` runs a smaller version on every push; 400 simulated experiments per cell): with 14, 28 or 42 days per phase and day-to-day autocorrelation 0.2 to 0.7, false positives on a null supplement were 4.0 to 7.5 % (nominal 5 %), the 95 % interval covered the true effect 92.5 to 96.5 % of the time, and power at the stated smallest detectable effect was 72 to 80 % (nominal 80 %). Honest consequence: with four-week phases on noisy HRV only differences of roughly 10 to 25 % can be seen, so most plausible supplement effects need longer phases or a less noisy outcome, and the preview says so before the person starts.

## Capture layer: how "say it" works (Phase 2)

1. The app sends one message, a `client_turn_id` and its local clock to `log-agent`. Gemini (flash-lite, key server-side) only **reads** the text into loosely typed entries (drink, meal, wellbeing, supplement taken, recovery).
2. `_shared/log-core/proposal.ts` turns entries into proposals and enforces the rules: every number must literally occur in the message (numeral or number word) or it is dropped and shown as a field to fill in; alcohol needs a stated strength; dates and times come from the client clock, never the model; supplements are matched to the active roster (ambiguous or unknown = a question).
3. The app shows the proposals. Nothing is written until SAVE. Ready, un-skipped proposals are written one by one through the same repositories as the forms (`saveBeverageDrink`, `addDrink`, `addSupplementsTaken`, `mergeWellbeing`, `addRecoverySession`); each card shows SAVED or its error. Food opens the normal food form with the text and time filled in (resolver and review unchanged).

Deviations from the plan, on purpose: the commit is client-side through existing repositories (no second write path to keep in sync; `addSupplementsTaken` alone has product links, nutrient intake and pantry effects); one structured model call instead of function-calling; saved/failed state comes from each write succeeding or throwing, not from a read-back. Timestamps: the app stores local wall-clock time labeled UTC, so the proposal's offset is dropped and only its digits are kept (`LogAgent.kt`). Wellbeing is written as a partial merge, so "energy 7" cannot null out mood or arousal already logged for that day.

## Retired, demoted, kept (Phase 9)

| Old path | Replacement | State |
| -- | -- | -- |
| Plain trailing-window baselines for HRV, RHR, sleep duration and stages, SRI | The same evaluators with `windowStart` at the last confirmed series break | **Replaced**, with no visible effect until a break is confirmed. Layer 1 evaluations themselves are kept as inputs |
| Unadjusted 29-lag scan in the console Relationship Lab | Confirmatory tab (weekday-adjusted, family-corrected, minimum detectable effect, "not distinguishable" as a first-class result) | **Demoted, not removed.** It stays under the EXPLORATORY tab, labelled uncorrected. The confirmatory catalogue currently supports no relationship on the real history, so the scan is still the only way to browse pairs. Remove it when the catalogue covers the pairs you browse for, or when you say so; removing it is a console change that deploys on a push to `main` |
| Sample domain levels and sample achievements on the User profile page | Real VO2max placement for running; real achievements; other domains read "Not enough data yet" | **Retired** (Phase 8) |
| Hard-coded outcome claims on supplements | `evidence_records` and `interaction_records`, each with a `reviewed` flag | **Replaced** earlier for outcomes; interactions are new and unreviewed |
| Single-metric "score" everywhere vs one cross-metric grade | One Healthspan grade, profile page only, enforced by a test | **Decision implemented** |
| Web `index.html` ACWR vs Android CTL/ATL load | A load series published from the core | **Not done.** Follow-up: the two still use different formulas |
| Hand-rolled windowed statistics in the `domain/*Evaluation.kt` files | `PersonalBaseline.kt` | **Not done.** Migrate opportunistically, not as a project |

## Still open

- **Needs you:** install an Android build and exercise SAY IT, PATTERNS, IS TODAY TYPICAL, reminders, TEST IT, the new INTERACTIONS card, GRIP and the HEALTHSPAN card; run the golden set of about 50 utterances against `log-agent` with a signed-in session; read the sources and mark `interaction_records` rows reviewed; decide when to push this branch.
- **Data:** alcohol, caffeine and recovery sessions are barely logged, so those hypotheses cannot be tested yet; the Healthspan labs expire after 365 days.
- **Method:** typicality missed its specificity gate on real history and ships as a quiet drill-down (a regime-aware baseline is the next try); Healthspan cut-offs and letter bands are provisional; grip needs a cited norm table before it can be scored; `CORE_VERSION` is still an alpha.

## Known gaps and cautions

- Everything is committed on the local branch `feat/analysis-layer-3` and **not pushed**. The deployed Edge Functions (`analysis-run`, `log-agent`, `zepp-extract` v14) were verified against the repo files when they were deployed; diff the deployed source against git before redeploying any of them.
- Pre-existing Supabase advisor findings (not touched): two `SECURITY DEFINER` views (`v_conditioning_drills`, `v_training_block_metrics`), three functions with a mutable `search_path`, `pg_trgm` in `public`, leaked-password protection off.
- The repaired console queries were typechecked and built but not exercised from a signed-in browser by me; the live deploy runs through GitHub Actions on `main`.
