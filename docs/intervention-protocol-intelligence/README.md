# Intervention & Protocol Intelligence

Design and status notes for Linear milestone **08 — Intervention & Protocol Intelligence** (`DAV-331`..`DAV-340`, `08.1`..`08.10`). The goal is a generalized, closed-loop personal-intervention layer -- supplements, nutrition, training, sleep, lifestyle -- built around:

MEASURE -> IDENTIFY A TARGET -> DEFINE INTERVENTION/PROTOCOL -> ESTABLISH BASELINE -> APPLY -> MEASURE RESPONSE -> ANALYZE PERSONAL SIGNAL -> KEEP / MODIFY / STOP -> REASSESS.

Non-goals, throughout: gamification, a universal health score, causal claims from simple before/after data.

## Architecture in one paragraph

This milestone is substantially a **generalization** of machinery `docs/analysis-layer-3` already built and shipped for supplements only: a pre-registered N-of-1 engine (`experiments` + `supabase/functions/_shared/analysis-core/experiment.ts`/`stats.ts`), an outcome-specific evidence registry (`evidence_records`), a pair-interaction registry (`interaction_records`), and a fully generic published-result envelope (`analysis_results`). None of those four are rebuilt here. What's missing is the canonical, domain-agnostic layer above them -- `interventions` and `protocols` -- plus the engines that are genuinely new: adaptive rules and protocol-wide audit. The N-of-1 statistics core itself needs no change; it already operates on arbitrary metric series, not supplement semantics.

## Reuse vs. generalize vs. new

| Reused as-is | Generalized | New |
| -- | -- | -- |
| `analysis_results` envelope | `experiments` (add `intervention_id`) | `interventions`, `intervention_state_history` |
| `evidence_records` (`subject_type`/`subject_key`/`outcome_key` already generic) | N-of-1 metric whitelist | `protocols`, `protocol_interventions` |
| `experiment.ts` / `stats.ts` core | `domain/SupplementInteractions.kt` -> `protocolAudit.ts` | `protocol_adjustment_proposals` |
| `interaction_records` pattern (pair matching) | | `interventionResponse.ts`, `protocolRules.ts` |

## Canonical entities

The Linear issue's entity list, mapped to concrete tables/types:

| Linear entity | Concrete shape |
| -- | -- |
| Intervention | `public.interventions` row (08.2) |
| InterventionInstance / exposure | `interventions.started_at`/`ended_at` + the day-level exposure a reader derives from them (no separate exposure table; `supplement_log`-style domain logs remain the source of actual-dose truth per domain) |
| Protocol | `public.protocols` row (08.3) |
| ProtocolStep | `public.protocol_interventions` row (08.3) |
| Objective / target outcome | `outcome_key` string, same vocabulary as `evidence_records.outcome_key` and `interventions.intended_outcomes` |
| MeasurementPlan | `interventions.required_measurements`/`optional_measurements` (08.2) and `protocols`' evaluation window (08.3) |
| Experiment | `public.experiments` row, generalized in 08.5 to reference `interventions.id` |
| ResponseObservation | `analysis_results` row of kind `experiment` (pre-registered) or `intervention_response` (observational, 08.6) |
| EvidenceLink | `evidence_records` row keyed by `(subject_type, subject_key, outcome_key)`, looked up from an intervention's own `(subject_type, subject_key)` (08.4) |
| Decision / lifecycle state | `interventions.lifecycle_state` + `intervention_state_history` (08.2); `protocol_adjustment_proposals.status` for an accepted/rejected/modified adaptive-rule proposal (08.7) |

## Tables

| Table | Purpose | Phase |
| -- | -- | -- |
| `interventions` | Canonical intervention, any domain, with lifecycle state. | 08.2 (done) |
| `intervention_state_history` | Append-only log of lifecycle transitions, written by a trigger. | 08.2 (done) |
| `protocols` | An objective-driven grouping of interventions with baseline/active/washout/evaluation windows. | 08.3 (done) |
| `protocol_interventions` | Join table: which interventions belong to a protocol, in what role/sequence. | 08.3 (done) |
| `protocol_state_history` | Append-only log of protocol lifecycle transitions, written by a trigger. | 08.3 (done) |
| `protocol_templates` | Shared, read-only reference: descriptive starting points (sleep optimization, aerobic base, strength, recovery, metabolic health, micronutrient sufficiency), seeded by migration like `benchmark_artifacts`. | 08.3 (done) |
| `protocol_adjustment_proposals` | Mutable proposal rows from the adaptive-rules engine (`proposed`/`accepted`/`rejected`/`modified`). | 08.7 (done) |

