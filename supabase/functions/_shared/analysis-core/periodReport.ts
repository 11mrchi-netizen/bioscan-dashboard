// Weekly and monthly reports (docs/reports/README.md): after each Monday-to-Sunday week and each calendar month, one report
// that compares the key metrics with the period before, scores the regimen's adherence, reads the Lab's interventions and
// experiments, and ends with a few suggestions. Suggestions are fixed rules (owner decision 2026-11-25): each one names the
// number it rests on, so the same data always gives the same report. Erasable TypeScript, no I/O (runPeriodReport.ts reads
// and writes).
//
// Dates are the person's local calendar days throughout. A period's value follows the series contract's own `period` rule
// (metric-series.json): a mean of the days that have a value, or a total for sessions; a month's totals are given per week
// so months of different lengths compare.

import { addDays, combine, daySpan, periodEnd, periodStart } from './seriesContract.ts'

export const REPORT_VERSION = '1.0.0'
export const REPORT_KIND = 'period_report'

export type ReportGrain = 'week' | 'month'

export interface ReportPeriod {
  grain: ReportGrain
  start: string
  end: string
}

/** The stored key: `week:2026-11-16` (its Monday) or `month:2026-11`. */
export function periodKey(p: ReportPeriod): string {
  return p.grain === 'week' ? `week:${p.start}` : `month:${p.start.slice(0, 7)}`
}

export function parsePeriodKey(key: string): ReportPeriod | null {
  const w = /^week:(\d{4}-\d{2}-\d{2})$/.exec(key)
  if (w && periodStart(w[1], 'week') === w[1]) return { grain: 'week', start: w[1], end: periodEnd(w[1], 'week') }
  const m = /^month:(\d{4}-\d{2})$/.exec(key)
  if (m) { const start = `${m[1]}-01`; return { grain: 'month', start, end: periodEnd(start, 'month') } }
  return null
}

/** The period of this grain that ends before [today] most recently (the week to last Sunday, the month to the last 1st). */
export function lastCompleted(today: string, grain: ReportGrain): ReportPeriod {
  const end = addDays(periodStart(today, grain), -1)
  return { grain, start: periodStart(end, grain), end }
}

export function previousOf(p: ReportPeriod): ReportPeriod {
  const end = addDays(p.start, -1)
  return { grain: p.grain, start: periodStart(end, p.grain), end }
}

/** "Week of 16 Nov 2026" or "November 2026". */
export function periodLabel(p: ReportPeriod): string {
  const months = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']
  const [y, m, d] = p.start.split('-').map(Number)
  if (p.grain === 'month') return `${months[m - 1]} ${y}`
  return `Week of ${d} ${months[m - 1].slice(0, 3)} ${y}`
}

// --- Metrics ---

export type Better = 'up' | 'down' | null
export type Section = 'recovery' | 'sleep' | 'activity' | 'body' | 'wellbeing' | 'nutrition' | 'gut'

export const SECTION_LABELS: Record<Section, string> = {
  recovery: 'Recovery', sleep: 'Sleep', activity: 'Activity and training', body: 'Body', wellbeing: 'How you felt',
  nutrition: 'Food and water', gut: 'Gut',
}

export interface ReportMetricSpec {
  /** A series of metric-series.json, or a report-only series (`stool_*`). */
  id: string
  section: Section
  better: Better
  /** Whole days with a value needed in each period for a week / a month; below it the line says "thin". */
  minDays: [number, number]
  /** The smallest change worth a word, in the metric's unit, when there is too little history to measure the usual swing. */
  minChange: number
}

