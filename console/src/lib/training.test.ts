// Run: node --test console/src/lib/training.test.ts
// Analysis review A5: the console's load and VO2max helpers match the Android model.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ema, loadAnchorsByDate, restingHrAsOf, robustMaxHr, sessionLoad, vo2maxFromPace } from './training.ts'

test('VO2max uses metres per minute and the duration term (Daniels & Gilbert)', () => {
  // 10 km in 50:00 is a VDOT of about 40; 5 km in 20:00 about 49.8.
  assert.ok(Math.abs(vo2maxFromPace(10, 50)! - 40.0) < 0.5)
  assert.ok(Math.abs(vo2maxFromPace(5, 20)! - 49.8) < 0.6)
  // 12 km/h for an hour (the old helper returned about -2.4)
  assert.ok(vo2maxFromPace(12, 60)! > 35)
  assert.equal(vo2maxFromPace(0, 30), null)
  assert.equal(vo2maxFromPace(1, 2), null) // too short to mean anything
})

test('session load: RPE x minutes first, then TRIMP against personal anchors, never a name-based guess', () => {
  const anchors = { restingHr: 53, maxHr: 196 }
  assert.equal(sessionLoad({ duration_minutes: 60, rpe: 6 }, anchors), 360)
  // Golden values shared with Android (EnduranceLoadTest) and the server (trainingLoad.test.ts).
  assert.ok(Math.abs(sessionLoad({ duration_minutes: 60, avg_hr: 148, max_hr: 160 }, anchors)! - 91.34231903347344) < 1e-9)
  assert.ok(Math.abs(sessionLoad({ duration_minutes: 60, avg_hr: 148 }, { restingHr: 53, maxHr: 160 })! - 187.49991909716343) < 1e-9)
  assert.ok(Math.abs(sessionLoad({ duration_minutes: 40, avg_hr: 175 }, anchors)! - 112.37154241030903) < 1e-9)
  assert.ok(Math.abs(sessionLoad({ duration_minutes: 30, avg_hr: 210 }, anchors)! - 130.96240261038238) < 1e-9)
  assert.equal(sessionLoad({ duration_minutes: 60 }, anchors), null)
  assert.equal(sessionLoad({ duration_minutes: 60, avg_hr: 150 }, { restingHr: null, maxHr: 196 }), null)
  assert.equal(sessionLoad({ duration_minutes: 60, avg_hr: 150, rpe: 0 }, { restingHr: 53, maxHr: null }), null, 'RPE 0 is not an RPE')
})

test('anchors are taken as of the date (Android TrimpAnchors)', () => {
  const on = loadAnchorsByDate(
    [['2026-05-12', 190], ['2026-05-22', 186], ['2026-06-06', 199], ['2026-06-07', 198]],
    [['2026-04-22', 60], ['2026-05-31', 52], ['2026-06-11', 48]],
  )
  assert.deepEqual(on('2026-06-01'), { maxHr: 186, restingHr: 52 })
  assert.deepEqual(on('2026-06-07'), { maxHr: 198, restingHr: 52 })
  assert.equal(on('2026-06-11').restingHr, 50)
  assert.equal(on('2026-02-21').restingHr, 60, 'before the first reading: the first 30 days')
  assert.equal(on('2026-02-21').maxHr, null)
  assert.equal(robustMaxHr([['2024-06-01', 199], ['2026-05-01', 180]], '2026-06-01'), 180, 'older than 24 months is left out')
  assert.equal(restingHrAsOf([['2026-01-01', 55], ['2026-01-02', 57]], '2026-06-01'), 56)
})

test('EWMA uses the N-day span convention, alpha = 2 / (N + 1)', () => {
  const out = ema([100, 0, 0], 7)
  assert.equal(out[0], 25) // 2 / 8 x 100
  assert.equal(out[1], 18.8) // 25 x 0.75, rounded to one decimal
})
