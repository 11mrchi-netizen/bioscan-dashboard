# Intervention & Protocol Intelligence

Design and status notes for Linear milestone **08 — Intervention & Protocol Intelligence** (`DAV-331`..`DAV-340`, `08.1`..`08.10`). The goal is a generalized, closed-loop personal-intervention layer -- supplements, nutrition, training, sleep, lifestyle -- built around:

MEASURE -> IDENTIFY A TARGET -> DEFINE INTERVENTION/PROTOCOL -> ESTABLISH BASELINE -> APPLY -> MEASURE RESPONSE -> ANALYZE PERSONAL SIGNAL -> KEEP / MODIFY / STOP -> REASSESS.

Non-goals, throughout: gamification, a universal health score, causal claims from simple before/after data.

## Architecture in one paragraph

This milestone is substantially a **generalization** of machinery `docs/analysis-layer-3` already built and shipped for supplements only: a pre-registered N-of-1 engine (`experiments` + `supabase/functions/_shared/analysis-core/experiment.ts`/`stats.ts`), an outcome-specific evidence registry (`evidence_records`), a pair-interaction registry (`interaction_records`), and a fully generic published-result envelope (`analysis_results`). None of those four are rebuilt here. What's missing is the canonical, domain-agnostic layer above them -- `interventions` and `protocols` -- plus the engines that are genuinely new: adaptive rules and protocol-wide audit. The N-of-1 statistics core itself needs no change; it already operates on arbitrary metric series, not supplement semantics.

## Reuse vs. generalize vs. new

| Reused as-is | Generalized | New |
| -- | -- | -- |
| `analysis_results` envelope | `experiments` (add `intervention_id`, widen the metric whitelist to the metric registry) | `interventions`, `intervention_state_history` |
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
| `protocol_adjustment_proposals` | Mutable proposal rows from the adaptive-rules engine (`proposed`/`accepted`/`rejected`/`modified`). | 08.7 |

## Status

| Phase | State |
| -- | -- |
| 08.1 Audit & architecture | **Done.** This document. |
| 08.2 Canonical `interventions` model + lifecycle | **Done.** Migration `supabase/migrations/20261013_interventions.sql` (applied to the live project); lifecycle graph in `supabase/functions/_shared/analysis-core/intervention.ts`, tested in `intervention.test.ts`. |
| 08.3 `protocols` model | **Done.** Migration `supabase/migrations/20261013_protocols.sql` (applied); lifecycle graph and `generateSchedule` in `supabase/functions/_shared/analysis-core/protocol.ts`, tested in `protocol.test.ts`. |
| 08.4 Evidence registry generalization | **Done.** No schema change needed (see notes); matching/ranking in `supabase/functions/_shared/analysis-core/evidenceLink.ts`, tested in `evidenceLink.test.ts`. |
| 08.5 Generalize the N-of-1 engine | Not started. |
| 08.6 Intervention response analysis (observational) | Not started. |
| 08.7 Adaptive protocol rules | Not started. |
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

## Still open

- 08.4 onward, in the sequence listed above -- each depends on `interventions`/`protocols` existing, nothing on 08.2/08.3 depends on them.
- Two verification test rows could not be deleted via the MCP SQL tool: `interventions` id `4bf314fd-faaf-457a-9ed7-5f07f706b806` and `protocols` id `bae02e05-d807-4380-a574-e8eed14fe313` (which cascades to one `protocol_interventions` row linking the two). Every DELETE attempted on this project this session timed out regardless of table, with no lock contention found server-side (`pg_locks`/`pg_stat_activity` both clean) -- looks like a session-level issue with the tool's handling of destructive statements, not a data problem. Both rows are clearly named "verification test row" / "(verification test row)" and are harmless to leave; delete them from the Supabase dashboard or retry the MCP tool in a fresh session.