/** The key metrics, in the order the report shows them (owner's list, 2026-11-25). */
export const REPORT_METRICS: readonly ReportMetricSpec[] = [
  { id: 'hrv', section: 'recovery', better: 'up', minDays: [4, 14], minChange: 3 },
  { id: 'rhr', section: 'recovery', better: 'down', minDays: [4, 14], minChange: 2 },
  { id: 'vo2max', section: 'recovery', better: 'up', minDays: [1, 1], minChange: 1 },
  { id: 'sleep_hours', section: 'sleep', better: 'up', minDays: [4, 14], minChange: 0.25 },
  { id: 'sleep_score', section: 'sleep', better: 'up', minDays: [4, 14], minChange: 3 },
  { id: 'sleep_deep_min', section: 'sleep', better: 'up', minDays: [4, 14], minChange: 8 },
  { id: 'sleep_rem_min', section: 'sleep', better: 'up', minDays: [4, 14], minChange: 8 },
  { id: 'sleep_light_min', section: 'sleep', better: null, minDays: [4, 14], minChange: 15 },
  { id: 'steps', section: 'activity', better: 'up', minDays: [4, 14], minChange: 800 },
  { id: 'zone_minutes', section: 'activity', better: 'up', minDays: [4, 14], minChange: 5 },
  { id: 'run_distance', section: 'activity', better: null, minDays: [0, 0], minChange: 3 },
  { id: 'strength_duration', section: 'activity', better: null, minDays: [0, 0], minChange: 20 },
  { id: 'session_rpe', section: 'activity', better: null, minDays: [2, 6], minChange: 0.7 },
  { id: 'body_weight', section: 'body', better: null, minDays: [1, 2], minChange: 0.4 },
  { id: 'body_fat_pct', section: 'body', better: 'down', minDays: [1, 2], minChange: 0.5 },
  { id: 'energy', section: 'wellbeing', better: 'up', minDays: [3, 10], minChange: 0.5 },
  { id: 'mood', section: 'wellbeing', better: 'up', minDays: [3, 10], minChange: 0.5 },
  { id: 'stress', section: 'wellbeing', better: 'down', minDays: [3, 10], minChange: 0.5 },
  { id: 'soreness', section: 'wellbeing', better: 'down', minDays: [3, 10], minChange: 0.5 },
  { id: 'meal_energy', section: 'nutrition', better: null, minDays: [3, 10], minChange: 150 },
  { id: 'meal_protein', section: 'nutrition', better: 'up', minDays: [3, 10], minChange: 10 },
  { id: 'meal_carbs', section: 'nutrition', better: null, minDays: [3, 10], minChange: 25 },
  { id: 'meal_fat', section: 'nutrition', better: null, minDays: [3, 10], minChange: 10 },
  { id: 'meal_fiber', section: 'nutrition', better: 'up', minDays: [3, 10], minChange: 4 },
  { id: 'water_intake', section: 'nutrition', better: 'up', minDays: [3, 10], minChange: 250 },
  { id: 'stool_normal_pct', section: 'gut', better: 'up', minDays: [3, 8], minChange: 15 },
  { id: 'stool_discomfort', section: 'gut', better: 'down', minDays: [3, 8], minChange: 0.5 },
]

/** Report-only series, built from stool_log by the reader (one value per local day). */
export const STOOL_SERIES: Record<string, { label: string; unit: string }> = {
  stool_normal_pct: { label: 'Normal stools (Bristol 3–5)', unit: '%' },
  stool_discomfort: { label: 'Stool discomfort', unit: '/10' },
}

export interface SeriesInfo { label: string; unit: string; period: 'mean' | 'sum' | 'last' }

export type DailySeries = Map<string, number>

export type LineStatus = 'better' | 'worse' | 'up' | 'down' | 'steady' | 'thin' | 'no_data'

export interface MetricLine {
  id: string
  /** The metric page's ref (#/m/<ref>); null for report-only series. */
  ref: string | null
  label: string
  unit: string
  section: Section
  better: Better
  value: number | null
  prev: number | null
  delta: number | null
  /** The usual swing between consecutive periods (SD of their changes), when at least three earlier changes exist. */
  usualSwing: number | null
  days: number
  prevDays: number
  status: LineStatus
  /** A month's total given per week. */
  perWeek: boolean
}

const round = (v: number, d = 2) => Math.round(v * 10 ** d) / 10 ** d

function daysIn(series: DailySeries, start: string, end: string): number[] {
  const out: number[] = []
  for (let d = start; d <= end; d = addDays(d, 1)) {
    const v = series.get(d)
    if (v !== undefined && Number.isFinite(v)) out.push(v)
  }
  return out
}

/** A period's value by the series' period rule; a month's total per week. */
export function periodValue(series: DailySeries, p: ReportPeriod, info: Pick<SeriesInfo, 'period'>): { value: number | null; days: number } {
  const vals = daysIn(series, p.start, p.end)
  if (info.period === 'sum') {
    const total = vals.reduce((a, b) => a + b, 0)
    const scale = p.grain === 'month' ? 7 / daySpan(p.start, p.end) : 1
    return { value: vals.length ? total * scale : 0, days: vals.length }
  }
  return { value: combine(vals, info.period).value, days: vals.length }
}

function sd(xs: number[]): number | null {
  if (xs.length < 2) return null
  const m = xs.reduce((a, b) => a + b, 0) / xs.length
  return Math.sqrt(xs.reduce((a, b) => a + (b - m) ** 2, 0) / (xs.length - 1))
}

/**
 * One metric's line. A change counts when it is larger than the usual swing between consecutive periods (the SD of the
 * changes over the earlier periods given in [history], oldest first, ending with the previous period), or, with fewer than
 * three earlier changes, larger than the spec's fixed smallest change.
 */
