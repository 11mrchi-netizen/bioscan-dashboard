import assert from "node:assert/strict";
import { test } from "node:test";
import {
  bmi, bodyComposition, compositionFromFatFreeMass, fatFreeMassKg, healthyWeightRangeKg, impedanceIndex, katchMcArdleKcal,
  skeletalMuscleMassKg, totalBodyWaterL, weightChangeSplit, type BodyCompositionInput, type WeighIn,
} from "./bodyComposition.ts";

// Same vectors as BodyCompositionTest.kt. The captured weigh-in: 75.0 kg, 503 ohm, with a 180 cm, 30-year-old man.
const man: BodyCompositionInput = { weightKg: 75, impedanceOhm: 503, heightCm: 180, ageYears: 30, isMale: true };
const woman: BodyCompositionInput = { weightKg: 62, impedanceOhm: 580, heightCm: 165, ageYears: 40, isMale: false };
const near = (a: number | null, b: number, tol: number) => assert.ok(a !== null && Math.abs(a - b) <= tol, `${a} vs ${b} ±${tol}`);

test("BMI and impedance index", () => {
  near(bmi(75, 180), 23.148, 0.001);
  near(impedanceIndex(180, 503), 64.4135, 0.001);
});

test("out-of-range inputs are rejected", () => {
  assert.equal(bmi(75, 90), null);
  assert.equal(impedanceIndex(180, 10), null);
  assert.equal(skeletalMuscleMassKg({ ...man, ageYears: 12 }), null);
});

test("healthy weight range", () => {
  const r = healthyWeightRangeKg(180)!;
  near(r.low, 59.94, 0.01);
  near(r.high, 81, 0.01);
});

test("Janssen 2000 skeletal muscle mass", () => {
  near(skeletalMuscleMassKg(man), 32.627, 0.01);
  near(skeletalMuscleMassKg(woman), 21.085, 0.01);
});

test("Sun 2003 equations, male and female", () => {
  near(fatFreeMassKg(man), 60.749, 0.01);
  near(totalBodyWaterL(man), 43.686, 0.01);
  near(fatFreeMassKg(woman), 44.998, 0.01);
  near(totalBodyWaterL(woman), 31.693, 0.01);
});

test("full composition for the captured weigh-in", () => {
  const c = bodyComposition(man);
  near(c.split!.bodyFatPct, 19.0, 0.05);
  near(c.split!.fatKg, 14.251, 0.01);
  near(c.bmrKcal, 1682.2, 0.5);
  assert.equal(c.qualityOk, true);
  assert.equal(fatFreeMassKg({ ...man, heightCm: 90 }), null);
});

test("a 50 ohm contact glitch yields no split and fails quality", () => {
  const glitch = bodyComposition({ ...man, impedanceOhm: 50 });
  assert.equal(glitch.split, null);
  assert.equal(glitch.qualityOk, false);
});

test("split from fat-free mass and Katch-McArdle", () => {
  const s = compositionFromFatFreeMass(75, 180, 60)!;
  near(s.fatKg, 15, 1e-9);
  near(s.bodyFatPct, 20, 1e-9);
  near(s.ffmi, 18.519, 0.001);
  near(s.fmi, 4.63, 0.001);
  assert.equal(compositionFromFatFreeMass(75, 180, 76), null);
  assert.equal(compositionFromFatFreeMass(75, 180, 20), null);
  assert.equal(katchMcArdleKcal(60), 1666);
});

test("weight change split uses the ends of the window", () => {
  const day = (n: number) => new Date(Date.UTC(2026, 8, 1 + n)).toISOString().slice(0, 10);
  const r = (d: number, w: number, fat: number): WeighIn => ({ date: day(d), weightKg: w, split: { fatFreeKg: w - fat, fatKg: fat, bodyFatPct: (fat / w) * 100, ffmi: 0, fmi: 0 } });
  const readings = [r(0, 80, 20), r(2, 80, 20), r(20, 79, 18.5), r(38, 78, 17), r(40, 78, 17)];
  const s = weightChangeSplit(readings, 45, day(40))!;
  near(s.deltaWeightKg, -2, 1e-9);
  near(s.deltaFatKg, -3, 1e-9);
  near(s.deltaFatFreeKg, 1, 1e-9);
  assert.equal(weightChangeSplit(readings.slice(0, 3), 45, day(40)), null);
  assert.equal(weightChangeSplit(readings, 200, day(40)), null);
});
