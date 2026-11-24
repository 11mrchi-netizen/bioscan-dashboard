// Port of the Android domain/fuel/SweatProfile.kt: how much the person sweats (tests, or a prior from weight, effort and temperature) and the
// fluid and sodium a session asks for. Parity by fixture (RaceFuelParityTest).

import type { Intensity } from './day.ts'
import { type HeatBand, raisesFluid } from './heat.ts'
import { roundToInt } from './plates.ts'

export interface SweatTest {
  date: string; sessionType: string; intensity: Intensity; durationMin: number; weightBeforeKg: number; weightAfterKg: number; fluidInMl: number
  urineMl?: number; tempC?: number | null; humidityPct?: number | null
}

export function sweatRateMlPerHour(t: SweatTest): number | null {
  if (t.durationMin <= 0) return null
  const lossMl = (t.weightBeforeKg - t.weightAfterKg) * 1000 + t.fluidInMl - (t.urineMl ?? 0)
  const rate = lossMl / (t.durationMin / 60)
  return rate >= 100 && rate <= 4000 ? rate : null
}

export const bodyMassLossPct = (t: SweatTest) => ((t.weightBeforeKg - t.weightAfterKg) / t.weightBeforeKg) * 100

function priorMlPerHour(intensity: Intensity, type: string, tempC: number, weightKg: number): number {
  const base = (intensity === 'EASY' ? 500 : intensity === 'MODERATE' ? 800 : 1000) * (type === 'run' || type === 'hike' ? 1 : 0.8) * (weightKg / 70)
  const tempFactor = tempC >= 20 ? 1 + 0.03 * (tempC - 20) : Math.max(0.6, 1 - 0.02 * (20 - tempC))
  return Math.max(300, base * tempFactor)
}

export interface SweatProfile { tests: SweatTest[]; ratio: number; testCount: number }
export const sweatSpread = (p: SweatProfile) => (p.testCount >= 3 ? 0.15 : p.testCount >= 1 ? 0.25 : 0.4)
export interface SweatEstimate { mlPerHour: number; lowMlPerHour: number; highMlPerHour: number; basis: string }
export const DEFAULT_TEST_TEMP_C = 18

export function sweatProfile(tests: SweatTest[], _weightKg: number): SweatProfile {
  const ratios: number[] = []
  for (const t of tests) {
    const m = sweatRateMlPerHour(t)
    if (m == null) continue
    ratios.push(m / priorMlPerHour(t.intensity, t.sessionType, t.tempC ?? DEFAULT_TEST_TEMP_C, t.weightBeforeKg))
  }
  ratios.sort((a, b) => a - b)
  const ratio = ratios.length === 0 ? 1 : Math.min(Math.max(ratios[Math.trunc(ratios.length / 2)], 0.4), 2.5)
  return { tests, ratio, testCount: ratios.length }
}

export function predictSweatRate(profile: SweatProfile, intensity: Intensity, type: string, tempC: number, weightKg: number): SweatEstimate {
  const mid = priorMlPerHour(intensity, type, tempC, weightKg) * profile.ratio
  const basis = profile.testCount === 0
    ? 'an estimate from body weight, effort and temperature (no sweat test yet)'
    : `your ${profile.testCount} sweat test${profile.testCount === 1 ? '' : 's'}, adjusted to ${roundToInt(tempC)} °C`
  const s = sweatSpread(profile)
  return { mlPerHour: mid, lowMlPerHour: mid * (1 - s), highMlPerHour: mid * (1 + s), basis }
}

export interface FluidPlan { mlPerHourLow: number; mlPerHourHigh: number; sodiumMgPerL: number; note: string }
export const MAX_LOSS_SHARE = 0.02
export const MAX_REPLACEMENT_SHARE = 0.8

export function fluidPlan(sweat: SweatEstimate, durationMin: number, weightKg: number, heat: HeatBand, saltySweater: boolean): FluidPlan {
  const hours = durationMin / 60
  const sodium = saltySweater ? 1000 : heat === 'HOT' || heat === 'VERY_HOT' ? 700 : 500
  if (durationMin < 60 && !raisesFluid(heat)) return { mlPerHourLow: 0, mlPerHourHigh: Math.min(500, sweat.mlPerHour * 0.5), sodiumMgPerL: 0, note: 'Under an hour: drink to thirst.' }
  const allowedLossMlPerH = (MAX_LOSS_SHARE * weightKg * 1000) / Math.max(hours, 1)
  const needLow = Math.max(0, sweat.lowMlPerHour - allowedLossMlPerH)
  const needMid = Math.max(0, sweat.mlPerHour - allowedLossMlPerH)
  const cap = sweat.lowMlPerHour * MAX_REPLACEMENT_SHARE
  const high = Math.min(cap, Math.max(needMid, 400))
  const low = Math.min(high, Math.max(needLow, 300))
  const note = `About ${roundToInt((low + high) / 2)} ml an hour against a sweat rate of ${roundToInt(sweat.mlPerHour)} (${sweat.basis}). Never more than you sweat: over-drinking dilutes blood sodium.`
  return { mlPerHourLow: low, mlPerHourHigh: high, sodiumMgPerL: sodium, note }
}

export function sweatTestProblems(t: SweatTest): string[] {
  const p: string[] = []
  if (t.durationMin < 30) p.push("At least 30 minutes, so the scale's error is small against the loss.")
  if (t.weightBeforeKg < 30 || t.weightBeforeKg > 200 || t.weightAfterKg < 30 || t.weightAfterKg > 200) p.push('Check the weights.')
  if (Math.abs(t.weightBeforeKg - t.weightAfterKg) > 0.08 * t.weightBeforeKg) p.push('More than 8 % of body weight changed: check the weights.')
  if (t.fluidInMl < 0 || (t.urineMl ?? 0) < 0) p.push('Fluid and urine cannot be negative.')
  if (p.length === 0 && sweatRateMlPerHour(t) == null) p.push('That works out under 0.1 or over 4 litres an hour: check the numbers.')
  return p
}
