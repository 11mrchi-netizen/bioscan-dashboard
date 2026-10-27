// Run: node --test console/src/lib/queryFoundation.test.ts
// Analysis hub phase B: keyset paging, the range cache with cancellation, and rows -> local-day observations.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { keysetFilter, readAllPages, type Cursor } from './pager.ts'
import { RangeCache, isAbort } from './rangeCache.ts'
import { queryBounds, seriesColumns, toObservations } from './seriesRows.ts'
import { SERIES_FILE, seriesById } from './schema.ts'

interface R { t: string; id: number }

// An in-memory table answering keyset pages exactly as PostgREST would: order by (t, id), rows after the cursor.
function table(rows: R[]) {
  const sorted = [...rows].sort((a, b) => (a.t < b.t ? -1 : a.t > b.t ? 1 : a.id - b.id))
  let requests = 0
  const fetchPage = async (after: Cursor | null, size: number) => {
    requests++
    const rest = after ? sorted.filter((r) => r.t > after.time || (r.t === after.time && r.id > Number(after.id))) : sorted
    return { rows: rest.slice(0, size), error: null }
  }
  return { fetchPage, requests: () => requests }
}

const cursor = (r: R): Cursor => ({ time: r.t, id: r.id })

test('keyset paging returns every row once, including ties on the time column and exact page multiples', async () => {
  const rows: R[] = []
  for (let i = 0; i < 3000; i++) rows.push({ t: `2026-10-${String(1 + (i % 27)).padStart(2, '0')}T07:00:00+00:00`, id: i })
  const t = table(rows)
  const out = await readAllPages(t.fetchPage, cursor, 1000)
  assert.equal(out.rows.length, 3000)
  assert.equal(new Set(out.rows.map((r) => r.id)).size, 3000)
  assert.equal(out.requests, 4) // three full pages and the empty one that proves the end
  const small = await readAllPages(table(rows.slice(0, 999)).fetchPage, cursor, 1000)
  assert.deepEqual([small.rows.length, small.requests], [999, 1])
})

test('a row written between two pages is neither skipped nor repeated', async () => {
  const rows: R[] = Array.from({ length: 4 }, (_, i) => ({ t: `2026-10-0${i + 1}`, id: i + 1 }))
  let calls = 0
  const out = await readAllPages(async (after, size) => {
    calls++
    if (calls === 2) rows.unshift({ t: '2026-09-30', id: 99 }) // an older day synced while paging
    const rest = rows.filter((r) => !after || r.t > after.time || (r.t === after.time && r.id > Number(after.id))).sort((a, b) => (a.t < b.t ? -1 : 1))
    return { rows: rest.slice(0, size), error: null }
  }, cursor, 2)
  assert.deepEqual(out.rows.map((r) => r.id), [1, 2, 3, 4])
})

test('an error or an endless table throws instead of returning a cut result', async () => {
  await assert.rejects(readAllPages(async () => ({ rows: [], error: 'boom' }), cursor), /boom/)
  const endless = async (_: Cursor | null, size: number) => ({ rows: Array.from({ length: size }, (_, i) => ({ t: 'x', id: i })), error: null })
  await assert.rejects(readAllPages(endless, cursor, 10, 3), /stopped after 3 pages/)
})

test('the keyset filter quotes timestamps for PostgREST', () => {
  assert.equal(keysetFilter('start_time', { time: '2026-10-27T07:00:00+00:00', id: 5 }),
    'start_time.gt."2026-10-27T07:00:00+00:00",and(start_time.eq."2026-10-27T07:00:00+00:00",id.gt."5")')
  assert.equal(keysetFilter('date', { time: '2026-10-27', id: 9 }, 'desc'), 'date.lt."2026-10-27",and(date.eq."2026-10-27",id.lt."9")')
})

const pts = (dates: string[]) => dates.map((date) => ({ date }))