export function metricLine(spec: ReportMetricSpec, info: SeriesInfo, series: DailySeries, period: ReportPeriod, history: ReportPeriod[]): MetricLine {
  const cur = periodValue(series, period, info)
  const prevP = history.at(-1)
  const prev = prevP ? periodValue(series, prevP, info) : { value: null, days: 0 }
  const need = spec.minDays[period.grain === 'week' ? 0 : 1]
  const isSum = info.period === 'sum'
  const histVals = history.map((h) => periodValue(series, h, info)).filter((x) => isSum || x.days >= Math.max(need, 1)).map((x) => x.value as number)
  const changes = histVals.slice(1).map((v, i) => v - histVals[i])
  const usualSwing = changes.length >= 3 ? sd(changes) : null
  const hasCur = isSum ? true : cur.days > 0
  const hasPrev = isSum ? prevP !== undefined : prev.days > 0
  const value = hasCur ? cur.value : null
  const prevValue = hasPrev ? prev.value : null
  const delta = value !== null && prevValue !== null ? value - prevValue : null
  let status: LineStatus
  if (!isSum && cur.days === 0) status = 'no_data'
  else if (!isSum && (cur.days < need || prev.days < need)) status = 'thin'
  else if (delta === null) status = 'thin'
  else {
    const threshold = Math.max(usualSwing ?? 0, spec.minChange)
    if (Math.abs(delta) < threshold) status = 'steady'
    else if (spec.better === null) status = delta > 0 ? 'up' : 'down'
    else status = (delta > 0) === (spec.better === 'up') ? 'better' : 'worse'
  }
  const report = STOOL_SERIES[spec.id]
  return {
    id: spec.id, ref: report ? null : `series:${spec.id}`, label: report?.label ?? info.label, unit: report?.unit ?? info.unit,
    section: spec.section, better: spec.better,
    value: value === null ? null : round(value), prev: prevValue === null ? null : round(prevValue), delta: delta === null ? null : round(delta),
    usualSwing: usualSwing === null ? null : round(usualSwing), days: cur.days, prevDays: prev.days, status,
    perWeek: isSum && period.grain === 'month',
  }
}

/** The earlier periods a report measures the usual swing over, oldest first, ending with the previous period. */
export function historyOf(p: ReportPeriod): ReportPeriod[] {
  const n = p.grain === 'week' ? 8 : 6
  const out: ReportPeriod[] = []
  let q = p
  for (let i = 0; i < n; i++) { q = previousOf(q); out.unshift(q) }
  return out
}

// --- Micronutrients ---

/** Daily reference intakes for an adult man (US RDA or AI; sodium is an upper limit). Units match nutrient_intake. */
export const MICRO_REFERENCES: readonly { key: string; label: string; unit: string; ref: number; limit?: boolean }[] = [
  { key: 'fiber', label: 'Fibre', unit: 'g', ref: 38 },
  { key: 'potassium', label: 'Potassium', unit: 'mg', ref: 3400 },
  { key: 'magnesium', label: 'Magnesium', unit: 'mg', ref: 420 },
  { key: 'calcium', label: 'Calcium', unit: 'mg', ref: 1000 },
  { key: 'iron', label: 'Iron', unit: 'mg', ref: 8 },
  { key: 'zinc', label: 'Zinc', unit: 'mg', ref: 11 },
  { key: 'selenium', label: 'Selenium', unit: 'mcg', ref: 55 },
  { key: 'copper', label: 'Copper', unit: 'mg', ref: 0.9 },
  { key: 'vitamin_a', label: 'Vitamin A', unit: 'IU', ref: 3000 },
  { key: 'vitamin_c', label: 'Vitamin C', unit: 'mg', ref: 90 },
  { key: 'vitamin_d', label: 'Vitamin D', unit: 'IU', ref: 600 },
  { key: 'vitamin_e', label: 'Vitamin E', unit: 'mg', ref: 15 },
  { key: 'vitamin_k', label: 'Vitamin K', unit: 'mcg', ref: 120 },
  { key: 'vitamin_b6', label: 'Vitamin B6', unit: 'mg', ref: 1.3 },
  { key: 'vitamin_b12', label: 'Vitamin B12', unit: 'mcg', ref: 2.4 },
  { key: 'folate_b9', label: 'Folate', unit: 'mcg', ref: 400 },
  { key: 'choline', label: 'Choline', unit: 'mg', ref: 550 },
  { key: 'omega_3', label: 'Omega-3', unit: 'g', ref: 1.6 },
  { key: 'sodium', label: 'Sodium', unit: 'mg', ref: 2300, limit: true },
]

const UNIT_TO_BASE: Record<string, number> = { g: 1, mg: 1e-3, mcg: 1e-6, µg: 1e-6, ug: 1e-6 }

/** An amount in [from] expressed in [to]; null when the units cannot be converted (IU against a mass). */
export function convertUnit(amount: number, from: string, to: string): number | null {
  if (from === to) return amount
  const a = UNIT_TO_BASE[from]
  const b = UNIT_TO_BASE[to]
  return a !== undefined && b !== undefined ? (amount * a) / b : null
}

export interface MicroLine {
  key: string
  label: string
  unit: string
  /** Mean a day over the days with food logged. */
  value: number | null
  prev: number | null
  reference: number
  /** value / reference, as a percentage. */
  pct: number | null
  limit: boolean
  status: 'low' | 'ok' | 'high' | 'no_data'
}

