import test from 'node:test'
import assert from 'node:assert/strict'
import { blankExercise, blankSet, editedDetails, exercisesOf, paceOrSpeed, sessionPatch, toEditable, toExercise, type StrengthExercise } from './sessionEdit.ts'

const zepp: StrengthExercise[] = [{ name: 'Bench Press', exercise_code: 12, sets: [{ reps: 5, weight_kg: 80, rpe: 8, duration_sec: 40, avg_hr: 120, detected_reps: 5 }, { reps: 5, weight_kg: 82.5, duration_sec: 38, avg_hr: 126 }] }]
const row = { exercises: zepp, exercises_source: 'zepp', zepp_fingerprint: 'abc', route_type: 'road' }

test('only the keys the form owns change; everything else in details survives', () => {
  const d = editedDetails(row, { routeType: 'trail' })
  assert.equal(d.route_type, 'trail')
  assert.equal(d.zepp_fingerprint, 'abc')
  assert.deepEqual(d.exercises, zepp)
  assert.equal(d.exercises_source, 'zepp') // exercises untouched: a Zepp list keeps following Zepp
  assert.equal(editedDetails(row, { routeType: null }).route_type, undefined) // cleared: the key is gone
  assert.equal(editedDetails({}, { runType: 'tempo' }).run_type, 'tempo')
  assert.deepEqual(editedDetails(null, {}), {})
})

test('saving exercises unchanged keeps the Zepp source; changing one makes the list the person\'s own', () => {
  const unchanged = toEditable(zepp[0])
  const d1 = editedDetails(row, { exercises: [toExercise(unchanged)!] })
  assert.equal(d1.exercises_source, 'zepp')
  const changed = toEditable(zepp[0]); changed.sets[0].weightKg = '85'
  const d2 = editedDetails(row, { exercises: [toExercise(changed)!] })
  assert.equal(d2.exercises_source, 'app')
  assert.equal(exercisesOf(d2)[0].sets[0].weight_kg, 85)
  // an empty list clears both keys
  const d3 = editedDetails(row, { exercises: [] })
  assert.equal(d3.exercises, undefined); assert.equal(d3.exercises_source, undefined)
})

test('what the watch measured rides along unchanged; a renamed exercise loses its Zepp code', () => {
  const e = toEditable(zepp[0]); e.sets[0].reps = '6'
  const out = toExercise(e)!
  assert.deepEqual([out.sets[0].duration_sec, out.sets[0].avg_hr, out.sets[0].detected_reps, out.exercise_code], [40, 120, 5, 12])
  e.name = 'Incline Bench'
  assert.equal(toExercise(e)!.exercise_code, null)
  e.name = '  Bench Press  '; e.originalName = 'Bench Press'
  assert.equal(toExercise(e)!.exercise_code, 12) // whitespace is not a rename
})

test('a set with no reps or weight is dropped, an exercise with no name or no valid set is dropped whole', () => {
  const e = blankExercise(); e.name = 'Squat'
  assert.equal(toExercise(e), null)
  e.sets = [{ ...blankSet(), reps: '5', weightKg: '100' }, { ...blankSet(), reps: '', weightKg: '90' }, { ...blankSet(), reps: 'x', weightKg: '90' }]
  assert.equal(toExercise(e)!.sets.length, 1)
  e.name = '  '
  assert.equal(toExercise(e), null)
})

test('RPE is a whole number from 0 to 10 or empty; notes are trimmed and an empty note is null', () => {
  assert.deepEqual(sessionPatch(row, { rpe: '7', notes: '  felt heavy ', edits: {} }), { rpe: 7, notes: 'felt heavy', details: row })
  assert.equal(sessionPatch(row, { rpe: '', notes: '', edits: {} }).rpe, null)
  assert.equal(sessionPatch(row, { rpe: '', notes: '   ', edits: {} }).notes, null)
  assert.equal(sessionPatch(row, { rpe: '0', notes: '', edits: {} }).rpe, 0)
  for (const bad of ['11', '-1', '7.5', 'x']) assert.throws(() => sessionPatch(row, { rpe: bad, notes: '', edits: {} }), /RPE/)
})

test('pace for a run, speed for a ride, nothing without a distance', () => {
  assert.equal(paceOrSpeed('run', 50, 10), '5:00 /km')
  assert.equal(paceOrSpeed('run', 40, 8), '5:00 /km')
  assert.equal(paceOrSpeed('ride', 60, 30), '30.0 km/h')
  assert.equal(paceOrSpeed('strength', 60, null), null)
  assert.equal(paceOrSpeed('run', 0, 5), null)
})

test('gear: a recorded list (even an empty one) is kept, an unrecorded set stays unrecorded, and changing gear counts as an edit', () => {
  const before = { exercises: [{ name: 'Deadlift', sets: [{ reps: 5, weight_kg: 150, gear: ['straps'] }, { reps: 5, weight_kg: 100, gear: [] }, { reps: 5, weight_kg: 90 }] }] }
  const ex = exercisesOf(before)
  const form = ex.map(toEditable)
  assert.deepEqual(form[0].sets.map((s) => s.gear), [['straps'], [], null])
  const out = form.map((e) => toExercise(e)!)
  assert.deepEqual(out[0].sets.map((s) => s.gear), [['straps'], [], undefined])
  assert.equal('gear' in out[0].sets[2], false, 'an unrecorded set gets no gear key')
  assert.equal(editedDetails(before, { exercises: out }).exercises_source, undefined, 'nothing changed')
  form[0].sets[2].gear = []
  const changed = editedDetails(before, { exercises: form.map((e) => toExercise(e)!) })
  assert.equal(changed.exercises_source, 'app', 'recording "no gear" on a set is an edit of the person')
  assert.deepEqual(exercisesOf(changed)[0].sets[2].gear, [])
})
