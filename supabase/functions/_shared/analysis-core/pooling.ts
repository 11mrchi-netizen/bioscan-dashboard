// Analysis review phase E: partial pooling of related questions (every alcohol outcome, every late-meal outcome).
// Erasable TypeScript, no I/O.
//
// Each question's estimate is put on a common scale -- its effect in units of its own no-effect zone, signed so that
// positive is the favourable direction of its outcome -- and the questions of one family are shrunk toward each other
// with a normal-normal model whose between-question spread tau^2 is the DerSimonian-Laird estimate. A noisy estimate
// moves toward the family; a precise one barely moves. The pooled interval and the probability that the effect lies
// beyond the zone (a Bayesian statement under that model) are reported next to the frequentist verdict, never instead
// of it: the decision recorded in the review keeps the verdicts frequentist and adds pooling for comparison.

export interface PoolInput {
  id: string
  /** Estimate and standard error on the common scale (zone units, favourable positive). */
  estimate: number
  se: number
}

export interface PoolOutput {
  id: string
  mean: number
  sd: number
  /** Share of the estimate taken from the family (0 = untouched, 1 = the family mean). */
  shrinkage: number
}

export interface Pooled {
  members: PoolOutput[]
  mu: number
  tau: number
}

const SQRT2 = Math.SQRT2
function erf(x: number): number {
  // Abramowitz & Stegun 7.1.26
  const t = 1 / (1 + 0.3275911 * Math.abs(x))
  const y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x)
  return x >= 0 ? y : -y
}
export const normalCdf = (z: number) => 0.5 * (1 + erf(z / SQRT2))

/** Normal-normal partial pooling with the DerSimonian-Laird tau^2; null with fewer than two usable members. */
export function partialPool(items: PoolInput[]): Pooled | null {
  const usable = items.filter((i) => Number.isFinite(i.estimate) && i.se > 0)
  if (usable.length < 2) return null
  const w = usable.map((i) => 1 / i.se ** 2)
  const sw = w.reduce((a, b) => a + b, 0)
  const fixed = usable.reduce((a, i, k) => a + w[k] * i.estimate, 0) / sw
  const q = usable.reduce((a, i, k) => a + w[k] * (i.estimate - fixed) ** 2, 0)
  const c = sw - w.reduce((a, b) => a + b * b, 0) / sw
  const tau2 = Math.max(0, (q - (usable.length - 1)) / c)
  const ws = usable.map((i) => 1 / (i.se ** 2 + tau2))
  const sws = ws.reduce((a, b) => a + b, 0)
  const mu = usable.reduce((a, i, k) => a + ws[k] * i.estimate, 0) / sws
  const varMu = 1 / sws
  const members = usable.map((i) => {
    const b = tau2 === 0 ? 1 : i.se ** 2 / (i.se ** 2 + tau2)
    const mean = b * mu + (1 - b) * i.estimate
    const variance = (1 - b) * i.se ** 2 + b * b * varMu
    return { id: i.id, mean, sd: Math.sqrt(variance), shrinkage: b }
  })
  return { members, mu, tau: Math.sqrt(tau2) }
}

/** Probability that a normal(mean, sd) effect lies beyond the zone [-1, 1] (zone units). */
export function probabilityBeyondZone(mean: number, sd: number): number {
  if (!(sd > 0)) return Math.abs(mean) > 1 ? 1 : 0
  return normalCdf((-1 - mean) / sd) + (1 - normalCdf((1 - mean) / sd))
}
