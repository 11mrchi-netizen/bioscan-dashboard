import test from 'node:test'
import assert from 'node:assert/strict'
import { PAGE_ROWS, readAll, readAllSoft } from './readAll.ts'

// A table of n rows behind a PostgREST-like range that never returns more than the server cap.
function table(n: number, failAt: number | null = null) {
  const calls: [number, number][] = []
  const build = () => ({
    range(from: number, to: number) {
      calls.push([from, to])
      if (failAt != null && from >= failAt) return Promise.resolve({ data: null, error: { message: 'boom' } })
      const end = Math.min(n, to + 1, from + PAGE_ROWS)
      return Promise.resolve({ data: Array.from({ length: Math.max(0, end - from) }, (_, i) => ({ i: from + i })), error: null })
    },
  })
  return { build, calls }
}

test('reads past the 1,000-row cap', async () => {
  const t = table(3714)
  const rows = await readAll<{ i: number }>(t.build, 'wearable_daily')
  assert.equal(rows.length, 3714)
  assert.equal(rows.at(-1)?.i, 3713)
  assert.equal(t.calls.length, 4)
})

test('an exact multiple of a page ends on the empty page', async () => {
  const t = table(2000)
  assert.equal((await readAll(t.build, 'x')).length, 2000)
  assert.equal(t.calls.length, 3)
})

test('an error is reported with the table name, or softly with the rows read so far', async () => {
  await assert.rejects(readAll(table(5000, 1000).build, 'supplement_log'), /supplement_log: boom/)
  const soft = await readAllSoft(table(5000, 1000).build)
  assert.equal(soft.rows.length, 1000)
  assert.equal(soft.error, 'boom')
})
