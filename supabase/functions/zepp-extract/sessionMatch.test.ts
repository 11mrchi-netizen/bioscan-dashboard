// Run: node --test supabase/functions/zepp-extract/sessionMatch.test.ts  (Node 22+ strips types)
import test from "node:test";
import assert from "node:assert/strict";
import { mergedSessionFields, pickSessionMatch, type SessionCandidate, spanOf } from "./sessionMatch.ts";

const T0 = Date.parse("2026-06-20T08:00:00Z");
const min = (m: number) => T0 + m * 60000;
const zepp = (startMin: number, endMin: number, type = "run") => ({ startMs: min(startMin), endMs: min(endMin), type });
const cand = (id: number, type: string, startMin: number, endMin: number, linkedTrackId: string | null = null): SessionCandidate =>
  ({ id, type, startMs: min(startMin), endMs: min(endMin), linkedTrackId });

test("a session starting within two minutes matches, whatever its type (the old rule, kept)", () => {
  assert.deepEqual(pickSessionMatch(zepp(0, 60), [cand(1, "other", 1, 61)], "t1"), { id: 1, kind: "start" });
  assert.deepEqual(pickSessionMatch(zepp(0, 60), [cand(1, "run", 3, 63), cand(2, "run", -1.5, 59)], "t1"), { id: 2, kind: "start" });
});

test("a Health Connect copy starting minutes earlier and running longer is the same workout", () => {
  // Run: HC starts 28 min earlier, 129 min against Zepp's 102 (the 20 Jun shape).
  assert.deepEqual(pickSessionMatch(zepp(0, 102), [cand(7, "run", -28, 101)], "t1"), { id: 7, kind: "overlap" });
  // Strength: HC starts 20 min earlier, 70 min against Zepp's 50.
  assert.deepEqual(pickSessionMatch(zepp(0, 50, "strength"), [cand(8, "strength", -20, 50)], "t1"), { id: 8, kind: "overlap" });
});

test("fragments, long containing sessions and other types are not the same workout", () => {
  // A 6-minute HC run inside a 70-minute Zepp run.
  assert.equal(pickSessionMatch(zepp(0, 70), [cand(1, "run", 51, 57)], "t1"), null);
  // A 189-minute HC ride containing a 71-minute Zepp ride.
  assert.equal(pickSessionMatch(zepp(0, 71, "ride"), [cand(2, "ride", -118, 71)], "t1"), null);
  // A walk over the same time as a run.
  assert.equal(pickSessionMatch(zepp(0, 60), [cand(3, "walk", -5, 60)], "t1"), null);
});

test("an imported log with a placeholder time matches only by start, never by overlap", () => {
  const notion = { ...cand(9, "strength", -20, 40), deviceTimed: false };
  assert.equal(pickSessionMatch(zepp(0, 60, "strength"), [notion], "t1"), null);
  assert.deepEqual(pickSessionMatch(zepp(0, 60, "strength"), [{ ...notion, startMs: min(1) }], "t1"), { id: 9, kind: "start" });
});

test("a session linked to another Zepp workout is never taken; one linked to this workout is", () => {
  assert.equal(pickSessionMatch(zepp(0, 60), [cand(1, "run", 0, 60, "t2")], "t1"), null);
  assert.deepEqual(pickSessionMatch(zepp(0, 60), [cand(1, "run", 0, 60, "t1")], "t1"), { id: 1, kind: "start" });
});

test("the largest overlap wins, then the closest start", () => {
  const picked = pickSessionMatch(zepp(0, 50, "strength"), [cand(1, "strength", -20, 45), cand(2, "strength", -10, 52)], "t1");
  assert.deepEqual(picked, { id: 2, kind: "overlap" });
});

test("a row's span: end_time, else start plus duration", () => {
  assert.deepEqual(spanOf({ start_time: "2026-06-20T08:00:00Z", end_time: "2026-06-20T09:00:00Z" }), { startMs: T0, endMs: min(60) });
  assert.deepEqual(spanOf({ start_time: "2026-06-20T08:00:00Z", end_time: null, duration_min: 30 }), { startMs: T0, endMs: min(30) });
  assert.equal(spanOf({ start_time: null }), null);
});

test("the kept session takes Zepp's measurements and timing, never a null", () => {
  const copy = { distance_km: 10.41, avg_hr: 150, max_hr: null, start_time: "2026-06-20T08:28:00Z", end_time: "2026-06-20T10:10:00Z", duration_min: 102 };
  const kept = { distance_km: 27.95, avg_hr: 177, max_hr: 190, start_time: "2026-06-20T08:00:00Z", duration_min: 129 };
  assert.deepEqual(mergedSessionFields(copy, kept), {
    distance_km: 10.41, avg_hr: 150, start_time: "2026-06-20T08:28:00Z", end_time: "2026-06-20T10:10:00Z", duration_min: 102,
  });
});

test("RPE, notes and exercises from the copy only fill what the kept session lacks", () => {
  const zeppList = { exercises: [{ name: "Squat", exercise_code: 1, sets: [] }], exercises_source: "zepp", zepp_fingerprint: "z1-00000001-1" };
  const own = { exercises: [{ name: "Bench", sets: [] }], foo: 1 };
  // The kept session's own list stays.
  assert.deepEqual(mergedSessionFields({ details: zeppList, rpe: 7 }, { details: own, rpe: 8 }), {});
  // An empty kept session takes Zepp's list, keeping its other detail keys, and the RPE.
  assert.deepEqual(mergedSessionFields({ details: zeppList, rpe: 7, notes: "felt good" }, { details: { foo: 1 }, rpe: null, notes: "" }), {
    rpe: 7, notes: "felt good", details: { foo: 1, ...zeppList },
  });
});
