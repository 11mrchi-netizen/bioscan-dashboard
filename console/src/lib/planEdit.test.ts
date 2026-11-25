import test from 'node:test'
import assert from 'node:assert/strict'
import { buildPrescription, formOf, liftForms, loadWords, movedFrom, prescriptionChanged, sessionChanges, startShiftDays, whyLocked } from './planEdit.ts'

const TODAY = '2026-11-24'
const ctx = { trainingMaxKg: null, maxReps: null, bodyweightKg: 80, barKg: 20, platesKg: [1.25, 2.5, 5, 10, 15, 20], addedStepKg: 2.5, weightedBase: 'total' as const }
const presc = [
  { type: 'lift', slot: 'SQ', exercise: 'Squat', role: 'main', sets: { min: 3, max: 5 }, reps: 5, load: { kind: 'unresolved', missing: 'OneRm' } },
  { type: 'lift', slot: 'BP', exercise: 'Bench', role: 'main', sets: 3, reps: { min: 5, max: 8 }, load: { kind: 'pct_1rm', pct: 80, target_kg: 80, loadable_kg: 80, bar_kg: 20, plates_per_side_kg: [20, 10] } },
  { type: 'conditioning', label: 'Zone 2', minutes: { min: 20, max: 30 } },
  { type: 'note', text: 'Keep it easy' },
]

test('who may edit: only a planned, unlinked session of today or later in a block that has not ended', () => {
  const ok = { status: 'planned', exercise_session_id: null, scheduled_date: TODAY }
  assert.equal(whyLocked(ok, 'scheduled', TODAY), null)
  assert.match(whyLocked({ ...ok, status: 'superseded' }, 'scheduled', TODAY)!, /set aside/)
  assert.match(whyLocked({ ...ok, status: 'confirmed' }, 'scheduled', TODAY)!, /confirmed/)
  assert.match(whyLocked({ ...ok, exercise_session_id: 4 }, 'scheduled', TODAY)!, /linked/)
  assert.match(whyLocked({ ...ok, scheduled_date: '2026-11-23' }, 'scheduled', TODAY)!, /before today/)
  assert.match(whyLocked(ok, 'completed', TODAY)!, /block has ended/)
})

test('the changes sent are only what differs, and each is checked', () => {
  const orig = { scheduled_date: '2026-11-30', start_time: '07:00:00', duration_min: 60, note: null }
  assert.deepEqual(sessionChanges(orig, formOf(orig), TODAY), {})
  assert.deepEqual(sessionChanges(orig, { ...formOf(orig), date: '2026-12-02', time: '18:30', duration: '75', note: ' light ' }, TODAY), { scheduled_date: '2026-12-02', start_time: '18:30', duration_min: 75, note: 'light' })
  assert.deepEqual(sessionChanges(orig, { ...formOf(orig), time: '', duration: '' }, TODAY), { start_time: null, duration_min: null })
  assert.throws(() => sessionChanges(orig, { ...formOf(orig), date: '2026-11-20' }, TODAY), /today or later/)
  assert.throws(() => sessionChanges(orig, { ...formOf(orig), date: 'tomorrow' }, TODAY), /not a date/)
  assert.throws(() => sessionChanges(orig, { ...formOf(orig), time: '25:00' }, TODAY), /not a time/)
  assert.throws(() => sessionChanges(orig, { ...formOf(orig), duration: '3' }, TODAY), /5 to 600/)
  assert.throws(() => sessionChanges(orig, { ...formOf(orig), note: 'x'.repeat(501) }, TODAY), /500/)
  assert.deepEqual(sessionChanges({ ...orig, note: 'old' }, { ...formOf(orig), note: '' }, TODAY), { note: null })
})

test('lift forms read sets, reps (a number or a range) and the load in words; other entries are left out', () => {
  const f = liftForms(presc)
  assert.equal(f.length, 2)
  assert.deepEqual([f[0].index, f[0].setsMin, f[0].setsMax, f[0].repsMin, f[0].repsMax], [0, '3', '5', '5', '5'])
  assert.deepEqual([f[1].index, f[1].setsMin, f[1].setsMax, f[1].repsMin, f[1].repsMax, f[1].pct], [1, '3', '3', '5', '8', '80'])
  assert.match(f[0].loadNow, /no target yet \(one rm not set\)/)
  assert.equal(f[1].loadNow, '80% of 1RM → 80 kg')
  assert.equal(loadWords({ kind: 'rpe', rpe: 8 }), 'RPE 8')
})

test('nothing edited builds exactly what was stored; the conditioning and note entries are never touched', () => {
  const out = buildPrescription(presc, liftForms(presc), ctx, () => null)
  assert.deepEqual(out, presc)
  assert.equal(prescriptionChanged(presc, out), false)
})

