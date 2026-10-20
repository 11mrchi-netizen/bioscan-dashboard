// Run: node --test supabase/functions/zepp-extract/workoutDistance.test.ts  (Node 22+ strips types)
import test from "node:test";
import assert from "node:assert/strict";
import { exerciseTypeForZeppSport, summaryDistanceM, takesDistance, trailTaggedDetails, zeppDistanceKm } from "./workoutDistance.ts";

test("the summary distance comes from highPrecisionDistance or dis, never a missing `distance` key", () => {
  // Shape of a real sport_history entry (synthetic values).
  assert.equal(summaryDistanceM({ dis: "18360.0", highPrecisionDistance: 18360.32 }), 18360.32);
  assert.equal(summaryDistanceM({ dis: "7810.0" }), 7810);
  assert.equal(summaryDistanceM({ distance: 5000 }), null);
  assert.equal(summaryDistanceM({ dis: "0.0", highPrecisionDistance: 0 }), null);
  assert.equal(summaryDistanceM({ dis: "" }), null);
});

test("GPS distance wins; a workout without a track (treadmill) uses the summary", () => {
  assert.equal(zeppDistanceKm(18.26, 18360), 18.26);
  assert.equal(zeppDistanceKm(undefined, 7810), 7.81);
  assert.equal(zeppDistanceKm(0, 7810), 7.81);
  assert.equal(zeppDistanceKm(null, null), null);
  assert.equal(zeppDistanceKm(Number.NaN, 0), null);
});

test("sport codes map to session types, the treadmill to a run", () => {
  assert.deepEqual(["1", "7", "8", "9", "52", "6"].map(exerciseTypeForZeppSport), ["run", "run", "run", "ride", "strength", "other"]);
  assert.equal(takesDistance("run"), true);
  assert.equal(takesDistance("strength"), false);
});

test("a Zepp trail run is tagged trail unless its session already has a route type", () => {
  assert.deepEqual(trailTaggedDetails(null, "7"), { route_type: "trail" });
  assert.deepEqual(trailTaggedDetails({ run_type: "long", route_type: null }, "7"), { run_type: "long", route_type: "trail" });
  assert.equal(trailTaggedDetails({ route_type: "road" }, "7"), null); // a tag set by hand stays
  assert.equal(trailTaggedDetails({ route_type: "trail" }, "7"), null); // already tagged: no write
  assert.equal(trailTaggedDetails(null, "1"), null); // a road run is left alone
});
