// Run: node --test supabase/functions/zepp-extract/redecode.test.ts  (Node 22+ strips types)
import assert from "node:assert/strict";
import { test } from "node:test";
import { redecodeReport, sessionStartMatches } from "./redecode.ts";

const pts = (...offsets: number[]) => offsets.map((offsetSeconds) => ({ offsetSeconds }));

test("a paused workout's start moves back by its pause", () => {
  // Stored: 08:10-09:10 (moving time 3,600 s). Elapsed 4,200 s with a 600 s pause, so it really started at 08:00.
  const r = redecodeReport(
    { heartRate: pts(0, 3500) },
    { heartRate: pts(0, 4200), routePoints: pts(1, 4199), pauses: [{ startOffset: 1800, durationSec: 600 }] },
    "2026-01-10T08:10:00.000Z",
    "2026-01-10T09:10:00.000Z",
  );
  assert.equal(r.hrEndOld, 3500);
  assert.equal(r.hrEndNew, 4200);
  assert.equal(r.elapsedSec, 4200);
  assert.equal(r.movingSec, 3600);
  assert.equal(r.pauses, 1);
  assert.equal(r.pausedSec, 600);
  assert.equal(r.newStartIso, "2026-01-10T08:00:00.000Z");
});

test("no pause, or a difference of seconds: the start stays", () => {
  const unpaused = redecodeReport(null, { heartRate: pts(0, 3630) }, "2026-01-10T08:00:00.000Z", "2026-01-10T09:00:00.000Z");
  assert.equal(unpaused.newStartIso, null);
  const small = redecodeReport(
    null, { heartRate: pts(0, 3630), pauses: [{ startOffset: 100, durationSec: 30 }] },
    "2026-01-10T08:00:00.000Z", "2026-01-10T09:00:00.000Z",
  );
  assert.equal(small.newStartIso, null);
});

test("a session is moved only when it still has the workout's old start", () => {
  assert.equal(sessionStartMatches("2026-01-10T08:10:30.000Z", "2026-01-10T08:10:00.000Z"), true);
  assert.equal(sessionStartMatches("2026-01-10T08:00:00.000Z", "2026-01-10T08:10:00.000Z"), false);
  assert.equal(sessionStartMatches(null, "2026-01-10T08:10:00.000Z"), false);
});
