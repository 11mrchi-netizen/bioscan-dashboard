// Run: node --test supabase/functions/zepp-extract/decodeSeries.test.ts  (Node 22+ strips types)
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  decodeAltitude, decodeCadenceAndVerticalRatio, decodeHeartRate, decodePauses, decodeSpeedKmh, decodeTrack, gpsOffsets,
  workoutStartSec,
} from "./decodeSeries.ts";

// A synthetic workout shaped like a real paused one: 1 s steps, a 2 s gap at second 20, a 600 s pause at second 50,
// then 30 s more. Every series below covers the same 0..682 s.
const STEPS = [0, ...Array.from({ length: 49 }, (_, i) => (i === 19 ? 2 : 1)), 601, ...Array.from({ length: 30 }, () => 1)];
const ELAPSED = STEPS.reduce((a, b) => a + b, 0);

function hrRaw(): string {
  return STEPS.map((s, i) => `${i === 0 ? "0" : s === 1 ? "" : String(s)},${i === 0 ? 120 : (i % 2 ? 1 : -1)}`).join(";") + ";";
}

test("heart rate follows its own steps: empty means 1 s, a gap and a pause move the clock", () => {
  const hr = decodeHeartRate(hrRaw());
  assert.equal(hr.length, STEPS.length);
  assert.equal(hr[0].offsetSeconds, 0);
  assert.equal(hr[0].value, 120);
  assert.equal(hr[21].offsetSeconds, 22); // after the 2 s gap
  assert.equal(hr[50].offsetSeconds, 50 + 1 + 600); // across the pause
  assert.equal(hr[hr.length - 1].offsetSeconds, ELAPSED);
});

test("speed and gait end where heart rate ends", () => {
  const speed = decodeSpeedKmh(STEPS.map((s) => `${s},2.5`).join(";"));
  assert.equal(speed[speed.length - 1].offsetSeconds, ELAPSED);
  assert.equal(speed[0].value, 9);
  const gait = decodeCadenceAndVerticalRatio(STEPS.map((s) => `${s},1,85,170`).join(";"));
  assert.equal(gait.cadenceSpm[gait.cadenceSpm.length - 1].offsetSeconds, ELAPSED);
  assert.equal(gait.verticalStrideRatioPct[0].value, 8.5);
});

test("GPS points and altitude are timed by the time field, distance unchanged", () => {
  const n = STEPS.length;
  const time = STEPS.map((s, i) => (i === 0 ? 1 : s)).join(";");
  const offsets = gpsOffsets(time, n);
  assert.equal(offsets[0], 1);
  assert.equal(offsets[n - 1], ELAPSED + 1);
  // 1e-5 degrees of latitude per point, about 1.11 m.
  const track = decodeTrack(["2500000000,12100000000", ...Array.from({ length: n - 1 }, () => "1000,0")].join(";"), offsets);
  assert.equal(track.routePoints.length, n);
  assert.equal(track.routePoints[n - 1].offsetSeconds, ELAPSED + 1);
  assert.ok(Math.abs(track.distanceKm[n - 1].value - (n - 1) * 0.00111195) < 1e-4);
  // The no-fix marker is dropped and the other points keep their own times.
  const alt = decodeAltitude(["-2000467", ...Array.from({ length: n - 1 }, () => "65900")].join(";"), offsets);
  assert.equal(alt.length, n - 1);
  assert.equal(alt[0].offsetSeconds, offsets[1]);
  assert.equal(alt[0].value, 659);
});

test("without a time field, one second per GPS point as before", () => {
  assert.deepEqual(gpsOffsets(undefined, 4), [0, 1, 2, 3]);
  assert.deepEqual(gpsOffsets("2;1", 4), [2, 3, 4, 5]);
});

test("pauses as offsets from the track start", () => {
  assert.deepEqual(decodePauses("1700000600,600,-1,-1,2", 1700000000), [{ startOffset: 600, durationSec: 600 }]);
  assert.deepEqual(decodePauses("1700000600,600;1700003000,120,-1", 1700000000).length, 2);
  assert.deepEqual(decodePauses("", 1700000000), []);
  assert.deepEqual(decodePauses("1700000600,600", null), []);
});

test("a paused workout starts at its trackid, not end minus moving time", () => {
  // 600 s paused, 3,600 s moving: the start is 4,200 s before the end.
  assert.equal(workoutStartSec("1700000000", 1700004200, 3600), 1700000000);
  // No pause: both agree.
  assert.equal(workoutStartSec("1700000600", 1700004200, 3600), 1700000600);
  // An id that is not a plausible start falls back.
  assert.equal(workoutStartSec("12345", 1700004200, 3600), 1700000600);
  assert.equal(workoutStartSec("1700002000", 1700004200, 3600), 1700000600);
});

test("old one-value entries still decode", () => {
  const hr = decodeHeartRate("100;1;-2");
  assert.deepEqual(hr.map((p) => [p.offsetSeconds, p.value]), [[0, 100], [1, 101], [2, 99]]);
});
