// Run: node --test console/src/lib/training.test.ts
// Analysis review A5: the console's load and VO2max helpers match the Android model.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ema, sessionLoad, vo2maxFromPace } from './training.ts'

test('VO2max uses metres per minute and the duration term (Daniels & Gilbert)', () => {
  // 10 km in 50:00 is a VDOT of about 40; 5 km in 20:00 about 49.8.
  assert.ok(Math.abs(vo2maxFromPace(10, 50)! - 40.0) < 0.5)
  assert.ok(Math.abs(vo2maxFromPace(5, 20)! - 49.8) < 0.6)
  // 12 km/h for an hour (the old helper returned about -2.4)
  assert.ok(vo2maxFromPace(12, 60)! > 35)
  assert.equal(vo2maxFromPace(0, 30), null)
  assert.equal(vo2maxFromPace(1, 2), null) // too short to mean anything
})

test('session load: RPE x minutes first, then TRIMP, never a name-based guess', () => {
  assert.equal(sessionLoad({ duration_minutes: 60, rpe: 6 }, 50), 360)
  const trimp = sessionLoad({ duration_minutes: 60, avg_hr: 150, max_hr: 190 }, 50)!
  const ratio = (150 - 50) / (190 - 50)
  assert.ok(Math.abs(trimp - 60 * ratio * 0.64 * Math.exp(1.92 * ratio)) < 1e-9)
  assert.equal(sessionLoad({ duration_minutes: 60 }, 50), null)
  assert.equal(sessionLoad({ duration_minutes: 60, avg_hr: 150, max_hr: 190 }, null), null)
})

test('EWMA uses the N-day span convention, alpha = 2 / (N + 1)', () => {
  const out = ema([100, 0, 0], 7)
  assert.equal(out[0], 25) // 2 / 8 x 100
  assert.equal(out[1], 18.8) // 25 x 0.75, rounded to one decimal
})
