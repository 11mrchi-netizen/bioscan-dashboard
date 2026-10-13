// I/O for the N-of-1 experiment engine: reads the user's registered experiments and their supplement log, analyses each with
// the shared daily frame, advances the experiment's status and publishes one analysis_results row per experiment (kind
// 'experiment', key = experiment id). Also builds the pre-registration preview (what could this design detect on your own
// data) that the app shows before a person starts. Duck-typed client, tested in Node, run unchanged in analysis-run.
import { analyseExperiment, METRIC_INFO, MIN_PHASE_DAYS, previewMde } from './experiment.ts'
import type { ExperimentResult, ExperimentSpec, MdePreview, MetricId, Protocol } from './experiment.ts'
import type { Admin, LoadedFrame } from './runRelationships.ts'
import { fnv1a } from './hash.ts'
import { CORE_VERSION } from './version.ts'

interface ExperimentRow {
  id: string
  supplement_id: number | null
  name: string
  primary_metric: MetricId
  secondary_metrics: MetricId[] | null
  protocol: Protocol
  status: 'planned' | 'running' | 'completed' | 'abandoned'
  preregistered_at: string
  mde_preview: unknown
}

export interface ExperimentsRun {
  userId: string
  error?: string
  results?: { id: string; stage: string; verdict: string }[]
}

export async function runExperiments(admin: Admin, loaded: LoadedFrame, now: Date = new Date()): Promise<ExperimentsRun> {
  const { userId, frame, today } = loaded
  const { data, error } = await admin
    .from('experiments')
    .select('id, supplement_id, name, primary_metric, secondary_metrics, protocol, status, preregistered_at, mde_preview')
    .eq('user_id', userId)
    .order('preregistered_at')
  if (error) return { userId, error: error.message }
  const rows = ((data ?? []) as ExperimentRow[]).filter((r) => r.status !== 'abandoned')

  const results: NonNullable<ExperimentsRun['results']> = []
  for (const row of rows) {
    const first = row.protocol?.phases?.[0]?.start
    if (!first) continue
    let taken = new Set<string>()
    if (row.supplement_id !== null) {
      const log = await admin.from('supplement_log').select('taken_at').eq('user_id', userId).eq('supplement_id', row.supplement_id).gte('taken_at', `${first}T00:00:00Z`)
      if (log.error) return { userId, error: log.error.message, results }
      taken = new Set(((log.data ?? []) as { taken_at: string }[]).map((l) => String(l.taken_at).slice(0, 10)))
    }
    const spec: ExperimentSpec = { id: row.id, name: row.name, primary: row.primary_metric, secondary: row.secondary_metrics ?? [], protocol: row.protocol }
    const result: ExperimentResult = analyseExperiment(spec, frame, taken, today)

    const nextStatus = result.stage === 'complete' ? 'completed' : result.stage === 'running' ? 'running' : 'planned'
    if (nextStatus !== row.status) {
      const up = await admin.from('experiments').update({ status: nextStatus }).eq('id', row.id)
      if (up.error) return { userId, error: up.error.message, results }
    }

    const primary = result.metrics[0]
    const state = result.stage === 'complete' ? primary.verdict : result.stage
    const { error: upErr } = await admin.from('analysis_results').upsert(
      {
        user_id: userId,
        kind: 'experiment',
        key: row.id,
        as_of: today,
        engine_version: CORE_VERSION,
        inputs_hash: fnv1a(JSON.stringify([row.id, row.protocol, today, [...taken].sort(), primary.n0, primary.n1])),
        state,
        confidence_have: primary.n1,
        confidence_need: MIN_PHASE_DAYS,
        payload: { ...result, status: nextStatus, supplementId: row.supplement_id, protocol: row.protocol, preregisteredAt: row.preregistered_at, mdePreview: row.mde_preview },
        calculated_at: now.toISOString(),
      },
      { onConflict: 'user_id,kind,key,as_of,engine_version' },
    )
    if (upErr) return { userId, error: upErr.message, results }
    await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', 'experiment').eq('key', row.id).eq('engine_version', CORE_VERSION).lt('as_of', today)
    results.push({ id: row.id, stage: result.stage, verdict: state })
  }
  return { userId, results }
}

export const ALL_METRICS = Object.keys(METRIC_INFO) as MetricId[]
const RECENT_BREAK_DAYS = 120

/** Pre-registration preview: the smallest difference a design with these phase lengths could detect, per outcome. */
export async function buildPreview(
  admin: Admin,
  loaded: LoadedFrame,
  plannedA: number,
  plannedB: number,
  metrics: MetricId[] = ALL_METRICS,
): Promise<{ error?: string; previews?: MdePreview[]; plannedA: number; plannedB: number }> {
  const { userId, frame } = loaded
  const { data, error } = await admin.from('analysis_results').select('key, payload').eq('user_id', userId).eq('kind', 'series_break')
  if (error) return { error: error.message, plannedA, plannedB }
  const since = frame.dates[Math.max(0, frame.dates.length - RECENT_BREAK_DAYS)]
  const unsettled = ((data ?? []) as { key: string; payload: { breaks?: { date: string }[] } }[])
    .filter((r) => (r.payload?.breaks ?? []).some((b) => b.date >= since))
    .map((r) => r.key)
  return { previews: previewMde(frame, metrics, plannedA, plannedB, unsettled), plannedA, plannedB }
}
