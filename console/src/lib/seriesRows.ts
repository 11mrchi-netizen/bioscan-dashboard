// Analysis hub phase B: the pure half of the series query (no Supabase client, so node --test can load it). What to
// select for a series, which time bounds to ask the database for, and how raw rows become observations on local days
// under the series' time contract (analysis-core/seriesContract.ts).
import {
  addDays,
  localDateOf,
  timeKindFor,
  type Observation,
  type SeriesContract,
} from '../../../supabase/functions/_shared/analysis-core/seriesContract.ts'
import type { DateRange } from '../types/analysis'

export type Row = Record<string, unknown>

/** Columns a series needs: id (for keyset paging and record links), time, value, offset and source where present. */
export function seriesColumns(s: SeriesContract): string[] {
  return [...new Set(['id', s.time.column, s.column, ...(s.time.offsetColumn ? [s.time.offsetColumn] : []), ...(s.hasSource ? ['source'] : [])])]
}

/** True when every row's local day is its time column's own date (no conversion, so the database bounds are exact). */
export function exactDateBounds(s: SeriesContract): boolean {
  return s.time.kind === 'local_date' && !(s.time.exceptions ?? []).length
}

/**
 * Database bounds for a range of local days. Calendar dates are exact. Timestamps are widened by a day on each side:
 * a UTC instant's local day can differ from its stored date, and the rows outside the range are dropped after their
 * local day is known (toObservations).
 */
export function queryBounds(s: SeriesContract, range: DateRange): { from: string; to: string } {
  if (exactDateBounds(s)) return { from: range.start, to: range.end }
  return { from: `${addDays(range.start, -1)}T00:00:00`, to: `${addDays(range.end, 1)}T23:59:59.999` }
}

export interface RowObservation extends Observation {
  /** The row's id, for drill-down to the record. */
  id: string | number
  raw: string
  /** Local clock time HH:MM for timestamp series, null for calendar dates. */
  localTime: string | null
  assumedOffset: boolean
}

function localTimeOf(raw: string, s: SeriesContract, offsetMin: number | null, defaultOffsetMin: number): string | null {
  const kind = timeKindFor(raw, s.time)
  if (kind === 'local_date') return null
  if (kind === 'local_wall') return raw.length >= 16 ? raw.slice(11, 16) : null
  const ms = Date.parse(raw)
  return Number.isNaN(ms) ? null : new Date(ms + (offsetMin ?? defaultOffsetMin) * 60_000).toISOString().slice(11, 16)
}

/** Rows (in time order) -> observations on local days inside the range. */
export function toObservations(rows: Row[], s: SeriesContract, range: DateRange, defaultOffsetMin: number): RowObservation[] {
  const out: RowObservation[] = []
  for (const r of rows) {
    const raw = r[s.time.column] as string | null
    const offset = s.time.offsetColumn ? ((r[s.time.offsetColumn] as number | null) ?? null) : null
    const local = localDateOf(raw, s.time, offset, defaultOffsetMin)
    if (!local || local.date < range.start || local.date > range.end) continue
    const v = r[s.column]
    // numeric columns come back as numbers or, for numeric/decimal types, strings
    const value = v === null || v === undefined || v === '' ? null : Number(v)
    out.push({
      date: local.date,
      value: value !== null && Number.isFinite(value) ? value : null,
      source: s.hasSource ? ((r.source as string | null) ?? null) : null,
      id: r.id as string | number,
      raw: raw as string,
      localTime: localTimeOf(raw as string, s, offset, defaultOffsetMin),
      assumedOffset: local.assumedOffset,
    })
  }
  return out
}
