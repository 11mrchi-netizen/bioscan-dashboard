// One address for every metric the app can show over time (web redesign, phase 2). The metric page (#/m/<ref>) opens it
// from anywhere: a ring, one of its inputs, a daily series of the contract file, or a tile or chart of a Me tab.
//
//   charge, sleep, recovery, load, fuel, balance   a ring       score_history, since Mar 2026
//   recovery.hrv, sleep.hours, ...                 an input     score_snapshots (value and your usual), since 6 Oct 2026
//   series:hrv, series:sleep_hours, ...            a series     the contract file's tables, the whole history
//   me:BODY:Weight                                 a Me item    the points the phone sent with the tile or chart
//   me:BODY:SECTION::Weight                        the same, in the section of that title (labels can repeat within a tab)
//   lab:Ferritin                                   a blood marker  lab_results, one point per draw, with the lab's range
//   test:grip:left, test:grip, test:sweat_rate     a phone test    grip_strength_log (best of the day), sweat_tests
//
// Refs of the retired History page (#/history?metric=charge|recovery.hrv) stay valid: they are the first two kinds.
import { SERIES, seriesById } from './schema.ts'

export type MetricRef =
  | { kind: 'ring'; key: string }
  | { kind: 'input'; key: string }
  | { kind: 'series'; key: string }
  | { kind: 'me'; tab: string; label: string }
  | { kind: 'lab'; key: string }
  | { kind: 'test'; key: string }

/** The tests of Lab › TESTS that have a number to follow: grip, for one hand or the best of either, and the sweat rate. */
export const isTestKey = (key: string): boolean => key === 'sweat_rate' || /^grip(:[a-z]+)?$/.test(key)

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
  gut_day_score: 'm-gut-comfort', energy_availability: 'm-energy-availability',
  watch_stress: 'm-phys-stress', watch_stress_high_share: 'm-phys-stress', energy_balance: 'm-calories',
}

/**
 * The ink of an outcome in the Lab's charts (the evidence board, the decision queue, experiments, discovery): the hue of the
 * metric it measures. The keys are the outcome ids of the catalogue (hypotheses.ts: hrv, rhr, sleep, energy, sleep_score,
 * recovery_score) and the metric ids of the experiments (experiment.ts METRIC_INFO). Recovery score is a domain score, so it
 * takes the Recovery domain's hue, as a domain does in CHARGE's list.
 */
export const OUTCOME_HUE: Record<string, string> = {
  hrv: 'm-hrv', rhr: 'm-resting-hr', sleep: 'm-sleep-duration', sleep_hours: 'm-sleep-duration', deep_rem_min: 'm-stage-deep',
  energy: 'm-energy', mood: 'm-mood', stress: 'm-stress', soreness: 'm-soreness', resp_rate: 'm-respiratory',
  fueling_carb_rate: 'm-carbs', screen_bed_min: 'm-night-screen', screen_pm_social: 'm-time-phone', outdoor_daylight_min: 'm-daylight',
  sleep_score: 'm-sleep-score', recovery_score: 'ft-domain-cardio', gut: 'm-gut-comfort', gut_day_score: 'm-gut-comfort',
}

/** `var(--token)` of an outcome or a series; a chart that names no known metric draws in the neutral weight-trend hue (contract). */
export const metricInk = (key: string | null | undefined): string =>
  `var(--${(key && (OUTCOME_HUE[key] ?? SERIES_HUE[key])) || 'm-weight-ema'})`

/**
 * The metric hue (design/tokens `--m-*`) of each score input: a copy of android ui/theme/MetricInk.kt, so a metric has the
 * same colour on the phone and here. Direction is never told by colour. inputs.test.ts checks every key of ScoreDomains.kt is here.
 */
