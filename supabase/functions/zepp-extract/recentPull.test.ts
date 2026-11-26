// Run: node --test supabase/functions/zepp-extract/recentPull.test.ts  (Node 22+ strips types)
import test from "node:test";
import assert from "node:assert/strict";
import { RECENT_DAYS_BACK, recentDates } from "./recentPull.ts";

test("uses the local calendar, not the UTC date", () => {
  // 07:30 local on 25 Nov in UTC+8 is still 24 Nov in UTC.
  const now = new Date("2026-11-24T23:30:00Z");
  assert.deepEqual(recentDates(now, 480), ["2026-11-22", "2026-11-23", "2026-11-24", "2026-11-25"]);
});

test("covers today plus the days before it", () => {
  const days = recentDates(new Date("2026-11-25T12:00:00Z"), 480);
  assert.equal(days.length, RECENT_DAYS_BACK + 1);
  assert.equal(days.at(-1), "2026-11-25");
});

test("crosses month and year boundaries", () => {
  assert.deepEqual(recentDates(new Date("2026-01-01T10:00:00Z"), 0), ["2025-12-29", "2025-12-30", "2025-12-31", "2026-01-01"]);
});

test("without a known offset, the range holds the local today in any zone", () => {
  const now = new Date("2026-11-24T23:30:00Z");
  const days = recentDates(now, null);
  assert.ok(days.includes("2026-11-24"));
  assert.ok(days.includes("2026-11-25"));
  assert.equal(days[0], "2026-11-21");
});
