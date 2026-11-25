// Run: node --test supabase/functions/zepp-extract/syncScope.test.ts  (Node 22+ strips types)
import test from "node:test";
import assert from "node:assert/strict";
import { DETAIL_SETTLE_MS, isSettled, orphanWindow } from "./syncScope.ts";

test("the orphan window covers the synced days with a margin each side", () => {
  assert.deepEqual(orphanWindow(["2026-11-24", "2026-11-22", "2026-11-25"]), {
    fromIso: "2026-11-20T00:00:00.000Z",
    toIso: "2026-11-28T00:00:00.000Z",
  });
});

test("no dates, no window", () => {
  assert.equal(orphanWindow([]), null);
  assert.equal(orphanWindow(["not a date"]), null);
});

const end = "2026-11-24T07:00:00Z";
const later = (ms: number) => new Date(Date.parse(end) + ms).toISOString();
const row = { zepp_track_id: "1", exercise_session_id: 5, end_time: end, fetched_at: later(DETAIL_SETTLE_MS), decoder_version: "2" };

test("a linked detail decoded now and fetched after it settled is skipped", () => {
  assert.equal(isSettled(row, 2), true);
});

test("fetched too soon after the workout, it is fetched again", () => {
  assert.equal(isSettled({ ...row, fetched_at: later(DETAIL_SETTLE_MS - 1) }, 2), false);
});

test("not linked to a session, it is fetched and reconciled again", () => {
  assert.equal(isSettled({ ...row, exercise_session_id: null }, 2), false);
});

test("an older decoder, or none, is fetched again", () => {
  assert.equal(isSettled({ ...row, decoder_version: "1" }, 2), false);
  assert.equal(isSettled({ ...row, decoder_version: null }, 2), false);
});

test("missing times are fetched again", () => {
  assert.equal(isSettled({ ...row, end_time: null }, 2), false);
  assert.equal(isSettled({ ...row, fetched_at: null }, 2), false);
});
