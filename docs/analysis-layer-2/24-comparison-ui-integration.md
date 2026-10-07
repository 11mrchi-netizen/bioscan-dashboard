# 24 — Exposing comparison outputs in the UI

**Linear:** [DAV-200](https://linear.app/biodashboard/issue/DAV-200) · **Status:** Phase 1 (personal comparison) live-wired to HRV; population comparison not yet built

## Reusing existing tile cards, not a new screen

Per DAV-200's own "no ranking math in presentation code" — `ui/components/ComparisonStrip.kt`
takes an already-computed `ComparisonResult` and only formats what `presentComparison()` (DAV-199)
already decided. It's one line inside an existing card (`HeartTileScreen.kt`'s `EvalCard`, which
HRV/RHR/sleep duration all already use), not a new destination — matching this app's established
IA (one tile per domain, DAV-93/95/97/98) rather than adding a "Comparisons" tab nobody asked for.

## First live wiring: HRV

`HeartTileScreen.kt`'s Cardio tab computes an `hrvComparison` from the same `wearable_daily.hrv`
points already fetched for the SWC evaluation — the most recent reading against every earlier one,
via `comparePersonal("hrv", ...)`. Passed into `EvalCard`'s new optional `comparison` param,
rendered right after the confidence line, before the existing `FTRangeIndicator` personal-baseline
band — the two are complementary (SWC's band answers "is this a meaningful change," the comparison
strip answers "where does this rank against my own history"), not a duplicate.

## Population comparison deliberately not shown yet, not even as a placeholder

`ComparisonStrip` takes only a personal `ComparisonResult` today. A "Population: no benchmark yet"
row was considered and rejected — DAV-196's population engine doesn't exist in code at all yet
(gated on sourcing real published reference data, see the milestone plan's own open question), so a
placeholder row would read as "the system checked and found nothing" rather than "this hasn't been
built," a different and misleading claim. `ComparisonStrip` gains a second parameter once DAV-196
actually produces a real `ComparisonResult` to show — not before.

## Next wiring targets

RHR and sleep duration's `EvalCard` calls are one `comparePersonal()` call away from the same
treatment (identical shape to HRV's). `TrainingScreen.kt`'s pace/distance stats are next after
that — real, deep history, but need a `ComparableSet` rule for session-shaped data
(`isComparableSessionDistance`, doc 22) applied first, unlike a daily metric's unrestricted history.
`SessionDetailScreen.kt`'s TrailCard/StrengthCard wait until DAV-198's comparable-set rules cover
trail/strength context specifically, per the milestone plan's own sequencing.

## Verification

`./gradlew compileDebugKotlin testDebugUnitTest` green. Live device check pending phone
reconnection: open the Health tab's Cardio sub-tab, confirm the HRV card shows a real personal
percentile/band line beneath its confidence chip.
