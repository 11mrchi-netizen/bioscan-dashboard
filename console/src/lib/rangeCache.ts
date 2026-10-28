// Analysis hub phase B: the shared cache for date-keyed series (replaces metricCache.ts, which was tied to the old
// metric requests). It keeps the performance pass's rules:
//  - one entry per key (a series id) at the widest range fetched; narrower ranges are sliced from it, so 1Y -> 90D -> 7D
//    costs one request;
//  - a failed load is never stored; entries expire after the TTL;
//  - callers asking for the same key and range at the same time share one load.
// and adds cancellation: each caller passes its own AbortSignal, and the shared load is aborted only when every caller
// waiting on it has gone (a range changed twice quickly stops the first, unneeded fetch).

export interface Dated {
  date: string
}

interface Stored<P> {
  start: string
  end: string
  points: P[]
  ts: number
}

interface Inflight<P> {
  promise: Promise<P[]>
  controller: AbortController
  users: number
}

export function abortError(): Error {
  const e = new Error('aborted')
  e.name = 'AbortError'
  return e
}

export function isAbort(e: unknown): boolean {
  return e instanceof Error && e.name === 'AbortError'
}

export class RangeCache<P extends Dated> {
  private stored = new Map<string, Stored<P>>()
  private inflight = new Map<string, Inflight<P>>()
  hits = 0
  misses = 0

  private ttlMs: number
  private now: () => number

  constructor(ttlMs = 5 * 60 * 1000, now: () => number = () => Date.now()) {
    this.ttlMs = ttlMs
    this.now = now
  }

  /** The slice for [start, end] if a fresh entry covers it. */
  lookup(key: string, start: string, end: string): P[] | null {
    const s = this.stored.get(key)
    if (!s || this.now() - s.ts > this.ttlMs || s.start > start || s.end < end) return null
    return s.points.filter((p) => p.date >= start && p.date <= end)
  }

  store(key: string, start: string, end: string, points: P[]): void {
    const old = this.stored.get(key)
    if (old && this.now() - old.ts <= this.ttlMs && old.start <= start && old.end >= end) return
    this.stored.set(key, { start, end, points, ts: this.now() })
  }

  load(key: string, start: string, end: string, loader: (signal: AbortSignal) => Promise<P[]>, signal?: AbortSignal): Promise<P[]> {
    const hit = this.lookup(key, start, end)
    if (hit) {
      this.hits++
      return Promise.resolve(hit)
    }
    const k = `${key}|${start}|${end}`
    let entry = this.inflight.get(k)
    if (!entry) {
      this.misses++
      const controller = new AbortController()
      const created: Inflight<P> = { promise: Promise.resolve([]), controller, users: 0 }
      created.promise = loader(controller.signal)
        .then((points) => {
          this.store(key, start, end, points)
          return points
        })
        .finally(() => {
          if (this.inflight.get(k) === created) this.inflight.delete(k)
        })
      created.promise.catch(() => {}) // a caller that left must not leave an unhandled rejection behind
      this.inflight.set(k, created)
      entry = created
    }
    const shared = entry
    shared.users++
    if (!signal) return shared.promise
    return new Promise<P[]>((resolve, reject) => {
      const leave = () => {
        shared.users--
        if (shared.users === 0) {
          shared.controller.abort()
          if (this.inflight.get(k) === shared) this.inflight.delete(k)
        }
        reject(abortError())
      }
      if (signal.aborted) return leave()
      signal.addEventListener('abort', leave, { once: true })
      shared.promise.then(
        (v) => { signal.removeEventListener('abort', leave); resolve(v) },
        (e) => { signal.removeEventListener('abort', leave); reject(e) },
      )
    })
  }

  clear(): void {
    this.stored.clear()
    for (const e of this.inflight.values()) e.controller.abort()
    this.inflight.clear()
  }

  size(): number {
    return this.stored.size
  }
}
