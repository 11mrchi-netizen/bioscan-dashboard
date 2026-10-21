# Health concerns: implementation status

The plan, the audit and the owner's decisions: [01-plan.md](01-plan.md). How the association findings were validated:
[02-validation.md](02-validation.md). This file says what was built, where it lives, how to deploy it and what is not
done.

Owner decisions (01-plan.md §7.2): Health › CONCERNS is the home; rose accent, amber only for WORSENING and safety
prompts; a 21:00 reminder; symptomatic at severity 2 or more; episodes end after 7 symptom-free days (3 for general
illness); OSTRC-H2 kept; illness exclusion read from both sources for one release; the two right-knee contusions in the
old log stay two separate concerns.

## What was built

| Part | Where | Tested by |
|---|---|---|
| Legacy schema in the repository (no-op on live) | `supabase/migrations/20261031_legacy_health_events_baseline.sql` | applied twice on Postgres 16 |
| Concerns, append-only check-ins with corrections, signals, episode marks, trial links and adherence, own-row RLS (including references to own concerns only), ledger and reminder kinds widened, the duplicate-session fold moves check-in links, legacy copy (one concern per old row), `v_health_events` | `supabase/migrations/20261101_health_concerns.sql` | applied twice on Postgres 16 with a Supabase stub: guards refuse edits, a correction supersedes once, other accounts see nothing and cannot reference another account's concern, the fold moves the link, the copy is idempotent |
| Region and symptom vocabulary (27 regions, 21 symptoms, 10 follow-up questions) | `supabase/functions/_shared/health-vocab/health-vocab.json`, `analysis-core/healthVocab.ts`; Android asset | the migration's seed equals the file; every exercise muscle loads a region |
| Day series (checked in, recalled, unobserved; corrections and retractions) | `analysis-core/healthSeries.ts` | `healthEpisodes.test.ts` |
| Episodes with uncertain edges, marks, measures, state, recurrence, data quality, safety prompts | `analysis-core/healthEpisodes.ts` | `healthEpisodes.test.ts` |
| Systemic episodes leave their days out of HRV, resting heart rate and sleep comparisons, read alongside `illnesses` | `analysis-core/healthLoad.ts`, `runRelationships.ts` (`loadFrame`) | `healthLoad.test.ts` (golden: the copied legacy rows give exactly the old excluded days) |
| Exposure catalogue `hx-cat-1`, context lanes, what came before each episode | `analysis-core/healthExposures.ts` | `healthAssociations.test.ts`, `runHealth.test.ts` |
| Case-crossover and session-level comparisons, one primary analysis per exposure, Benjamini-Hochberg per concern | `analysis-core/healthAssociations.ts` | `healthAssociations.test.ts` (hand-computed Fisher and Poisson-binomial values) |
| Evidence ladder, five answers per finding, next steps | `analysis-core/healthFindings.ts` | `healthFindings.test.ts` (the ladder; no cause words) |
| Trials read once at their date | `analysis-core/healthTrials.ts` | `healthFindings.test.ts` (interim, evaluated, insufficient; Clopper-Pearson golden values) |
| Publisher and ledger; nightly and on demand (`{"action":"health"}`) | `analysis-core/runHealth.ts`, `analysis-run/index.ts` | `runHealth.test.ts` (rows, hashes stable, an evaluated trial is never redone, quiet before the migration) |
| Simulation gates | `analysis-core/healthSimulation.test.ts`, `healthSimKit.ts`, `healthSimMeasure.ts` | see 02-validation.md |
| Android data and domain | `data/HealthConcernsRepository.kt`, `data/model/HealthConcernModels.kt`, `domain/health/*` | `HealthConcernsDomainTest` (15 tests, run in a JVM harness here) |
| Android UI: quick check-in with body map and follow-ups; Health › CONCERNS; the concern page (OVERVIEW, CONTEXT, PATTERNS, TRIALS); Lab › N=1 section; Training › INJURY; Status figure; 21:00 reminder; add-sheet entries | `ui/screens/health/*`, `ui/components/BodyMap.kt`, `HealthTileScreen.kt`, `TrainingTileScreen.kt`, `N1Tab.kt`, `StatusRepository.kt`, `AddEntrySheet.kt`, notification files | **not compiled here** (no Android SDK in the build container); build in Android Studio before release |
| Web dashboard and console read concerns through `v_health_events` (quietly falling back before the migration) | `index.html`, `console/src/workspaces/Timeline.tsx` | console `tsc` |
| Registry entries | `metric-registry.json`: `health_concern_state`, `health_finding`, `health_trial_read` | `metricRegistry.test.ts` |

Removed: the old injury and illness forms and list (`InjuryFormSheet.kt`, `IllnessFormSheet.kt`, `HealthEventsScreen.kt`,
`HealthEventsRepository.kt`), so the app no longer writes `injuries` or `illnesses`. The tables stay, read-only in
practice; their insert policies are dropped after one release (below).

## Deploy, in this order

1. Apply `20261031_legacy_health_events_baseline.sql` (changes nothing on live) and `20261101_health_concerns.sql`.
   Check: `select private.backfill_health_concerns()` returned 4 on the first apply (two knee contusions, two illnesses);
   RLS on the seven new tables; advisors clean.
2. Deploy `analysis-run` (it now runs the health engine nightly and answers `{"action":"health"}`) and `zepp-extract`
   (moves check-in links when it merges a Zepp copy). Both read the new tables softly, so the order with step 1 only
   matters for the new features.
3. Run `analysis-run` once for the owner (the nightly job will otherwise do it at 04:30) and check the
   `health_overview` row: four concerns, all RESOLVED, no findings.
4. Build and install the Android app (Android Studio). Check: Health › CONCERNS, a NOTHING check-in, a body-map
   check-in, the concern page, the 21:00 reminder in Setup › Notifications.
5. Deploy the web dashboard and the console (the console deploys from `main`).
6. After one release (owner decision 7.2.7): remove the `illnesses` read from `loadFrame` (the systemic episodes carry
   the same days), and drop the insert policies on `injuries` and `illnesses`.

## Not done, or done differently from the plan

- Android Compose UI has not been compiled in this environment; the pure domain code has (15 tests pass).
- The daily series and context lanes are published in the payload instead of two SQL views (`v_health_concern_daily`,
  `v_health_day`): one implementation of the rules instead of a SQL copy of them (plan §2.4 updated).
- A finding is shown as POSSIBLE only after it passes again at least 14 days later with new data (a first pass is a
  hint), and PERSISTENT needs the later flare-ups or sessions to hold up on their own. Both were added after the first
  simulations showed that repeated nightly looks made one lucky night show as a pattern (02-validation.md).
- "Quality run" uses zone 4 by % of the robust maximum heart rate on the server (the app's own zone method is not
  available there); a run type tag always wins.
- Console `Recovery.tsx` still bands HRV with the old `injuries` only.
- Deferred, as planned: prediction, risk scores, rehabilitation plans, randomised injury experiments, footwear, descent
  and weather exposures, photo and voice logging.
