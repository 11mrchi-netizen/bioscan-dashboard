// Analysis Layer 3 core statistics (docs/analysis-layer-3). Pure and dependency-free.
// Erasable TypeScript only (no enums, namespaces or parameter properties) so the same file runs
// unchanged under Node 24 (node --test), Deno (Edge Functions) and Vite (console).
//
// Series convention: arrays aligned on one consecutive-day axis; a missing day is `null`, never 0
// (docs/analysis-layer-2/05-confidence-propagation.md: missingness is a state, not a value).

export type Num = number | null

// Canonical mulberry32 (same generator the seeded fixtures used).
export function mulberry32(seed: number): () => number {
  let a = seed | 0
  return () => {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

// Standard normal via Box-Muller from a uniform generator.
export function gaussian(rng: () => number): number {
  let u = 0
  let v = 0
  while (u === 0) u = rng()
  while (v === 0) v = rng()
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v)
}

export function mean(xs: number[]): number {
  return xs.length === 0 ? NaN : xs.reduce((a, b) => a + b, 0) / xs.length
}

// Sample (n-1) standard deviation. Note: domain/Stats.kt uses population SD for SWC bands; this core is for
// inference, where the unbiased estimator is the right default.
export function sd(xs: number[]): number {
  if (xs.length < 2) return NaN
  const m = mean(xs)
  return Math.sqrt(xs.reduce((a, b) => a + (b - m) ** 2, 0) / (xs.length - 1))
}

export function median(xs: number[]): number {
  if (xs.length === 0) return NaN
  const s = [...xs].sort((a, b) => a - b)
  const mid = s.length >> 1
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2
}

export function pearson(x: number[], y: number[]): number {
  const n = x.length
  if (n < 2 || y.length !== n) return NaN
  const mx = mean(x)
  const my = mean(y)
  let sxy = 0
  let sxx = 0
  let syy = 0
  for (let i = 0; i < n; i++) {
    sxy += (x[i] - mx) * (y[i] - my)
    sxx += (x[i] - mx) ** 2
    syy += (y[i] - my) ** 2
  }
  return sxx * syy === 0 ? 0 : sxy / Math.sqrt(sxx * syy)
}

// Average ranks (ties share the mean rank).
export function ranks(xs: number[]): number[] {
  const order = xs.map((v, i) => [v, i] as const).sort((a, b) => a[0] - b[0])
  const out = new Array<number>(xs.length)
  let i = 0
  while (i < order.length) {
    let j = i
    while (j + 1 < order.length && order[j + 1][0] === order[i][0]) j++
    const r = (i + j) / 2 + 1
    for (let k = i; k <= j; k++) out[order[k][1]] = r
    i = j + 1
  }
  return out
}

export function spearman(x: number[], y: number[]): number {
  return pearson(ranks(x), ranks(y))
}

// Pairs cause[t] with target[t+lag]; both must be present (null drops the pair).
export function pairedLag(cause: Num[], target: Num[], lag: number): { x: number[]; y: number[]; idx: number[] } {
  const x: number[] = []
  const y: number[] = []
  const idx: number[] = []
  const n = Math.min(cause.length, target.length)
  for (let t = 0; t < cause.length; t++) {
    const u = t + lag
    if (u < 0 || u >= n) continue
    const a = cause[t]
    const b = target[u]
    if (a === null || b === null || !Number.isFinite(a) || !Number.isFinite(b)) continue
    x.push(a)
    y.push(b)
    idx.push(t)
  }
  return { x, y, idx }
}

// ---- Student-t p-value (regularised incomplete beta, Numerical Recipes continued fraction) ----

function lgamma(z: number): number {
  const c = [76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5]
  let y = z
  let tmp = z + 5.5
  tmp -= (z + 0.5) * Math.log(tmp)
  let ser = 1.000000000190015
  for (let j = 0; j < 6; j++) ser += c[j] / ++y
  return -tmp + Math.log((2.5066282746310005 * ser) / z)
}

function betacf(a: number, b: number, x: number): number {
  const qab = a + b
  const qap = a + 1
  const qam = a - 1
  let c = 1
  let d = 1 - (qab * x) / qap
  if (Math.abs(d) < 1e-300) d = 1e-300
  d = 1 / d
  let h = d
  for (let m = 1; m < 300; m++) {
    const m2 = 2 * m
    let aa = (m * (b - m) * x) / ((qam + m2) * (a + m2))
    d = 1 + aa * d
    if (Math.abs(d) < 1e-300) d = 1e-300
    c = 1 + aa / c
    if (Math.abs(c) < 1e-300) c = 1e-300
    d = 1 / d
    h *= d * c
    aa = (-(a + m) * (qab + m) * x) / ((a + m2) * (qap + m2))
    d = 1 + aa * d
    if (Math.abs(d) < 1e-300) d = 1e-300
    c = 1 + aa / c
    if (Math.abs(c) < 1e-300) c = 1e-300
    d = 1 / d
    const del = d * c
    h *= del
    if (Math.abs(del - 1) < 3e-15) break
  }
  return h
}

function betai(a: number, b: number, x: number): number {
  if (x <= 0) return 0
  if (x >= 1) return 1
  const bt = Math.exp(lgamma(a + b) - lgamma(a) - lgamma(b) + a * Math.log(x) + b * Math.log(1 - x))
  return x < (a + 1) / (a + b + 2) ? (bt * betacf(a, b, x)) / a : 1 - (bt * betacf(b, a, 1 - x)) / b
}

// Two-sided p for a correlation r over n pairs (t distribution, n-2 df).
export function pFromR(r: number, n: number): number {
  if (!(n > 2) || !Number.isFinite(r)) return 1
  if (Math.abs(r) >= 1 - 1e-15) return 0
  const df = n - 2
  const t = r * Math.sqrt(df / (1 - r * r))
  return betai(df / 2, 0.5, df / (df + t * t))
}

// Benjamini-Hochberg adjusted p-values (monotone step-up), same order as the input.
export function benjaminiHochberg(ps: number[]): number[] {
  const m = ps.length
  const order = ps.map((p, i) => [p, i] as const).sort((a, b) => a[0] - b[0])
  const adj = new Array<number>(m)
  let prev = 1
  for (let rank = m; rank >= 1; rank--) {
    const [p, i] = order[rank - 1]
    prev = Math.min(prev, (p * m) / rank)
    adj[i] = prev
  }
  return adj
}

// ---- Calendar helpers ----

export function weekdayOf(isoDate: string): number {
  return new Date(isoDate + 'T00:00:00Z').getUTCDay() // 0 = Sunday
}

// Subtract each weekday's mean (computed over the non-null days). Removes the calendar confound that made a
// strength-day -> next-day-HRV link look "strong" on the real history (report 14 §4.4).
export function weekdayDemean(dates: string[], values: Num[]): Num[] {
  const sums = [0, 0, 0, 0, 0, 0, 0]
  const counts = [0, 0, 0, 0, 0, 0, 0]
  dates.forEach((d, i) => {
    const v = values[i]
    if (v === null || !Number.isFinite(v)) return
    const w = weekdayOf(d)
    sums[w] += v
    counts[w]++
  })
  return dates.map((d, i) => {
    const v = values[i]
    if (v === null || !Number.isFinite(v)) return null
    const w = weekdayOf(d)
    return v - sums[w] / counts[w]
  })
}

// Consecutive ISO dates from start to end inclusive.
export function dateAxis(start: string, end: string): string[] {
  const out: string[] = []
  const d = new Date(start + 'T00:00:00Z')
  const e = new Date(end + 'T00:00:00Z')
  while (d <= e) {
    out.push(d.toISOString().slice(0, 10))
    d.setUTCDate(d.getUTCDate() + 1)
  }
  return out
}

// Places dated points on an axis; absent days stay null.
export function alignToAxis(axis: string[], points: { date: string; value: number | null }[]): Num[] {
  const m = new Map(points.map((p) => [p.date, p.value]))
  return axis.map((d) => m.get(d) ?? null)
}

// ---- Dependence-aware inference ----

// Lag-1 autocorrelation over consecutive present pairs; 0 when there are too few.
export function autocorr1(xs: Num[]): number {
  const a: number[] = []
  const b: number[] = []
  for (let i = 0; i + 1 < xs.length; i++) {
    const p = xs[i]
    const q = xs[i + 1]
    if (p !== null && q !== null) {
      a.push(p)
      b.push(q)
    }
  }
  return a.length < 5 ? 0 : pearson(a, b)
}

// Bartlett-style effective sample size for a correlation between two AR(1)-like series.
export function effectiveN(n: number, rho1x: number, rho1y: number): number {
  const prod = Math.max(0, rho1x * rho1y)
  return Math.max(3, Math.min(n, (n * (1 - prod)) / (1 + prod)))
}

// Exact circular-shift permutation p: every shift of the cause series (at least `minShift` days from zero) is
// enumerated, so the smallest attainable p is 1/(shifts+1) (about 0.02 for 61 days). Keeps within-series autocorrelation.
export function circularShiftP(cause: Num[], target: Num[], lag: number, stat: (x: number[], y: number[]) => number = pearson, minShift = 7): number {
  const n = cause.length
  const obs = pairedLag(cause, target, lag)
  if (obs.x.length < 5) return 1
  const r0 = Math.abs(stat(obs.x, obs.y))
  let hits = 0
  let shifts = 0
  for (let k = minShift; k <= n - minShift; k++) {
    const rotated = cause.slice(k).concat(cause.slice(0, k))
    const pr = pairedLag(rotated, target, lag)
    if (pr.x.length < 5) continue
    shifts++
    if (Math.abs(stat(pr.x, pr.y)) >= r0) hits++
  }
  return shifts === 0 ? 1 : (hits + 1) / (shifts + 1)
}

// Circular block bootstrap percentile interval for a paired statistic (blocks keep short-range dependence).
export function blockBootstrapCi(
  x: number[],
  y: number[],
  rng: () => number,
  stat: (x: number[], y: number[]) => number = pearson,
  reps = 1000,
  block = 7,
  level = 0.95,
): [number, number] {
  const n = x.length
  if (n < 8) return [NaN, NaN]
  const b = Math.min(block, n)
  const draws: number[] = []
  for (let r = 0; r < reps; r++) {
    const bx: number[] = []
    const by: number[] = []
    while (bx.length < n) {
      const s = Math.floor(rng() * n)
      for (let k = 0; k < b && bx.length < n; k++) {
        bx.push(x[(s + k) % n])
        by.push(y[(s + k) % n])
      }
    }
    const v = stat(bx, by)
    if (Number.isFinite(v)) draws.push(v)
  }
  draws.sort((p, q) => p - q)
  const lo = draws[Math.floor(((1 - level) / 2) * (draws.length - 1))]
  const hi = draws[Math.ceil((1 - (1 - level) / 2) * (draws.length - 1))]
  return [lo, hi]
}

// Minimum detectable difference between two equal-length windows (two-sided alpha 0.05, power 0.8), ignoring
// autocorrelation (which makes the real MDE larger). Used for the experiment power preview.
export function minDetectableDifference(sigma: number, nPerArm: number): number {
  return 2.8 * sigma * Math.sqrt(2 / nPerArm)
}

// ---- Analysis Layer 3 / Phase 3: adjusted effects and a weekly-block permutation test ----

// Least-squares slope of y on x (units of y per unit of x); 0 when x has no variance.
export function slope(x: number[], y: number[]): number {
  const n = x.length
  if (n < 2 || y.length !== n) return NaN
  const mx = mean(x)
  const my = mean(y)
  let sxy = 0
  let sxx = 0
  for (let i = 0; i < n; i++) {
    sxy += (x[i] - mx) * (y[i] - my)
    sxx += (x[i] - mx) ** 2
  }
  return sxx === 0 ? 0 : sxy / sxx
}

// Permutation p for the association between cause[t] and target[t+lag]. The cause series is cut into whole 7-day blocks and
// the blocks are shuffled (the tail shorter than 7 days stays in place), so every day keeps its weekday position and the
// short-range dependence inside a week; only the alignment between weeks is destroyed. Smallest attainable p is
// 1/(reps+1), unlike an exhaustive circular shift whose resolution is 1/(n-13) and cannot survive a correction across a
// catalogue. `stat` is evaluated on paired complete days only.
export function blockPermutationP(
  cause: Num[],
  target: Num[],
  lag: number,
  rng: () => number,
  stat: (x: number[], y: number[]) => number = pearson,
  reps = 2000,
  block = 7,
  minPairs = 8,
): number {
  const obs = pairedLag(cause, target, lag)
  if (obs.x.length < minPairs) return 1
  const s0 = stat(obs.x, obs.y)
  const r0 = Number.isFinite(s0) ? Math.abs(s0) : 0
  const blocks = Math.floor(cause.length / block)
  if (blocks < 3) return 1
  const tail = cause.slice(blocks * block)
  const order = Array.from({ length: blocks }, (_, i) => i)
  let hits = 0
  for (let r = 0; r < reps; r++) {
    for (let i = blocks - 1; i > 0; i--) {
      const j = Math.floor(rng() * (i + 1))
      const tmp = order[i]
      order[i] = order[j]
      order[j] = tmp
    }
    const perm: Num[] = []
    for (const b of order) for (let k = 0; k < block; k++) perm.push(cause[b * block + k])
    for (const v of tail) perm.push(v)
    const pr = pairedLag(perm, target, lag)
    if (pr.x.length < minPairs) continue
    const s = stat(pr.x, pr.y)
    if (Number.isFinite(s) && Math.abs(s) >= r0 - 1e-12) hits++
  }
  return (hits + 1) / (reps + 1)
}

// ---- Analysis Layer 3 / Phase 5: Student t helpers for the N-of-1 experiments ----

// Two-sided p for a t statistic with (possibly fractional) df degrees of freedom.
export function pFromT(t: number, df: number): number {
  if (!(df > 0) || !Number.isFinite(t)) return 1
  return pFromR(t / Math.sqrt(t * t + df), df + 2)
}

// Two-sided critical value: the t for which pFromT(t, df) = alpha (bisection; df >= 1).
export function tCritical(df: number, alpha = 0.05): number {
  let lo = 0
  let hi = 200
  for (let i = 0; i < 60; i++) {
    const mid = (lo + hi) / 2
    if (pFromT(mid, df) > alpha) lo = mid
    else hi = mid
  }
  return (lo + hi) / 2
}
