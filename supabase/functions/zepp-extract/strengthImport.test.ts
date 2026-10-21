// Run: node --test supabase/functions/zepp-extract/strengthImport.test.ts  (Node 22+ strips types)
// Synthetic laps only.
import test from "node:test";
import assert from "node:assert/strict";
import {
  buildZeppExercises, doneSets, exercisesFingerprint, exercisesSource, parseLapSets, planStrengthDetails, type ZeppExercise,
} from "./strengthImport.ts";

// One lap in Zepp's comma layout: [0]=idx [1]=duration [4]=avg_hr [15]=detected reps [21]=kg [22]=reps [26]=status [28]=code.
function lap(idx: number, code: number, reps: number, kg: number, status = 0, duration = 40): string {
  const c = new Array(29).fill("0");
  c[0] = String(idx); c[1] = String(duration); c[4] = "120"; c[15] = String(reps);
  c[21] = String(kg); c[22] = String(reps); c[26] = String(status); c[28] = String(code);
  return c.join(",");
}
const NAMES: Record<number, string> = { 3: "Barbell Squats", 6: "Bench Press", 61: "Deadlift" };
const nameOf = (code: number) => NAMES[code];
const exercisesFrom = (laps: string[]) => buildZeppExercises(doneSets(parseLapSets(laps.join(";"))), nameOf);

// The workout as first synced: nothing picked on the watch, every set under one default exercise.
const before = exercisesFrom([lap(0, 3, 5, 60), lap(1, 3, 5, 60), lap(2, 3, 5, 80), lap(3, 3, 5, 80)]);
// The same workout after correcting the exercises in the Zepp app.
const after = exercisesFrom([lap(0, 3, 5, 60), lap(1, 3, 5, 60), lap(2, 6, 5, 80), lap(3, 6, 5, 80)]);

test("laps become exercises in order, skipped planned sets dropped", () => {
  const ex = exercisesFrom([lap(0, 3, 5, 60), lap(1, 3, 5, 60), lap(2, 6, 8, 40, 2), lap(3, 6, 8, 40), lap(4, 3, 3, 100)]);
  assert.deepEqual(ex.map((e) => [e.name, e.exercise_code, e.sets.length]), [["Barbell Squats", 3, 2], ["Bench Press", 6, 1], ["Barbell Squats", 3, 1]]);
  assert.deepEqual(ex[0].sets[0], { reps: 5, weight_kg: 60, duration_sec: 40, avg_hr: 120, detected_reps: 5 });
  assert.equal(exercisesFrom([lap(0, 999, 5, 0)])[0].name, "Exercise 999");
});

test("the fingerprint follows exercises, reps and weight, not measured duration", () => {
  assert.notEqual(exercisesFingerprint(before), exercisesFingerprint(after));
  const longerSets = exercisesFrom([lap(0, 3, 5, 60, 0, 55), lap(1, 3, 5, 60, 0, 55), lap(2, 3, 5, 80, 0, 55), lap(3, 3, 5, 80, 0, 55)]);
  assert.equal(exercisesFingerprint(longerSets), exercisesFingerprint(before));
  assert.match(exercisesFingerprint(before), /^z1-[0-9a-f]{8}-1$/);
});

test("a correction made in Zepp after the first sync replaces the exercises Zepp wrote", () => {
  const first = planStrengthDetails({}, before);
  assert.equal(first.action, "written");
  const stored = first.details!;
  assert.equal(stored.exercises_source, "zepp");
  // Re-sync of the unchanged workout: nothing to write.
  assert.equal(planStrengthDetails(stored, before).action, "unchanged");
  // Re-sync after the correction in Zepp: written.
  const second = planStrengthDetails(stored, after);
  assert.equal(second.action, "written");
  assert.deepEqual((second.details!.exercises as ZeppExercise[]).map((e) => e.name), ["Barbell Squats", "Bench Press"]);
  assert.equal(second.details!.zepp_fingerprint, exercisesFingerprint(after));
});

test("exercises changed in the app are never overwritten by a sync", () => {
  const edited = { exercises: [{ name: "Front Squat", sets: [{ reps: 5, weight_kg: 60 }] }], exercises_source: "app", zepp_fingerprint: exercisesFingerprint(before) };
  assert.deepEqual(planStrengthDetails(edited, after), { details: null, action: "kept_app_edit" });
});

test("rows from before the markers: a list with a code on every exercise is Zepp's, one without is the person's", () => {
  const oldZepp = { exercises: before.map((e) => ({ ...e })) };
  assert.equal(exercisesSource(oldZepp), "zepp");
  assert.equal(planStrengthDetails(oldZepp, after).action, "written");
  const oldEdit = { exercises: [{ name: "Back Squat", sets: [{ reps: 5, weight_kg: 60 }] }] };
  assert.equal(exercisesSource(oldEdit), "app");
  assert.equal(planStrengthDetails(oldEdit, after).action, "kept_app_edit");
  assert.equal(exercisesSource({}), "none");
  assert.equal(exercisesSource({ exercises: [] }), "none");
});

test("other keys of details are kept, and a workout without done sets writes nothing", () => {
  const plan = planStrengthDetails({ route_type: null, note_key: "kept" }, after);
  assert.equal(plan.details!.note_key, "kept");
  assert.deepEqual(planStrengthDetails({}, []), { details: null, action: "no_sets" });
});
