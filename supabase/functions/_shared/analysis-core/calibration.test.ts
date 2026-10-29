// Null-world calibration of the evidence boards: with no real effect anywhere, how often does the engine say "clear"?
// Seeded, so a failure is reproducible. Added with the lag-curve correction (analysis review 2026-10-29), which measured
// 16% of no-effect histories showing at least one "clear" day when each of six lags was judged on its own.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { analyseLagCurve, bandModel, LAG_CURVES } from './evidence.ts'
import { HYPOTHESES } from './hypotheses.ts'
import { runCatalogue } from './relationship.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'

const axis = (n: number) => dateAxis('2026-01-05', new Date(Date.parse('2026-01-05T00:00:00Z') + (n - 1) * 86400000).toISOString().slice(0, 10))

test('lag curves: with no effect, a "clear" day is rare once the six days are judged together', () => {
  const spec = LAG_CURVES.find((s) => s.id === 'steps_hrv')!
  const RUNS = 150
  let anyDay = 0
  let aloneDay = 0
  let week = 0
  for (let seed = 1; seed <= RUNS; seed++) {
    const rng = mulberry32(seed * 7919)
    const dates = axis(300)
    let ar = 0
    const hrv = dates.map(() => { ar = 0.5 * ar + 3.5 * gaussian(rng); return 62 + ar })
    const steps = dates.map((d) => Math.max(0, 8000 + 2500 * gaussian(rng) + (new Date(d + 'T00:00:00Z').getUTCDay() % 6 === 0 ? 2000 : 0)))
    const frame = { dates, series: { hrv, steps }, excluded: dates.map(() => false) }
    const r = analyseLagCurve(spec, frame as never, bandModel(frame as never, 'hrv')!)
    assert.equal(r.state, 'ready')
    assert.ok(r.simultaneous && r.simultaneous.criticalValue > r.simultaneous.individualCritical)
    if (r.clearLags.length > 0) anyDay++
    if (r.lags.some((l) => l.lag >= 1 && (l.lo > 0 || l.hi < 0))) aloneDay++
    if (r.cumulative && (r.cumulative.lo > 0 || r.cumulative.hi < 0)) week++
  }
  // judged alone, about one history in six has a "clear" day; judged together it is about the intended 5% or less
  assert.ok(aloneDay / RUNS > 0.1, `single-lag intervals alone: ${aloneDay / RUNS}`)
  assert.ok(anyDay / RUNS <= 0.08, `a clear day under no effect: ${anyDay / RUNS}`)
  assert.ok(week / RUNS <= 0.1, `a clear week total under no effect: ${week / RUNS}`)
})

test('the confirmatory catalogue: with no effect in any of its questions, none is called clear', () => {
  const RUNS = 40
  let anyClear = 0
  for (let seed = 1; seed <= RUNS; seed++) {
    const rng = mulberry32(seed * 104729)
    const dates = axis(300)
    const series: Record<string, (number | null)[]> = {}
    for (const h of HYPOTHESES) {
      if (!series[h.target]) {
        let ar = 0
        series[h.target] = dates.map(() => {
          ar = 0.5 * ar + gaussian(rng)
          const base = h.target === 'hrv' ? 62 + 3.5 * ar : h.target === 'rhr' ? 55 + 1.5 * ar : h.target === 'sleep_hours' ? 7.4 + 0.6 * ar : 6 + 1.2 * ar
          return h.target === 'energy' ? Math.max(1, Math.min(10, Math.round(base))) : base
        })
      }
      if (!series[h.cause]) series[h.cause] = dates.map(() => (h.exposure === 'binary' ? (rng() < 0.3 ? 1 : 0) : Math.max(0, 8000 + 2500 * gaussian(rng))))
    }
    const outcomes = runCatalogue(HYPOTHESES, { dates, series, excluded: dates.map(() => false) } as never)
    if (outcomes.some((o) => o.state === 'clear')) anyClear++
  }
  // a verdict needs the whole widened interval beyond the no-effect zone, which is far stricter than p < 0.05
  assert.equal(anyClear, 0)
})
