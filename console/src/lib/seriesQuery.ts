// Analysis hub phase B: the console's one data path for series (replaces metricAdapter.ts). Components ask for a series
// (metric-series.json) over a range of local days; this pages the rows with keyset paging, places them on local days
// with the series' time contract, combines each day with the series' day method and caches the result per series.
// Weeks and months are aggregated from the days in the browser (aggregatePeriods); the daily tables are small (all of
// wearable_daily since 2016 is 3,685 rows), so no summary tables are needed for them.
import { supabase } from './supabase'
import { readAllPages, keysetFilter, type Cursor, type Page } from './pager'
import { RangeCache, abortError } from './rangeCache'
import { LIVE_COLUMNS, SERIES_FILE } from './schema'
import { queryBounds, seriesColumns, toObservations, type Row, type RowObservation } from './seriesRows'
import { aggregateDays, type DayValue, type SeriesContract } from '../../../supabase/functions/_shared/analysis-core/seriesContract.ts'
import type { DateRange } from '../types/analysis'

// Large JSON columns, never part of a list read; Data loads them for one selected record.
export const HEAVY_COLUMNS = new Set(['hr_timeseries', 'raw_body', 'details', 'stage_intervals'])

/** Counters for the perf readout (and window.__seriesStats in the browser console). */
export const queryStats = { requests: 0, rows: 0 }
;(globalThis as { __seriesStats?: typeof queryStats }).__seriesStats = queryStats

const cache = new RangeCache<DayValue>()

export function cacheCounters(): { hits: number; misses: number } {
  return { hits: cache.hits, misses: cache.misses }
}

export function clearSeriesCache(): void {
  cache.clear()
}

async function readRows(s: SeriesContract, range: DateRange, select: string, signal: AbortSignal): Promise<Row[]> {
  const { from, to } = queryBounds(s, range)
  const col = s.time.column
  const { rows } = await readAllPages<Row>(async (after: Cursor | null, size: number): Promise<Page<Row>> => {
    let q = supabase.from(s.table).select(select).gte(col, from).lte(col, to)
    for (const [c, vals] of Object.entries(s.filters ?? {})) q = q.in(c, vals)
    if (after) q = q.or(keysetFilter(col, after))
    queryStats.requests++
    const { data, error } = await q.order(col).order('id').limit(size).abortSignal(signal)
    if (error) {
      if (signal.aborted) throw abortError()
      return { rows: [], error: error.message }
    }
    const page = (data ?? []) as unknown as Row[]
    queryStats.rows += page.length
    return { rows: page, error: null }
  }, (r) => ({ time: String(r[col]), id: r.id as string | number }))
  return rows
}

/** One value per local day of the range that has rows, by the series' day method. */
export function loadDays(s: SeriesContract, range: DateRange, signal?: AbortSignal): Promise<DayValue[]> {
  return cache.load(s.id, range.start, range.end, async (sig) => {
    const rows = await readRows(s, range, seriesColumns(s).join(', '), sig)
    return aggregateDays(toObservations(rows, s, range, SERIES_FILE.defaultUtcOffsetMin), s.day)
  }, signal)
}

/**
 * Several series at once. Cache misses that share a table, filters and time column are read in ONE paged query selecting
 * all their value columns (Explore's default HRV, resting HR and steps cost one read of wearable_daily, not three); each
 * series' days are then cached on their own. Results come back in the order asked.
 */
export async function loadDaysMany(list: SeriesContract[], range: DateRange, signal: AbortSignal): Promise<DayValue[][]> {
  const out: (DayValue[] | undefined)[] = list.map((s) => cache.lookup(s.id, range.start, range.end) ?? undefined)
  out.forEach((hit) => { if (hit) cache.hits++ })
  const groups = new Map<string, number[]>()
  list.forEach((s, i) => {
    if (out[i]) return
    const key = [s.table, s.time.column, s.time.offsetColumn ?? '', JSON.stringify(s.time.exceptions ?? []), JSON.stringify(s.filters ?? {})].join('|')
    groups.set(key, [...(groups.get(key) ?? []), i])
  })
  await Promise.all([...groups.values()].map(async (idx) => {
    if (idx.length === 1) { out[idx[0]] = await loadDays(list[idx[0]], range, signal); return }
    cache.misses += idx.length
    const columns = [...new Set(idx.flatMap((i) => seriesColumns(list[i])))]
    const rows = await readRows(list[idx[0]], range, columns.join(', '), signal)
    for (const i of idx) {
      const s = list[i]
      const days = aggregateDays(toObservations(rows, s, range, SERIES_FILE.defaultUtcOffsetMin), s.day)
      cache.store(s.id, range.start, range.end, days)
      out[i] = days
    }
  }))
  return out as DayValue[][]
}

export interface DayRecords {
  observations: RowObservation[]
  rows: Row[]
}

/** The rows behind one local day of a series, every light column, for the inspector. Not cached. */
export async function loadDayRecords(s: SeriesContract, date: string, signal: AbortSignal): Promise<DayRecords> {
  const known = LIVE_COLUMNS[s.table]
  const select = known ? known.filter((c) => !HEAVY_COLUMNS.has(c)).join(', ') : seriesColumns(s).join(', ')
  const range = { start: date, end: date }
  const rows = await readRows(s, range, select, signal)
  const observations = toObservations(rows, s, range, SERIES_FILE.defaultUtcOffsetMin)
  const ids = new Set(observations.map((o) => String(o.id)))
  return { observations, rows: rows.filter((r) => ids.has(String(r.id))) }
}
