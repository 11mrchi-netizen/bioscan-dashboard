// I/O for the event evidence engine (eventEvidence.ts): reads the user's episodic interventions, their declared
// expectations, events, tracked outcomes and the stored series those expectations name, and publishes one
// analysis_results row per intervention (kind 'event_evidence', key = intervention id). Mirrors
// runInterventionResponse.ts's shape; duck-typed client, tested in Node, run unchanged in analysis-run.
//
// A stored daily series enters as one point at an anchor hour on its `date`: sleep_daily is keyed by the wake-up date
// (HealthConnectDailySyncRepository.syncSleep), so a night sits at 04:00 on the morning it ends, and the overnight
// wearable readings (hrv, rhr, spo2_avg) are placed with it; every other daily series sits at midday. A tracked outcome
// enters at the local minute it was logged.
import { analyseEventEvidence, dayNumber, EVENT_EVIDENCE } from './eventEvidence.ts'
import type { EventEvidenceResult, EventInput, ExpectationInput, SeriesPoint } from './eventEvidence.ts'
import { fetchAll } from './paging.ts'
import { combine } from './seriesContract.ts'
import type { SeriesFile } from './seriesContract.ts'
import type { Admin, LoadedFrame } from './runRelationships.ts'
import { fnv1a } from './hash.ts'
import { CORE_VERSION } from './version.ts'

export const EVENT_KIND = 'event_evidence'
/** Events and series older than this are not read. */
export const EVENT_LOOKBACK_DAYS = 180

const NIGHT_ANCHOR_MIN = 4 * 60
const DAY_ANCHOR_MIN = 12 * 60
const OVERNIGHT_COLUMNS = new Set(['hrv', 'rhr', 'spo2_avg', 'respiratory_rate'])

interface InterventionRow { id: string; name: string; version: number }
interface ExpectationRow {
  id: string; intervention_id: string; outcome_kind: 'series' | 'tracked'; outcome_key: string; role: 'primary' | 'secondary' | 'watch'
  direction: 'up' | 'down' | 'none'; window_start_h: number | string; window_end_h: number | string; aggregation: ExpectationInput['aggregation']; exploratory: boolean
}
interface EventRow {
  id: string; intervention_id: string; local_date: string; local_minute: number; adhered: 'yes' | 'partial' | 'no'; confounders: string[] | null
  feeling: number | null; assigned_by: 'self' | 'drawn'
}
interface TrackedRow { id: string; name: string; unit: string | null }
interface TrackedValueRow { outcome_id: string; local_date: string; local_minute: number; value: number }

export interface EventEvidenceRun {
  userId: string
  error?: string
  results?: { id: string; ladder: string; nUsed: number }[]
}

const shiftDay = (date: string, n: number) => new Date(Date.parse(date + 'T00:00:00Z') + n * 86400000).toISOString().slice(0, 10)