## Status

| Phase | State |
| -- | -- |
| 08.1 Audit & architecture | **Done.** This document. |
| 08.2 Canonical `interventions` model + lifecycle | **Done.** Migration `supabase/migrations/20261013_interventions.sql` (applied to the live project); lifecycle graph in `supabase/functions/_shared/analysis-core/intervention.ts`, tested in `intervention.test.ts`. |
| 08.3 `protocols` model | **Done.** Migration `supabase/migrations/20261013_protocols.sql` (applied); lifecycle graph and `generateSchedule` in `supabase/functions/_shared/analysis-core/protocol.ts`, tested in `protocol.test.ts`. |
| 08.4 Evidence registry generalization | **Done.** No schema change needed (see notes); matching/ranking in `supabase/functions/_shared/analysis-core/evidenceLink.ts`, tested in `evidenceLink.test.ts`. |
| 08.5 Generalize the N-of-1 engine | **Done.** Migration `supabase/migrations/20261013_experiments_intervention_id.sql` (applied); `runExperiments.ts` reads/publishes `intervention_id` alongside `supplement_id`. `experiment.ts`/`stats.ts` unchanged. |
| 08.6 Intervention response analysis (observational) | **Code complete, not deployed.** `interventionResponse.ts` (pure) + `runInterventionResponse.ts` (I/O), wired into `analysis-run/index.ts`'s source but the Edge Function has not been redeployed (see notes). |
| 08.7 Adaptive protocol rules | **Done.** `protocolRules.ts` (pure, tested in `protocolRules.test.ts`) + `protocol_adjustment_proposals` migration (applied). Nothing calls it yet -- no I/O wiring into `analysis-run` in this pass (see notes). |
| 08.8 Protocol audit | Not started. |
| 08.9 UI (Console + Android) | Not started. |
| 08.10 Validation | Not started. |

## 08.2 notes

`interventions` generalizes `supplements`-as-intervention to any domain. Unlike `experiments` (pre-registered, protocol-locked by a trigger that refuses changes to its own columns), an intervention is a living record -- dose, dates and the measurement plan can be edited while it runs. Only the lifecycle transition itself is audited, via `interventions_log_lifecycle_change` (before-update trigger -> `intervention_state_history`, bumps `version`). The transition *graph* (which `lifecycle_state` can follow which) is enforced in the app, by `isValidTransition` in `intervention.ts` -- the database's CHECK constraint only restricts the set of values, not the graph, the same division of labour `Experiment.kt` uses ahead of the (looser) database constraints on `experiments`.

Domain pointers (e.g. `supplement_id`) are nullable FK columns added one per domain as they're wired in, never duplicating a domain's own canonical data -- a supplement intervention links to `public.supplements` rather than re-storing its name/dose.

Verified live against the Supabase project: schema, RLS (owner-only on `interventions`; parent-ownership-scoped select/insert on `intervention_state_history`), the lifecycle trigger (a `proposed -> baseline` transition correctly wrote one history row and bumped `version`), and the `lifecycle_state` CHECK constraint (rejected an invalid value). `get_advisors` reported no new findings beyond the four pre-existing ones already listed in `docs/analysis-layer-3/README.md`.

## 08.3 notes

`protocols` groups interventions (via `protocol_interventions`) around one `objective_outcome_key`. Its lifecycle is coarser than an intervention's (`proposed -> active -> evaluating -> completed|stopped`, no separate `baseline` state at the protocol level -- baseline is just the first window of the generated schedule, not a stage the whole protocol moves through) and is audited into `protocol_state_history` the same way `interventions_log_lifecycle_change` works, with the transition graph enforced by `isValidProtocolTransition` in `protocol.ts`.