export const INPUT_HUE: Record<string, string> = {
  'sleep.hours': 'm-sleep-duration', 'sleep.debt_7d': 'm-sleep-duration', 'sleep.regularity_min': 'm-regularity', 'sleep.midpoint_min': 'm-circadian',
  'sleep.deep_rem_min': 'm-stage-deep', 'sleep.respiratory': 'm-respiratory', 'recovery.hrv': 'm-hrv', 'ans.hrv_cv_7d': 'm-hrv', 'recovery.rhr': 'm-resting-hr',
  'recovery.night_stress': 'm-phys-stress', 'ans.stressed_pct': 'm-phys-stress', 'ans.relaxed_pct': 'z-stress-calm', 'recovery.soreness': 'm-soreness',
  'recovery.energy': 'm-energy', 'recovery.morning_erection': 'm-arousal', 'recovery.arousal': 'm-arousal', 'load.tsb': 'm-tsb',
  'load.days_without_rest': 'm-atl', 'load.monotony': 'm-atl', 'load.plan_done_7d': 'm-ctl', 'fuel.logged_share': 'm-calories', 'fuel.energy_ratio': 'm-calories',
  'fuel.protein_ratio': 'm-protein', 'fuel.carb_ratio': 'm-carbs', 'fuel.water_ratio': 'm-hydration', 'fuel.drinks': 'm-sugar', 'fuel.late_caffeine': 'm-caffeine',
  'gut.symptoms': 'm-gut-comfort', 'gut.timing_min': 'm-stool-healthy', 'gut.timing_spread_min': 'm-stool-healthy', 'gut.frequency': 'm-stool-healthy',
  'gut.session': 'm-stool-healthy', 'gut.form_3d': 'm-stool-loose', 'ans.wind_down': 'm-night-screen', 'ans.hr_dip_pct': 'm-hr-dip', 'mind.stress': 'm-stress',
  'mind.mood': 'm-mood', 'rhythm.late_screen': 'm-phone-sleep', 'rhythm.meal_timing_spread_min': 'm-meal-timing',
}

