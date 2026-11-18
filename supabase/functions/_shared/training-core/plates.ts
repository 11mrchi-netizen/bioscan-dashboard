// Port of domain/training/PlateMath.kt. Equipment-aware load rounding in centi-kg integers so 1.25 kg plates never
// accumulate floating-point error. Plates are per side, any quantity of each size.

export const KG_PER_LB = 0.45359237
export const lbToKg = (lb: number): number => lb * KG_PER_LB
export const kgToLb = (kg: number): number => kg / KG_PER_LB

export interface Loadable { barKg: number; platesPerSideKg: number[] }
export const loadableTotal = (l: Loadable): number => l.barKg + 2 * l.platesPerSideKg.reduce((a, b) => a + b, 0)

export type Rounding = 'nearest' | 'down' | 'up'

const CENTI = 100
const gcd = (a: number, b: number): number => (b === 0 ? a : gcd(b, a % b))
const centi = (kg: number): number => Math.round(kg * CENTI)

function checkPlates(platesKg: number[]): void {
  if (platesKg.length === 0 || platesKg.some((p) => !(p > 0))) throw new Error('plates must be positive')
}

/** Smallest total-weight step the plates allow: twice the greatest common divisor of the plate sizes. */
export function loadStepKg(platesKg: number[]): number {
  checkPlates(platesKg)
  return (2 * platesKg.map(centi).reduce(gcd)) / CENTI
}

/** The nearest loadable total to [targetKg] (ties go down), with the fewest plates per side; below the bar, the bar alone. */
export function loadableFor(targetKg: number, barKg: number, platesKg: number[], rounding: Rounding = 'nearest'): Loadable {
  checkPlates(platesKg)
  if (targetKg <= barKg) return { barKg, platesPerSideKg: [] }
  const sizes = [...new Set(platesKg.map(centi))].sort((a, b) => b - a)
  const unit = sizes.reduce(gcd)
  const perSideUnits = (((targetKg - barKg) / 2) * CENTI) / unit
  const lower = Math.floor(perSideUnits + 1e-9)
  const upper = Math.ceil(perSideUnits - 1e-9)
  const units = rounding === 'down' ? lower : rounding === 'up' ? upper : perSideUnits - lower <= upper - perSideUnits ? lower : upper
  const plates = fewestPlates(units, sizes.map((s) => s / unit)).map((p) => (p * unit) / CENTI).sort((a, b) => b - a)
  return { barKg, platesPerSideKg: plates }
}

// Minimum-plate decomposition (coin change); greedy is not safe because 15 is not a multiple of 10.
function fewestPlates(units: number, denominations: number[]): number[] {
  if (units <= 0) return []
  const best = new Array<number>(units + 1).fill(Number.MAX_SAFE_INTEGER)
  const last = new Array<number>(units + 1).fill(0)
  best[0] = 0
  for (let u = 1; u <= units; u++) {
    for (const d of denominations) {
      if (d <= u && best[u - d] !== Number.MAX_SAFE_INTEGER && best[u - d] + 1 < best[u]) {
        best[u] = best[u - d] + 1
        last[u] = d
      }
    }
  }
  if (best[units] === Number.MAX_SAFE_INTEGER) throw new Error('unreachable plate total')
  const out: number[] = []
  let u = units
  while (u > 0) { out.push(last[u]); u -= last[u] }
  return out
}

/** Rounds an added (belt or vest) load to a single-plate step, nearest, ties down. */
export function roundToStep(valueKg: number, stepKg: number): number {
  if (!(stepKg > 0)) throw new Error('step must be positive')
  const steps = valueKg / stepKg
  const lower = Math.floor(steps + 1e-9)
  const upper = Math.ceil(steps - 1e-9)
  return (steps - lower <= upper - steps ? lower : upper) * stepKg
}