export interface MicroSection {
  /** Days with food logged in the period (the denominator). */
  foodDays: number
  lines: MicroLine[]
}

/**
 * Micronutrients from food, a daily mean over the days with any food logged (a day with nothing logged is missing, not
 * zero). Below 70% of the reference is low; a limit (sodium) above 100% is high. Supplements are not counted.
 */
export function microSection(daily: Map<string, DailySeries>, foodDays: Set<string>, period: ReportPeriod, prev: ReportPeriod): MicroSection {
  const meanOver = (s: DailySeries | undefined, p: ReportPeriod): number | null => {
    const days = [...foodDays].filter((d) => d >= p.start && d <= p.end)
    if (!days.length) return null
    return days.reduce((a, d) => a + (s?.get(d) ?? 0), 0) / days.length
  }
  const daysNow = [...foodDays].filter((d) => d >= period.start && d <= period.end).length
  const lines = MICRO_REFERENCES.map((m): MicroLine => {
    const s = daily.get(m.key)
    const has = s !== undefined && [...s.keys()].some((d) => d >= period.start && d <= period.end)
    const value = has ? meanOver(s, period) : null
    const prevValue = s ? meanOver(s, prev) : null
    const pct = value === null ? null : (value / m.ref) * 100
    const status = pct === null ? 'no_data' : m.limit ? (pct > 100 ? 'high' : 'ok') : pct < 70 ? 'low' : 'ok'
    return {
      key: m.key, label: m.label, unit: m.unit, value: value === null ? null : round(value), prev: prevValue === null ? null : round(prevValue),
      reference: m.ref, pct: pct === null ? null : Math.round(pct), limit: !!m.limit, status,
    }
  })
  return { foodDays: daysNow, lines }
}

// --- Regimen ---

export interface RegimenItem {
  id: number
  name: string
  kind: string | null
  status: string | null
  slots: string[]
  /** daily | every_n_days | weekdays | as_needed */
  type: string
  n: number | null
  /** ISO weekdays, Monday = 1. */
  weekdays: number[]
  cycle: { onDays: number; offDays: number; start: string } | null
  startDate: string | null
  endDate: string | null
}

/** A row of `supplements` as the regimen model reads it (android domain/Regimen.kt `regimenSpec`, legacy fallback included). */
export function regimenItem(row: {
  id: number; name: string; kind?: string | null; status?: string | null; time_of_day?: string | null; slots?: string[] | null
  every_n_days?: number | null; schedule?: { type?: string; n?: number; days?: number[]; cycle?: { on_days: number; off_days: number; start: string } } | null
  start_date?: string | null; end_date?: string | null
}): RegimenItem {
  const SLOTS = ['morning', 'afternoon', 'night']
  const tod = (row.time_of_day ?? '').toLowerCase()
  const legacyAsNeeded = tod === 'as-needed'
  const known = ['daily', 'every_n_days', 'weekdays', 'as_needed']
  const type = row.schedule?.type && known.includes(row.schedule.type) ? row.schedule.type
    : legacyAsNeeded ? 'as_needed' : (row.every_n_days ?? 0) > 1 ? 'every_n_days' : 'daily'
  const raw = (row.slots ?? []).length ? row.slots! : legacyAsNeeded || row.schedule ? [] : [tod]
  const slots = [...new Set(raw.filter((s) => SLOTS.includes(s)))].sort((a, b) => SLOTS.indexOf(a) - SLOTS.indexOf(b))
  const c = row.schedule?.cycle
  return {
    id: row.id, name: row.name, kind: row.kind ?? null, status: row.status ?? null,
    slots: type === 'as_needed' ? [] : slots, type,
    n: type === 'every_n_days' ? ((row.schedule?.n ?? row.every_n_days) ?? null) : null,
    weekdays: row.schedule?.days ?? [],
    cycle: c && c.on_days > 0 && c.off_days > 0 ? { onDays: c.on_days, offDays: c.off_days, start: c.start } : null,
    startDate: row.start_date ?? null, endDate: row.end_date ?? null,
  }
}

const asNeeded = (it: RegimenItem) => it.type === 'as_needed' || it.slots.length === 0
const isoWeekday = (d: string) => ((new Date(`${d}T00:00:00Z`).getUTCDay() + 6) % 7) + 1

function calendarDay(it: RegimenItem, d: string): boolean {
  if (asNeeded(it)) return false
  if (it.startDate && d < it.startDate) return false
  if (it.endDate && d > it.endDate) return false
  if (it.cycle && d >= it.cycle.start) {
    const period = it.cycle.onDays + it.cycle.offDays
    if ((daySpan(it.cycle.start, d) - 1) % period >= it.cycle.onDays) return false
  }
  return it.type === 'weekdays' ? it.weekdays.includes(isoWeekday(d)) : true
}

