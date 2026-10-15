import assert from 'node:assert/strict'
import { test } from 'node:test'
import { normalCdf, partialPool, probabilityBeyondZone } from './pooling.ts'

test('identical estimates need no spread between questions and stay where they are', () => {
  const p = partialPool([{ id: 'a', estimate: 1, se: 1 }, { id: 'b', estimate: 1, se: 1 }])!
  assert.equal(p.tau, 0)
  for (const m of p.members) assert.ok(Math.abs(m.mean - 1) < 1e-12)
})

test('clearly different precise estimates are left almost alone', () => {
  const p = partialPool([{ id: 'a', estimate: -3, se: 0.3 }, { id: 'b', estimate: 0, se: 0.3 }, { id: 'c', estimate: 3, se: 0.3 }])!
  assert.ok(p.tau > 2)
  for (const m of p.members) assert.ok(m.shrinkage < 0.05)
  assert.ok(Math.abs(p.members[0].mean + 3) < 0.2)
})

test('a noisy outlier in a consistent family moves toward the family', () => {
  const p = partialPool([
    { id: 'a', estimate: 1.2, se: 0.2 }, { id: 'b', estimate: 0.8, se: 0.2 }, { id: 'c', estimate: 1.0, se: 0.2 }, { id: 'd', estimate: 5, se: 4 },
  ])!
  const d = p.members.find((m) => m.id === 'd')!
  assert.ok(d.shrinkage > 0.9)
  assert.ok(d.mean < 1.6 && d.mean > 0.8, `noisy member pulled to ${d.mean}`)
  assert.ok(d.sd < 4)
  assert.equal(partialPool([{ id: 'a', estimate: 1, se: 1 }]), null)
})

test('probability beyond the zone', () => {
  assert.ok(Math.abs(normalCdf(1.959964) - 0.975) < 1e-6)
  assert.ok(probabilityBeyondZone(0, 0.1) < 1e-6)
  assert.ok(probabilityBeyondZone(3, 0.5) > 0.999)
  assert.ok(Math.abs(probabilityBeyondZone(1, 0.01) - 0.5) < 0.01)
})
