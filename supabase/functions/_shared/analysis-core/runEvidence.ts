// I/O for the evidence engines (analysis review phase D, evidence.ts): takes the shared daily frame, builds the expected-
// band model of each outcome once (restarting at its confirmed series breaks), and publishes event curves, case-crossover
// tables and lag curves as analysis_results rows (kinds 'event_curve', 'case_crossover', 'lag_curve'). Duck-typed client,
// tested in Node, run unchanged in analysis-run.
import type { Admin, LoadedFrame } from './runRelationships.ts'
import { loadConfirmedBreaks } from './runExpectedBands.ts'
import {
  analyseCaseCrossover, analyseEventCurve, analyseLagCurve, bandModel, CROSSOVER_OUTCOMES, EVENT_CURVES, EVENT_WINDOW, LAG, LAG_CURVES,
  MIN_CROSSOVER_CASES,
} from './evidence.ts'
import type { BandModel } from './evidence.ts'
import { fnv1a } from './hash.ts'
import { publishResults } from './publish.ts'
import { stepTimer } from './trace.ts'
import type { Trace } from './trace.ts'

export interface EvidenceRun {
  userId: string
  error?: string
  published?: { kind: string; key: string; state: string }[]
}

export async function runEvidence(admin: Admin, loaded: LoadedFrame, now: Date = new Date(), trace?: Trace): Promise<EvidenceRun> {
  const { userId, frame, today } = loaded
  const mark = stepTimer(trace)
  const breaks = await loadConfirmedBreaks(admin, userId)
  mark('breaks')
  if ('error' in breaks) return { userId, error: breaks.error as string }
  const models = new Map<string, BandModel>()
  for (const metric of ['hrv', 'rhr', 'sleep_hours']) {
    const m = bandModel(frame, metric, breaks.get(metric) ?? [])
    if (m) models.set(metric, m)
    mark(`model.${metric}`)
  }
  const hashOf = (...parts: unknown[]) => fnv1a(JSON.stringify([frame.dates[0], frame.dates[frame.dates.length - 1], ...parts]))
  const rows: { kind: string; key: string; state: string; have: number; need: number; payload: unknown; hash: string }[] = []

  for (const spec of EVENT_CURVES) {
    const m = models.get(spec.outcome)
    if (!m) continue
    const r = analyseEventCurve(spec, frame, m)
    mark(`curve.${spec.id}`, { events: r.events })
    rows.push({ kind: 'event_curve', key: spec.id, state: r.state, have: r.events, need: EVENT_WINDOW.minEvents, payload: r, hash: hashOf(spec.id, frame.series[spec.exposure], m.values) })
  }
  for (const o of CROSSOVER_OUTCOMES) {
    const m = models.get(o.outcome)
    if (!m) continue
    const r = analyseCaseCrossover(frame, m, o.side, o.text)
    mark(`crossover.${o.outcome}`)
    const best = Math.max(0, ...r.rows.map((x) => x.cases))
    rows.push({ kind: 'case_crossover', key: o.outcome, state: r.rows.some((x) => x.verdict !== 'not_yet') ? 'ready' : 'not_yet', have: best, need: MIN_CROSSOVER_CASES, payload: r, hash: hashOf(o.outcome, m.z) })
  }
  for (const spec of LAG_CURVES) {
    const m = models.get(spec.outcome)
    if (!m) continue
    const r = analyseLagCurve(spec, frame, m)
    mark(`lag.${spec.id}`, { rows: r.rows })
    rows.push({ kind: 'lag_curve', key: spec.id, state: r.state, have: r.rows, need: LAG.minRows, payload: r, hash: hashOf(spec.id, frame.series[spec.exposure], m.values) })
  }

  mark('hashes', { rows: rows.length })
  // one upsert per batch of rows and one delete per kind and batch (publish.ts), not an upsert and a delete per row
  const out = await publishResults(admin, userId, today, rows, now)
  mark('publish', { rows: rows.length })
  if (out.error) return { userId, error: out.error, published: out.published }
  return { userId, published: out.published }
}
