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
| `protocols` | An objective-driven grouping of interventions with baseline/active/washout/evaluation windows. | 08.3 |
| `protocol_interventions` | Join table: which interventions belong to a protocol, in what role/sequence. | 08.3 |
| `protocol_adjustment_proposals` | Mutable proposal rows from the adaptive-rules engine (`proposed`/`accepted`/`rejected`/`modified`). | 08.7 |

## Status

| Phase | State |
| -- | -- |
| 08.1 Audit & architecture | **Done.** This document. |
| 08.2 Canonical `interventions` model + lifecycle | **Done.** Migration `supabase/migrations/20261013_interventions.sql` (applied to the live project); lifecycle graph in `supabase/functions/_shared/analysis-core/intervention.ts`, tested in `intervention.test.ts`. |
| 08.3 `protocols` model | Not started. |
| 08.4 Evidence registry generalization | Not started. |
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

## Still open

- 08.3 onward, in the sequence listed above -- each depends on `interventions` existing, nothing on 08.2 depends on them.
- A single verification test row in `interventions` (id `4bf314fd-faaf-457a-9ed7-5f07f706b806`, name contains "verification test row") could not be deleted via the MCP SQL tool (repeated timeouts on the DELETE specifically, no lock contention found server-side) and needs manual cleanup or a retry.
