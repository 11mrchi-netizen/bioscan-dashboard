// Test helpers for the health-concern engine (not a test file itself): an in-memory stand-in for the Supabase query
// builder, and the legacy-illness copy that private.backfill_health_concerns() makes, so tests can check that the copy
// leaves the analysis frame unchanged.
import { shiftDate } from './healthSeries.ts'
import type { ConcernRow, MarkRow, ObservationRow, SignalRow } from './healthSeries.ts'

type Row = Record<string, unknown>

export function fakeAdmin(tables: Record<string, Row[]>, failing: string[] = []) {
  const upserts: { table: string; row: Row }[] = []
  const inserts: { table: string; rows: Row[] }[] = []
  const deletes: { table: string; filters: [string, string, unknown][] }[] = []
  return {
    upserts,
    inserts,
    deletes,
    from(table: string) {
      const filters: [string, string, unknown][] = []
      let window: [number, number] | null = null
      let isDelete = false
      const q = {
        select() { return q },
        eq(c: string, v: unknown) { filters.push(['eq', c, v]); return q },
        gte(c: string, v: unknown) { filters.push(['gte', c, v]); return q },
        lt(c: string, v: unknown) { filters.push(['lt', c, v]); return q },
        in(c: string, v: unknown[]) { filters.push(['in', c, v]); return q },
        order() { return q },
        range(a: number, b: number) { window = [a, b]; return q },
        delete() { isDelete = true; return q },
        upsert(row: Row) { upserts.push({ table, row }); return Promise.resolve({ error: null }) },
        insert(rows: Row | Row[]) { inserts.push({ table, rows: Array.isArray(rows) ? rows : [rows] }); return Promise.resolve({ error: null }) },
        then(resolve: (v: { data: Row[] | null; error: { message: string } | null }) => void) {
          if (failing.includes(table)) return resolve({ data: null, error: { message: `relation "public.${table}" does not exist` } })
          let rows = (tables[table] ?? []).filter((r) => filters.every(([op, c, v]) =>
            op === 'eq' ? r[c] === v : op === 'gte' ? String(r[c]) >= String(v) : op === 'in' ? (v as unknown[]).includes(r[c]) : String(r[c]) < String(v)))
          if (isDelete) deletes.push({ table, filters })
          if (window) rows = rows.slice(window[0], window[1] + 1)
          resolve({ data: rows, error: null })
        },
      }
      return q
    },
  }
}

/** What the backfill makes of one legacy illness row. */
export function legacyIllnessCopy(ill: { id: number; start_date: string; end_date: string | null; status: string }): {
  concern: ConcernRow; observation: ObservationRow; signal: SignalRow; marks: MarkRow[]
} {
  const cid = `legacy-ill-${ill.id}`
  const oid = `legacy-ill-obs-${ill.id}`
  return {
    concern: {
      id: cid, title: `illness ${ill.id}`, kind: 'systemic', onset_type: 'unknown', regions: [], symptoms: ['sym.other'],
      excludes_from_baselines: true, resolution_days: null, archived_at: null, created_at: `${ill.start_date}T12:00:00Z`,
    },
    observation: {
      id: oid, observed_at: `${ill.start_date}T12:00:00Z`, local_date: ill.start_date, concern_id: cid, scope: 'concern',
      symptom_free: false, recall: 'today', recall_from: null, function_impact: null, change_vs_last: null, onset: true,
      session_id: null, timing: null, supersedes: null, superseded_at: null, retracted: false, source: 'legacy_backfill',
      created_at: `${ill.start_date}T12:00:00Z`,
    },
    signal: { observation_id: oid, concern_id: cid, symptom: 'sym.other', region: null, side: null, severity: null },
    marks: ill.status === 'resolved' && ill.end_date ? [{ concern_id: cid, kind: 'end', date: ill.end_date, retracted_at: null }] : [],
  }
}

// ---- builders for day-level tests ----

export const D0 = '2026-06-01'
const day = (n: number) => shiftDate(D0, n)

export function concern(id: string, over: Partial<ConcernRow> = {}): ConcernRow {
  return {
    id, title: id, kind: 'anatomical', onset_type: 'gradual', regions: [{ code: 'knee', side: 'right' }], symptoms: ['sym.pain'],
    excludes_from_baselines: false, resolution_days: null, archived_at: null, created_at: `${D0}T00:00:00Z`, ...over,
  }
}

let seq = 0
export function obs(dayN: number, over: Partial<ObservationRow> = {}): ObservationRow {
  seq++
  const date = day(dayN)
  return {
    id: `o${seq}`, observed_at: `${date}T20:00:${String(seq % 60).padStart(2, '0')}Z`, local_date: date, concern_id: 'k', scope: 'concern',
    symptom_free: false, recall: 'today', recall_from: null, function_impact: null, change_vs_last: null, onset: false,
    session_id: null, timing: null, supersedes: null, superseded_at: null, retracted: false, source: 'app', created_at: `${date}T20:00:00Z`,
    ...over,
  }
}

export function sig(o: ObservationRow, severity: number | null, over: Partial<SignalRow> = {}): SignalRow {
  return { observation_id: o.id, concern_id: o.concern_id, symptom: 'sym.pain', region: 'knee', side: 'right', severity, ...over }
}

