// One address for every metric the app can show over time (web redesign, phase 2). The metric page (#/m/<ref>) opens it
// from anywhere: a ring, one of its inputs, a daily series of the contract file, or a tile or chart of a Me tab.
//
//   charge, sleep, recovery, load, fuel, balance   a ring       score_history, since Mar 2026
//   recovery.hrv, sleep.hours, ...                 an input     score_snapshots (value and your usual), since 6 Oct 2026
//   series:hrv, series:sleep_hours, ...            a series     the contract file's tables, the whole history
//   me:BODY:Weight                                 a Me item    the points the phone sent with the tile or chart
//
// Refs of the retired History page (#/history?metric=charge|recovery.hrv) stay valid: they are the first two kinds.
import { seriesById } from './schema.ts'

export type MetricRef =
  | { kind: 'ring'; key: string }
  | { kind: 'input'; key: string }
  | { kind: 'series'; key: string }
  | { kind: 'me'; tab: string; label: string }

export const RING_KEYS = ['charge', 'recovery', 'sleep', 'load', 'fuel', 'balance'] as const

/** The ring an input belongs to, from its key's prefix (android domain/score/ScoreDomains.kt). */
export const INPUT_DOMAIN: Record<string, string> = { sleep: 'SLEEP', recovery: 'RECOVERY', load: 'LOAD', fuel: 'FUEL', gut: 'FUEL', ans: 'BALANCE', mind: 'BALANCE', rhythm: 'BALANCE' }
export const domainOfInput = (key: string): string | null => INPUT_DOMAIN[key.split('.')[0]] ?? null

/**
 * The few score inputs that are a daily series of the contract file as they are. The rest (debt, regularity, monotony, form...)
 * are worked out on the phone and have no series: their history starts with the kept mornings.
 */
export const INPUT_SERIES: Record<string, string> = {
  'recovery.hrv': 'hrv', 'recovery.rhr': 'rhr', 'recovery.energy': 'energy', 'recovery.soreness': 'soreness',
  'mind.mood': 'mood', 'mind.stress': 'stress', 'sleep.hours': 'sleep_hours', 'sleep.respiratory': 'sleep_respiratory_rate',
}

/** The design token (design/tokens `--m-*`) of each series: one fixed hue per metric, app-wide. */
export const SERIES_HUE: Record<string, string> = {
  hrv: 'm-hrv', rhr: 'm-resting-hr', steps: 'm-steps', vo2max: 'm-vo2max', spo2: 'm-spo2', calories_active: 'm-calories', calories_total: 'm-calories',
  zone_minutes: 'm-duration', sleep_hours: 'm-sleep-duration', sleep_score: 'm-sleep-score', sleep_respiratory_rate: 'm-respiratory',
  sleep_deep_min: 'm-stage-deep', sleep_rem_min: 'm-stage-rem', sleep_light_min: 'm-stage-light', energy: 'm-energy', mood: 'm-mood',
  stress: 'm-stress', soreness: 'm-soreness', body_weight: 'm-weight', body_fat_pct: 'm-body-fat', lean_mass: 'm-lean-mass',
  water_intake: 'm-hydration', meal_energy: 'm-calories', meal_protein: 'm-protein', meal_carbs: 'm-carbs', meal_fat: 'm-fat',
  meal_fiber: 'm-fiber', run_duration: 'm-duration', run_distance: 'm-distance', run_avg_hr: 'm-heart-rate', strength_duration: 'm-duration',
  session_rpe: 'm-rpe',
}

/** The ref a `#/m/<ref>` address or an old `?metric=` value names; null when nothing is known by that name. */
export function parseMetricRef(raw: string | null | undefined): MetricRef | null {
  if (!raw) return null
  if (raw.startsWith('series:')) { const key = raw.slice(7); return seriesById(key) ? { kind: 'series', key } : null }
  if (raw.startsWith('me:')) {
    const [, tab, ...rest] = raw.split(':')
    const label = rest.join(':')
    return tab && label ? { kind: 'me', tab: tab.toUpperCase(), label } : null
  }
  const lower = raw.toLowerCase()
  if ((RING_KEYS as readonly string[]).includes(lower)) return { kind: 'ring', key: lower }
  if (domainOfInput(raw)) return { kind: 'input', key: raw }
  return seriesById(raw) ? { kind: 'series', key: raw } : null // a bare series id is accepted too
}

export function refString(ref: MetricRef): string {
  switch (ref.kind) {
    case 'ring': case 'input': return ref.key
    case 'series': return `series:${ref.key}`
    case 'me': return `me:${ref.tab}:${ref.label}`
  }
}

export interface MetricLinkOpts { day?: string; range?: string }

/** The link every tappable number uses. `ref` is a ref or its string form. */
export function metricHref(ref: MetricRef | string, opts: MetricLinkOpts = {}): string {
  const s = typeof ref === 'string' ? ref : refString(ref)
  const q = new URLSearchParams()
  if (opts.day) q.set('day', opts.day)
  if (opts.range) q.set('range', opts.range)
  const qs = q.toString()
  return `#/m/${encodeURIComponent(s)}${qs ? `?${qs}` : ''}`
}

/** The raw series behind an input, when there is one: the page offers its whole history. */
export function seriesBehind(ref: MetricRef): string | null {
  return ref.kind === 'input' ? INPUT_SERIES[ref.key] ?? null : null
}

export const METRIC_RANGES = [{ id: '30', label: '30 DAYS', days: 30 }, { id: '90', label: '90 DAYS', days: 90 }, { id: '365', label: '1 YEAR', days: 365 }, { id: 'all', label: 'ALL', days: 4000 }] as const

export interface WindowStats { average: number; readings: number; lowest: { day: string; value: number }; highest: { day: string; value: number } }

/** The numbers under the chart: average, readings, lowest and highest of the points in the window (phone's MetricHistoryOverlay). */
export function windowStats(points: { day: string; value: number | null }[]): WindowStats | null {
  const have = points.filter((p): p is { day: string; value: number } => p.value != null && Number.isFinite(p.value))
  if (have.length === 0) return null
  let lo = have[0], hi = have[0], sum = 0
  for (const p of have) { sum += p.value; if (p.value < lo.value) lo = p; if (p.value > hi.value) hi = p }
  return { average: sum / have.length, readings: have.length, lowest: { day: lo.day, value: lo.value }, highest: { day: hi.day, value: hi.value } }
}