test('range cache: narrower ranges are sliced from the widest load; expiry and failures are not stored', async () => {
  let now = 0
  const c = new RangeCache<{ date: string }>(1000, () => now)
  let loads = 0
  const loader = async () => { loads++; return pts(['2026-10-01', '2026-10-15', '2026-10-27']) }
  await c.load('hrv', '2026-10-01', '2026-10-27', loader)
  assert.deepEqual(await c.load('hrv', '2026-10-10', '2026-10-20', loader), pts(['2026-10-15']))
  assert.equal(loads, 1)
  now = 2000
  await c.load('hrv', '2026-10-10', '2026-10-20', loader)
  assert.equal(loads, 2)
  await assert.rejects(c.load('rhr', '2026-10-01', '2026-10-02', async () => { throw new Error('down') }), /down/)
  assert.equal(c.lookup('rhr', '2026-10-01', '2026-10-02'), null)
})

test('range cache: simultaneous callers share one load; it is aborted only when the last caller leaves', async () => {
  const c = new RangeCache<{ date: string }>()
  let release!: (v: { date: string }[]) => void
  let loaderSignal!: AbortSignal
  let loads = 0
  const loader = (sig: AbortSignal) => { loads++; loaderSignal = sig; return new Promise<{ date: string }[]>((r) => { release = r }) }
  const a = new AbortController()
  const b = new AbortController()
  const pa = c.load('steps', '2026-10-01', '2026-10-07', loader, a.signal)
  const pb = c.load('steps', '2026-10-01', '2026-10-07', loader, b.signal)
  assert.equal(loads, 1)
  a.abort()
  await assert.rejects(pa, (e) => isAbort(e))
  assert.equal(loaderSignal.aborted, false, 'b still needs the load')
  release(pts(['2026-10-03']))
  assert.deepEqual(await pb, pts(['2026-10-03']))

  const c2 = new RangeCache<{ date: string }>()
  const x = new AbortController()
  const px = c2.load('steps', '2026-10-01', '2026-10-07', loader, x.signal)
  x.abort()
  await assert.rejects(px, (e) => isAbort(e))
  assert.equal(loaderSignal.aborted, true, 'nobody waits: the fetch is cancelled')
  // a new caller starts a fresh load instead of joining the cancelled one
  const py = c2.load('steps', '2026-10-01', '2026-10-07', loader)
  release(pts(['2026-10-02']))
  assert.deepEqual(await py, pts(['2026-10-02']))
})

test('rows -> observations: local days under the time contract, rows outside the range dropped', () => {
  const meals = seriesById('meal_energy')!
  assert.deepEqual(queryBounds(meals, { start: '2026-09-10', end: '2026-09-20' }), { from: '2026-09-09T00:00:00', to: '2026-09-21T23:59:59.999' })
  assert.deepEqual(queryBounds(seriesById('hrv')!, { start: '2026-09-10', end: '2026-09-20' }), { from: '2026-09-10', to: '2026-09-20' })
  assert.deepEqual(seriesColumns(meals), ['id', 'logged_at', 'calories', 'source'])
  const rows = [
    { id: 1, logged_at: '2026-09-09T17:00:00+00:00', calories: '400', source: 'manual' }, // backfill UTC -> 01:00 on the 10th
    { id: 2, logged_at: '2026-09-09T11:30:00+00:00', calories: 500, source: 'manual' }, // backfill UTC -> 19:30 on the 9th: outside
    { id: 3, logged_at: '2026-09-20T23:30:00+00:00', calories: null, source: 'manual' }, // live local wall time: the 20th
    { id: 4, logged_at: '2026-09-21T08:00:00+00:00', calories: 300, source: 'manual' }, // outside
  ]
  const obs = toObservations(rows, meals, { start: '2026-09-10', end: '2026-09-20' }, SERIES_FILE.defaultUtcOffsetMin)
  assert.deepEqual(obs.map((o) => [o.id, o.date, o.localTime, o.value, o.assumedOffset]), [[1, '2026-09-10', '01:00', 400, true], [3, '2026-09-20', '23:30', null, false]])
  const hrv = toObservations([{ id: 5, date: '2026-09-12', hrv: 50, source: 'zepp' }], seriesById('hrv')!, { start: '2026-09-10', end: '2026-09-20' }, 480)
  assert.equal(hrv[0].localTime, null)
})
