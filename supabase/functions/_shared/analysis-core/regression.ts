// Analysis review phase D: least squares with a covariance that respects neighbouring days. Erasable TypeScript, no I/O.
//
// Daily residuals are rarely independent, so plain OLS standard errors are too small. The covariance here is the
// Newey-West (1987) sandwich with a Bartlett kernel in calendar days: two rows `d` days apart contribute with weight
// 1 - d / (bandwidth + 1). The kernel is positive definite on any set of integer days, so missing days are handled
// without pretending rows are consecutive. Two small-sample corrections keep 95% intervals at 95% with a few months of
// days (both measured in relationship.test.ts): each row's residual is scaled by 1 / sqrt(1 - leverage) (HC2, MacKinnon
// & White 1985), and the critical value is widened for the bandwidth (fixed-b, Kiefer & Vogelsang 2005).
import { invert } from './linalg.ts'
import type { Matrix } from './linalg.ts'
import { pFromT, tCritical } from './stats.ts'

export interface OlsFit {
  beta: number[]
  cov: Matrix
  se: number[]
  n: number
  p: number
  df: number
  resid: number[]
  /** Residual standard deviation (n - p). */
  sigma: number
  /** Two-sided 95% critical value: Student t on n - p, widened for the bandwidth (fixed-b). */
  critical: number
}

/** Default bandwidth for daily data: floor(4 (n / 100)^(2/9)), at least 2 days. */
export function neweyWestBandwidth(n: number): number {
  return Math.max(2, Math.floor(4 * (n / 100) ** (2 / 9)))
}

/**
 * OLS of y on the columns of X (rows x p, intercept included by the caller) with a calendar-day HAC covariance.
 * `days` are the rows' day indices (any integers, ascending or not). Null when X'X is singular or there is no residual df.
 */
export function olsHac(X: number[][], y: number[], days: number[], bandwidth = neweyWestBandwidth(y.length)): OlsFit | null {
  const n = y.length
  const p = X[0]?.length ?? 0
  if (n <= p + 1 || p === 0) return null
  const xtx: Matrix = Array.from({ length: p }, () => new Array(p).fill(0))
  const xty = new Array(p).fill(0)
  for (let i = 0; i < n; i++) {
    const r = X[i]
    for (let a = 0; a < p; a++) {
      xty[a] += r[a] * y[i]
      for (let b = a; b < p; b++) xtx[a][b] += r[a] * r[b]
    }
  }
  for (let a = 0; a < p; a++) for (let b = 0; b < a; b++) xtx[a][b] = xtx[b][a]
  const inv = invert(xtx)
  if (!inv) return null
  const beta = inv.map((row) => row.reduce((s, v, j) => s + v * xty[j], 0))
  const resid = y.map((v, i) => v - X[i].reduce((s, x, j) => s + x * beta[j], 0))

  // Meat: sum over pairs within the bandwidth of w(|d_i - d_j|) (x_i e_i)(x_j e_j)', each residual scaled for its leverage.
  const lev = X.map((r) => r.reduce((acc, xa, a) => acc + xa * r.reduce((s2, xb, b) => s2 + inv[a][b] * xb, 0), 0))
  const order = days.map((d, i) => [d, i] as const).sort((a, b) => a[0] - b[0])
  const u = order.map(([, i]) => X[i].map((x) => (x * resid[i]) / Math.sqrt(Math.max(1e-6, 1 - lev[i]))))
  const t = order.map(([d]) => d)
  const meat: Matrix = Array.from({ length: p }, () => new Array(p).fill(0))
  for (let i = 0; i < n; i++) {
    for (let a = 0; a < p; a++) for (let b = 0; b < p; b++) meat[a][b] += u[i][a] * u[i][b]
    for (let j = i + 1; j < n && t[j] - t[i] <= bandwidth; j++) {
      const w = 1 - (t[j] - t[i]) / (bandwidth + 1)
      if (w <= 0) continue
      for (let a = 0; a < p; a++) for (let b = 0; b < p; b++) meat[a][b] += w * (u[i][a] * u[j][b] + u[j][a] * u[i][b])
    }
  }
  const left = inv.map((row) => meat.map((_, b) => row.reduce((s, v, k) => s + v * meat[k][b], 0)))
  const cov = left.map((row) => inv.map((_, b) => row.reduce((s, v, k) => s + v * inv[k][b], 0)))
  const se = cov.map((row, i) => Math.sqrt(Math.max(0, row[i])))
  const sigma = Math.sqrt(resid.reduce((s, e) => s + e * e, 0) / (n - p))
  const fb = (bandwidth + 1) / n
  const critical = tCritical(n - p) * ((1.96 + 2.9694 * fb + 0.416 * fb ** 2 - 0.5324 * fb ** 3) / 1.96)
  return { beta, cov, se, n, p, df: n - p, resid, sigma, critical }
}

/** Estimate, 95% interval and two-sided p for one coefficient (p from the same widened reference as the interval). */
export function coefficient(fit: OlsFit, j: number): { estimate: number; se: number; lo: number; hi: number; p: number; tc: number } {
  const tc = fit.critical
  const est = fit.beta[j]
  const se = fit.se[j]
  const widen = tc / tCritical(fit.df)
  return { estimate: est, se, lo: est - tc * se, hi: est + tc * se, p: se > 0 ? pFromT(est / (se * widen), fit.df) : 1, tc }
}

// Linear-spline trend: t in [0, 1] plus hinge terms at knots every `every` days, so a slow drift is not read as an effect.
export function trendColumns(dayIndex: number, firstDay: number, lastDay: number, every = 60): number[] {
  const span = Math.max(1, lastDay - firstDay)
  const x = (dayIndex - firstDay) / span
  const cols = [x]
  for (let k = firstDay + every; k < lastDay - every / 2; k += every) cols.push(Math.max(0, (dayIndex - k) / span))
  return cols
}

// Weekday indicator columns (Monday as the reference level).
export function weekdayColumns(isoDate: string): number[] {
  const w = new Date(isoDate + 'T00:00:00Z').getUTCDay() // 0 = Sunday
  return [0, 2, 3, 4, 5, 6].map((d) => (w === d ? 1 : 0))
}
