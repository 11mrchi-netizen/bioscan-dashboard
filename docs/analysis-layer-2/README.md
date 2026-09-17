# Analysis Layer 2 — data & model contract

Design documents for the [Analysis Layer 2](https://linear.app/biodashboard/project/analysis-layer-2)
project. Together they answer: what data actually exists, what a canonical metric is, how the two
hardest domains (strength, endurance) map real rows onto it, how confidence propagates through
derived values, and the stable shape everything downstream (this app's own UI, or P7's future
Zepp-metric integration) reads.

These are contract/design documents, not implementation — each is scoped to exactly one Linear
issue's acceptance criteria. Where a doc surfaces a gap that isn't this project's own job to fix,
it's filed as a separate follow-up issue (linked inline) rather than solved here.

| Doc | Linear issue | Answers |
|---|---|---|
| [01-data-inventory.md](01-data-inventory.md) | [DAV-52](https://linear.app/biodashboard/issue/DAV-52) | What fields exist, how much real data backs each, what overlaps/duplicates |
| [02-metric-registry.md](02-metric-registry.md) | [DAV-53](https://linear.app/biodashboard/issue/DAV-53) | The canonical name/unit/provenance contract every metric maps into |
| [03-strength-session-model.md](03-strength-session-model.md) | [DAV-54](https://linear.app/biodashboard/issue/DAV-54) | How structured sets/reps/load fits into `exercise_sessions` |
| [04-endurance-metric-adapters.md](04-endurance-metric-adapters.md) | [DAV-55](https://linear.app/biodashboard/issue/DAV-55) | How run/ride/swim/walk/hike map to load, without one universal score |
| [05-confidence-propagation.md](05-confidence-propagation.md) | [DAV-66](https://linear.app/biodashboard/issue/DAV-66) | How confidence/breadth propagate through derived and combined values |
| [06-output-contract.md](06-output-contract.md) | [DAV-67](https://linear.app/biodashboard/issue/DAV-67) | The stable output shapes every downstream consumer reads |

Milestone 1 ("Data & model contract") is fully closed as of docs 01-04. Docs 05-06 pull forward two
contract-defining tickets filed under milestones 3-4, ahead of the implementation work (DAV-56
through DAV-63) that needs to conform to them — see each doc's own header for why.

All real-data figures below are a snapshot of the live Supabase project (`ugfrglbcoivkprjqvjzz`) as
queried on 2026-09-17 — re-run the queries in each doc's "Method" note if the numbers matter later
and time has passed.

## Prior art this builds on

This is *Analysis Layer 2* because Analysis Layer 1 already exists and works: `domain/*.kt` in the
Android app already implements real per-metric evaluations (HRV/RHR SWC bands, training load
CTL/ATL/TSB, sleep regularity, body composition trend, subjective wellbeing, bloodwork flags,
OSTRC injury screening) against a shared `EvalState`/`Confidence` contract
(`android-app/app/src/main/java/com/bioscan/fieldterminal/domain/EvalState.kt`). This project
doesn't replace that — it fills in the data-contract layer underneath it that was never written
down: exactly what's available, what a "canonical metric" means precisely enough to add new ones
without re-deriving the rules each time, and two concrete domains (strength, endurance) where the
existing generic `exercise_sessions` table needs a documented adapter rather than more special
cases.
