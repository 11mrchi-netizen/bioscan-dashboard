import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fetchAll, fetchAllWaves } from './paging.ts'

function source(total: number, failAtPage = -1, pageSize = 1000) {
  const all = Array.from({ length: total }, (_, i) => i)
  let inFlight = 0
  let maxInFlight = 0
  let calls = 0
  const page = async (from: number, to: number) => {
    calls++
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    await new Promise((r) => setTimeout(r, 1))
    inFlight--
    if (failAtPage >= 0 && Math.floor(from / pageSize) === failAtPage) return { data: null, error: { message: `page ${failAtPage} failed` } }
    return { data: all.slice(from, to + 1), error: null }
  }
  return { page, all, stats: () => ({ calls, maxInFlight }) }
}

test('fetchAllWaves returns exactly what fetchAll returns, in order, for every page-boundary case', async () => {
  for (const total of [0, 1, 999, 1000, 1001, 2000, 3500, 4999, 5000, 5001, 12345]) {
    const a = source(total)
    const b = source(total)
    const seq = await fetchAll(a.page)
    const waves = await fetchAllWaves(b.page)
    assert.deepEqual(waves.rows, seq.rows, `total ${total}`)
    assert.deepEqual(waves.rows, a.all)
    assert.equal(waves.error, undefined)
  }
})

test('fetchAllWaves asks for the pages after the first together, and stops at a short page', async () => {
  const s = source(4500)
  const out = await fetchAllWaves(s.page)
  assert.equal(out.rows.length, 4500)
  assert.ok(s.stats().maxInFlight > 1, 'pages overlapped')
  assert.ok(s.stats().maxInFlight <= 4, 'no more than one wave at a time')
  const small = source(500)
  await fetchAllWaves(small.page)
  assert.equal(small.stats().calls, 1, 'a short first page needs no more requests')
})

test('fetchAllWaves reports the first failing page and keeps the rows before it', async () => {
  const s = source(6000, 3)
  const out = await fetchAllWaves(s.page)
  assert.equal(out.error, 'page 3 failed')
  assert.equal(out.rows.length, 3000)
  assert.deepEqual(out.rows, s.all.slice(0, 3000))
  const first = source(6000, 0)
  assert.equal((await fetchAllWaves(first.page)).error, 'page 0 failed')
})
