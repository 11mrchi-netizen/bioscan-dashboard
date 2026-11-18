import { test } from 'node:test'
import assert from 'node:assert/strict'
import { remainingByProduct } from './pantry.ts'

const ev = (pid: number, kind: string, servings: number, at: string, expires: string | null = null) =>
  ({ supplement_product_id: pid, kind, servings, occurred_at: at, expires_on: expires })

test('stock is recorded minus taken since the first purchase', () => {
  const m = remainingByProduct(
    [ev(1, 'purchase', 60, '2026-10-01T00:00:00Z', '2027-06-01'), ev(1, 'adjustment', -2, '2026-10-10T00:00:00Z'), ev(1, 'purchase', 30, '2026-11-01T00:00:00Z', '2027-03-01')],
    [
      { productId: 1, at: Date.parse('2026-09-30T08:00:00Z'), servings: 1 }, // before the first purchase: not counted
      { productId: 1, at: Date.parse('2026-10-02T08:00:00Z'), servings: 2 },
      { productId: 1, at: Date.parse('2026-11-02T08:00:00Z'), servings: 1 },
      { productId: 2, at: Date.parse('2026-11-02T08:00:00Z'), servings: 5 },
    ],
  )
  assert.deepEqual(m.get(1), { remaining: 85, expires: '2027-03-01' })
})

test('no purchase means no stock number, and stock never goes below zero', () => {
  const m = remainingByProduct([ev(3, 'adjustment', 10, '2026-10-01T00:00:00Z'), ev(4, 'purchase', 2, '2026-10-01T00:00:00Z')],
    [{ productId: 4, at: Date.parse('2026-10-03T00:00:00Z'), servings: 9 }])
  assert.equal(m.has(3), false)
  assert.equal(m.get(4)!.remaining, 0)
})
