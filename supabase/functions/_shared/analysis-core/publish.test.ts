import assert from 'node:assert/strict'
import { test } from 'node:test'
import { publishResults, PUBLISH_BATCH } from './publish.ts'
import type { ResultRow } from './publish.ts'
import { CORE_VERSION } from './version.ts'

type Call = { table: string; op: string; arg?: unknown; filters?: [string, unknown][] }

function fakeAdmin(failOnUpsert = -1) {
  const calls: Call[] = []
  let upserts = 0
  return {
    calls,
    from(table: string) {
      const filters: [string, unknown][] = []
      const q = {
        upsert(rows: unknown) {
          calls.push({ table, op: 'upsert', arg: rows })
          return Promise.resolve({ error: upserts++ === failOnUpsert ? { message: 'boom' } : null })
        },
        delete() { calls.push({ table, op: 'delete', filters }); return q },
        eq(c: string, v: unknown) { filters.push([c, v]); return q },
        in(c: string, v: unknown) { filters.push([c, v]); return q },
        lt(c: string, v: unknown) { filters.push([c, v]); return q },
        then(resolve: (v: { error: null }) => void) { resolve({ error: null }) },
      }
      return q
    },
  }
}

const row = (kind: string, key: string, state = 'ok'): ResultRow => ({ kind, key, state, have: 1, need: 2, payload: { key }, hash: `h-${key}` })
const NOW = new Date('2026-10-29T20:30:00Z')

test('46 rows go up in batches, not one by one, with the same fields as before', async () => {
  const rows = [...Array.from({ length: 5 }, (_, i) => row('state', `c${i}`)), ...Array.from({ length: 40 }, (_, i) => row('finding', `f${i}`)), row('overview', 'all')]
  const a = fakeAdmin()
  const out = await publishResults(a, 'u1', '2026-10-29', rows, NOW)
  assert.equal(out.error, undefined)
  assert.equal(out.published.length, 46)
  const upserts = a.calls.filter((c) => c.op === 'upsert')
  assert.equal(upserts.length, Math.ceil(46 / PUBLISH_BATCH))
  const first = (upserts[0].arg as Record<string, unknown>[])[0]
  assert.deepEqual(first, {
    user_id: 'u1', kind: 'state', key: 'c0', as_of: '2026-10-29', engine_version: CORE_VERSION, inputs_hash: 'h-c0', state: 'ok',
    confidence_have: 1, confidence_need: 2, payload: { key: 'c0' }, calculated_at: NOW.toISOString(),
  })
  assert.ok(a.calls.filter((c) => c.op === 'delete').length <= upserts.length * 2, 'deletes are per kind and batch')
})

test('older copies of exactly the batch\'s keys are removed, per kind, after their upsert', async () => {
  const a = fakeAdmin()
  await publishResults(a, 'u1', '2026-10-29', [row('state', 'a'), row('state', 'b'), row('finding', 'x')], NOW)
  assert.deepEqual(a.calls.map((c) => c.op), ['upsert', 'delete', 'delete'])
  const del = a.calls.filter((c) => c.op === 'delete')
  const f = (c: Call) => Object.fromEntries(c.filters!.map(([k, v]) => [k, v]))
  assert.deepEqual(f(del[0]).key, ['a', 'b'])
  assert.equal(f(del[0]).kind, 'state')
  assert.equal(f(del[0]).as_of, '2026-10-29', 'older than today')
  assert.equal(f(del[0]).engine_version, CORE_VERSION)
  assert.deepEqual(f(del[1]).key, ['x'])
})

test('a repeated key keeps the later row, as two sequential upserts would have', async () => {
  const a = fakeAdmin()
  const out = await publishResults(a, 'u1', '2026-10-29', [row('state', 'a', 'first'), row('state', 'b'), row('state', 'a', 'second')], NOW)
  const sent = (a.calls.find((c) => c.op === 'upsert')!.arg as Record<string, unknown>[])
  assert.equal(sent.length, 2)
  assert.equal(sent.find((r) => r.key === 'a')!.state, 'second')
  assert.equal(out.published.find((p) => p.key === 'a')!.state, 'second')
})

test('a failed upsert stops the run and reports what was already published', async () => {
  const rows = Array.from({ length: 25 }, (_, i) => row('finding', `f${i}`))
  const a = fakeAdmin(1) // the second batch fails
  const out = await publishResults(a, 'u1', '2026-10-29', rows, NOW)
  assert.equal(out.error, 'boom')
  assert.equal(out.published.length, PUBLISH_BATCH)
  assert.equal(a.calls.filter((c) => c.op === 'upsert').length, 2, 'no third batch after the failure')
})

test('no rows means no requests', async () => {
  const a = fakeAdmin()
  const out = await publishResults(a, 'u1', '2026-10-29', [], NOW)
  assert.deepEqual(out, { published: [] })
  assert.equal(a.calls.length, 0)
})
