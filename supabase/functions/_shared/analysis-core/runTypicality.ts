// I/O for the typicality engine: takes the daily frame the relationship engine already loaded, restarts the baseline at the
// last confirmed series break among the signals' own series, runs the engine and publishes one analysis_results row
// (kind 'typicality', key 'personal_normal'). Duck-typed client, tested in Node, run unchanged in analysis-run.
import type { Admin, LoadedFrame } from './runRelationships.ts'
import { analyseTypicality, BASELINE_DAYS, EVAL_DAYS, MIN_COMPLETE } from './typicality.ts'
import type { SignalName, TypicalityResult } from './typicality.ts'
import { fnv1a } from './hash.ts'
import { CORE_VERSION } from './version.ts'

// Series-break metrics that describe the five signals; a confirmed break in any of them starts a new regime.
export const TYPICALITY_BREAK_METRICS = ['hrv', 'rhr', 'sleep_hours', 'deep_min', 'rem_min']
export const TYPICALITY_KEY = 'personal_normal'

// Device-sensitive signals and the series-break metrics that describe them. A signal is left out while a detected break
// (candidate, confirmed or dismissed: a real change mixes regimes in one window just the same) lies inside the window used.
const DEVICE_SENSITIVE: { signal: SignalName; metrics: string[] }[] = [{ signal: 'deep_rem', metrics: ['deep_min', 'rem_min'] }]

export interface TypicalityRun {
  userId: string
  error?: string
  state?: string
  baselineDays?: number
}

export async function runTypicality(admin: Admin, loaded: LoadedFrame, now: Date = new Date()): Promise<TypicalityRun> {
  const { userId, frame, today } = loaded
  const { data, error: readError } = await admin
    .from('analysis_results')
    .select('key, payload')
    .eq('user_id', userId)
    .eq('kind', 'series_break')
  if (readError) return { userId, error: readError.message }
  type BreakRow = { key: string; payload: { lastConfirmed?: string | null; breaks?: { date: string }[] } }
  const rows = (data ?? []) as BreakRow[]
  const windowStart = frame.dates[Math.max(0, frame.dates.length - EVAL_DAYS - BASELINE_DAYS)]
  const skip = DEVICE_SENSITIVE.filter((d) => rows.some((r) => d.metrics.includes(r.key) && (r.payload?.breaks ?? []).some((b) => b.date >= windowStart))).map((d) => d.signal)
  const confirmed: string[] = rows
    .filter((r) => TYPICALITY_BREAK_METRICS.includes(r.key) && r.payload?.lastConfirmed)
    .map((r) => r.payload.lastConfirmed as string)
  const latest = confirmed.sort()[confirmed.length - 1]
  const restartIndex = latest ? Math.max(0, frame.dates.findIndex((d) => d >= latest)) : 0

  const s = frame.series
  const result: TypicalityResult = analyseTypicality({
    dates: frame.dates,
    hrv: s.hrv,
    rhr: s.rhr,
    sleepHours: s.sleep_hours,
    deepRemMin: s.deep_rem_min,
    steps: s.steps,
    excluded: frame.excluded,
    restartIndex,
    skip,
  })

  const { error } = await admin.from('analysis_results').upsert(
    {
      user_id: userId,
      kind: 'typicality',
      key: TYPICALITY_KEY,
      as_of: today,
      engine_version: CORE_VERSION,
      inputs_hash: fnv1a(JSON.stringify([frame.dates[0], s.hrv, s.rhr, s.sleep_hours, s.deep_rem_min, s.steps, frame.excluded, restartIndex, skip])),
      state: result.state,
      confidence_have: result.baselineDays,
      confidence_need: MIN_COMPLETE,
      payload: result,
      calculated_at: now.toISOString(),
    },
    { onConflict: 'user_id,kind,key,as_of,engine_version' },
  )
  if (error) return { userId, error: error.message }
  await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', 'typicality').eq('key', TYPICALITY_KEY).eq('engine_version', CORE_VERSION).lt('as_of', today)
  return { userId, state: result.state, baselineDays: result.baselineDays }
}
