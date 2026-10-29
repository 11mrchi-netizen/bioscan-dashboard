// Health concerns (docs/health-concerns/01-plan.md §4): the publisher. Reads one person's concerns, check-ins and the
// context the app already keeps, runs the pure modules (healthSeries, healthEpisodes, healthExposures,
// healthAssociations, healthFindings, healthTrials) and writes:
//   analysis_results  health_overview (one row: every concern's state, safety prompts, signals no concern claims),
//                     health_concern_state (per concern: state, episodes, recurrence, data quality, the day series, the
//                     context lanes and what came before each episode), health_finding (per concern and exposure, plus
//                     the recurrence finding), health_trial_read (per trial; an evaluated read is kept, never redone)
//   evidence_ledger   every finding's raw verdict, every night (source 'health'; a re-run the same night keeps the first)
// Duck-typed client as the other run* modules; Node-tested; run unchanged in analysis-run (nightly, and on demand right
// after a check-in so the app shows the new state at once).
import vocabJson from '../health-vocab/health-vocab.json' with { type: 'json' }
import { bandModel } from './evidence.ts'
import { q80 } from './expectedBand.ts'
import { fnv1a, hashSeries } from './hash.ts'
import { evaluateExposures, sessionKindFor, sessionLevel } from './healthAssociations.ts'
import { summariseConcern } from './healthEpisodes.ts'
import { buildExposureSeries, CATALOGUE_VERSION_HEALTH, contextLanes, exposuresFor, whatCameBefore } from './healthExposures.ts'
import type { ExposureContext, LibraryRow, SessionRow } from './healthExposures.ts'
import { buildFindings } from './healthFindings.ts'
import type { Finding, LedgerEntry } from './healthFindings.ts'
import { healthAxisStart, loadHealthInputs } from './healthLoad.ts'
import { buildConcernSeries, HEALTH_RULES, shiftDate } from './healthSeries.ts'
import { readTrial } from './healthTrials.ts'
import type { TrialInput, TrialOutcome } from './healthTrials.ts'
import type { HealthVocab } from './healthVocab.ts'
import { fetchAll } from './paging.ts'
import { loadConfirmedBreaks } from './runExpectedBands.ts'
import { localClock } from './runRelationships.ts'
import type { Admin, LoadedFrame } from './runRelationships.ts'
import { weekdayOf } from './stats.ts'
import type { Num } from './stats.ts'
import { loadAnchorsByDate } from './trainingLoad.ts'
import { CORE_VERSION } from './version.ts'

export const HEALTH_KINDS = {
  overview: 'health_overview',
  state: 'health_concern_state',
  finding: 'health_finding',
  trial: 'health_trial_read',
} as const

export const vocab = vocabJson as unknown as HealthVocab

export interface HealthRun {
  userId: string
  error?: string
  skipped?: string
  published?: { kind: string; key: string; state: string }[]
  ledgerError?: string
}

const DAY_SERIES_DAYS = 365

interface PublishRow { kind: string; key: string; state: string; have: number | null; need: number | null; payload: unknown; hash: string; keep?: boolean }

/**
 * Optional timing hook: called with the name of the step that just finished, its wall time in ms and a few counts. Steps are
 * named by position (c0, c1, ...) never by concern, so nothing about a person's health reaches a log. Wall time includes
 * waits on the database, so read the compute steps (series, evaluate, findings) against the I/O ones (reads, publish).
 */
export type HealthTrace = (step: string, ms: number, info?: Record<string, number>) => void