`protocol_templates` are descriptive starting points, not prescriptive rules the app applies automatically -- `suggested_structure` is read by the UI (08.9) as a pre-fill suggestion, nothing reads or branches on it server-side. Six seeded: sleep optimization, aerobic base development, strength development, recovery, metabolic health, micronutrient sufficiency; `objective_outcome_key` uses the canonical `metric-registry.json` key where one already exists (`sleep_duration`, `vo2max`, `hrv`) and a reasonable free-text key otherwise (`strength_level`, `hba1c`, `ferritin` have no metric-registry entry yet -- `evidence_records.outcome_key`/`interventions.intended_outcomes` are likewise unconstrained text, so this doesn't block anything, but 08.4/08.5 should reconcile the vocabulary once the registry is widened).

`generateSchedule` (acceptance: "a protocol can be instantiated for a user, generate an actionable schedule") computes baseline/washout/active dates and slices the active window into sequential groups by `sequence_order`, with steps sharing an order running concurrently -- pure and tested, no I/O.

Verified live: schema, RLS (owner-only on `protocols`; parent-ownership-scoped on `protocol_interventions`/`protocol_state_history`, with the `protocol_interventions` insert policy also requiring the linked intervention to belong to the same user), all 6 `protocol_templates` rows seeded correctly, the lifecycle trigger (`proposed -> active` logged one history row), and a `protocol_interventions` insert linking a real intervention. No new `get_advisors` findings.

## 08.4 notes

Confirmed live: `evidence_records.subject_type`/`subject_key`/`outcome_key` have no CHECK constraint (just `text not null`) and today hold only `subject_type = 'ingredient'` rows (15, all from Supplement Intelligence, all with `evidence_tier`/`study_design` still null). So an intervention's own `(subject_type, subject_key)` -- already meant to share this vocabulary by convention, not a foreign key, per the 08.2 migration's comment -- is already a valid lookup key with no migration needed; this phase is the matching logic, not a schema change.

`evidenceForIntervention` in `evidenceLink.ts` is pure (the caller fetches the rows, same join-in-the-app-layer convention `EvidenceRepository.kt` already uses) and does exactly what DAV-334 asks: groups evidence per outcome instead of collapsing it into one score, since the same subject can have different evidence strength for different outcomes, and ranks by tier (meta-analysis/systematic-review and controlled-human first, the person's own N-of-1 result last -- "did it work for me" is never mixed into "how well-established is this"). Unreviewed rows are kept, matching `interaction_records`' "(unreviewed)" convention, just ordered after a reviewed row of the same tier.

## 08.5 notes

`experiment.ts`/`stats.ts` needed **no change**: the N-of-1 statistics already operate on arbitrary metric series from the shared daily frame and never encode supplement semantics. The original plan for this phase ("widen the metric whitelist to the canonical metric registry") turned out to be based on a false premise once checked against `buildFrame` in `runRelationships.ts` -- the frame only ever carries eight series (`hrv`, `rhr`, `sleep_hours`, `deep_rem_min`, `energy`, `mood`, `stress`, `soreness`), which don't line up 1:1 with `metric-registry.json`'s keys (e.g. `sleep_duration` there vs. `sleep_hours` here; per-wellbeing-component keys don't exist there at all). Forcing alignment would have meant renaming live, already-deployed metric keys or fabricating series the frame doesn't build (`vo2max`, `training_load`, `body_weight`, ...), which is a materially bigger, riskier change than this phase needs -- those eight outcomes were already domain-agnostic biometric/wellbeing signals, not supplement-specific, so no widening was actually required for generalization. Adding new selectable outcomes (which would need new `RawInputs` fields and loader queries, not just a whitelist edit) is real future work, tracked here rather than silently bundled into 08.5.

What generalizes `experiments` is solely the new nullable `intervention_id` column alongside `supplement_id` (a `CHECK` requires at least one of the two), locked by `experiments_lock_protocol` the same as every other protocol column. `runExperiments.ts` reads and publishes it; adherence logging stays domain-owned (`supplement_log` is the only wired-in source today, so a bare intervention with no supplement reports zero logged days rather than a guess -- not wrong, just less informative, same as any other source-less adherence would be).

Verified live: schema (`intervention_id uuid null`, FK to `interventions`), the `experiments_has_a_subject` CHECK (rejected a row with neither id set), an insert with only `intervention_id` set, and the lock trigger (rejected changing `intervention_id` after registration). No new `get_advisors` findings.

## 08.6 notes

`analyseInterventionResponse` compares a metric's baseline window (before `started_at`) against its active window (since `started_at`, through `ended_at` or `today` if still ongoing) for any intervention -- no pre-registration, no washout, no ABAB replication check, so it is always reported as weaker evidence than 08.5's engine: verdicts are `signal_observed`/`no_signal`/`insufficient` (never "effect"), and summaries use "an observed signal during intervention... other things that changed over the same period could explain part or all of it" rather than claiming causation, per DAV-336. Because most interventions have no fixed end date, there is deliberately no "complete" stage either -- this is always a rolling, interim read of whatever data exists as of `today`.

The small statistical helpers (`lagOneRho`, `effN`, `groupStats`, `signedText`) are a deliberate, small duplication of `experiment.ts`'s private ones rather than a shared import -- the same convention `relationship.ts` already uses for its own local `signed`/`toNatural`/`round` (each engine file owns its small helpers).

`runInterventionResponse.ts` only analyses outcomes from `interventions.intended_outcomes` that happen to name one of the eight `MetricId`s the shared daily frame actually carries (same vocabulary gap noted in 08.5) -- an intervention naming an outcome the frame can't measure yet (e.g. `vo2max`) is silently skipped for this kind, not an error. It's wired into `analysis-run/index.ts`'s source (a new `intervention_response` result alongside the existing ones) but **the Edge Function has not been redeployed** -- unlike the database migrations in 08.2/08.3/08.5, redeploying a live, cron'd production function is a separate, higher-blast-radius action (the nightly job is the only thing hitting it for the one real account on this project) and is left as an explicit next step rather than done silently in the same pass as everything else.

## 08.7 notes

`proposeProtocolAction` is a deterministic, ordered rule set, not a model: the first matching rule wins, every branch is a named, commented reason, and every proposal echoes the exact `sourceInputs` that produced it -- there is no weighted score anywhere underneath, per DAV-337's "without becoming an opaque recommendation engine". It treats 08.5's pre-registered vocabulary (`effect_detected`/`no_detectable_effect`) and 08.6's observational one (`signal_observed`/`no_signal`) as equivalent for deciding an *action*, since they differ in how strong a causal claim the evidence supports, not in what should happen next.

`protocol_adjustment_proposals` is deliberately its own table rather than another `analysis_results` kind: a proposal's `status` must be writable by the owning user (accepting/rejecting/modifying is the entire point), whereas `analysis_results` has no user-write policies at all. Its engine-produced columns (`action`/`rationale`/`source_inputs`/`engine_version`/`protocol_id`/`intervention_id`) are locked by `protocol_adjustment_proposals_lock_output` the same way `experiments_lock_protocol` locks a pre-registered protocol -- verified live: a `status`/`decided_at` update succeeds, a direct `action` rewrite is rejected.

Nothing calls `proposeProtocolAction` yet and nothing writes to `protocol_adjustment_proposals` outside this verification -- wiring it into `analysis-run` (reading the latest `experiment`/`intervention_response`/`relationship` rows, an intervention's `burden_cost_risk`, and `evidenceForIntervention`'s strongest tier per outcome to build `ProtocolRuleInputs`) is follow-up work, tracked below rather than done in this pass given how much of 08.6's I/O-wiring risk discussion already applies here too.

## Still open

- Wire `proposeProtocolAction` into an I/O layer (own `runProtocolRules.ts`, mirroring `runExperiments.ts`/`runInterventionResponse.ts`) that builds `ProtocolRuleInputs` from the latest relevant `analysis_results` rows and an intervention/protocol's own columns, and writes new `protocol_adjustment_proposals` rows -- not done in this pass (08.7 notes).

- Deploy `analysis-run` with `runInterventionResponse.ts` included (via `mcp__Supabase__deploy_edge_function` or the CLI, entrypoint `index.ts` plus every file under `_shared/analysis-core/` it imports, same as `docs/analysis-layer-3/README.md`'s "Deploying `analysis-run`" section) -- not done in this pass, see 08.6 notes.

- 08.8 onward, in the sequence listed above -- each depends on `interventions`/`protocols`/`experiments.intervention_id` existing, nothing on 08.2-08.7 depends on them.
- Future, separate from this milestone's scope: widening the daily frame (`buildFrame`/`RawInputs`) to carry more outcome series (`vo2max`, `training_load`, `body_weight`, ...) so experiments and relationships can use them -- tracked here, not attempted in 08.5.
- Four verification test rows could not be deleted via the MCP SQL tool: `interventions` id `4bf314fd-faaf-457a-9ed7-5f07f706b806`, `protocols` id `bae02e05-d807-4380-a574-e8eed14fe313` (cascades to one `protocol_interventions` row), `experiments` id `d175027b-fc0e-48e7-94da-41dda537ec01`, and `protocol_adjustment_proposals` id `791c6ce1-1434-4434-b405-13176b04b4f4`. Every DELETE attempted on this project this session timed out regardless of table, with no lock contention found server-side (`pg_locks`/`pg_stat_activity` both clean) -- looks like a session-level issue with the tool's handling of destructive statements, not a data problem. All four are clearly named "verification test row" / "(verification test row)" and are harmless to leave; delete them from the Supabase dashboard or retry the MCP tool in a fresh session.