test('sets and reps are rebuilt as the generator writes them: one number when min = max, else a range', () => {
  const f = liftForms(presc); f[0].setsMin = '4'; f[0].setsMax = '4'; f[0].repsMin = '3'; f[0].repsMax = '5'
  const out = buildPrescription(presc, f, ctx, () => null)
  assert.equal(out[0].sets, 4); assert.deepEqual(out[0].reps, { min: 3, max: 5 })
  assert.deepEqual(out[0].load, presc[0].load) // load left as planned
  assert.equal(prescriptionChanged(presc, out), true)
  assert.deepEqual(out[2], presc[2]); assert.deepEqual(out[3], presc[3])
  const g = liftForms(presc); g[1].setsMin = '3'; g[1].setsMax = ''
  assert.equal(buildPrescription(presc, g, ctx, () => null)[1].sets, 3)
})

test('a fixed weight is resolved to what can be loaded; a percentage needs a recorded 1RM', () => {
  const f = liftForms(presc); f[0].load = 'fixed'; f[0].kg = '102.5'
  const out = buildPrescription(presc, f, ctx, () => null)
  const l = out[0].load as Record<string, unknown>
  assert.deepEqual([l.kind, l.kg, l.loadable_kg], ['fixed', 102.5, 102.5])
  const p = liftForms(presc); p[0].load = 'pct'; p[0].pct = '80'
  assert.throws(() => buildPrescription(presc, p, ctx, () => null), /no 1RM recorded/)
  const q = buildPrescription(presc, p, ctx, (ex) => (ex === 'Squat' ? 150 : null))[0].load as Record<string, unknown>
  assert.deepEqual([q.kind, q.pct, q.target_kg, q.loadable_kg], ['pct_1rm', 80, 120, 120])
  const r = liftForms(presc); r[0].load = 'rpe'; r[0].rpe = '8'
  assert.deepEqual(buildPrescription(presc, r, ctx, () => null)[0].load, { kind: 'rpe', rpe: 8 })
})

test('bad numbers are refused with the lift named', () => {
  const f = liftForms(presc); f[0].setsMin = '0'
  assert.throws(() => buildPrescription(presc, f, ctx, () => null), /Squat: sets/)
  const g = liftForms(presc); g[0].repsMin = '8'; g[0].repsMax = '5'
  assert.throws(() => buildPrescription(presc, g, ctx, () => null), /Squat: the most reps/)
  const h = liftForms(presc); h[0].load = 'fixed'; h[0].kg = '-5'
  assert.throws(() => buildPrescription(presc, h, ctx, () => null), /Squat: the weight/)
  assert.throws(() => buildPrescription(null, [], ctx, () => null), /no prescription/)
  const i = liftForms(presc); i[0].index = 9
  assert.throws(() => buildPrescription(presc, i, ctx, () => null), /changed while/)
})

test('"moved from" is for the sessions the person moved, not for a block whose start was shifted', () => {
  assert.equal(startShiftDays({ start_shift_days: 7 }), 7)
  assert.equal(startShiftDays(null), 0)
  assert.equal(movedFrom('2026-11-30', '2026-11-30', 0), null)
  assert.equal(movedFrom('2026-11-30', '2026-12-03', 0), '2026-11-30')
  assert.equal(movedFrom('2026-11-30', '2026-12-07', 7), null) // the block shifted a week: not a move
  assert.equal(movedFrom('2026-11-30', '2026-12-09', 7), '2026-11-30') // moved two days within a shifted block
})

test('a lift swapped for another in one session takes the new name and needs a load of its own when the planned one came from a max', () => {
  const f = liftForms(presc); f[0].swapTo = 'Front Squat'
  assert.throws(() => buildPrescription(presc, f, ctx, () => null), /choose a load for Front Squat/)
  f[0].load = 'pct'; f[0].pct = '75'
  const out = buildPrescription(presc, f, ctx, (ex) => (ex === 'Front Squat' ? 100 : ex === 'Squat' ? 150 : null))
  assert.equal(out[0].exercise, 'Front Squat')
  assert.equal((out[0].load as { target_kg: number }).target_kg, 75) // the new lift's own max
  const r = liftForms(presc); r[0].swapTo = 'Belt Squat'; r[0].load = 'rpe'; r[0].rpe = '8'
  assert.deepEqual(buildPrescription(presc, r, ctx, () => null)[0], { ...presc[0], exercise: 'Belt Squat', load: { kind: 'rpe', rpe: 8 } })
  const same = liftForms(presc); same[0].swapTo = 'Squat'
  assert.deepEqual(buildPrescription(presc, same, ctx, () => null), presc) // the same name is no swap
})
