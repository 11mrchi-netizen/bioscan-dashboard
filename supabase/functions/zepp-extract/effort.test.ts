// Run: node supabase/functions/zepp-extract/effort.test.ts  (Node 22+ strips types)
import assert from "node:assert/strict";
import { computeEffort, minettiCost, type Point } from "./effort.ts";

function run(opts: { seconds: number; speedMs: number | ((t: number) => number); grade?: number; hr?: number | ((t: number) => number); lt?: number | null }) {
  const speedFn = typeof opts.speedMs === "number" ? () => opts.speedMs as number : opts.speedMs;
  const hrFn = typeof opts.hr === "number" || opts.hr === undefined ? () => (opts.hr as number | undefined) ?? 150 : opts.hr;
  const speedKmh: Point[] = [], altitudeM: Point[] = [], distanceKm: Point[] = [], heartRate: Point[] = [];
  let meters = 0;
  for (let t = 0; t < opts.seconds; t++) {
    meters += speedFn(t);
    speedKmh.push({ offsetSeconds: t, value: speedFn(t) * 3.6 });
    distanceKm.push({ offsetSeconds: t, value: meters / 1000 });
    altitudeM.push({ offsetSeconds: t, value: (opts.grade ?? 0) * meters });
    heartRate.push({ offsetSeconds: t, value: hrFn(t) });
  }
  return computeEffort({ speedKmh, altitudeM, distanceKm, heartRate, lactateThresholdHrBpm: opts.lt ?? null });
}

// Flat run: GAP == actual pace, EF == (m/min) / bpm.
{
  const r = run({ seconds: 1800, speedMs: 10 / 3.6, hr: 150 }); // 10 km/h = 6:00/km
  assert.ok(Math.abs(r.gapMinPerKm! - 6.0) < 0.01, `flat GAP ${r.gapMinPerKm}`);
  assert.ok(Math.abs(r.efficiencyFactor! - (10000 / 60) / 150) < 0.005, `flat EF ${r.efficiencyFactor}`);
  assert.ok(Math.abs(r.hrDecouplingPct!) < 0.5, `flat decoupling ${r.hrDecouplingPct}`);
  assert.ok(Math.abs(r.smoothedAscentM!) < 1);
}

// Constant 5% climb: GAP = pace x cost(0)/cost(0.05) (Minetti), same as Kotlin's gradeAdjustedPaceMinPerKm.
{
  const r = run({ seconds: 1800, speedMs: 10 / 3.6, grade: 0.05, hr: 150 });
  const expected = 6.0 * (minettiCost(0) / minettiCost(0.05));
  assert.ok(Math.abs(r.gapMinPerKm! - expected) < 0.05, `climb GAP ${r.gapMinPerKm} vs ${expected}`);
  assert.ok(r.gapMinPerKm! < 6.0);
}

// Same HR but slowing in the second half => positive decoupling.
{
  const r = run({ seconds: 2400, speedMs: (t) => (t < 1200 ? 3.0 : 2.7), hr: 150 });
  assert.ok(r.hrDecouplingPct! > 5, `decoupling ${r.hrDecouplingPct}`);
}

// Above the watch's own lactate-threshold HR => not an aerobic run, no EF.
{
  const r = run({ seconds: 1800, speedMs: 3, hr: 165, lt: 160 });
  assert.equal(r.efficiencyFactor, null);
  assert.ok(r.gapMinPerKm !== null);
}

// Under 20 minutes => GAP yes, EF no.
{
  const r = run({ seconds: 900, speedMs: 3, hr: 140 });
  assert.equal(r.efficiencyFactor, null);
  assert.ok(r.gapMinPerKm !== null);
}

// Steady flat run at 3 m/s: GAP is exactly the actual pace.
{
  const r = run({ seconds: 1800, speedMs: 3, hr: 140 });
  assert.ok(Math.abs(r.gapMinPerKm! - 1000 / 3 / 60) < 0.01);
}

console.log("effort.test.ts: all passed");
