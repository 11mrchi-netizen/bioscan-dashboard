// Run: node --test supabase/functions/zepp-extract/hrHistogram.test.ts  (Node 22+ strips types)
import test from "node:test";
import assert from "node:assert/strict";
import { hrHistogram } from "./hrHistogram.ts";

const pts = (xs: [number, number][]) => xs.map(([offsetSeconds, value]) => ({ offsetSeconds, value }));

test("each sample's time runs to the next one, attributed to its own heart rate", () => {
  assert.deepEqual(hrHistogram(pts([[0, 140], [1, 140], [2, 141], [3, 150]])), { "140": 2, "141": 1 });
});

test("a gap counts at most ten seconds, out of order samples are sorted, implausible readings are dropped", () => {
  assert.deepEqual(hrHistogram(pts([[100, 150], [0, 148], [1, 0], [2, 255], [3, 148.6]])), { "148": 1, "149": 10 });
  // 0 -> 1: 148 for 1 s; 1 -> 2: a 0 reading, dropped; 2 -> 3: 255, dropped; 3 -> 100: 149 for 10 s (capped).
});

test("nothing to count gives null", () => {
  assert.equal(hrHistogram(pts([[0, 140]])), null);
  assert.equal(hrHistogram([]), null);
  assert.equal(hrHistogram(null), null);
  assert.equal(hrHistogram(pts([[0, 0], [1, 0]])), null);
});
