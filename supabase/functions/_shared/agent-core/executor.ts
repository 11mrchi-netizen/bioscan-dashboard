// Personal health agent, phase 1: the read tools over the person's own data (docs/personal-health-agent).
//
// Every tool reads through a `Db` the caller builds from the person's own JWT, so row-level security applies, and every
// row passes scope.ts before it reaches the model: unshared tables are refused, free text and location columns dropped,
// routine-derived results withheld. Tools serve published results and records; the only arithmetic here is the series
// contract's own day and period aggregation (analysis-core/seriesContract.ts), the same the console uses, plus display
// forms ("7 h 02 min") so the model can quote values without converting them. Payloads are trimmed to stay small.

import { evidenceItems, latestDecisions, place, type DecisionRecord, type ResultRow } from '../analysis-core/evidenceItems.ts'
import { aggregateDays, aggregatePeriods, addDays, daySpan, localDateOf, type Grain, type Observation, type SeriesContract, type SeriesFile } from '../analysis-core/seriesContract.ts'
import registryFile from '../metric-registry/metric-registry.json' with { type: 'json' }
import seriesFile from '../metric-registry/metric-series.json' with { type: 'json' }
import { resultAllowed, serveRows, tableAccess, type ScopeSettings } from './scope.ts'
import type { ExecuteTool } from './turn.ts'

export type Row = Record<string, unknown>

/** One read. Ranges compare as strings (ISO dates and timestamps); the adapter pages past the API's row cap. */
export interface Query {
  table: string
  columns: string
  eq?: Record<string, string | number | boolean>
  in?: Record<string, (string | number)[]>
  gte?: Record<string, string>
  lte?: Record<string, string>
  order?: { column: string; ascending: boolean }
  limit?: number
}
export type Db = (q: Query) => Promise<Row[]>

const SERIES = (seriesFile as unknown as SeriesFile).series as SeriesContract[]
const DEFAULT_OFFSET = (seriesFile as unknown as SeriesFile).defaultUtcOffsetMin
const REGISTRY = (registryFile as { metrics: { key: string; docName?: string; methodology?: string; limitations?: string[] }[] }).metrics

/** Tools phase 1 serves (EXPLAIN); the others answer that they are not available yet. */
export const PHASE1_TOOLS = ['get_results', 'get_evidence_items', 'get_decisions', 'get_integrity', 'get_series', 'get_metric_info', 'get_context_events']

const MAX_SPAN_DAYS = 400
const EVIDENCE_KINDS = ['relationship', 'registered_question', 'experiment', 'intervention_its']

// ---- trimming and display ---------------------------------------------------------------------------------------------

/** A payload small enough for a prompt: long arrays keep their last items, long strings are cut, deep nesting stops. */
export function compact(v: unknown, depth = 0, arrayKeep = 8): unknown {
  if (typeof v === 'string') return v.length > 400 ? v.slice(0, 400) + '…' : v
  if (typeof v === 'number') return Number.isInteger(v) ? v : Math.round(v * 1000) / 1000
  if (v === null || typeof v !== 'object') return v
  if (depth >= 5) return Array.isArray(v) ? `[${v.length} items]` : '{…}'
  if (Array.isArray(v)) {
    const kept = v.length > arrayKeep ? v.slice(-arrayKeep) : v
    const out = kept.map((x) => compact(x, depth + 1, arrayKeep))
    return v.length > arrayKeep ? [{ omitted_earlier: v.length - arrayKeep }, ...out] : out
  }
  return Object.fromEntries(Object.entries(v as Row).map(([k, x]) => [k, compact(x, depth + 1, arrayKeep)]))
}

/** compact() tightened until the JSON fits `maxChars`. */
export function fit(v: unknown, maxChars = 6000): unknown {
  for (const keep of [8, 4, 2, 1]) {
    const c = compact(v, 0, keep)
    if (JSON.stringify(c).length <= maxChars) return c
  }
  return { note: 'too large to show; ask for one key or a shorter span' }
}

const round = (x: number) => (Math.abs(x) >= 100 ? Math.round(x) : Math.round(x * 10) / 10)