export async function runEventEvidence(admin: Admin, loaded: LoadedFrame, seriesFile: SeriesFile, now: Date = new Date()): Promise<EventEvidenceRun> {
  const { userId, today } = loaded
  const since = shiftDay(today, -EVENT_LOOKBACK_DAYS)
  const own = (table: string, cols: string, col?: string) => (a: number, b: number) => {
    const q = admin.from(table).select(cols).eq('user_id', userId)
    return (col ? q.gte(col, since) : q).range(a, b)
  }

  const interventions = await fetchAll<InterventionRow>((a, b) => admin.from('interventions').select('id, name, version').eq('user_id', userId).eq('mode', 'episodic').range(a, b))
  if (interventions.error) return { userId, error: interventions.error }
  if (interventions.rows.length === 0) return { userId, results: [] }

  const [exps, evs, outcomes, values, checkins] = await Promise.all([
    fetchAll<ExpectationRow>(own('intervention_expectations', 'id, intervention_id, outcome_kind, outcome_key, role, direction, window_start_h, window_end_h, aggregation, exploratory')),
    fetchAll<EventRow>(own('intervention_events', 'id, intervention_id, local_date, local_minute, adhered, confounders, feeling, assigned_by', 'local_date')),
    fetchAll<TrackedRow>(own('tracked_outcomes', 'id, name, unit')),
    fetchAll<TrackedValueRow>(own('tracked_outcome_values', 'outcome_id, local_date, local_minute, value', 'local_date')),
    fetchAll<{ date: string; drinks: number | null }>(own('evening_checkins', 'date, drinks', 'date')),
  ])
  for (const x of [exps, evs, outcomes, values]) if (x.error) return { userId, error: x.error }

  const seriesById = new Map(seriesFile.series.map((s) => [s.id, s]))
  const trackedById = new Map(outcomes.rows.map((o) => [o.id, o]))
  // alcohol on the day is a confounder of most things worth measuring; it comes from the evening check-in, not retyped
  const alcoholDays = new Set(checkins.rows.filter((c) => Number(c.drinks) > 0).map((c) => c.date))

  // points per series, loaded once for every series any expectation names
  const wanted = new Set(exps.rows.filter((e) => e.outcome_kind === 'series').map((e) => e.outcome_key))
  const pointsByKey: Record<string, SeriesPoint[]> = {}
  const notes: Record<string, string> = {}
  for (const key of wanted) {
    const contract = seriesById.get(key)
    if (!contract || contract.time.kind !== 'local_date') { notes[key] = 'unsupported series'; continue }
    const page = fetchAll<Record<string, unknown>>((a, b) => {
      let q = admin.from(contract.table).select(`${contract.time.column}, ${contract.column}`).eq('user_id', userId).gte(contract.time.column, since)
      for (const [col, allowed] of Object.entries(contract.filters ?? {})) q = q.in(col, allowed)
      return q.order(contract.time.column).range(a, b)
    })
    const res = await page
    if (res.error) { notes[key] = res.error; continue }
    const byDay = new Map<string, number[]>()
    for (const row of res.rows) {
      const v = Number(row[contract.column])
      if (row[contract.column] === null || row[contract.column] === undefined || !Number.isFinite(v)) continue
      if (contract.plausible && (v < contract.plausible[0] || v > contract.plausible[1])) continue
      const day = String(row[contract.time.column]).slice(0, 10)
      byDay.set(day, [...(byDay.get(day) ?? []), v])
    }
    const anchor = contract.table === 'sleep_daily' || OVERNIGHT_COLUMNS.has(contract.column) ? NIGHT_ANCHOR_MIN : DAY_ANCHOR_MIN
    pointsByKey[key] = [...byDay.entries()].flatMap(([day, vs]) => {
      const c = combine(vs, contract.day)
      return c.value === null ? [] : [{ wallMin: dayNumber(day) * 1440 + anchor, value: c.value }]
    })
  }
  for (const v of values.rows) {
    ;(pointsByKey[v.outcome_id] ??= []).push({ wallMin: dayNumber(v.local_date) * 1440 + v.local_minute, value: Number(v.value) })
  }

  const eventMin = (e: EventRow) => dayNumber(e.local_date) * 1440 + e.local_minute
  const results: NonNullable<EventEvidenceRun['results']> = []
  for (const iv of interventions.rows) {
    const mine = evs.rows.filter((e) => e.intervention_id === iv.id)
    if (mine.length === 0) continue
    const expectations: ExpectationInput[] = exps.rows.filter((e) => e.intervention_id === iv.id).map((e) => {
      const contract = e.outcome_kind === 'series' ? seriesById.get(e.outcome_key) : undefined
      const tracked = e.outcome_kind === 'tracked' ? trackedById.get(e.outcome_key) : undefined
      return {
        id: e.id, outcomeKey: e.outcome_key, label: contract?.label ?? tracked?.name ?? e.outcome_key, unit: contract?.unit ?? tracked?.unit ?? null,
        role: e.role, direction: e.direction, windowStartH: Number(e.window_start_h), windowEndH: Number(e.window_end_h), aggregation: e.aggregation, exploratory: e.exploratory,
      }
    })
    if (expectations.length === 0) continue
    const events: EventInput[] = mine.map((e) => ({
      id: e.id, wallMin: eventMin(e), localDate: e.local_date, adhered: e.adhered,
      confounders: [...(e.confounders ?? []), ...(alcoholDays.has(e.local_date) ? ['alcohol'] : [])],
      feeling: e.feeling, assignedBy: e.assigned_by,
    }))
    const result: EventEvidenceResult = analyseEventEvidence({
      interventionId: iv.id, asOf: today, events, expectations, points: pointsByKey,
      otherEventMins: evs.rows.filter((e) => e.intervention_id !== iv.id).map(eventMin),
      confoundedDays: [...alcoholDays],
    })
    const missing = expectations.filter((e) => notes[e.outcomeKey]).map((e) => `${e.label}: ${notes[e.outcomeKey]}`)
    const primary = result.expectations.find((e) => e.role === 'primary' && !e.exploratory) ?? result.expectations[0]
    const { error: upErr } = await admin.from('analysis_results').upsert(
      {
        user_id: userId,
        kind: EVENT_KIND,
        key: iv.id,
        as_of: today,
        engine_version: CORE_VERSION,
        inputs_hash: fnv1a(JSON.stringify([iv.id, iv.version, today, events.map((e) => [e.id, e.adhered, e.confounders]), expectations.map((e) => e.id)])),
        state: result.ladder,
        confidence_have: primary?.nWithData ?? 0,
        confidence_need: EVENT_EVIDENCE.ladder.ready,
        payload: { ...result, name: iv.name, version: iv.version, loaderNotes: missing },
        calculated_at: now.toISOString(),
      },
      { onConflict: 'user_id,kind,key,as_of,engine_version' },
    )
    if (upErr) return { userId, error: upErr.message, results }
    await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', EVENT_KIND).eq('key', iv.id).eq('engine_version', CORE_VERSION).lt('as_of', today)
    results.push({ id: iv.id, ladder: result.ladder, nUsed: result.nUsed })
  }
  return { userId, results }
}
