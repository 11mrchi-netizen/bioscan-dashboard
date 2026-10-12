// Series-break detection (Phase 1 / R1): finds the dates where a daily series changes regime in level or in noise,
// e.g. a different device or classifier starting to write sleep stages with a different level, or HRV switching from
// many-sample daily averages to very few samples (a jump in day-to-day variability and in whole-number readings).
//
// Method: binary segmentation. For every admissible split the two sides are compared with robust statistics
//   level      z_L = |median2 - median1| / pooled robust sd * sqrt(n1 n2 / (n1 + n2))
//   dispersion z_D = |ln(s2 / s1)| / sqrt(1/(2(n1-1)) + 1/(2(n2-1)))      (s = MAD-based robust sd)
// both divided by an autocorrelation inflation factor sqrt((1 + rho) / (1 - rho)) so persistent noise does not
// raise false alarms. The best split is a break when its score reaches 1 (critical values below), then each side is
// searched again. Missing days are skipped, never filled (missingness is a state).
import { median } from './stats.ts'
import type { Num } from './stats.ts'

export interface SegmentStats {
  n: number
  median: number
  robustSd: number
  integerShare: number // share of readings that are whole numbers (a single-sample sensor signature for HRV)
}

export interface SeriesBreak {
  index: number // axis index of the first day of the new regime
  date: string
  kind: 'level' | 'dispersion' | 'both'
  score: number // >= 1 means the break passed its critical value
  before: SegmentStats
  after: SegmentStats
}

export interface BreakOptions {
  minSegment?: number // minimum present readings on each side
  levelCritical?: number
  dispersionCritical?: number
  maxBreaks?: number
}

const DEFAULTS = { minSegment: 21, levelCritical: 6, dispersionCritical: 6, maxBreaks: 4 }

interface Pt {
  i: number
  v: number
}

// MAD-based robust standard deviation (consistent with the sd for normal data).
export function robustSd(xs: number[]): number {
  if (xs.length < 2) return NaN
  const m = median(xs)
  return 1.4826 * median(xs.map((x) => Math.abs(x - m)))
}

export function integerShare(xs: number[]): number {
  return xs.length === 0 ? 0 : xs.filter((x) => Math.abs(x - Math.round(x)) < 1e-9).length / xs.length
}

function segmentStats(pts: Pt[]): SegmentStats {
  const v = pts.map((p) => p.v)
  return { n: v.length, median: median(v), robustSd: robustSd(v), integerShare: integerShare(v) }
}

// Autocorrelation of the residuals around a local (+-7 reading) median. A regime jump inside the segment would look
// like strong autocorrelation if estimated on the raw series, which hides exactly the breaks we are after.
function localRho(pts: Pt[]): number {
  const v = pts.map((p) => p.v)
  if (v.length < 15) return 0
  const res = v.map((x, k) => x - median(v.slice(Math.max(0, k - 7), Math.min(v.length, k + 8))))
  let num = 0
  let den = 0
  for (let k = 0; k < res.length; k++) {
    den += res[k] ** 2
    if (k > 0) num += res[k] * res[k - 1]
  }
  return den === 0 ? 0 : Math.max(0, Math.min(0.9, num / den))
}

function evalSplit(pts: Pt[], k: number, inflation: number, o: Required<BreakOptions>): { score: number; kind: SeriesBreak['kind'] } | null {
  const n = pts.length
  const a = pts.slice(0, k).map((p) => p.v)
  const b = pts.slice(k).map((p) => p.v)
  const sa = robustSd(a)
  const sb = robustSd(b)
  if (!(sa > 0) || !(sb > 0)) return null
  const pooled = Math.sqrt((sa * sa * a.length + sb * sb * b.length) / n)
  const zL = (Math.abs(median(b) - median(a)) / pooled) * Math.sqrt((a.length * b.length) / n) / inflation
  const zD = Math.abs(Math.log(sb / sa)) / Math.sqrt(1 / (2 * (a.length - 1)) + 1 / (2 * (b.length - 1))) / inflation
  const sL = zL / o.levelCritical
  const sD = zD / o.dispersionCritical
  return { score: Math.max(sL, sD), kind: sL >= 1 && sD >= 1 ? 'both' : sL >= 1 ? 'level' : 'dispersion' }
}