export async function runHealth(admin: Admin, loaded: LoadedFrame, now: Date = new Date(), trace?: HealthTrace): Promise<HealthRun> {
  const { userId, frame, today } = loaded
  let last = performance.now()
  const mark = (step: string, info?: Record<string, number>) => {
    const t = performance.now()
    trace?.(step, t - last, info)
    last = performance.now()
  }
  const inputs = await loadHealthInputs(admin, userId)
  mark('inputs', { concerns: inputs.concerns?.length ?? 0 })
  if (inputs.error) return { userId, skipped: `health tables not readable: ${inputs.error}` }

  const since = frame.dates[0]
  const sinceStamp = `${shiftDate(since, -1)}T00:00:00Z`
  const page = (table: string, cols: string, order: string, from?: [string, string]) => (a: number, b: number) => {
    let q = admin.from(table).select(cols).eq('user_id', userId)
    if (from) q = q.gte(from[0], from[1])
    return q.order(order).range(a, b)
  }
  const [sessions, details, library, context, ledger, links, interventions, adherence, trialRows, breaks] = await Promise.all([
    fetchAll<SessionRow>(page('exercise_sessions', 'id, start_time, duration_min, distance_km, elevation_gain_m, type, avg_hr, max_hr, details', 'start_time', ['start_time', sinceStamp])),
    fetchAll<{ exercise_session_id: number | null; sport_type: string | null; hr_histogram: Record<string, number> | null }>(page('zepp_workout_detail', 'exercise_session_id, sport_type, hr_histogram', 'start_time', ['start_time', sinceStamp])),
    fetchAll<LibraryRow>((a: number, b: number) => admin.from('exercise_library').select('name, primary_muscles').order('name').range(a, b)),
    fetchAll<{ date: string; utc_offset_min: number; time_zone: string | null }>(page('daily_context', 'date, utc_offset_min, time_zone', 'date')),
    fetchAll<LedgerEntry>((a: number, b: number) => admin.from('evidence_ledger').select('question_id, as_of, verdict, n').eq('user_id', userId).eq('source', 'health').lt('as_of', today).order('as_of').range(a, b)),
    fetchAll<{ intervention_id: string; concern_id: string; hypothesis: string; outcome: TrialOutcome; exposure_unit: TrialInput['exposureUnit']; pre_days: number }>(page('intervention_concerns', 'intervention_id, concern_id, hypothesis, outcome, exposure_unit, pre_days', 'registered_at')),
    fetchAll<{ id: string; name: string; started_at: string | null; ended_at: string | null; evaluate_on: string | null; lifecycle_state: string }>(page('interventions', 'id, name, started_at, ended_at, evaluate_on, lifecycle_state', 'created_at')),
    fetchAll<{ intervention_id: string; date: string; adhered: 'yes' | 'partial' | 'no' }>(page('intervention_adherence', 'intervention_id, date, adhered', 'date')),
    fetchAll<{ key: string; payload: { state?: string } }>((a: number, b: number) => admin.from('analysis_results').select('key, payload').eq('user_id', userId).eq('kind', HEALTH_KINDS.trial).order('as_of').range(a, b)),
    loadConfirmedBreaks(admin, userId),
  ])
  mark('reads', { sessions: sessions.rows.length, details: details.rows.length })
  const failed = [sessions, details].find((r) => r.error)
  if (failed) return { userId, error: failed.error }

  // ---- the exposure context, on the frame's day axis ----
  const clock = localClock((context.error ? [] : context.rows).filter((r) => r.utc_offset_min !== null))
  const dates = frame.dates
  const dateSet = new Set(dates)
  const sess = sessions.rows.map((s) => ({ ...s, date: clock(s.start_time).date })).filter((s) => dateSet.has(s.date))
  const histograms = new Map<number, Record<string, number>>()
  const zeppSport = new Map<number, string>()
  for (const d of details.rows) {
    if (d.exercise_session_id === null) continue
    if (d.hr_histogram) histograms.set(d.exercise_session_id, d.hr_histogram)
    if (d.sport_type) zeppSport.set(d.exercise_session_id, String(d.sport_type))
  }
  const maxima: [string, number][] = sess.filter((s) => s.max_hr !== null).map((s) => [s.date, Number(s.max_hr)])
  const resting: [string, number][] = dates.flatMap((d, i) => (frame.series.rhr?.[i] != null ? [[d, frame.series.rhr[i] as number] as [string, number]] : []))
  const anchors = loadAnchorsByDate(maxima, resting)
  const hrvBelow: Num[] = dates.map(() => null)
  const hrvDeviation: Num[] = dates.map(() => null)
  const m = bandModel(frame, 'hrv', 'error' in breaks ? [] : breaks.get('hrv') ?? [])
  if (m) {
    for (let t = 0; t < dates.length; t++) {
      const z = m.z[t]
      if (z !== null) hrvBelow[t] = z < -q80(m.df[t]) ? 1 : 0
      const y = m.values[t]
      const st = m.states[t]
      if (y !== null && st && !m.excluded[t]) hrvDeviation[t] = Math.round((Math.exp(y - (st.level + st.season[weekdayOf(dates[t])])) - 1) * 1000) / 10
    }
  }
  const tzByDate = new Map((context.error ? [] : context.rows).map((r) => [r.date, r.time_zone ?? null]))
  const ctx: ExposureContext = {
    dates,
    sessions: sess,
    histograms,
    zeppSport,
    library: library.error ? [] : library.rows,
    robustMax: (d) => anchors(d).maxHr,
    sleepHours: frame.series.sleep_hours ?? dates.map(() => null),
    hrvBelow,
    hrvDeviation,
    load: frame.series.training_load ?? dates.map(() => null),
    alcohol: frame.series.alcohol_any ?? dates.map(() => null),
    stressHigh: frame.series.stress_high ?? dates.map(() => null),
    timeZone: dates.map((d) => tzByDate.get(d) ?? null),
    trace,
  }

  mark('context', { days: dates.length })

  // ---- concerns ----
  const axisStart = [healthAxisStart(inputs, today), since].sort()[0]
  const built = buildConcernSeries(inputs.concerns, inputs.observations, inputs.signals, axisStart, today)
  mark('series', { concerns: inputs.concerns.length })
  const rows: PublishRow[] = []
  const ledgerRows: Record<string, unknown>[] = []
  const overview: Record<string, unknown>[] = []
  const history = ledger.error ? [] : ledger.rows
  const winStart = built.series.get(inputs.concerns[0]?.id ?? '')?.dates.indexOf(since) ?? -1
  const versions = { core: CORE_VERSION, rules: HEALTH_RULES.version, catalogue: CATALOGUE_VERSION_HEALTH, vocab: vocab.vocabVersion }

  const concernData = new Map<string, { cells: ReturnType<typeof built.series.get> extends infer S ? S extends { cells: infer C } ? C : never : never; episodes: ReturnType<typeof summariseConcern>['episodes'] }>()
  let ci = -1
  for (const concern of inputs.concerns) {
    ci++
    const s = built.series.get(concern.id)
    if (!s) continue
    const marks = inputs.marks.filter((mk) => mk.concern_id === concern.id)
    const summary = summariseConcern(concern, s.cells, marks, today)
    const cellsWin = winStart >= 0 ? s.cells.slice(winStart) : s.cells.filter((c) => dateSet.has(c.date))
    concernData.set(concern.id, { cells: s.cells, episodes: summary.episodes })
    mark(`c${ci}.summary`, { cells: s.cells.length, episodes: summary.episodes.length })
    const exposureSeries = buildExposureSeries(ctx, concern, vocab.regions)
    mark(`c${ci}.exposureSeries`, { series: Object.keys(exposureSeries).length })
    const regionMuscles = new Set(concern.regions.flatMap((r) => vocab.regions.find((v) => v.code === r.code)?.muscles ?? []))
    const analysable = concern.onset_type !== 'acute' && !concern.archived_at
    const specs = analysable ? exposuresFor(concern, vocab.regions) : []
    const ev = evaluateExposures(ctx, cellsWin, summary.episodes, specs, exposureSeries, regionMuscles, summary.quality)
    mark(`c${ci}.evaluate`, { specs: specs.length })
    let ssMs = 0
    let ssCalls = 0
    const findings: Finding[] = buildFindings({
      concern, summary, evaluations: ev.evaluations, cases: ev.cases, referenceOk: ev.referenceOk, dates,
      series: exposureSeries, droppedCases: ev.dropped, history,
      sessionsSince: (spec, from) => {
        const t0 = performance.now()
        const kind = sessionKindFor(spec, ctx, regionMuscles)
        const out = kind ? sessionLevel(ctx, cellsWin, summary.episodes, kind, spec.window, from) : null
        ssMs += performance.now() - t0
        ssCalls++
        return out
      },
    })
    mark(`c${ci}.findings`, { findings: findings.length })
    // already inside the findings step above; reported separately so the two can be told apart
    if (ssCalls > 0) trace?.(`c${ci}.sessionsSince`, ssMs, { calls: ssCalls })
    const hash = fnv1a(JSON.stringify([
      versions,
      s.cells.map((c) => [c.observed ? (c.recalled ? 2 : 1) : 0, c.severity, c.impact, c.onset ? 1 : 0]),
      marks.map((mk) => [mk.kind, mk.date, mk.retracted_at ? 1 : 0]),
      Object.keys(exposureSeries).sort().map((k) => hashSeries(dates, exposureSeries[k])),
    ]))

    const dayCells = s.cells.slice(-DAY_SERIES_DAYS)
    const statePayload = {
      concernId: concern.id,
      title: concern.title,
      kind: concern.kind,
      onsetType: concern.onset_type,
      regions: concern.regions,
      archived: concern.archived_at !== null,
      asOf: today,
      ...summary,
      daily: {
        dates: dayCells.map((c) => c.date),
        severity: dayCells.map((c) => c.severity),
        // 0 unobserved, 1 checked in, 2 recalled ("nothing since")
        observed: dayCells.map((c) => (c.observed ? (c.recalled ? 2 : 1) : 0)),
        present: dayCells.map((c) => (c.present ? 1 : 0)),
        impact: dayCells.map((c) => c.impact),
        sessions: dayCells.map((c) => c.sessionIds),
      },
      lanes: contextLanes(ctx, shiftDate(today, -(DAY_SERIES_DAYS - 1))),
      before: summary.episodes.slice(-10).filter((e) => dateSet.has(e.start)).map((e) => whatCameBefore(ctx, e.start)),
      exposuresAsked: specs.map((x) => x.id),
      rules: HEALTH_RULES,
      versions,
    }
    rows.push({ kind: HEALTH_KINDS.state, key: concern.id, state: summary.state, have: Math.round(summary.quality.coverage90 * 100), need: Math.round(summary.quality.need * 100), payload: statePayload, hash })
    for (const f of findings) {
      const key = f.questionId.slice('health:'.length)
      rows.push({ kind: HEALTH_KINDS.finding, key, state: f.status, have: f.evidence.cases ?? null, need: null, payload: { ...f, versions }, hash })
      ledgerRows.push({
        user_id: userId, question_id: f.questionId, source: 'health', as_of: today, engine_version: CORE_VERSION,
        catalogue_version: CATALOGUE_VERSION_HEALTH, inputs_hash: hash,
        verdict: f.rawVerdict === 'observed' ? 'observed' : f.rawVerdict,
        estimate: f.evidence.or ?? null, lo: f.evidence.lo ?? null, hi: f.evidence.hi ?? null, unit: f.exposure ? 'odds_ratio' : null,
        no_effect: f.exposure ? 1 : null, n: f.primaryN ?? f.evidence.cases ?? summary.recurrence.episodes, n_on: f.evidence.exposedCases ?? null,
      })
    }
    overview.push({
      concernId: concern.id, title: concern.title, kind: concern.kind, onsetType: concern.onset_type, regions: concern.regions,
      archived: concern.archived_at !== null, state: summary.state, stateReason: summary.stateReason, lastObserved: summary.lastObserved,
      currentEpisode: summary.currentEpisode ? { start: summary.currentEpisode.start, peakSeverity: summary.currentEpisode.peakSeverity } : null,
      lastSeverity: [...s.cells].reverse().find((c) => c.observed && c.severity !== null)?.severity ?? null,
      episodes: summary.recurrence.episodes, safety: summary.safety, dataQuality: summary.quality.word,
      findings: findings.filter((f) => f.status === 'possible' || f.status === 'persistent').length,
      hints: findings.filter((f) => f.status === 'emerging').length,
    })
    mark(`c${ci}.rows`)
  }

  // ---- trials ----
  const kept = new Set((trialRows.error ? [] : trialRows.rows).filter((r) => r.payload?.state === 'evaluated').map((r) => r.key))
  for (const link of links.error ? [] : links.rows) {
    const iv = (interventions.error ? [] : interventions.rows).find((x) => x.id === link.intervention_id)
    const cd = concernData.get(link.concern_id)
    const key = `${link.intervention_id}:${link.concern_id}`
    if (!iv || !iv.started_at || !cd || kept.has(key)) continue
    const concurrent = (interventions.error ? [] : interventions.rows)
      .filter((x) => x.id !== iv.id && x.started_at && x.started_at <= today && (!x.ended_at || x.ended_at >= iv.started_at!) && ['active', 'evaluating', 'maintained', 'modified'].includes(x.lifecycle_state))
      .map((x) => x.name)
    const read = readTrial({
      interventionId: iv.id, name: iv.name, hypothesis: link.hypothesis, outcome: link.outcome, exposureUnit: link.exposure_unit,
      preDays: link.pre_days, startedAt: iv.started_at, endedAt: iv.ended_at, evaluateOn: iv.evaluate_on, today,
      adherence: (adherence.error ? [] : adherence.rows).filter((a) => a.intervention_id === iv.id), concurrent,
    }, cd.cells, cd.episodes, ctx)
    rows.push({ kind: HEALTH_KINDS.trial, key, state: read.state, have: null, need: null, payload: { ...read, concernId: link.concern_id, hypothesis: link.hypothesis, name: iv.name, versions }, hash: fnv1a(JSON.stringify([key, read])), keep: read.state === 'evaluated' })
  }

  mark('trials', { links: links.error ? 0 : links.rows.length })
  rows.push({
    kind: HEALTH_KINDS.overview, key: 'all', state: 'ready', have: inputs.concerns.length, need: null,
    payload: {
      asOf: today, concerns: overview,
      unassigned: built.unassigned.filter((u) => u.date > shiftDate(today, -30)),
      lastCheckin: inputs.observations.map((o) => o.local_date).sort().pop() ?? null,
      versions,
    },
    hash: fnv1a(JSON.stringify(overview)),
  })

  mark('overview')
  const published: NonNullable<HealthRun['published']> = []
  for (const row of rows) {
    const { error } = await admin.from('analysis_results').upsert(
      {
        user_id: userId, kind: row.kind, key: row.key, as_of: today, engine_version: CORE_VERSION, inputs_hash: row.hash,
        state: row.state, confidence_have: row.have, confidence_need: row.need, payload: row.payload, calculated_at: now.toISOString(),
      },
      { onConflict: 'user_id,kind,key,as_of,engine_version' },
    )
    if (error) return { userId, error: error.message, published }
    // Only the newest row per key is kept (the ledger keeps the history), except an evaluated trial read, which stays.
    await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', row.kind).eq('key', row.key).eq('engine_version', CORE_VERSION).lt('as_of', today)
    published.push({ kind: row.kind, key: row.key, state: row.state })
  }
  mark('publish', { rows: rows.length })
  const { error: ledgerError } = ledgerRows.length > 0
    ? await admin.from('evidence_ledger').upsert(ledgerRows, { onConflict: 'user_id,question_id,as_of,engine_version', ignoreDuplicates: true })
    : { error: null }
  mark('ledger', { rows: ledgerRows.length })
  return { userId, published, ...(ledgerError ? { ledgerError: ledgerError.message } : {}) }
}