/** Planned doses in [from, to]: dosing days (every-N-days plans one in N) times the times of day. Regimen.kt `plannedDays`. */
export function plannedDoses(it: RegimenItem, from: string, to: string): number {
  if (asNeeded(it)) return 0
  let days = 0
  for (let d = from; d <= to; d = addDays(d, 1)) if (calendarDay(it, d)) days++
  if (it.type === 'every_n_days') days = Math.ceil(days / Math.max(1, it.n ?? 1))
  return days * it.slots.length
}

export interface AdherenceLine {
  id: number
  name: string
  kind: string | null
  asNeeded: boolean
  planned: number
  /** Doses logged, at most the planned times of day on any one day. */
  taken: number
  /** taken / planned; null for as-needed or nothing planned. */
  rate: number | null
  prevRate: number | null
  /** Days since the item started, at the period's end. */
  daysOn: number | null
  status: 'followed' | 'partly' | 'not_followed' | 'as_needed'
}

export const ADHERENCE = { followed: 0.85, notFollowed: 0.7 }

/** Doses counted towards adherence: per day, at most its planned times of day (two logs of a once-a-day item count once). */
function takenIn(it: RegimenItem, takes: Map<string, number>, from: string, to: string): number {
  let n = 0
  for (const [d, c] of takes) if (d >= from && d <= to) n += asNeeded(it) ? c : Math.min(c, it.slots.length)
  return n
}

export function adherenceLine(it: RegimenItem, takes: Map<string, number>, period: ReportPeriod, prev: ReportPeriod): AdherenceLine {
  const planned = plannedDoses(it, period.start, period.end)
  const taken = takenIn(it, takes, period.start, period.end)
  const prevPlanned = plannedDoses(it, prev.start, prev.end)
  const rate = planned > 0 ? Math.min(1, taken / planned) : null
  const prevRate = prevPlanned > 0 ? Math.min(1, takenIn(it, takes, prev.start, prev.end) / prevPlanned) : null
  const status = asNeeded(it) ? 'as_needed' : rate === null ? 'as_needed' : rate >= ADHERENCE.followed ? 'followed' : rate >= ADHERENCE.notFollowed ? 'partly' : 'not_followed'
  return {
    id: it.id, name: it.name, kind: it.kind, asNeeded: asNeeded(it), planned, taken,
    rate: rate === null ? null : round(rate, 3), prevRate: prevRate === null ? null : round(prevRate, 3),
    daysOn: it.startDate ? daySpan(it.startDate, period.end) : null, status,
  }
}

/** The items the report covers: those whose dates touch the period. */
export function itemsInPeriod(items: RegimenItem[], p: ReportPeriod): RegimenItem[] {
  return items.filter((it) => (!it.startDate || it.startDate <= p.end) && (!it.endDate || it.endDate >= p.start))
}

// --- Lab ---

export type LabVerdict = 'working' | 'no_effect' | 'not_followed' | 'too_early' | 'worse'

export interface LabLine {
  /** intervention | experiment */
  source: 'intervention' | 'experiment'
  id: string
  name: string
  verdict: LabVerdict
  /** One sentence, the numbers behind the verdict. */
  detail: string
  /** The linked regimen item, when there is one. */
  supplementId: number | null
  /** Where the Lab shows it. */
  href: string
}

/** Which way is better for the metrics the Lab engines read (experiment.ts MetricId and the frame's series). */
export const LAB_BETTER: Record<string, Better> = {
  hrv: 'up', rhr: 'down', sleep_hours: 'up', deep_rem_min: 'up', energy: 'up', mood: 'up', stress: 'down', soreness: 'down',
  fueling_carb_rate: 'up', sleep_score: 'up', steps: 'up',
}

export interface LabMetricRead {
  metric: string
  label: string
  /** What the engine concluded: clear / signal means a change was found, null means not yet decided. */
  outcome: 'change' | 'none' | 'undecided'
  diff: number | null
  unit: string
}

/** One verdict from the engine reads and the adherence (null when not tracked). Not followed wins over every result. */
export function labVerdict(reads: LabMetricRead[], adherence: number | null): { verdict: LabVerdict; detail: string } {
  if (adherence !== null && adherence < ADHERENCE.notFollowed) {
    return { verdict: 'not_followed', detail: `Followed on ${Math.round(adherence * 100)}% of planned days, too few for its result to mean anything.` }
  }
  const decided = reads.filter((r) => r.outcome !== 'undecided')
  if (!decided.length) return { verdict: 'too_early', detail: reads.length ? 'Still collecting days; no result yet.' : 'Nothing measured for it yet.' }
  const fmt = (r: LabMetricRead) => `${r.label} ${r.diff !== null && r.diff > 0 ? '+' : ''}${r.diff === null ? '' : round(r.diff, 1)}${r.unit ? ` ${r.unit}` : ''}`.trim()
  const good = decided.filter((r) => r.outcome === 'change' && r.diff !== null && LAB_BETTER[r.metric] && (r.diff > 0) === (LAB_BETTER[r.metric] === 'up'))
  const bad = decided.filter((r) => r.outcome === 'change' && r.diff !== null && LAB_BETTER[r.metric] && (r.diff > 0) !== (LAB_BETTER[r.metric] === 'up'))
  if (good.length && !bad.length) return { verdict: 'working', detail: `${good.map(fmt).join(', ')} with it.` }
  if (bad.length && !good.length) return { verdict: 'worse', detail: `${bad.map(fmt).join(', ')} with it.` }
  if (good.length && bad.length) return { verdict: 'working', detail: `Mixed: ${good.map(fmt).join(', ')}; but ${bad.map(fmt).join(', ')}.` }
  return { verdict: 'no_effect', detail: `No change found in ${decided.map((r) => r.label).join(', ')}.` }
}

