# Health concerns: validation of the association findings

How often the findings engine (01-plan.md §3.6-3.7; `healthAssociations.ts`, `healthFindings.ts`) shows a pattern that
is not there, and how often it finds one that is. Synthetic years, so the truth is known; the numbers below decide what
the ladder's words can promise.

## The synthetic years

`analysis-core/healthSimKit.ts`. One lower-limb concern. Runs on 55% of days (30% of them trail, 15% long, 20%
intervals), strength twice a week, sleep 6.0-8.4 h, an HRV-band flag on 20% of mornings, random daily load. Flare-ups
start with a daily hazard of 0.02 (about seven a year, lasting 3-8 days, severity 3-6) once the previous one has been
symptom-free for a week; in the planted scenarios the hazard is multiplied in the three days after a trail run.
Check-ins on 90% of flare-up days and 50% of other days (check-ins 1.8x as frequent during flare-ups, as real logging
is). The engine is re-run every four weeks from day 120 (every week where stated), carrying its ledger, as the nightly
job does. A run counts as "ever POSSIBLE" if any exposure (no effect) or any exposure other than trail runs and runs in
general (planted) was ever shown POSSIBLE.

Reproduce: `node supabase/functions/_shared/analysis-core/healthSimMeasure.ts <null|planted> <runs> <effect> <step days> <days>`.
CI runs a smaller, seeded version (`healthSimulation.test.ts`) as a regression guard.

## Results (final engine)

| Scenario | Runs | Ever POSSIBLE | Ever PERSISTENT |
|---|---|---|---|
| No effect, 1 year, re-checked weekly | 200 | 0.5% | 0% |
| No effect, 1 year, re-checked every 4 weeks (CI, seeded) | 150 | 0% | 0% |
| No effect, 2 years, every 4 weeks | 300 | 5.7% (2.8% a year) | 0.3% |
| No effect, 2 years, every 4 weeks, twice the flare-ups (hazard 0.04) | 200 | 10.5% (5.2% a year) | 1.5% |
| Trail runs 3x, 2 years | 100 | trail runs: 21% | trail runs: 7% |
| Trail runs 3x, 2 years, twice the flare-ups | 100 | trail runs: 41% | trail runs: 16% |
| Trail runs 5x, 1 year | 100 | trail runs: 19% | trail runs: 2% |
| Trail runs 5x, 2 years | 100 | trail runs: 65% | trail runs: 38% |

Gates from the plan (§3.7, safeguard 7): with no effect, at most 5% of concerns a year ever shown POSSIBLE and at most
1% PERSISTENT. Met at the expected number of flare-ups; at the gate (5.2% a year) with twice as many. The plan's power
target (a 3x effect found in 60% of runs with about ten episodes) is **not** met and cannot be with this much data while
the false-positive gate holds: with seven to fourteen flare-ups a year and about ten questions per concern, a 3x effect
needs two years and many flare-ups to show reliably, a 5x effect about two years. The engine is built to say NOT ENOUGH
YET or NO PATTERN in the meantime, which is the honest answer. The owner's ask was that it never claim more than the data
supports; power was not bought by loosening the safeguards.

## What the simulations changed (in order)

1. **A first version passed the 1-year null gate but failed over 2 years** (11% ever POSSIBLE, 2.3% PERSISTENT with
   monthly re-checks): a finding re-checked every night gets many chances to cross a line by luck. Added: a first pass
   is only a hint (HINT · RE-CHECKING); it is shown as POSSIBLE once it passes again at least 14 days later with more
   data, every re-check in between passing too; PERSISTENT needs the flare-ups (or sessions) after it was first shown to
   reach an exact one-sided p of 0.2 or less on their own.
2. **"Long run" duplicated "any run"**: a long run was "at least the person's 80th-percentile duration", which with runs
   of two usual lengths fell on an ordinary run. Now 90 minutes, or 1.5 times the person's median run.
3. **Two analyses per exposure entered the correction and either could pass.** Now one primary analysis is fixed per
   exposure in advance: the session-level comparison for a kind of session (trail, long, quality runs, strength for the
   area), the flare-up comparison otherwise. The other is shown as supporting evidence.
4. **Comparison sessions shadowed by an exposed one** (an ordinary run the day after a trail run shares its follow-up
   days) diluted the session comparison; they are left out of it.
5. **One flare-up was counted for several sessions** when their follow-up windows overlapped, which made the session
   comparison look more certain than it was and produced most of the remaining null hits (long, quality and trail
   runs). Each flare-up is now credited to the last session of the sport before it; an earlier session whose window
   also held it is left out.

The figures before step 5 overstated power (a planted 3x effect "found" in 39% of two-year runs); the table above is
after it.

## Limits of these simulations

- One exposure structure; real exposures are correlated (a trail run is often a long one). The findings name
  co-exposures, but the simulations do not test how well.
- Check-in behaviour is simplified (missingness depends only on being in a flare-up).
- The re-check rule assumes one look a day: the ledger keeps the first entry of each day, so the on-demand runs after a
  check-in add no extra looks to the history (they can only change what today's screen shows).
