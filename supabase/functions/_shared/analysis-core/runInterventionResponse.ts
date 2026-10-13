// I/O for the observational intervention-response engine (08.6, interventionResponse.ts): reads the user's
// active/evaluating/maintained interventions and publishes one analysis_results row per intervention (kind
// 'intervention_response', key = intervention id). Mirrors runExperiments.ts's shape; duck-typed client, tested in
// Node, meant to run unchanged in analysis-run -- not yet wired into analysis-run/index.ts or deployed (see
// docs/intervention-protocol-intelligence/README.md's "Still open").
import { analyseInterventionResponse } from './interventionResponse.ts'
import type { InterventionResponseWindows } from './interventionResponse.ts'
import { METRIC_INFO } from './experiment.ts'
import type { MetricId } from './experiment.ts'
import type { Admin, LoadedFrame } from './runRelationships.ts'
import { fnv1a } from './hash.ts'
import { CORE_VERSION } from './version.ts'

interface InterventionRow {
  id: string
  lifecycle_state: string
  baseline_started_at: string | null
  started_at: string | null
  ended_at: string | null
  intended_outcomes: string[] | null
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