// --- Food and stool (tentative) ---

export interface FoodStoolLine {
  food: string
  label: string
  /** Share of normal stools (Bristol 3-5) the day after a high day vs a low day, in percent. */
  highPct: number
  lowPct: number
  highDays: number
  lowDays: number
  /** Median that splits high from low, in the food's unit. */
  split: number
  unit: string
}

export const FOOD_STOOL = { windowDays: 90, minEachSide: 8, minGap: 20 }

/**
 * Tentative, an association only: over the 90 days to the period's end, the share of normal stools on the day after a
 * day above the median of a food against the day after one below it. Shown only with eight days each side and a gap of
 * 20 points or more.
 */
export function foodStool(
  foods: { key: string; label: string; unit: string; series: DailySeries }[],
  normalByDay: DailySeries,
  end: string,
): FoodStoolLine[] {
  const start = addDays(end, -(FOOD_STOOL.windowDays - 1))
  const out: FoodStoolLine[] = []
  for (const f of foods) {
    const pairs: { x: number; y: number }[] = []
    for (const [d, x] of f.series) {
      if (d < start || d > end) continue
      const y = normalByDay.get(addDays(d, 1))
      if (y !== undefined) pairs.push({ x, y })
    }
    if (pairs.length < FOOD_STOOL.minEachSide * 2) continue
    const xs = pairs.map((p) => p.x).sort((a, b) => a - b)
    const split = xs[Math.floor(xs.length / 2)]
    // Ties at the median go to the side that leaves both sides fuller.
    let high = pairs.filter((p) => p.x > split)
    let low = pairs.filter((p) => p.x <= split)
    if (high.length < low.length) { high = pairs.filter((p) => p.x >= split); low = pairs.filter((p) => p.x < split) }
    if (high.length < FOOD_STOOL.minEachSide || low.length < FOOD_STOOL.minEachSide) continue
    const pct = (ps: { y: number }[]) => Math.round(ps.reduce((a, p) => a + p.y, 0) / ps.length)
    const line = { food: f.key, label: f.label, unit: f.unit, highPct: pct(high), lowPct: pct(low), highDays: high.length, lowDays: low.length, split: round(split, 1) }
    if (Math.abs(line.highPct - line.lowPct) >= FOOD_STOOL.minGap) out.push(line)
  }
  return out
}

// --- Suggestions ---

export interface Suggestion {
  id: string
  /** 1 most pressing .. 3 */
  priority: 1 | 2 | 3
  text: string
  /** The number it rests on. */
  because: string
  ref: string | null
}

export const TARGETS = { sleepHours: 7, steps: 7000, waterMl: 2500, fiberG: 30, proteinPerKg: 1.6, untestedDays: 90 }

const fmtN = (v: number, d = 1) => (Math.abs(v) >= 100 ? Math.round(v).toLocaleString('en-US') : String(Number(v.toFixed(d))))

/** What to do about a metric that moved the wrong way, in words. */
const WORSE_ADVICE: Record<string, string> = {
  hrv: 'HRV dropped: ease training intensity for a few days and protect sleep; check alcohol and late meals.',
  rhr: 'Resting heart rate rose: a sign of accumulated load, illness or poor sleep. Plan an easier few days.',
  sleep_hours: 'Sleep got shorter: set a fixed lights-out and keep screens out of the last half hour.',
  sleep_score: 'Sleep quality slipped: keep bed and wake times within 30 minutes of each other.',
  sleep_deep_min: 'Deep sleep fell: avoid late hard sessions, alcohol and heavy meals within three hours of bed.',
  sleep_rem_min: 'REM sleep fell: REM comes late in the night, so a later or more regular wake time helps.',
  steps: 'Fewer steps: add a 20-minute walk after one meal a day.',
  zone_minutes: 'Less time in heart-rate zones: schedule two easy aerobic sessions.',
  body_fat_pct: 'Body fat went up: check the energy intake against training.',
  energy: 'Energy was lower: look at sleep and training load first.',
  mood: 'Mood was lower: daylight outside in the morning and social time are the cheapest levers.',
  stress: 'Stress was higher: add a breathwork or downtime block on the hardest days.',
  soreness: 'More soreness: space hard sessions and keep protein up after them.',
  meal_protein: 'Protein went down: add one protein-rich food to the meal that has none.',
  meal_fiber: 'Fibre went down: add legumes, oats or vegetables to one meal a day.',
  water_intake: 'Less water: keep a bottle in sight and drink one glass with every meal.',
  stool_normal_pct: 'Fewer normal stools: look at fibre and water, and at what changed in the food.',
  stool_discomfort: 'More gut discomfort: note the foods on the day before the uncomfortable ones.',
}

