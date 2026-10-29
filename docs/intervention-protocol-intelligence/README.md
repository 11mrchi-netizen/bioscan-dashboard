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
| 08.8 Protocol audit | **Pure engine done.** `protocolAudit.ts`, tested in `protocolAudit.test.ts`. No new table (publishes as `analysis_results` kind `protocol_audit`, same generic envelope, no schema change); no I/O wiring in this pass (see notes). |
| 08.9 UI (Console + Android) | **Console: done, build-verified.** New INTERVENTIONS tab in the Relationship Lab (`console/src/workspaces/Interventions.tsx`). **Android: domain mirrors only, unverified.** See notes -- no screen/nav wiring. |
| 08.10 Validation | **Done** for what's checkable from pure functions and synthetic data. `interventionValidation.test.ts` (see notes); real-longitudinal-data backtesting is out of reach here by design (no real health data in this repo). |

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

## 08.8 notes

`auditProtocol` returns findings only, never a score, matching DAV-338 literally -- a dedicated test (`auditProtocol never emits a numeric score`) asserts no finding ever carries one. Its same-outcome clustering finding reproduces the issue's own example almost verbatim ("Attribution quality is low because N interventions affecting X were started within the same 10-day window").

Pair-level findings (shared mechanisms/ingredients, timing conflicts) are **not recomputed here**: the module takes already-computed `PrecomputedInteraction` rows as input (from `interaction_records` + `domain/SupplementInteractions.kt`'s pairing/timing logic, or its generalized successor) and only decides whether both sides of a pair belong to the protocol being audited -- this is what DAV-338's "integrate with Supplement Intelligence... without duplicating stack-analysis logic" asks for.

No new table: findings publish as another `analysis_results` kind (`protocol_audit`), the same generic envelope `experiment`/`intervention_response` already use, so no migration was needed. No I/O layer yet -- same reasoning as 08.6/08.7 (an I/O wiring pass into `analysis-run` needs the other two engines' outputs plus a live interaction lookup, better done together with their own wiring than ahead of it).

## 08.9 notes

**Console.** A new INTERVENTIONS tab in the Relationship Lab (`console/src/workspaces/Interventions.tsx`, wired into `RelationshipLab.tsx`'s tab bar alongside `confirmatory`/`exploratory`/`experiments`) lists the signed-in user's `interventions` and `protocols`, their latest `intervention_response`/`protocol_audit` `analysis_results` rows when present, and nothing (not an error) when absent -- reuses `Confirmatory.tsx`'s `IntervalBar` and `conf-*` CSS classes exactly as `Experiments.tsx` already does, same read-only, direct-Supabase-read pattern. Verified: `tsc -b` (clean), `oxlint` (no new warnings beyond the same `set-state-in-effect`/`exhaustive-deps` class already present on sibling files), `vite build` (succeeds, bundles into the `RelationshipLab` chunk). **Not verified:** rendered in a live, authenticated browser session -- that needs real account credentials this session doesn't have, and the console has no component-test harness (only `node --test` on pure-function `lib/*.test.ts` files) to render around the auth gate.

**Android.** `domain/Intervention.kt` and `domain/Protocol.kt` are client-side mirrors of `intervention.ts`/`protocol.ts`'s lifecycle graphs (the same role `Experiment.kt` plays for experiment protocols), with JUnit tests (`InterventionTest.kt`, `ProtocolTest.kt`) mirroring the TS tests' exhaustive-pair style, following the existing enum/`.entries` idiom used elsewhere in the app (`TopLevelTab.kt`, `PeriodToggle.kt`, ...). **None of this could be compiled or run**: this environment has no Android SDK at all (`ANDROID_HOME` unset, nothing under `~/Android`/`~/.android`), so not even a JVM-only unit-test task could execute -- these files are hand-written to match the codebase's conventions as closely as possible, but are unverified in the literal sense (never compiled). No Compose screen or navigation wiring was attempted: `TopLevelTab.kt`'s own comments show tab/screen placement here is a deliberate, mockup-backed IA decision (see its DAV-70/96/97/209/216 history), not something to invent unilaterally without a design pass, and Compose UI code has far more surface area to get wrong than the two small enums/maps above with zero ability to verify any of it. This mirrors the file's own `MapScreen.kt` precedent: a screen can exist in the codebase, written and ready, without being referenced from the nav host.

## 08.10 notes

`interventionValidation.test.ts` is cross-cutting -- it tests the generalized layer's properties, not any one engine's own logic (that's what `experiment.test.ts`/`interventionResponse.test.ts`/`protocolRules.test.ts`/`protocolAudit.test.ts` already do). Four kinds of checks, mapped to DAV-340's exit criteria:

1. **Reproducibility.** `analyseInterventionResponse`/`proposeProtocolAction`/`auditProtocol` each produce identical output from identical input (`assert.deepEqual` on two calls) -- expected, since all three are pure functions with no hidden state, but asserted explicitly rather than assumed.
2. **No unsupported causal claim.** A forbidden-phrase list (`cause(s/d)`, `prove(s)`, `due to`, `confirm(s)`) is checked against text *generated from realistic synthetic runs* of `interventionResponse`/`protocolRules`/`protocolAudit` (signal and no-signal cases, every rule branch, multiple findings) -- not just a static grep, though a static grep across every new source file in this milestone and the console's new copy also found nothing (belt-and-suspenders).
3. **Evidence stays separate from personal observation.** A dedicated test feeds `evidenceForIntervention` a `user_observed` row in every position relative to literature tiers and confirms it always sorts last, regardless of input order.
4. **Cross-engine composition.** One synthetic protocol spans three domains (a supplement with a planted HRV signal, a sleep intervention started 4 days later targeting the same outcome, and an unrelated training deload) through `interventionResponse` -> `auditProtocol` -> `protocolRules` together: the audit correctly clusters the two same-outcome interventions and leaves the unrelated one out, and the rules engine still proposes a sensible action from the response result, independent of the audit (the two are decoupled by design, 08.8's notes).

Two of DAV-340's criteria are **not** independently tested here because they are structural properties already built into the schema and documented per-phase above, not something a synthetic-data test can usefully add to: "raw observations remain distinct from derived outputs" (raw tables `interventions`/`protocols` vs. derived `analysis_results`/`protocol_adjustment_proposals` are separate tables by construction) and "model/version changes are traceable" (`engine_version` is part of every `analysis_results` row's unique key, same as every other Analysis Layer 3 kind). "Existing Evaluation/Comparison contracts are not duplicated" is likewise a code-review property, recorded phase by phase in the notes above (08.5 reuses `stats.ts` unchanged; 08.4 adds no schema; etc.) rather than a thing to assert at runtime.

Real longitudinal backtesting (DAV-340's "backtest selected historical interventions where sufficient data exists") is out of reach in this environment by design: this is a public repository with no real health data in it (same constraint `docs/analysis-layer-3/README.md` states for its own backtests), and no intervention has actually run on the one real account yet (the test rows created during 08.2-08.7's live verification were synthetic/placeholder, not a real lived intervention). This is the one piece of DAV-340 that can only be done privately, by the account holder, once real data exists.

## Still open

- Wire `proposeProtocolAction` into an I/O layer (own `runProtocolRules.ts`, mirroring `runExperiments.ts`/`runInterventionResponse.ts`) that builds `ProtocolRuleInputs` from the latest relevant `analysis_results` rows and an intervention/protocol's own columns, and writes new `protocol_adjustment_proposals` rows -- not done in this pass (08.7 notes).
- Wire `auditProtocol` into an I/O layer that gathers a protocol's interventions, reads evidence/interaction rows and publishes `analysis_results` kind `protocol_audit` -- not done in this pass (08.8 notes).
- Android: an actual Compose screen and its navigation placement, once there is a mockup/IA decision for where interventions/protocols live in the app (Labs? A new top-level tab? Something else?) -- and a real Android build/emulator to verify `Intervention.kt`/`Protocol.kt` actually compile, which this session never had (08.9 notes).
- Console: a live, authenticated-session check of the new INTERVENTIONS tab (08.9 notes) -- build-verified only.

- Deploy `analysis-run` with `runInterventionResponse.ts` included (via `mcp__Supabase__deploy_edge_function` or the CLI, entrypoint `index.ts` plus every file under `_shared/analysis-core/` it imports, same as `docs/analysis-layer-3/README.md`'s "Deploying `analysis-run`" section) -- not done in this pass, see 08.6 notes.

- 08.8 onward, in the sequence listed above -- each depends on `interventions`/`protocols`/`experiments.intervention_id` existing, nothing on 08.2-08.7 depends on them.
- Future, separate from this milestone's scope: widening the daily frame (`buildFrame`/`RawInputs`) to carry more outcome series (`vo2max`, `training_load`, `body_weight`, ...) so experiments and relationships can use them -- tracked here, not attempted in 08.5.
- Four verification test rows could not be deleted via the MCP SQL tool: `interventions` id `4bf314fd-faaf-457a-9ed7-5f07f706b806`, `protocols` id `bae02e05-d807-4380-a574-e8eed14fe313` (cascades to one `protocol_interventions` row), `experiments` id `d175027b-fc0e-48e7-94da-41dda537ec01`, and `protocol_adjustment_proposals` id `791c6ce1-1434-4434-b405-13176b04b4f4`. Every DELETE attempted on this project this session timed out regardless of table, with no lock contention found server-side (`pg_locks`/`pg_stat_activity` both clean) -- looks like a session-level issue with the tool's handling of destructive statements, not a data problem. All four are clearly named "verification test row" / "(verification test row)" and are harmless to leave; delete them from the Supabase dashboard or retry the MCP tool in a fresh session.

## Repeatable interventions (events) -- 20261113

An informal N-of-1 layer beside the pre-registered experiments, for things tried now and then (nasal strips, a lactose-heavy meal) where one instance is never an answer.

- **Schema** (`20261113_intervention_events.sql`): `interventions.mode` ('continuous' as before, or 'episodic'), `intervention_expectations` (outcome + direction + window in hours after the event, declared before the first event and locked by trigger; later additions are stamped exploratory), `intervention_events` (one occurrence: local day and minute, adherence, confounders, a 0-10 feeling rated before numbers are shown), `tracked_outcomes` / `tracked_outcome_values` (user-defined manual outcomes such as a Bristol stool score).
- **Engine** (`analysis-core/eventEvidence.ts`, I/O in `runEventEvidence.ts`, run by `analysis-run`, published as `analysis_results` kind `event_evidence`): each event's outcome is the aggregate of the outcome inside its window, compared with the same window at the same clock time on days without an event. Events with another intervention in the window, a flagged confounder, alcohol (evening check-in) or overlapping a previous event are left out. Hodges-Lehmann shift with a rank-based interval; p-values only from the top rung. Label by events with data: 1-2 anecdote, 3-6 early signal, 7-14 emerging, 15+ ready to test properly. A daily series enters at an anchor hour on its `date` (`sleep_daily` is keyed by wake-up date, so a night sits at 04:00).
- **Android**: Lab > N=1 gains REPEATABLE INTERVENTIONS (create, log, rate, park, tracked outcomes) and PROTOCOLS (create from a template or blank, lifecycle buttons). **Console**: Evidence > REPEATABLE, read-only.
- **Not done**: ledger rows for events (the `evidence_ledger` verdict vocabulary has no ladder equivalent yet), the "draw tonight" coin flip (`assigned_by = 'drawn'` is in the schema), promotion to a formal experiment, follow-up reminders, auto-detecting events from meal logs, engine support for respiratory rate / SpO2 as formal experiment outcomes.
- **Unverified here**: the Android repository and Compose files (`InterventionEventsRepository`, `InterventionEventSheets`, `InterventionEventsSection`, `ProtocolsRepository`, `ProtocolsSection`) were written without an Android SDK; the pure domain code (`EventEvidence.kt`) and the serialisable models were compiled and tested with kotlinc. Build with `./gradlew :app:assembleDebug` before relying on them. The migration has not been applied to the live project.