/** The value as the person reads it: hours as "7 h 02 min", everything else with its unit. */
export function display(value: number | null, unit: string): string | null {
  if (value === null || !Number.isFinite(value)) return null
  if (unit === 'h') {
    const total = Math.round(value * 60)
    return `${Math.floor(total / 60)} h ${String(total % 60).padStart(2, '0')} min`
  }
  if (unit.startsWith('/')) return `${round(value)}${unit}`
  return `${round(value)} ${unit}`
}

const minutes = (m: unknown) => (typeof m === 'number' && Number.isFinite(m) ? `${Math.round(m)} min` : null)
const clock = (iso: unknown) => (typeof iso === 'string' && /T\d{2}:\d{2}/.test(iso) ? iso.slice(11, 16) : null)

// ---- the tools ---------------------------------------------------------------------------------------------------------

function seriesSpec(id: unknown): SeriesContract | null {
  return SERIES.find((s) => s.id === id) ?? null
}

function span(from: unknown, to: unknown, today: string): { from: string; to: string } | { error: string } {
  const f = typeof from === 'string' ? from : addDays(today, -27)
  const t = typeof to === 'string' ? to : today
  if (f > t) return { error: 'from is after to' }
  if (addDays(f, MAX_SPAN_DAYS) < t) return { error: `ask for at most ${MAX_SPAN_DAYS} days at a time` }
  return { from: f, to: t }
}

function resultRow(r: Row) {
  return {
    ref: `ar:${r.kind}:${r.key}:${r.as_of}`,
    kind: r.kind,
    key: r.key,
    as_of: r.as_of,
    state: r.state,
    have: r.confidence_have,
    need: r.confidence_need,
    payload: r.payload,
  }
}

