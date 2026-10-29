// Body composition from a scale weigh-in (weight + one impedance value) and profile (height, age, sex). TypeScript twin of
// android-app/.../domain/BodyComposition.kt: same equations, same quality gate, same test vectors (bodyComposition.test.ts and
// BodyCompositionTest.kt), so the app and the console cannot drift. Erasable TypeScript, like the rest of _shared.
// Methodology and limits: docs/body-composition/README.md.

export interface BodyCompositionInput {
  weightKg: number
  impedanceOhm: number
  heightCm: number
  ageYears: number
  isMale: boolean
}

export interface CompositionSplit {
  fatFreeKg: number
  fatKg: number
  bodyFatPct: number
  ffmi: number
  fmi: number
}

export interface BodyComposition {
  bmi: number | null
  impedanceIndex: number | null
  totalBodyWaterL: number | null
  split: CompositionSplit | null
  skeletalMuscleKg: number | null
  bmrKcal: number | null
  qualityOk: boolean
}

const between = (v: number, lo: number, hi: number) => v >= lo && v <= hi

// Height² / impedance in cm²/ohm: model-free, the cleanest trend signal.
export function impedanceIndex(heightCm: number, impedanceOhm: number): number | null {
  return between(heightCm, 100, 230) && between(impedanceOhm, 50, 3000) ? (heightCm * heightCm) / impedanceOhm : null
}

export function bmi(weightKg: number, heightCm: number): number | null {
  return between(weightKg, 5, 400) && between(heightCm, 100, 230) ? weightKg / ((heightCm / 100) * (heightCm / 100)) : null
}

// Weight range for BMI 18.5..25 at this height.
export function healthyWeightRangeKg(heightCm: number): { low: number; high: number } | null {
  if (!between(heightCm, 100, 230)) return null
  const m2 = (heightCm / 100) * (heightCm / 100)
  return { low: 18.5 * m2, high: 25 * m2 }
}

// Janssen et al. 2000, J Appl Physiol 89:465-471 (doi 10.1152/jappl.2000.89.2.465):
// SM (kg) = Ht²/R × 0.401 + sex × 3.825 − age × 0.071 + 5.102 (men = 1, women = 0). SEE 2.7 kg.
export function skeletalMuscleMassKg(i: BodyCompositionInput): number | null {
  const s = impedanceIndex(i.heightCm, i.impedanceOhm)
  if (s === null || !between(i.ageYears, 18, 99)) return null
  const sm = s * 0.401 + (i.isMale ? 3.825 : 0) - i.ageYears * 0.071 + 5.102
  return sm > 0 && sm < i.weightKg ? sm : null
}

// Sun et al. 2003, Am J Clin Nutr 77:331-340 (doi 10.1093/ajcn/77.2.331), Table 5, final race-combined equations.
// Stature in cm, resistance in ohm, weight in kg; S = stature² / resistance.
//   Males   TBW = 1.20 + 0.45 S + 0.18 W        FFM = -10.68 + 0.65 S + 0.26 W + 0.02 R
//   Females TBW = 3.75 + 0.45 S + 0.11 W        FFM =  -9.53 + 0.69 S + 0.17 W + 0.02 R
export function totalBodyWaterL(i: BodyCompositionInput): number | null {
  const s = impedanceIndex(i.heightCm, i.impedanceOhm)
  if (s === null || !between(i.ageYears, 18, 99) || !between(i.weightKg, 5, 400)) return null
  return i.isMale ? 1.2 + 0.45 * s + 0.18 * i.weightKg : 3.75 + 0.45 * s + 0.11 * i.weightKg
}

export function fatFreeMassKg(i: BodyCompositionInput): number | null {
  const s = impedanceIndex(i.heightCm, i.impedanceOhm)
  if (s === null || !between(i.ageYears, 18, 99) || !between(i.weightKg, 5, 400)) return null
  return i.isMale
    ? -10.68 + 0.65 * s + 0.26 * i.weightKg + 0.02 * i.impedanceOhm
    : -9.53 + 0.69 * s + 0.17 * i.weightKg + 0.02 * i.impedanceOhm
}

export function compositionFromFatFreeMass(weightKg: number, heightCm: number, fatFreeKg: number): CompositionSplit | null {
  if (!between(heightCm, 100, 230) || !between(weightKg, 5, 400)) return null
  const fat = weightKg - fatFreeKg
  const pct = (fat / weightKg) * 100
  if (fatFreeKg <= 0 || !between(pct, 1, 70)) return null // same range as body_metrics.body_fat_pct
  const m2 = (heightCm / 100) * (heightCm / 100)
  return { fatFreeKg, fatKg: fat, bodyFatPct: pct, ffmi: fatFreeKg / m2, fmi: fat / m2 }
}

// Katch-McArdle resting energy: 370 + 21.6 × FFM(kg).
export const katchMcArdleKcal = (fatFreeKg: number): number => 370 + 21.6 * fatFreeKg

// qualityOk: a split exists and water is 60-80 % of fat-free mass (physiological ~73 %).
export function bodyComposition(i: BodyCompositionInput): BodyComposition {
  const ffm = fatFreeMassKg(i)
  const tbw = totalBodyWaterL(i)
  const split = ffm === null ? null : compositionFromFatFreeMass(i.weightKg, i.heightCm, ffm)
  const hydration = tbw !== null && ffm !== null && ffm > 0 ? tbw / ffm : null
  return {
    bmi: bmi(i.weightKg, i.heightCm),
    impedanceIndex: impedanceIndex(i.heightCm, i.impedanceOhm),
    totalBodyWaterL: tbw,
    split,
    skeletalMuscleKg: skeletalMuscleMassKg(i),
    bmrKcal: split ? katchMcArdleKcal(split.fatFreeKg) : null,
    qualityOk: split !== null && hydration !== null && hydration >= 0.6 && hydration <= 0.8,
  }
}

export interface WeightChangeSplit {
  deltaWeightKg: number
  deltaFatKg: number
  deltaFatFreeKg: number
  spanDays: number
}

export interface WeighIn {
  date: string // YYYY-MM-DD
  weightKg: number
  split: CompositionSplit
}

const DAY_MS = 86_400_000
const dayNumber = (iso: string) => Math.floor(Date.parse(`${iso}T00:00:00Z`) / DAY_MS)
const mean = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length

// How much of a weight change over the last windowDays was fat and how much fat-free mass. Each end is the mean of the two
// readings nearest that end of the window; needs 4+ readings spanning at least half the window.
export function weightChangeSplit(readings: WeighIn[], windowDays: number, asOf: string): WeightChangeSplit | null {
  const end = dayNumber(asOf)
  const inWindow = readings
    .filter((r) => dayNumber(r.date) <= end && dayNumber(r.date) >= end - windowDays)
    .sort((a, b) => dayNumber(a.date) - dayNumber(b.date))
  if (inWindow.length < 4) return null
  const span = dayNumber(inWindow[inWindow.length - 1].date) - dayNumber(inWindow[0].date)
  if (span < Math.floor(windowDays / 2)) return null
  const first = inWindow.slice(0, 2)
  const last = inWindow.slice(-2)
  const delta = (f: (r: WeighIn) => number) => mean(last.map(f)) - mean(first.map(f))
  return {
    deltaWeightKg: delta((r) => r.weightKg),
    deltaFatKg: delta((r) => r.split.fatKg),
    deltaFatFreeKg: delta((r) => r.split.fatFreeKg),
    spanDays: span,
  }
}
