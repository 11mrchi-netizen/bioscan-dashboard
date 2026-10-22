// Run: node --test supabase/functions/zepp-extract/historyPull.test.ts  (Node 22+ strips types)
import test from "node:test";
import assert from "node:assert/strict";
import { afterWindow, HISTORY_FLOOR, HISTORY_STOP_AFTER_EMPTY, type HistoryState, windowDates } from "./historyPull.ts";

const start = (nextEnd: string): HistoryState => ({
  next_end: nextEnd, empty_streak: 0, workouts: 0, band_days: 0, weeks_done: 0, oldest_with_data: null, done: false,
});

test("weeks walk back from the start day without gaps or overlaps", () => {
  const s0 = start("2026-10-16");
  assert.deepEqual(windowDates(s0), ["2026-10-10", "2026-10-11", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16"]);
  const s1 = afterWindow(s0, { workouts: 4, bandDays: 7 });
  assert.equal(windowDates(s1)[6], "2026-10-09");
  assert.equal(s1.oldest_with_data, "2026-10-10");
  assert.equal(s1.workouts, 4);
  assert.equal(s1.band_days, 7);
  assert.equal(s1.weeks_done, 1);
  // Across a month and a year boundary.
  assert.deepEqual(windowDates(start("2026-01-03")), ["2025-12-28", "2025-12-29", "2025-12-30", "2025-12-31", "2026-01-01", "2026-01-02", "2026-01-03"]);
});

test("the pull ends after eight empty weeks in a row; a shorter break does not end it", () => {
  let s = afterWindow(start("2026-10-16"), { workouts: 1, bandDays: 7 });
  for (let i = 0; i < HISTORY_STOP_AFTER_EMPTY - 1; i++) s = afterWindow(s, { workouts: 0, bandDays: 0 });
  assert.equal(s.done, false);
  s = afterWindow(s, { workouts: 0, bandDays: 3 });
  assert.equal(s.empty_streak, 0);
  for (let i = 0; i < HISTORY_STOP_AFTER_EMPTY; i++) s = afterWindow(s, { workouts: 0, bandDays: 0 });
  assert.equal(s.done, true);
  assert.equal(s.band_days, 10);
});

test("the pull never goes before the floor", () => {
  const s = afterWindow(start("2015-01-05"), { workouts: 1, bandDays: 1 });
  assert.ok(s.next_end < HISTORY_FLOOR);
  assert.equal(s.done, true);
});
