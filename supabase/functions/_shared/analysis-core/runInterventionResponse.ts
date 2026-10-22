// I/O for the observational intervention-response engine (08.6, interventionResponse.ts): reads the user's
// active/evaluating/maintained interventions and publishes one analysis_results row per intervention (kind
// 'intervention_response', key = intervention id). Mirrors runExperiments.ts's shape; duck-typed client, tested in
// Node, run unchanged in analysis-run.
//
// Analysis review phase F adds the interrupted-time-series read (its.ts, kind 'intervention_its'): each intervention is
// read once, on its evaluate_on date (four weeks after the start unless set), against a forecast from the eight weeks
// before it started; the published read is kept, never redone as more days arrive.
import { analyseInterventionResponse } from './interventionResponse.ts'
import type { InterventionResponseWindows } from './interventionResponse.ts'
import { analyseIts, ITS } from './its.ts'
import type { ItsResult, ItsState } from './its.ts'
import { DEFAULT_ZONE, METRIC_INFO } from './experiment.ts'
import type { MetricId } from './experiment.ts'
import type { Admin, LoadedFrame } from './runRelationships.ts'
import { loadConfirmedBreaks } from './runExpectedBands.ts'
import { shiftDay } from './randomized.ts'
import { fnv1a } from './hash.ts'
import { CORE_VERSION } from './version.ts'

interface InterventionRow {
  id: string
  lifecycle_state: string
  baseline_started_at: string | null
  started_at: string | null
  ended_at: string | null
  intended_outcomes: string[] | null
  /** Phase F: the date the interrupted-time-series read happens (absent before the migration). */
  evaluate_on?: string | null
}

// An intervention is analysable once it has actually started; 'proposed'/'baseline'/'stopped' are excluded ('baseline'
// is before the active window even begins, so there is no "since starting" comparison to make yet).
const ANALYSABLE_STATES = new Set(['active', 'evaluating', 'validated', 'maintained', 'modified'])
const KNOWN_METRICS = new Set<string>(Object.keys(METRIC_INFO))
const dayBefore = (date: string) => new Date(Date.parse(date + 'T00:00:00Z') - 86400000).toISOString().slice(0, 10)

export interface InterventionResponsesRun {
  userId: string
  error?: string
  results?: { id: string; metrics: number }[]
}

export async function runInterventionResponses(admin: Admin, loaded: LoadedFrame, now: Date = new Date()): Promise<InterventionResponsesRun> {
  const { userId, frame, today } = loaded
  const { data, error } = await admin
    .from('interventions')
    .select('id, lifecycle_state, baseline_started_at, started_at, ended_at, intended_outcomes')
    .eq('user_id', userId)
  if (error) return { userId, error: error.message }
  const rows = ((data ?? []) as InterventionRow[]).filter((r) => ANALYSABLE_STATES.has(r.lifecycle_state) && r.started_at)

  const results: NonNullable<InterventionResponsesRun['results']> = []
  for (const row of rows) {
    // Only intended outcomes that map onto a metric the shared daily frame actually carries are analysable today --
    // 08.5's notes: the frame's eight series don't line up 1:1 with the wider outcome_key vocabulary yet.
    const metrics = (row.intended_outcomes ?? []).filter((o): o is MetricId => KNOWN_METRICS.has(o))
    if (metrics.length === 0) continue

    const windows: InterventionResponseWindows = {
      baselineStart: row.baseline_started_at ?? frame.dates[0],
      baselineEnd: dayBefore(row.started_at!),
      activeStart: row.started_at!,
      activeEnd: row.ended_at,
    }
    const result = analyseInterventionResponse(row.id, metrics, windows, frame, today)

    const { error: upErr } = await admin.from('analysis_results').upsert(
      {
        user_id: userId,
        kind: 'intervention_response',
        key: row.id,
        as_of: today,
        engine_version: CORE_VERSION,
        inputs_hash: fnv1a(JSON.stringify([row.id, windows, today, metrics])),
        state: result.metrics[0]?.verdict ?? 'insufficient',
        confidence_have: result.metrics[0]?.n1 ?? 0,
        payload: result,
        calculated_at: now.toISOString(),
      },
      { onConflict: 'user_id,kind,key,as_of,engine_version' },
    )
    if (upErr) return { userId, error: upErr.message, results }
    await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', 'intervention_response').eq('key', row.id).eq('engine_version', CORE_VERSION).lt('as_of', today)
    results.push({ id: row.id, metrics: result.metrics.length })
  }
  return { userId, results }
}

