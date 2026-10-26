// Run: node --test supabase/functions/zepp-extract/minuteHr.test.ts  (Node 22+ strips types)
import test from "node:test";
import assert from "node:assert/strict";
import { decodeMinuteHr, plausibleMinuteBpm } from "./minuteHr.ts";

const b64 = (bytes: number[]) => btoa(String.fromCharCode(...bytes));

test("the band's no-reading markers are not heart rates", () => {
  // Synthetic day start: two minutes not worn (255, 254), one with no measurement (0), then three real minutes.
  const pts = decodeMinuteHr(b64([255, 254, 0, 58, 61, 140]));
  assert.deepEqual(pts, [
    { offsetMinutes: 3, bpm: 58 },
    { offsetMinutes: 4, bpm: 61 },
    { offsetMinutes: 5, bpm: 140 },
  ]);
});

test("the minute offset is the byte's position, so gaps stay gaps", () => {
  const bytes = new Array(1440).fill(255);
  bytes[0] = 52;
  bytes[720] = 75;
  bytes[1439] = 49;
  const pts = decodeMinuteHr(b64(bytes))!;
  assert.deepEqual(pts.map((p) => p.offsetMinutes), [0, 720, 1439]);
});

test("values a heart cannot take are dropped, the edges of the range are kept", () => {
  assert.equal(plausibleMinuteBpm(29), false);
  assert.equal(plausibleMinuteBpm(30), true);
  assert.equal(plausibleMinuteBpm(220), true);
  assert.equal(plausibleMinuteBpm(221), false);
  assert.deepEqual(decodeMinuteHr(b64([5, 230, 30, 220])), [
    { offsetMinutes: 2, bpm: 30 },
    { offsetMinutes: 3, bpm: 220 },
  ]);
});

test("a day without a single reading, a missing series and bad base64 give no series", () => {
  assert.equal(decodeMinuteHr(b64(new Array(1440).fill(255))), null);
  assert.equal(decodeMinuteHr(undefined), null);
  assert.equal(decodeMinuteHr(""), null);
  assert.equal(decodeMinuteHr("not base64!"), null);
});
