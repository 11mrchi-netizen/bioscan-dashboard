// Tiny dense linear algebra for the typicality engine (matrices are at most 5 x 5). Erasable TypeScript, no dependencies.

export type Matrix = number[][]

export function identity(p: number): Matrix {
  return Array.from({ length: p }, (_, i) => Array.from({ length: p }, (_, j) => (i === j ? 1 : 0)))
}

/** Gauss-Jordan inverse with partial pivoting; null when the matrix is singular. */
export function invert(m: Matrix): Matrix | null {
  const n = m.length
  const a = m.map((row, i) => [...row, ...identity(n)[i]])
  for (let c = 0; c < n; c++) {
    let piv = c
    for (let r = c + 1; r < n; r++) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r
    if (Math.abs(a[piv][c]) < 1e-12) return null
    ;[a[c], a[piv]] = [a[piv], a[c]]
    const d = a[c][c]
    for (let j = 0; j < 2 * n; j++) a[c][j] /= d
    for (let r = 0; r < n; r++) {
      if (r === c) continue
      const f = a[r][c]
      if (f !== 0) for (let j = 0; j < 2 * n; j++) a[r][j] -= f * a[c][j]
    }
  }
  return a.map((row) => row.slice(n))
}

/** Quadratic form v' M v. */
export function quad(m: Matrix, v: number[]): number {
  let s = 0
  for (let i = 0; i < v.length; i++) for (let j = 0; j < v.length; j++) s += v[i] * m[i][j] * v[j]
  return s
}

/** Sub-matrix on the given indices. */
export function sub(m: Matrix, idx: number[]): Matrix {
  return idx.map((i) => idx.map((j) => m[i][j]))
}

/**
 * Sample covariance (divide by n) of the rows, around their own mean, plus the Ledoit-Wolf shrinkage towards a scaled identity:
 * S* = delta * mu * I + (1 - delta) * S, mu = trace(S) / p, delta = min(b2, d2) / d2 with d2 = ||S - mu I||_F^2 and
 * b2 = (1 / n^2) * sum_k ||x_k x_k' - S||_F^2. Always positive definite (delta has a small floor), so it can be inverted
 * even when signals are collinear or there are few rows. Returns the mean and shrinkage intensity as well.
 */
export function shrunkCovariance(rows: number[][], floor = 0.01): { mean: number[]; cov: Matrix; delta: number } {
  const n = rows.length
  const p = rows[0].length
  const mean = Array.from({ length: p }, (_, j) => rows.reduce((a, r) => a + r[j], 0) / n)
  const x = rows.map((r) => r.map((v, j) => v - mean[j]))
  const s: Matrix = Array.from({ length: p }, () => new Array(p).fill(0))
  for (const r of x) for (let i = 0; i < p; i++) for (let j = 0; j < p; j++) s[i][j] += (r[i] * r[j]) / n
  const mu = s.reduce((a, row, i) => a + row[i], 0) / p
  let d2 = 0
  for (let i = 0; i < p; i++) for (let j = 0; j < p; j++) d2 += (s[i][j] - (i === j ? mu : 0)) ** 2
  let b2 = 0
  for (const r of x) {
    for (let i = 0; i < p; i++) for (let j = 0; j < p; j++) b2 += (r[i] * r[j] - s[i][j]) ** 2
  }
  b2 /= n * n
  const delta = d2 === 0 ? 1 : Math.min(1, Math.max(floor, Math.min(b2, d2) / d2))
  const cov = s.map((row, i) => row.map((v, j) => (1 - delta) * v + (i === j ? delta * mu : 0)))
  return { mean, cov, delta }
}
