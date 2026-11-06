// I/O for the expected-band engine (analysis review phase C): takes the daily frame the relationship engine already
// loaded, starts a new regime at each confirmed series break of the metric's own series, and publishes one
// analysis_results row per metric (kind 'expected_band', key = metric). Duck-typed client, tested in Node, run unchanged
// in analysis-run.
import type { Admin, LoadedFrame } from './runRelationships.ts'
import { analyseExpectedBand, BAND_SPECS } from './expectedBand.ts'
import { fnv1a } from './hash.ts'
import { publishResults } from './publish.ts'
import type { ResultRow } from './publish.ts'

export const EXPECTED_BAND_KIND = 'expected_band'

export interface ExpectedBandRun {
  userId: string
  error?: string
  metrics?: { metric: string; state: string; have: number }[]
}

/**
 * Confirmed series-break dates per metric, oldest first. Only confirmed breaks (a source change or the person's answer)
 * start a regime; candidates are noise until then.
 */
export async function loadConfirmedBreaks(admin: Admin, userId: string): Promise<Map<string, string[]> | { error: string }> {
  const { data, error } = await admin
    .from('analysis_results')
    .select('key, payload')
    .eq('user_id', userId)
    .eq('kind', 'series_break')
  if (error) return { error: error.message }
  type BreakRow = { key: string; payload: { breaks?: { date: string; status?: string }[] } }
  const out = new Map<string, string[]>()
  for (const r of (data ?? []) as BreakRow[]) {
    const dates = (r.payload?.breaks ?? []).filter((b) => b.status === 'confirmed').map((b) => b.date)
    out.set(r.key, [...(out.get(r.key) ?? []), ...dates].sort())
  }
  return out
}

export async function runExpectedBands(admin: Admin, loaded: LoadedFrame, now: Date = new Date()): Promise<ExpectedBandRun> {
  const { userId, frame, today } = loaded
  const breaks = await loadConfirmedBreaks(admin, userId)
  if ('error' in breaks) return { userId, error: breaks.error as string }

  const rows: ResultRow[] = []
  const have = new Map<string, number>()
  for (const spec of BAND_SPECS) {
    const restartDates = breaks.get(spec.metric) ?? []
    const values = frame.series[spec.metric] ?? frame.dates.map(() => null)
    const result = analyseExpectedBand({ spec, dates: frame.dates, values, excluded: frame.excluded, restartDates })
    rows.push({
      kind: EXPECTED_BAND_KIND, key: spec.metric, state: result.state, have: result.have, need: result.need, payload: result,
      hash: fnv1a(JSON.stringify([frame.dates[0], values, frame.excluded, restartDates])),
    })
    have.set(spec.metric, result.have)
  }
  // one upsert per batch and one delete per batch (publish.ts)
  const out = await publishResults(admin, userId, today, rows, now)
  const metrics: NonNullable<ExpectedBandRun['metrics']> = out.published.map((p) => ({ metric: p.key, state: p.state, have: have.get(p.key) ?? 0 }))
  if (out.error) return { userId, error: out.error, metrics }
  return { userId, metrics }
}