/** The ref a `#/m/<ref>` address or an old `?metric=` value names; null when nothing is known by that name. */
export function parseMetricRef(raw: string | null | undefined): MetricRef | null {
  if (!raw) return null
  if (raw.startsWith('series:')) { const key = raw.slice(7); return seriesById(key) ? { kind: 'series', key } : null }
  if (raw.startsWith('lab:')) { const key = raw.slice(4); return key.trim() ? { kind: 'lab', key } : null }
  if (raw.startsWith('test:')) { const key = raw.slice(5).toLowerCase(); return isTestKey(key) ? { kind: 'test', key } : null }
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

/** A Me ref's label split into the section title and the item's label; no title when the ref names the label alone. */
export function splitMeLabel(label: string): { section: string | null; item: string } {
  const i = label.indexOf('::')
  return i < 0 ? { section: null, item: label } : { section: label.slice(0, i), item: label.slice(i + 2) }
}

/** The ref of a Me tile or chart: its section title keeps two items of one label apart. */
export const meRef = (tab: string, section: string, label: string): string => `me:${tab}:${section}::${label}`

export function refString(ref: MetricRef): string {
  switch (ref.kind) {
    case 'ring': case 'input': return ref.key
    case 'series': return `series:${ref.key}`
    case 'me': return `me:${ref.tab}:${ref.label}`
    case 'lab': return `lab:${ref.key}`
    case 'test': return `test:${ref.key}`
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

/** The smallest range that holds a focused morning (a day opened from Today, maybe months back), so its marker is on the chart. */
export function rangeFor(focus: string | null, today: string): (typeof METRIC_RANGES)[number] {
  const dflt = METRIC_RANGES[1]
  if (!focus || !/^\d{4}-\d{2}-\d{2}$/.test(focus)) return dflt
  const ago = Math.round((Date.parse(`${today}T12:00:00Z`) - Date.parse(`${focus}T12:00:00Z`)) / 864e5)
  if (!Number.isFinite(ago) || ago < 0) return dflt
  const fit = METRIC_RANGES.find((r) => r.days > ago)
  return !fit || fit.days < dflt.days ? dflt : fit
}

const DOMAIN_WASH: Record<string, string> = { SLEEP: 'ft-domain-sleep', RECOVERY: 'ft-domain-cardio', LOAD: 'ft-domain-load', FUEL: 'ft-domain-fuel', BALANCE: 'ft-domain-balance' }
export const SERIES_DOMAIN: Record<string, string> = {
  hrv: 'RECOVERY', rhr: 'RECOVERY', spo2: 'RECOVERY', vo2max: 'RECOVERY', energy: 'RECOVERY', soreness: 'RECOVERY',
  steps: 'LOAD', calories_active: 'LOAD', calories_total: 'LOAD', zone_minutes: 'LOAD', run_duration: 'LOAD', run_distance: 'LOAD', run_avg_hr: 'LOAD', strength_duration: 'LOAD', session_rpe: 'LOAD',
  water_intake: 'FUEL', meal_energy: 'FUEL', meal_protein: 'FUEL', meal_carbs: 'FUEL', meal_fat: 'FUEL', meal_fiber: 'FUEL',
  gut_day_score: 'FUEL', energy_availability: 'FUEL', energy_balance: 'FUEL',
}

/** The page wash of a metric (visual contract: background = domain): its ring's domain; CHARGE its own wash; Me items and the rest, BALANCE. */
export function washFor(ref: MetricRef): string {
  if (ref.kind === 'ring') return ref.key === 'charge' ? 'ft-charge-b' : DOMAIN_WASH[ref.key.toUpperCase()] ?? 'ft-domain-balance'
  if (ref.kind === 'input') return DOMAIN_WASH[domainOfInput(ref.key) ?? ''] ?? 'ft-domain-balance'
  if (ref.kind === 'series') return DOMAIN_WASH[SERIES_DOMAIN[ref.key] ?? (ref.key.startsWith('sleep_') ? 'SLEEP' : '')] ?? 'ft-domain-balance'
  if (ref.kind === 'test') return ref.key === 'sweat_rate' ? DOMAIN_WASH.FUEL : DOMAIN_WASH.LOAD
  return 'ft-domain-balance'
}

/** The ring a series is grouped under in the picker: its entry of SERIES_DOMAIN, sleep_* under SLEEP, the rest under BODY AND MIND. */
export function seriesGroup(id: string): string {
  return SERIES_DOMAIN[id] ?? (id.startsWith('sleep_') ? 'SLEEP' : 'BALANCE')
}

/** The most metrics drawn beside the one the page is about. */
export const MAX_COMPARE = 3

/** The extra metrics of an address (`?with=a&with=b`, one parameter each: refs hold commas and colons), known, distinct, not the page's own. */
export function compareRefs(withParams: string[], own: MetricRef | null): MetricRef[] {
  const seen = new Set<string>(own ? [refString(own)] : [])
  const out: MetricRef[] = []
  for (const raw of withParams) {
    const ref = parseMetricRef(raw)
    if (!ref || ref.kind === 'me') continue // a Me item can be the page's metric, not yet an extra
    const key = refString(ref)
    if (seen.has(key)) continue
    seen.add(key); out.push(ref)
    if (out.length === MAX_COMPARE) break
  }
  return out
}

export type SessionField = 'duration' | 'distance' | 'avg_hr' | 'rpe'
const SESSION_COLUMN: Record<SessionField, string> = { duration: 'duration_min', distance: 'distance_km', avg_hr: 'avg_hr', rpe: 'rpe' }

/**
 * The series a number of a recorded session belongs to (run distance, strength duration, RPE...), read from the contract
 * file's filters so a series added there is linked here too; null when no series follows that number for that type (a ride's
 * distance), and the number is then shown as plain text.
 */
export function sessionSeries(type: string | null, field: SessionField): string | null {
  const col = SESSION_COLUMN[field]
  const s = SERIES.find((x) => x.table === 'exercise_sessions' && x.column === col && (!x.filters?.type || (type != null && x.filters.type.includes(type))))
  return s?.id ?? null
}
