// Health concerns: reading the raw tables (docs/health-concerns/01-plan.md §4). Duck-typed client as the other run*
// modules, tested in Node, run unchanged in analysis-run. Every read is soft: before the migration the tables do not
// exist, and the nightly run must keep working (the same rule as the phase B tables in loadFrame).
import { fetchAll } from './paging.ts'
import { buildConcernSeries, shiftDate } from './healthSeries.ts'
import type { ConcernRow, MarkRow, ObservationRow, SignalRow } from './healthSeries.ts'
import { deriveEpisodes } from './healthEpisodes.ts'

// deno-lint-ignore no-explicit-any
type Admin = any

export interface HealthInputs {
  concerns: ConcernRow[]
  observations: ObservationRow[]
  signals: SignalRow[]
  marks: MarkRow[]
  /** Set when the tables could not be read (for example before the migration). */
  error?: string
}

const CONCERN_COLS = 'id, title, kind, onset_type, regions, symptoms, excludes_from_baselines, resolution_days, archived_at, created_at'
const OBS_COLS = 'id, observed_at, local_date, concern_id, scope, symptom_free, recall, recall_from, function_impact, change_vs_last, onset, session_id, timing, supersedes, superseded_at, retracted, source, created_at'
const SIG_COLS = 'observation_id, concern_id, symptom, region, side, severity, details, temperature_c'
const MARK_COLS = 'concern_id, kind, date, retracted_at'

export async function loadHealthInputs(admin: Admin, userId: string): Promise<HealthInputs> {
  const page = (table: string, cols: string, order: string) => (a: number, b: number) =>
    admin.from(table).select(cols).eq('user_id', userId).order(order).range(a, b)
  const [c, o, s, m] = await Promise.all([
    fetchAll<ConcernRow>(page('health_concerns', CONCERN_COLS, 'created_at')),
    fetchAll<ObservationRow>(page('health_observations', OBS_COLS, 'observed_at')),
    fetchAll<SignalRow>(page('health_signals', SIG_COLS, 'id')),
    fetchAll<MarkRow>(page('health_episode_marks', MARK_COLS, 'date')),
  ])
  const failed = [c, o, s, m].find((r) => r.error)
  if (failed) return { concerns: [], observations: [], signals: [], marks: [], error: failed.error }
  return {
    concerns: c.rows.map((r) => ({ ...r, regions: Array.isArray(r.regions) ? r.regions : [], symptoms: r.symptoms ?? [] })),
    observations: o.rows,
    signals: s.rows,
    marks: m.rows,
  }
}

/** First local date any check-in or mark of these inputs touches (the start of the day axis). */
export function healthAxisStart(inputs: HealthInputs, today: string, floorDays = 730): string {
  const dates = [
    ...inputs.observations.map((o) => o.recall_from ?? o.local_date),
    ...inputs.marks.map((m) => m.date),
  ].filter((d) => d <= today)
  const floor = shiftDate(today, -(floorDays - 1))
  const first = dates.sort()[0] ?? today
  return first < floor ? floor : first
}

/**
 * Episodes of systemic concerns that leave their days out of baseline comparisons, in the shape loadFrame already uses
 * for logged illness ({start_date, end_date}; end_date null while open). An ended episode passes its end (the first
 * day after it), so a legacy illness copied in with an end mark on its end date gives exactly the old span.
 */
export function baselineExclusionSpans(inputs: HealthInputs, today: string): { start_date: string; end_date: string | null }[] {
  const concerns = inputs.concerns.filter((c) => c.excludes_from_baselines)
  if (concerns.length === 0) return []
  const { series } = buildConcernSeries(concerns, inputs.observations, inputs.signals, healthAxisStart(inputs, today), today)
  const out: { start_date: string; end_date: string | null }[] = []
  for (const c of concerns) {
    const cells = series.get(c.id)?.cells ?? []
    for (const e of deriveEpisodes(c, cells, inputs.marks)) out.push({ start_date: e.start, end_date: e.end })
  }
  return out
}
