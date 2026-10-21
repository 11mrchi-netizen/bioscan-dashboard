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
import { CORE_VERSION } from './version.ts'

export interface EvidenceRun {
  userId: string
  error?: string
  published?: { kind: string; key: string; state: string }[]
}

export async function runEvidence(admin: Admin, loaded: LoadedFrame, now: Date = new Date()): Promise<EvidenceRun> {
  const { userId, frame, today } = loaded
  const breaks = await loadConfirmedBreaks(admin, userId)
  if ('error' in breaks) return { userId, error: breaks.error as string }
  const models = new Map<string, BandModel>()
  for (const metric of ['hrv', 'rhr', 'sleep_hours']) {
    const m = bandModel(frame, metric, breaks.get(metric) ?? [])
    if (m) models.set(metric, m)
  }
  const hashOf = (...parts: unknown[]) => fnv1a(JSON.stringify([frame.dates[0], frame.dates[frame.dates.length - 1], ...parts]))
  const rows: { kind: string; key: string; state: string; have: number; need: number; payload: unknown; hash: string }[] = []

  for (const spec of EVENT_CURVES) {
    const m = models.get(spec.outcome)
    if (!m) continue
    const r = analyseEventCurve(spec, frame, m)
    rows.push({ kind: 'event_curve', key: spec.id, state: r.state, have: r.events, need: EVENT_WINDOW.minEvents, payload: r, hash: hashOf(spec.id, frame.series[spec.exposure], m.values) })
  }
  for (const o of CROSSOVER_OUTCOMES) {
    const m = models.get(o.outcome)
    if (!m) continue
    const r = analyseCaseCrossover(frame, m, o.side, o.text)
    const best = Math.max(0, ...r.rows.map((x) => x.cases))
    rows.push({ kind: 'case_crossover', key: o.outcome, state: r.rows.some((x) => x.verdict !== 'not_yet') ? 'ready' : 'not_yet', have: best, need: MIN_CROSSOVER_CASES, payload: r, hash: hashOf(o.outcome, m.z) })
  }
  for (const spec of LAG_CURVES) {
    const m = models.get(spec.outcome)
    if (!m) continue
    const r = analyseLagCurve(spec, frame, m)
    rows.push({ kind: 'lag_curve', key: spec.id, state: r.state, have: r.rows, need: LAG.minRows, payload: r, hash: hashOf(spec.id, frame.series[spec.exposure], m.values) })
  }

  const published: NonNullable<EvidenceRun['published']> = []
  for (const row of rows) {
    const { error } = await admin.from('analysis_results').upsert(
      {
        user_id: userId,
        kind: row.kind,
        key: row.key,
        as_of: today,
        engine_version: CORE_VERSION,
        inputs_hash: row.hash,
        state: row.state,
        confidence_have: row.have,
        confidence_need: row.need,
        payload: row.payload,
        calculated_at: now.toISOString(),
      },
      { onConflict: 'user_id,kind,key,as_of,engine_version' },
    )
    if (error) return { userId, error: error.message, published }
    await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', row.kind).eq('key', row.key).eq('engine_version', CORE_VERSION).lt('as_of', today)
    published.push({ kind: row.kind, key: row.key, state: row.state })
  }
  return { userId, published }
}
