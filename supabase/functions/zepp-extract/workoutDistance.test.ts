// Run: node --test supabase/functions/zepp-extract/workoutDistance.test.ts  (Node 22+ strips types)
import test from "node:test";
import assert from "node:assert/strict";
import {
  avgSpeedKmhFromPace, exerciseTypeForZeppSport, summaryDistanceM, takesDistance, trailTaggedDetails, workoutAvgSpeedKmh, zeppDistanceKm,
} from "./workoutDistance.ts";

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

test("avg_pace is seconds per metre: a 6:02/km run is 9.9 km/h, never thousands", () => {
  // 0.362 s/m is what a 10.0 km run in 60:25 gives (synthetic, shaped like the summary).
  assert.ok(Math.abs(avgSpeedKmhFromPace(0.362, "run")! - 9.945) < 0.01);
  assert.ok(Math.abs(avgSpeedKmhFromPace(0.777, "run")! - 4.633) < 0.01);
  assert.equal(avgSpeedKmhFromPace(0, "run"), null);
  assert.equal(avgSpeedKmhFromPace(null, "run"), null);
  // A value read in the wrong unit (seconds per km) would be implausible and is dropped.
  assert.equal(avgSpeedKmhFromPace(0.05, "run"), null);
  assert.ok(avgSpeedKmhFromPace(0.05, "ride")! > 70);
});

test("average speed is distance over moving time; avg_pace only without them", () => {
  // Synthetic interval session: 7.4 km in 41 moving minutes is 10.8 km/h, though avg_pace says 8:28/km.
  assert.ok(Math.abs(workoutAvgSpeedKmh(7.4, 41, 0.508, "run")! - 10.83) < 0.01);
  assert.ok(Math.abs(workoutAvgSpeedKmh(null, 41, 0.362, "run")! - 9.945) < 0.01);
  assert.ok(Math.abs(workoutAvgSpeedKmh(7.4, 0, 0.362, "run")! - 9.945) < 0.01);
  // An unbelievable distance over time falls back too, and with nothing believable there is no speed.
  assert.ok(Math.abs(workoutAvgSpeedKmh(70, 41, 0.362, "run")! - 9.945) < 0.01);
  assert.equal(workoutAvgSpeedKmh(70, 41, null, "run"), null);
});
