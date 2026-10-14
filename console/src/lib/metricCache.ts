// Performance pass, Stage 2. The metric cache the adapter was missing:
//  - the key includes the date column and the filters (it used to collide for requests that differed only in those);
//  - one series is stored per (table, column, filters) at the widest range fetched, and narrower requests are sliced from
//    it, so 90D -> 28D -> 7D costs one request, not three;
//  - requests for the same data that are in flight at the same time share one promise (Comparison with A = B, two
//    workspaces mounting together);
//  - a failed load is never stored.
import type { MetricPoint, MetricRequest } from './metricTypes'

interface Stored {
  start: string
  end: string
  points: MetricPoint[]
  ts: number
}

export class SeriesCache {
  private series = new Map<string, Stored>()
  private inflight = new Map<string, Promise<unknown>>()

  private ttlMs: number
  private now: () => number

  constructor(ttlMs = 5 * 60 * 1000, now: () => number = () => Date.now()) {
    this.ttlMs = ttlMs
    this.now = now
  }

  // Everything that changes which rows come back, except the range.
  seriesKey(req: MetricRequest): string {
    const filters = Object.entries(req.extraFilters ?? {}).sort(([a], [b]) => a.localeCompare(b))
    return `${req.table}.${req.column}|${req.dateColumn ?? 'date'}|${req.aggregation}|${JSON.stringify(filters)}`
  }

  // The requested slice if a fresh stored series covers the range. Only for the plain 'date' column, where the string
  // comparison below is exactly what the database does; any other date column always goes to the database.
  lookup(req: MetricRequest): MetricPoint[] | null {
    if ((req.dateColumn ?? 'date') !== 'date') return null
    const s = this.series.get(this.seriesKey(req))
    if (!s || this.now() - s.ts > this.ttlMs) return null
    if (s.start > req.range.start || s.end < req.range.end) return null
    return s.points.filter(p => p.date >= req.range.start && p.date <= req.range.end)
  }

  store(req: MetricRequest, points: MetricPoint[]): void {
    const key = this.seriesKey(req)
    const old = this.series.get(key)
    const covers = old && this.now() - old.ts <= this.ttlMs && old.start <= req.range.start && old.end >= req.range.end
    if (covers) return // the stored series is already at least as wide and still fresh
    this.series.set(key, { start: req.range.start, end: req.range.end, points, ts: this.now() })
  }

  // One load per key at a time; later callers get the same promise. The entry is removed when it settles.
  dedupe<T>(key: string, load: () => Promise<T>): Promise<T> {
    const existing = this.inflight.get(key) as Promise<T> | undefined
    if (existing) return existing
    const p = load().finally(() => this.inflight.delete(key))
    this.inflight.set(key, p)
    return p
  }

  clear(): void {
    this.series.clear()
    this.inflight.clear()
  }

  size(): number {
    return this.series.size
  }
}