export const ITS_KIND = 'intervention_its'
/** The fixed pre-period: the eight weeks before the start, a whole number of weeks so every weekday counts the same. */
export const ITS_PRE_DAYS = 56
/** Without its own evaluate_on date, an intervention is read four weeks after it started. */
export const ITS_ACTIVE_DAYS = 28

export interface InterventionItsPayload {
  interventionId: string
  preStart: string
  activeStart: string
  evaluateOn: string
  state: ItsState
  metrics: ItsResult[]
}

export interface InterventionItsRun {
  userId: string
  error?: string
  /** 'kept' when the read was already published; 'outside_window' when the eight weeks before the start fall outside the loaded year. */
  results?: { id: string; state: ItsState | 'kept' | 'outside_window' }[]
}

/** The read date: the intervention's own, or four weeks after the start -- and never after it stopped. */
export function itsEvaluateOn(row: { started_at: string; ended_at: string | null; evaluate_on?: string | null }): string {
  const planned = row.evaluate_on ?? shiftDay(row.started_at, ITS_ACTIVE_DAYS - 1)
  return row.ended_at && row.ended_at < planned ? row.ended_at : planned
}

export async function runInterventionIts(admin: Admin, loaded: LoadedFrame, now: Date = new Date()): Promise<InterventionItsRun> {
  const { userId, frame, today } = loaded
  // select('*') so the run keeps working before the migration adds evaluate_on.
  const { data, error } = await admin.from('interventions').select('*').eq('user_id', userId)
  if (error) return { userId, error: error.message }
  const rows = ((data ?? []) as InterventionRow[]).filter((r) => r.started_at && (ANALYSABLE_STATES.has(r.lifecycle_state) || r.lifecycle_state === 'stopped'))
  const results: NonNullable<InterventionItsRun['results']> = []
  if (rows.length === 0) return { userId, results }

  // Read once: a read already published (evaluated, or too thin to read) is kept as it is.
  const { data: existing, error: exErr } = await admin.from('analysis_results').select('key, state').eq('user_id', userId).eq('kind', ITS_KIND)
  if (exErr) return { userId, error: exErr.message }
  const done = new Set(((existing ?? []) as { key: string; state: string }[]).filter((r) => r.state !== 'collecting').map((r) => r.key))
  const breaks = await loadConfirmedBreaks(admin, userId)
  if ('error' in breaks) return { userId, error: breaks.error as string }

  for (const row of rows) {
    const metrics = (row.intended_outcomes ?? []).filter((o): o is MetricId => KNOWN_METRICS.has(o) && o !== 'fueling_carb_rate')
    if (metrics.length === 0) continue
    if (done.has(row.id)) {
      results.push({ id: row.id, state: 'kept' })
      continue
    }
    const activeStart = row.started_at!
    const preStart = shiftDay(activeStart, -ITS_PRE_DAYS)
    if (preStart < frame.dates[0]) {
      results.push({ id: row.id, state: 'outside_window' })
      continue
    }
    const evaluateOn = itsEvaluateOn({ started_at: activeStart, ended_at: row.ended_at, evaluate_on: row.evaluate_on })
    const reads = metrics.map((m) => {
      const info = METRIC_INFO[m]
      const z = DEFAULT_ZONE[m]
      return analyseIts({
        metric: m, label: info.label, dates: frame.dates, values: frame.series[info.series] ?? frame.dates.map(() => null), excluded: frame.excluded,
        ln: info.ln, unit: z.unit, scale: z.scale, zone: z.zone, preStart, activeStart, evaluateOn, today,
        breaks: info.breakKeys.flatMap((k) => breaks.get(k) ?? []),
      })
    })
    const state: ItsState = reads.some((x) => x.state === 'collecting') ? 'collecting' : reads.some((x) => x.state === 'evaluated') ? 'evaluated' : 'insufficient'
    const payload: InterventionItsPayload = { interventionId: row.id, preStart, activeStart, evaluateOn, state, metrics: reads }
    const { error: upErr } = await admin.from('analysis_results').upsert(
      {
        user_id: userId,
        kind: ITS_KIND,
        key: row.id,
        as_of: today,
        engine_version: CORE_VERSION,
        inputs_hash: fnv1a(JSON.stringify([row.id, preStart, activeStart, evaluateOn, metrics])),
        state,
        confidence_have: reads[0].activeDays,
        confidence_need: ITS.minActiveDays,
        payload,
        calculated_at: now.toISOString(),
      },
      { onConflict: 'user_id,kind,key,as_of,engine_version' },
    )
    if (upErr) return { userId, error: upErr.message, results }
    await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', ITS_KIND).eq('key', row.id).eq('engine_version', CORE_VERSION).lt('as_of', today)
    results.push({ id: row.id, state })
  }
  return { userId, results }
}
