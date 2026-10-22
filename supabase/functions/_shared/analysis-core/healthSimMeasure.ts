// Health concerns: the larger simulation runs behind docs/health-concerns/02-validation.md (not a test; CI runs the
// smaller gates in healthSimulation.test.ts). Usage:
//   node supabase/functions/_shared/analysis-core/healthSimMeasure.ts <null|planted> <runs> <effect> <step days> <days>
// Prints the share of runs in which any exposure (null) or another exposure than trail runs and runs in general (planted)
// was ever shown POSSIBLE / PERSISTENT, and the same for the trail-run finding.
import { replay, simulateYear } from './healthSimKit.ts'

const [mode = 'null', runs = '100', effect = '1', step = '28', days = '365'] = process.argv.slice(2)
const N = Number(runs)
let other = 0, otherPersistent = 0, trail = 0, trailPersistent = 0
for (let k = 0; k < N; k++) {
  const r = replay(simulateYear(20000 + k, Number(effect), 0.02, Number(days)), Number(step))
  const others = [...r.entries()].filter(([id]) => mode === 'null' || (id !== 'trail_run' && id !== 'any_run'))
  if (others.some(([, v]) => v.possible)) other++
  if (others.some(([, v]) => v.persistent)) otherPersistent++
  if (r.get('trail_run')?.possible) trail++
  if (r.get('trail_run')?.persistent) trailPersistent++
}
console.log(JSON.stringify({ mode, runs: N, effect: Number(effect), stepDays: Number(step), days: Number(days), otherPossible: other / N, otherPersistent: otherPersistent / N, trailPossible: trail / N, trailPersistent: trailPersistent / N }))
