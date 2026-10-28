// Run: node --test supabase/functions/zepp-extract/sleepStages.test.ts  (Node 22+ strips types)
import test from "node:test";
import assert from "node:assert/strict";
import { compactSleepStages, tzOffsetMinutes } from "./sleepStages.ts";

test("stages become [offset from onset, inclusive duration, mode]", () => {
  // From a real night: light 1527..1533, deep 1534..1591, then an awake minute.
  const out = compactSleepStages([
    { start: 1534, stop: 1591, mode: 5 },
    { start: 1527, stop: 1533, mode: 4 },
    { start: 1592, stop: 1592, mode: 7 },
  ]);
  assert.deepEqual(out, [[0, 7, 4], [7, 58, 5], [65, 1, 7]]);
});

test("unknown modes, bad bounds and non-arrays are dropped", () => {
  assert.equal(compactSleepStages(undefined), null);
  assert.equal(compactSleepStages([]), null);
  assert.equal(compactSleepStages([{ start: 1, stop: 0, mode: 4 }, { start: 1, stop: 2, mode: 9 }]), null);
  assert.deepEqual(compactSleepStages([{ start: 10, stop: 12, mode: 8 }, { start: 13, stop: 12, mode: 4 }]), [[0, 3, 8]]);
});

test("the stored durations add up to the night", () => {
  const out = compactSleepStages([{ start: 100, stop: 109, mode: 4 }, { start: 110, stop: 129, mode: 5 }])!;
  assert.equal(out.reduce((a, s) => a + s[1], 0), 30);
});

test("timezone seconds become minutes", () => {
  assert.equal(tzOffsetMinutes("28800"), 480);
  assert.equal(tzOffsetMinutes(undefined), null);
  assert.equal(tzOffsetMinutes(""), null);
});
