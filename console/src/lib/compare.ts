// Two daily metrics side by side, the second a chosen number of days later (web redesign, phase 3). A port of the phone's
// Lab › Explore (android domain/Correlation.kt, after NOOP's CorrelationEngine): Pearson's r with its 95% interval by
// Fisher's z over the pairs of days, descriptive only (no detrending, no effective sample size, no hunt for the best lag).
// The same pair therefore reads the same on the phone and here. The evidence board (Evidence) is the corrected, registered
// version of the same question.
export const MIN_CORRELATION_DAYS = 10

export interface Correlation {
  n: number
  r: number
  lo: number
  hi: number
  /** y on x: change in y per one unit of x. */
  slope: number
  pairs: { day: string; x: number; y: number }[]
  /** True when the interval leaves out zero. */
  clear: boolean
}

/** The calendar day `n` days after an ISO date (UTC arithmetic on a date-only value: no zone or DST involved). */
export function addDays(day: string, n: number): string {
  const d = new Date(`${day}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}

/** x on day d against y on day d + lag; null with fewer than 10 pairs or no spread in either. */
export function correlate(x: Map<string, number>, y: Map<string, number>, lagDays: number, from: string | null = null): Correlation | null {
  const pairs = [...x.entries()]
    .filter(([d]) => from == null || d >= from)
    .sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
    .flatMap(([day, xv]) => {
      const yv = y.get(addDays(day, lagDays))
      return yv !== undefined && Number.isFinite(xv) && Number.isFinite(yv) ? [{ day, x: xv, y: yv }] : []
    })
  const n = pairs.length
  if (n < MIN_CORRELATION_DAYS) return null
  const mx = pairs.reduce((s, p) => s + p.x, 0) / n
  const my = pairs.reduce((s, p) => s + p.y, 0) / n
  let sxy = 0, sxx = 0, syy = 0
  for (const p of pairs) { sxy += (p.x - mx) * (p.y - my); sxx += (p.x - mx) ** 2; syy += (p.y - my) ** 2 }
  if (sxx <= 0 || syy <= 0) return null
  const r = Math.min(0.999999, Math.max(-0.999999, sxy / Math.sqrt(sxx * syy)))
  const z = 0.5 * Math.log((1 + r) / (1 - r))
  const se = 1 / Math.sqrt(n - 3)
  const lo = Math.tanh(z - 1.96 * se), hi = Math.tanh(z + 1.96 * se)
  return { n, r, lo, hi, slope: sxy / sxx, pairs, clear: lo > 0 || hi < 0 }
}

/** "a moderate" etc. by |r| (Cohen's rough bands: 0.1 small, 0.3 moderate, 0.5 large). */
export function strength(r: number): 'no' | 'a weak' | 'a moderate' | 'a strong' {
  const a = Math.abs(r)
  return a < 0.1 ? 'no' : a < 0.3 ? 'a weak' : a < 0.5 ? 'a moderate' : 'a strong'
}

/** "A moderate negative link: r −0.34 (95% interval −0.52 to −0.13), 64 days." (the phone's own sentence) */
export function correlationWords(c: Correlation): string {
  const dir = c.r >= 0 ? 'positive' : 'negative'
  const s = strength(c.r)
  const head = s === 'no' ? 'No clear link' : `${s[0].toUpperCase()}${s.slice(1)} ${dir} link`
  const f = (v: number) => `${v < 0 ? '−' : ''}${Math.abs(v).toFixed(2)}`
  return `${head}: r ${f(c.r)} (95% interval ${f(c.lo)} to ${f(c.hi)}), ${c.n} days.${c.clear ? '' : ' The interval includes zero.'}`
}

/** The phone's note under every comparison. */
export const ASSOCIATION_NOTE = 'An association in your own days, not proof of cause: something else (a busy week, an illness) can move both. Pick the lag before looking; trying every lag until one stands out finds links that are not there.'

export interface Panel<T> { unit: string; items: T[] }

/** One panel per unit, in the order the units first appear: metrics of one unit share an axis, a different unit gets its own. */
export function panelsByUnit<T extends { unit: string }>(items: T[]): Panel<T>[] {
  const out: Panel<T>[] = []
  for (const it of items) {
    const p = out.find((q) => q.unit === it.unit)
    if (p) p.items.push(it); else out.push({ unit: it.unit, items: [it] })
  }
  return out
}