export interface SuggestionInputs {
  metrics: MetricLine[]
  micro: MicroSection
  regimen: AdherenceLine[]
  lab: LabLine[]
  /** The latest body weight, kg, for the protein target. */
  weightKg: number | null
  /** Regimen items with no intervention or experiment measuring them. */
  untested: AdherenceLine[]
}

/** Every rule that fires, most pressing first; the report shows the first five. */
export function suggestions(x: SuggestionInputs): Suggestion[] {
  const out: Suggestion[] = []
  const m = new Map(x.metrics.map((l) => [l.id, l]))
  const val = (id: string) => { const l = m.get(id); return l && l.status !== 'no_data' && l.status !== 'thin' ? l.value : null }

  const sleep = val('sleep_hours')
  if (sleep !== null && sleep < TARGETS.sleepHours) {
    out.push({ id: 'sleep_short', priority: 1, ref: 'series:sleep_hours', text: `Sleep averaged ${fmtN(sleep)} h: aim for at least ${TARGETS.sleepHours} h by moving lights-out earlier by 30 minutes.`, because: `sleep ${fmtN(sleep)} h a night` })
  }
  for (const l of x.metrics) {
    if (l.status !== 'worse' || !WORSE_ADVICE[l.id]) continue
    if (l.id === 'sleep_hours' && out.some((s) => s.id === 'sleep_short')) continue
    const pri = ['hrv', 'rhr', 'sleep_hours', 'sleep_score'].includes(l.id) ? 1 : 2
    out.push({ id: `worse_${l.id}`, priority: pri, ref: l.ref, text: WORSE_ADVICE[l.id], because: `${l.label} ${fmtN(l.prev!)} → ${fmtN(l.value!)} ${l.unit}`.trim() })
  }
  const protein = val('meal_protein')
  if (protein !== null && x.weightKg) {
    const target = TARGETS.proteinPerKg * x.weightKg
    if (protein < target) out.push({ id: 'protein_low', priority: 2, ref: 'series:meal_protein', text: `Protein averaged ${Math.round(protein)} g a day, under ${Math.round(target)} g (${TARGETS.proteinPerKg} g per kg): add a protein source to breakfast.`, because: `${Math.round(protein)} g of ${Math.round(target)} g` })
  }
  const fiber = val('meal_fiber')
  if (fiber !== null && fiber < TARGETS.fiberG && !out.some((s) => s.id === 'worse_meal_fiber')) {
    out.push({ id: 'fiber_low', priority: 3, ref: 'series:meal_fiber', text: `Fibre averaged ${Math.round(fiber)} g a day: aim for ${TARGETS.fiberG} g with legumes, oats, berries or vegetables.`, because: `${Math.round(fiber)} g of ${TARGETS.fiberG} g` })
  }
  const water = val('water_intake')
  if (water !== null && water < TARGETS.waterMl && !out.some((s) => s.id === 'worse_water_intake')) {
    out.push({ id: 'water_low', priority: 3, ref: 'series:water_intake', text: `Water averaged ${(water / 1000).toFixed(1)} L a day: aim for ${(TARGETS.waterMl / 1000).toFixed(1)} L, more on training days.`, because: `${Math.round(water)} mL a day` })
  }
  const steps = val('steps')
  if (steps !== null && steps < TARGETS.steps && !out.some((s) => s.id === 'worse_steps')) {
    out.push({ id: 'steps_low', priority: 3, ref: 'series:steps', text: `Steps averaged ${Math.round(steps).toLocaleString('en-US')} a day: a 20-minute walk adds about 2,000.`, because: `${Math.round(steps)} steps a day` })
  }
  const lowMicros = x.micro.lines.filter((l) => l.status === 'low' && l.key !== 'fiber').sort((a, b) => (a.pct ?? 0) - (b.pct ?? 0)).slice(0, 2)
  if (lowMicros.length && x.micro.foodDays >= 3) {
    out.push({ id: 'micro_low', priority: 3, ref: null, text: `From food, ${lowMicros.map((l) => `${l.label} reached ${l.pct}%`).join(' and ')} of the daily reference. Check whether a supplement already covers ${lowMicros.length > 1 ? 'them' : 'it'}; if not, add a food rich in ${lowMicros.length > 1 ? 'them' : 'it'}.`, because: `${x.micro.foodDays} days of food logged` })
  }
  const sodium = x.micro.lines.find((l) => l.key === 'sodium' && l.status === 'high')
  if (sodium && x.micro.foodDays >= 3) out.push({ id: 'sodium_high', priority: 3, ref: null, text: `Sodium averaged ${Math.round(sodium.value!)} mg a day, above the ${sodium.reference} mg limit.`, because: `${sodium.pct}% of the limit` })

  for (const r of x.regimen.filter((a) => a.status === 'not_followed')) {
    out.push({ id: `regimen_${r.id}`, priority: 2, ref: null, text: `${r.name} was taken ${r.taken} of ${r.planned} planned times. Either set it to a time you will keep or take it off the regimen.`, because: `${Math.round((r.rate ?? 0) * 100)}% adherence` })
  }
  for (const l of x.lab) {
    if (l.verdict === 'no_effect') out.push({ id: `lab_${l.id}`, priority: 2, ref: null, text: `${l.name}: ${l.detail} Consider stopping it, or test it on another outcome.`, because: 'Lab result' })
    if (l.verdict === 'worse') out.push({ id: `lab_${l.id}`, priority: 1, ref: null, text: `${l.name}: ${l.detail} Consider stopping it.`, because: 'Lab result' })
    if (l.verdict === 'not_followed') out.push({ id: `lab_${l.id}`, priority: 2, ref: null, text: `${l.name}: ${l.detail} Restart it properly or end the test.`, because: 'Lab adherence' })
  }
  const longUntested = x.untested.filter((u) => !u.asNeeded && (u.daysOn ?? 0) >= TARGETS.untestedDays && u.status === 'followed')
  if (longUntested.length) {
    const names = longUntested.slice(0, 3).map((u) => u.name)
    out.push({ id: 'untested', priority: 3, ref: null, text: `${names.join(', ')}${longUntested.length > 3 ? ` and ${longUntested.length - 3} more` : ''} ${longUntested.length > 1 ? 'have' : 'has'} been taken for months with nothing measuring ${longUntested.length > 1 ? 'them' : 'it'}. Start an intervention in Lab for the one you are least sure of.`, because: `${longUntested.length} items without a test` })
  }
  const thin = x.metrics.filter((l) => l.status === 'thin' && ['energy', 'mood', 'stress', 'meal_energy', 'water_intake'].includes(l.id))
  if (thin.length >= 2) out.push({ id: 'log_more', priority: 3, ref: null, text: `Too few days logged for ${thin.map((l) => l.label.toLowerCase()).join(', ')} to compare: a daily check-in makes next report's verdicts possible.`, because: `${thin.length} metrics thin` })

  return out.sort((a, b) => a.priority - b.priority)
}

