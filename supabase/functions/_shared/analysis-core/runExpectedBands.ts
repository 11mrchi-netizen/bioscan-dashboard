// I/O for the expected-band engine (analysis review phase C): takes the daily frame the relationship engine already
// loaded, starts a new regime at each confirmed series break of the metric's own series, and publishes one
// analysis_results row per metric (kind 'expected_band', key = metric). Duck-typed client, tested in Node, run unchanged
// in analysis-run.
import type { Admin, LoadedFrame } from './runRelationships.ts'
import { analyseExpectedBand, BAND_SPECS } from './expectedBand.ts'
import { fnv1a } from './hash.ts'
import { CORE_VERSION } from './version.ts'

export const EXPECTED_BAND_KIND = 'expected_band'

export interface ExpectedBandRun {
  userId: string
  error?: string
  metrics?: { metric: string; state: string; have: number }[]
}

export async function runExpectedBands(admin: Admin, loaded: LoadedFrame, now: Date = new Date()): Promise<ExpectedBandRun> {
  const { userId, frame, today } = loaded
  const { data, error: readError } = await admin
    .from('analysis_results')
    .select('key, payload')
    .eq('user_id', userId)
    .eq('kind', 'series_break')
  if (readError) return { userId, error: readError.message }
  type BreakRow = { key: string; payload: { breaks?: { date: string; status?: string }[] } }
  const rows = (data ?? []) as BreakRow[]

  const metrics: NonNullable<ExpectedBandRun['metrics']> = []
  for (const spec of BAND_SPECS) {
    // Only confirmed breaks (a source change or the person's answer) start a regime; candidates are noise until then.
    const restartDates = rows
      .filter((r) => r.key === spec.metric)
      .flatMap((r) => (r.payload?.breaks ?? []).filter((b) => b.status === 'confirmed').map((b) => b.date))
      .sort()
    const values = frame.series[spec.metric] ?? frame.dates.map(() => null)
    const result = analyseExpectedBand({ spec, dates: frame.dates, values, excluded: frame.excluded, restartDates })
    const { error } = await admin.from('analysis_results').upsert(
      {
        user_id: userId,
        kind: EXPECTED_BAND_KIND,
        key: spec.metric,
        as_of: today,
        engine_version: CORE_VERSION,
        inputs_hash: fnv1a(JSON.stringify([frame.dates[0], values, frame.excluded, restartDates])),
        state: result.state,
        confidence_have: result.have,
        confidence_need: result.need,
        payload: result,
        calculated_at: now.toISOString(),
      },
      { onConflict: 'user_id,kind,key,as_of,engine_version' },
    )
    if (error) return { userId, error: error.message, metrics }
    await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', EXPECTED_BAND_KIND).eq('key', spec.metric).eq('engine_version', CORE_VERSION).lt('as_of', today)
    metrics.push({ metric: spec.metric, state: result.state, have: result.have })
  }
  return { userId, metrics }
}
