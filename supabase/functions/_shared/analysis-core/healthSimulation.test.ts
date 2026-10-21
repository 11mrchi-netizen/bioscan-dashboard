// Health concerns, plan §3.7 safeguard 7: simulation gates for the association findings (healthSimKit.ts builds the
// synthetic years: one lower-limb concern, runs with some trail and long ones, strength, sleep, HRV-band flags; flare-ups
// start with a daily hazard of 0.02, about seven a year, tripled in the planted scenario in the three days after a trail
// run; check-ins 1.8x as frequent during flare-ups as between them). The engine is re-run with its ledger as the nightly
// job would. Gates (measured values with larger runs in docs/health-concerns/02-validation.md):
//   no effect: at most 5% of concerns ever show POSSIBLE in a year, at most 1% PERSISTENT
//   planted 3x: the trail-run finding is POSSIBLE within two years in at least a quarter of these 30 seeded runs (the
//   100-run estimate is 21%: power is limited by the number of flare-ups, and the safeguards are not loosened to buy it).
// The seeds are fixed, so these are regression guards; the estimates to quote are in 02-validation.md.
// Kept small enough for CI; the larger runs (weekly re-checks, two years without an effect, more effect sizes) are
// reproduced with: node supabase/functions/_shared/analysis-core/healthSimMeasure.ts <null|planted> <runs> <effect> <step> <days>
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { replay, simulateYear } from './healthSimKit.ts'

function rate(n: number, seed: number, effect: number, step: number, days: number, pick: (r: Map<string, { possible: boolean; persistent: boolean }>) => [boolean, boolean]) {
  let a = 0
  let b = 0
  for (let k = 0; k < n; k++) {
    const [x, y] = pick(replay(simulateYear(seed + k, effect, 0.02, days), step))
    if (x) a++
    if (y) b++
  }
  return [a / n, b / n]
}

const anyExposure = (r: Map<string, { possible: boolean; persistent: boolean }>): [boolean, boolean] =>
  [[...r.values()].some((v) => v.possible), [...r.values()].some((v) => v.persistent)]

test('no effect, a year re-checked every four weeks: few concerns ever show a pattern', () => {
  const [possible, persistent] = rate(150, 1000, 1, 28, 365, anyExposure)
  console.log(`# null, monthly, 1 year: ever POSSIBLE ${(possible * 100).toFixed(1)}%, ever PERSISTENT ${(persistent * 100).toFixed(1)}%`)
  assert.ok(possible <= 0.05, `ever possible ${possible}`)
  assert.ok(persistent <= 0.01, `ever persistent ${persistent}`)
})

test('a planted 3x effect of trail runs is found within two years in a quarter of runs or more', () => {
  const [possible, persistent] = rate(30, 9000, 3, 28, 730, (r) => [r.get('trail_run')?.possible ?? false, r.get('trail_run')?.persistent ?? false])
  console.log(`# planted 3x, 2 years: trail_run POSSIBLE ${(possible * 100).toFixed(0)}%, PERSISTENT ${(persistent * 100).toFixed(0)}%`)
  assert.ok(possible >= 0.25, `found ${possible}`)
})