// --- The report ---

export interface PeriodReport {
  version: string
  key: string
  grain: ReportGrain
  label: string
  start: string
  end: string
  prevStart: string
  prevEnd: string
  generatedOn: string
  metrics: MetricLine[]
  micro: MicroSection
  regimen: AdherenceLine[]
  lab: LabLine[]
  foodStool: FoodStoolLine[]
  /** Short lists for the top of the report. */
  summary: { better: string[]; worse: string[]; working: string[]; wasted: string[]; notFollowed: string[] }
  suggestions: Suggestion[]
}

export const MAX_SUGGESTIONS = 5

export function buildReport(x: {
  period: ReportPeriod
  today: string
  metrics: MetricLine[]
  micro: MicroSection
  regimen: AdherenceLine[]
  lab: LabLine[]
  foodStool: FoodStoolLine[]
  weightKg: number | null
}): PeriodReport {
  const prev = previousOf(x.period)
  const tested = new Set(x.lab.map((l) => l.supplementId).filter((v): v is number => v !== null))
  const untested = x.regimen.filter((r) => !tested.has(r.id))
  const all = suggestions({ metrics: x.metrics, micro: x.micro, regimen: x.regimen, lab: x.lab, weightKg: x.weightKg, untested })
  const notFollowed = [...x.regimen.filter((r) => r.status === 'not_followed').map((r) => r.name), ...x.lab.filter((l) => l.verdict === 'not_followed').map((l) => l.name)]
  return {
    version: REPORT_VERSION, key: periodKey(x.period), grain: x.period.grain, label: periodLabel(x.period),
    start: x.period.start, end: x.period.end, prevStart: prev.start, prevEnd: prev.end, generatedOn: x.today,
    metrics: x.metrics, micro: x.micro, regimen: x.regimen, lab: x.lab, foodStool: x.foodStool,
    summary: {
      better: x.metrics.filter((l) => l.status === 'better').map((l) => l.label),
      worse: x.metrics.filter((l) => l.status === 'worse').map((l) => l.label),
      working: x.lab.filter((l) => l.verdict === 'working').map((l) => l.name),
      wasted: x.lab.filter((l) => l.verdict === 'no_effect' || l.verdict === 'worse').map((l) => l.name),
      notFollowed: [...new Set(notFollowed)],
    },
    suggestions: all.slice(0, MAX_SUGGESTIONS),
  }
}