// Coarse scan on a stride for long series (the Edge Function has a small CPU budget), then every split around the best
// coarse candidate, so the reported split is the exact optimum of that neighbourhood.
function bestSplit(pts: Pt[], o: Required<BreakOptions>): { k: number; score: number; kind: SeriesBreak['kind'] } | null {
  const n = pts.length
  if (n < 2 * o.minSegment) return null
  const rho = localRho(pts)
  const inflation = Math.sqrt((1 + rho) / (1 - rho))
  const stride = n > 120 ? 3 : 1
  let bestK = -1
  let bestScore = -1
  for (let k = o.minSegment; k <= n - o.minSegment; k += stride) {
    const e = evalSplit(pts, k, inflation, o)
    if (e && e.score > bestScore) {
      bestScore = e.score
      bestK = k
    }
  }
  if (bestK < 0) return null
  let best: { k: number; score: number; kind: SeriesBreak['kind'] } | null = null
  for (let k = Math.max(o.minSegment, bestK - stride + 1); k <= Math.min(n - o.minSegment, bestK + stride - 1); k++) {
    const e = evalSplit(pts, k, inflation, o)
    if (e && e.score >= 1 && (best === null || e.score > best.score)) best = { k, score: e.score, kind: e.kind }
  }
  return best
}

export function detectSeriesBreaks(dates: string[], values: Num[], options: BreakOptions = {}): SeriesBreak[] {
  const o = { ...DEFAULTS, ...options } as Required<BreakOptions>
  const pts: Pt[] = []
  values.forEach((v, i) => {
    if (v !== null && Number.isFinite(v)) pts.push({ i, v })
  })

  // 1) binary segmentation finds how many breaks there are (positions are indices into pts)
  const cuts: number[] = []
  const queue: [number, number][] = [[0, pts.length]]
  while (queue.length > 0 && cuts.length < o.maxBreaks) {
    const [lo, hi] = queue.shift() as [number, number]
    const s = bestSplit(pts.slice(lo, hi), o)
    if (!s) continue
    cuts.push(lo + s.k)
    queue.push([lo, lo + s.k], [lo + s.k, hi])
  }
  cuts.sort((a, b) => a - b)

  // 2) refine each cut using only the stretch between its neighbours, where exactly one change remains; a cut that is
  //    no longer significant once its neighbours are fixed was a false positive of the greedy search and is dropped
  for (let pass = 0; pass < 3; pass++) {
    for (let c = 0; c < cuts.length; c++) {
      const lo = c === 0 ? 0 : cuts[c - 1]
      const hi = c === cuts.length - 1 ? pts.length : cuts[c + 1]
      const s = bestSplit(pts.slice(lo, hi), o)
      if (s) cuts[c] = lo + s.k
      else {
        cuts.splice(c, 1)
        c--
      }
    }
  }

  const bounds = [0, ...cuts, pts.length]
  return cuts.map((cut, c) => {
    const seg = pts.slice(bounds[c], bounds[c + 2])
    const s = bestSplit(seg, o)
    const before = pts.slice(bounds[c], cut)
    const after = pts.slice(cut, bounds[c + 2])
    return {
      index: pts[cut].i,
      date: dates[pts[cut].i],
      kind: s ? s.kind : 'level',
      score: s ? s.score : 1,
      before: segmentStats(before),
      after: segmentStats(after),
    } as SeriesBreak
  })
}

// The latest break (for restarting a personal baseline after it) or null.
export function latestBreak(breaks: SeriesBreak[]): SeriesBreak | null {
  return breaks.length === 0 ? null : breaks[breaks.length - 1]
}