export function makeExecutor(db: Db, settings: ScopeSettings, today: string): ExecuteTool {
  const tools: Record<string, (a: Row) => Promise<unknown>> = {
    async get_results(a) {
      const kind = String(a.kind)
      const q: Query = {
        table: 'analysis_results',
        columns: 'kind,key,as_of,state,confidence_have,confidence_need,payload,calculated_at',
        eq: { kind, ...(typeof a.key === 'string' ? { key: a.key } : {}) },
        order: { column: 'as_of', ascending: false },
        limit: typeof a.key === 'string' ? 3 : 200,
      }
      if (typeof a.from === 'string') q.gte = { as_of: a.from }
      if (typeof a.to === 'string') q.lte = { as_of: a.to }
      const rows = (await db(q)).filter((r) => resultAllowed(r as { kind: string; key: string; payload?: unknown }, settings))
      // Rows come newest first; without a key, keep the newest row of each key.
      const seen = new Set<string>()
      const newest = typeof a.key === 'string' ? rows : rows.filter((r) => !seen.has(String(r.key)) && (seen.add(String(r.key)), true))
      if (!newest.length) return { results: [], note: 'nothing published for this request' }
      return { results: fit(newest.slice(0, 10).map(resultRow)) }
    },

    async get_evidence_items(a) {
      const rows = (await db({
        table: 'analysis_results',
        columns: 'kind,key,as_of,engine_version,inputs_hash,state,payload,calculated_at',
        in: { kind: EVIDENCE_KINDS },
        order: { column: 'as_of', ascending: false },
        limit: 600,
      })).filter((r) => resultAllowed(r as { kind: string; key: string; payload?: unknown }, settings)) as unknown as ResultRow[]
      const decisions = await db({ table: 'decisions', columns: 'id,item_id,title,decision,rationale,threshold,review_condition,review_on,evidence_class,evidence,supersedes,decided_at' })
      const latest = latestDecisions(decisions as unknown as DecisionRecord[])
      const items = evidenceItems(rows)
        .filter((i) => typeof a.outcome !== 'string' || i.outcome === a.outcome)
        .map((i) => {
          const p = place(i, latest.get(i.id), today)
          return {
            id: i.id, evidenceClass: i.evidenceClass, title: i.title, outcome: i.outcome, verdict: i.verdict, word: i.verdict.replace('_', ' ').toUpperCase(),
            engineState: i.engineState, effect: i.effect, noEffect: i.noEffect, n: i.n, readiness: i.readiness, running: i.running,
            controllable: i.controllable, summary: i.summary, asOf: i.asOf, queue: p.state, queueReason: p.reason,
          }
        })
      return items.length ? { items: fit(items.slice(0, 15)) } : { items: [], note: 'no evidence items for this request' }
    },

    async get_decisions(a) {
      const q: Query = {
        table: 'decisions',
        columns: 'id,item_id,title,decision,rationale,threshold,review_condition,review_on,evidence_class,evidence,decided_at',
        order: { column: 'decided_at', ascending: false },
        limit: 20,
      }
      if (typeof a.item_id === 'string') q.eq = { item_id: a.item_id }
      const served = serveRows('decisions', await db(q), settings)
      if (!served.ok) return { error: served.reason }
      return served.rows.length ? { decisions: fit(served.rows) } : { decisions: [], note: 'no decisions recorded for this request' }
    },

    async get_integrity(a) {
      const rows = await db({ table: 'analysis_results', columns: 'as_of,payload', eq: { kind: 'integrity' }, order: { column: 'as_of', ascending: false }, limit: 1 })
      const report = rows[0]?.payload as { today?: string; streams?: Row[]; issues?: Row[]; totals?: Row } | undefined
      if (!report) return { results: [], note: 'no data-quality report published yet' }
      const known = new Set(SERIES.map((s) => s.id))
      const streams = (report.streams ?? []).filter((s) => known.has(String(s.seriesId)))
      const issues = (report.issues ?? []).filter((i) => known.has(String(i.seriesId)))
      const view = (s: Row) => ({
        series: s.seriesId, label: s.label, freshness: s.freshness, lastSeen: s.lastSeen, lagDays: s.lagDays,
        coverage: typeof s.daysDue === 'number' && s.daysDue > 0 ? `${s.daysObserved} of ${s.daysDue} days` : 'not judged', sources: s.sources, issues: s.issues,
      })
      const issueView = (i: Row) => ({ kind: i.kind, severity: i.severity, title: i.title, detail: i.detail, firstDate: i.firstDate, lastDate: i.lastDate, days: i.days })
      if (typeof a.series === 'string') {
        const s = streams.find((x) => x.seriesId === a.series)
        if (!s) return { as_of: rows[0].as_of, note: 'this series is not in the data-quality report' }
        return fit({ as_of: rows[0].as_of, stream: view(s), issues: issues.filter((i) => i.seriesId === a.series).map(issueView) })
      }
      return fit({ as_of: rows[0].as_of, totals: report.totals, streams: streams.map(view), issues: issues.slice(0, 10).map(issueView) })
    },

    async get_series(a) {
      const spec = seriesSpec(a.series)
      if (!spec) return { error: 'unknown series' }
      if (!tableAccess(spec.table, settings).ok) return { error: 'not shared' }
      const s = span(a.from, a.to, today)
      if ('error' in s) return s
      const timeCol = spec.time.column
      const local = spec.time.kind === 'local_date'
      const cols = [timeCol, spec.column, ...(spec.hasSource ? ['source'] : []), ...Object.keys(spec.filters ?? {})]
      const rows = await db({
        table: spec.table,
        columns: [...new Set(cols)].join(','),
        gte: { [timeCol]: local ? s.from : addDays(s.from, -1) },
        lte: { [timeCol]: local ? s.to : addDays(s.to, 1) + 'T23:59:59' },
        ...(spec.filters ? { in: spec.filters } : {}),
        order: { column: timeCol, ascending: true },
      })
      const obs = rows
        .map((r) => {
          const d = localDateOf(r[timeCol] as string, spec.time, null, DEFAULT_OFFSET)
          const v = r[spec.column]
          const obs: Observation | null = d ? { date: d.date, value: typeof v === 'number' ? v : v === null || v === undefined ? null : Number(v), source: typeof r.source === 'string' ? r.source : null } : null
          return obs
        })
        .filter((o): o is Observation => o !== null && o.date >= s.from && o.date <= s.to)
      const days = aggregateDays(obs, spec.day)
      const grain = (['day', 'week', 'month'].includes(String(a.grain)) ? a.grain : 'day') as Grain
      const base = { series: spec.id, label: spec.label, unit: spec.unit, from: s.from, to: s.to, grain }
      if (!days.length) return { ...base, points: [], note: 'nothing recorded in this span' }
      if (grain === 'day') {
        return fit({ ...base, points: days.map((d) => ({ date: d.date, value: d.value === null ? null : round(d.value), display: display(d.value, spec.unit), n: d.n })) }, 9000)
      }
      const periods = aggregatePeriods(days, s.from, s.to, grain, spec.period)
      return fit({
        ...base,
        points: periods.map((p) => ({
          start: p.start, end: p.end, value: p.value === null ? null : round(p.value), display: display(p.value, spec.unit),
          daysObserved: p.daysObserved, coverage: `${p.daysObserved} of ${daySpan(p.start < s.from ? s.from : p.start, p.end > s.to ? s.to : p.end)} days`,
        })),
      }, 9000)
    },

    async get_metric_info(a) {
      const spec = seriesSpec(a.series)
      if (!spec) return { error: 'unknown series' }
      const entry = REGISTRY.find((m) => m.key === spec.metric)
      return fit({
        series: spec.id, label: spec.label, unit: spec.unit, plausible: spec.plausible,
        methodology: entry?.methodology ?? null, limitations: (entry?.limitations ?? []).slice(0, 4),
      })
    },

    async get_context_events(a) {
      const s = span(a.from, a.to, today)
      if ('error' in s) return s
      const out: Row = { from: s.from, to: s.to }
      const take = async (table: string, q: Omit<Query, 'table'>, keep: (r: Row) => boolean, view: (r: Row) => Row) => {
        if (!tableAccess(table, settings).ok) return 'not shared'
        const served = serveRows(table, (await db({ table, ...q })).filter(keep), settings)
        return served.ok ? served.rows.map(view) : served.reason
      }
      const overlaps = (r: Row) => String(r.start_date ?? '') <= s.to && (r.end_date === null || r.end_date === undefined || String(r.end_date) >= s.from)
      out.illnesses = await take('illnesses', { columns: 'name,status,severity,start_date,end_date,doctor_seen,notes,symptoms', lte: { start_date: s.to } }, overlaps, (r) => r)
      out.injuries = await take('injuries', { columns: 'part,type,severity,status,start_date,end_date,notes', lte: { start_date: s.to } }, overlaps, (r) => r)
      out.rest_days = await take('rest_days', { columns: 'date', gte: { date: s.from }, lte: { date: s.to } }, () => true, (r) => ({ date: r.date }))
      out.sessions = await take(
        'exercise_sessions',
        { columns: 'type,start_time,end_time,duration_min,distance_km,avg_hr,rpe', gte: { start_time: s.from }, lte: { start_time: s.to + 'T23:59:59' }, order: { column: 'start_time', ascending: true } },
        () => true,
        (r) => ({ date: String(r.start_time).slice(0, 10), type: r.type, start: clock(r.start_time), duration: minutes(r.duration_min), distance_km: r.distance_km, avg_hr: r.avg_hr, rpe: r.rpe }),
      )
      const doses = await take('supplement_log', { columns: 'supplement_name,taken_at', gte: { taken_at: s.from }, lte: { taken_at: s.to + 'T23:59:59' } }, () => true, (r) => r)
      if (Array.isArray(doses)) {
        const counts = new Map<string, number>()
        for (const d of doses) counts.set(String(d.supplement_name), (counts.get(String(d.supplement_name)) ?? 0) + 1)
        out.supplements = [...counts].map(([name, times]) => ({ name, times }))
      } else out.supplements = doses
      return fit(out, 9000)
    },
  }

  return async (name, args) => {
    const tool = tools[name]
    if (!tool) return { error: 'not available yet in this version of the assistant' }
    try {
      return await tool(args)
    } catch (e) {
      return { error: `could not read this right now (${String(e).slice(0, 120)})` }
    }
  }
}
