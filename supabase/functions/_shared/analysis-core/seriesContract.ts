// Analysis hub, phase A (docs/analysis-console/PLAN.md): the query contract of every stored series, shared by the console
// and the analysis job so they read time, aggregate and judge quality the same way. The contracts themselves are data
// (../metric-registry/metric-series.json); this module describes their shape, checks them and holds the rules that use
// them. Erasable TypeScript, no I/O.
//
// Rules this module enforces:
//  - Time is declared per series. Calendar `date` columns are already the local day. Session and (since 2026-09-17) meal
//    timestamps are local wall time labelled UTC: the local day is the first ten characters. Sleep bedtimes and the meals
//    backfilled before 2026-09-17 are true UTC instants: the local day needs an offset (the record's, else the default,
//    and then the date is marked as resting on an assumed offset).
//  - Several rows on one local day combine with the series' `day` method; several days in a week or month combine with
//    its `period` method. A daily total (steps) is never summed within a day: duplicate rows would double it.
//  - Missing is never zero. A day or period without an observation has value null and n 0, and every period says how
//    many of its days were observed, so an aggregate is never shown as if it were one reading.
//  - Weeks start on Monday (ISO 8601); months are calendar months. Periods are clipped to the requested range and say so.

export const TIME_KINDS = ['local_date', 'local_wall', 'utc'] as const
export type TimeKind = (typeof TIME_KINDS)[number]

export const METHODS = ['mean', 'sum', 'last'] as const
export type Method = (typeof METHODS)[number]

export const GRAINS = ['day', 'week', 'month'] as const
export type Grain = (typeof GRAINS)[number]

export const CADENCES = ['daily', 'per_event'] as const
export type Cadence = (typeof CADENCES)[number]

/** Rows whose raw time value starts (first ten characters) before `before` are read with `kind` instead. */
export interface TimeException {
  before: string
  kind: TimeKind
  note: string
}

export interface TimeContract {
  column: string
  kind: TimeKind
  /** For 'utc': a column holding the record's UTC offset in minutes. Without it, or when null, the default offset is assumed. */
  offsetColumn?: string
  exceptions?: TimeException[]
}

export interface SeriesContract {
  /** Stable id, snake_case. */
  id: string
  /** Key of the methodology entry in metric-registry.json, or null while the series has none yet. */
  metric: string | null
  label: string
  unit: string
  table: string
  column: string
  time: TimeContract
  /** 'daily': at most one row a day is expected; 'per_event': any number (sessions, meals). */
  cadence: Cadence
  /** How several rows on one local day combine. */
  day: Method
  /** How several days in a week or month combine. */
  period: Method
  /** The grain for ranges of three to twelve months: 'day' for slow, sparse series, 'week' otherwise. */
  longGrain: 'day' | 'week'
  /** Inclusive plausible range; a value outside it is invalid, not an outlier. Null when no range is defined. */
  plausible: [number, number] | null
  /** Extra row filters: column -> allowed values. */
  filters?: Record<string, (string | number)[]>
  hasSource: boolean
}

export interface SeriesFile {
  seriesVersion: string
  note: string
  /** UTC offset (minutes) assumed for 'utc' times without a stored offset. */
  defaultUtcOffsetMin: number
  series: SeriesContract[]
}

// --- Calendar arithmetic on YYYY-MM-DD strings (UTC midnight internally, so no local timezone leaks in) ---

const DAY_MS = 86_400_000
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

function toMs(date: string): number {
  return Date.parse(`${date}T00:00:00Z`)
}

function fromMs(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}

export function isIsoDate(s: unknown): s is string {
  return typeof s === 'string' && ISO_DATE.test(s) && fromMs(toMs(s)) === s
}

export function addDays(date: string, n: number): string {
  return fromMs(toMs(date) + n * DAY_MS)
}

/** Inclusive number of days from start to end. */
export function daySpan(start: string, end: string): number {
  return Math.round((toMs(end) - toMs(start)) / DAY_MS) + 1
}

export function periodStart(date: string, grain: Grain): string {
  if (grain === 'day') return date
  if (grain === 'month') return `${date.slice(0, 7)}-01`
  const weekday = (new Date(toMs(date)).getUTCDay() + 6) % 7 // Monday 0 .. Sunday 6
  return addDays(date, -weekday)
}

export function periodEnd(start: string, grain: Grain): string {
  if (grain === 'day') return start
  if (grain === 'week') return addDays(start, 6)
  const d = new Date(toMs(start))
  return fromMs(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0))
}

export interface Period {
  start: string
  end: string
  /** Days of the period inside the requested range. */
  days: number
  /** The range cuts the period short (a week or month only partly inside it). */
  clipped: boolean
}

/** Every period that touches [start, end], clipped to it, in order. Empty periods are included: gaps stay gaps. */
export function periodsInRange(start: string, end: string, grain: Grain): Period[] {
  const out: Period[] = []
  for (let p = periodStart(start, grain); p <= end; p = addDays(periodEnd(p, grain), 1)) {
    const full = periodEnd(p, grain)
    const s = p < start ? start : p
    const e = full > end ? end : full
    out.push({ start: s, end: e, days: daySpan(s, e), clipped: s !== p || e !== full })
  }
  return out
}

/** Default grain for a range: daily up to 90 days, the series' long grain up to a year, monthly beyond. */
export function grainFor(start: string, end: string, series: Pick<SeriesContract, 'longGrain'>): Grain {
  const days = daySpan(start, end)
  if (days <= 90) return 'day'
  if (days <= 366) return series.longGrain
  return 'month'
}

// --- Local time ---

export function timeKindFor(raw: string, time: TimeContract): TimeKind {
  const head = raw.slice(0, 10)
  for (const ex of time.exceptions ?? []) if (head < ex.before) return ex.kind
  return time.kind
}

export interface LocalDate {
  date: string
  /** The date rests on the default offset because the record carries none. */
  assumedOffset: boolean
}

/** The local calendar day of a raw time value under its contract. Null for an empty or unreadable value. */
export function localDateOf(raw: string | null | undefined, time: TimeContract, offsetMin: number | null, defaultOffsetMin: number): LocalDate | null {
  if (!raw) return null
  const kind = timeKindFor(raw, time)
  if (kind !== 'utc') {
    const date = raw.slice(0, 10)
    return isIsoDate(date) ? { date, assumedOffset: false } : null
  }
  const ms = Date.parse(raw)
  if (Number.isNaN(ms)) return null
  const offset = offsetMin ?? defaultOffsetMin
  return { date: fromMs(ms + offset * 60_000), assumedOffset: offsetMin === null || offsetMin === undefined }
}

// --- Aggregation ---

export interface Observation {
  date: string
  value: number | null
  source: string | null
}

export interface DayValue {
  date: string
  value: number | null
  /** Rows with a finite value that went into the value. */
  n: number
  /** The day's distinct sources; 'mixed' readers show them all. */
  sources: string[]
}

function finite(v: number | null | undefined): v is number {
  return typeof v === 'number' && Number.isFinite(v)
}

/** Values in row order -> one value by `method`; null when none is finite (never 0). */
export function combine(values: (number | null)[], method: Method): { value: number | null; n: number } {
  const xs = values.filter(finite)
  if (xs.length === 0) return { value: null, n: 0 }
  if (method === 'sum') return { value: xs.reduce((a, b) => a + b, 0), n: xs.length }
  if (method === 'last') return { value: xs[xs.length - 1], n: xs.length }
  return { value: xs.reduce((a, b) => a + b, 0) / xs.length, n: xs.length }
}

/** Observations (already on local dates, in time order) -> one value per day that has rows, ascending. */
export function aggregateDays(obs: Observation[], method: Method): DayValue[] {
  const byDay = new Map<string, Observation[]>()
  for (const o of obs) {
    const list = byDay.get(o.date)
    if (list) list.push(o)
    else byDay.set(o.date, [o])
  }
  return [...byDay.keys()].sort().map((date) => {
    const rows = byDay.get(date)!
    const { value, n } = combine(rows.map((r) => r.value), method)
    const sources = [...new Set(rows.map((r) => r.source).filter((s): s is string => s !== null))]
    return { date, value, n, sources }
  })
}

export interface PeriodPoint extends Period {
  grain: Grain
  method: Method
  value: number | null
  /** Days with a value. */
  daysObserved: number
  /** Rows behind those days. */
  samples: number
  /** Share of the period's days (inside the range) that have a value. */
  coverage: number
  min: number | null
  max: number | null
  sources: string[]
}

/** Day values -> one point per period of the range (including empty ones) by `method`. */
export function aggregatePeriods(days: DayValue[], start: string, end: string, grain: Grain, method: Method): PeriodPoint[] {
  const sorted = days.filter((d) => d.date >= start && d.date <= end).sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))
  let i = 0
  return periodsInRange(start, end, grain).map((p) => {
    const inside: DayValue[] = []
    while (i < sorted.length && sorted[i].date <= p.end) {
      if (sorted[i].date >= p.start) inside.push(sorted[i])
      i++
    }
    const observed = inside.filter((d) => finite(d.value))
    const values = observed.map((d) => d.value as number)
    const { value } = combine(values, method)
    return {
      ...p,
      grain,
      method,
      value,
      daysObserved: observed.length,
      samples: observed.reduce((a, d) => a + d.n, 0),
      coverage: observed.length / p.days,
      min: values.length ? Math.min(...values) : null,
      max: values.length ? Math.max(...values) : null,
      sources: [...new Set(observed.flatMap((d) => d.sources))].sort(),
    }
  })
}

// --- Quality states ---

/**
 * unknown  coverage or collector signal is insufficient to say whether a value was expected
 * missing  a value was expected and none arrived
 * invalid  a value exists but breaks a defined rule (the plausible range)
 * conflict competing rows for one day of a daily series disagree
 * valid    the defined checks passed; this says nothing about physiological accuracy
 */
export const QUALITY_STATES = ['unknown', 'missing', 'invalid', 'conflict', 'valid'] as const
export type QualityState = (typeof QUALITY_STATES)[number]

export interface DayEvidence {
  /** Every row of the day, in time order. */
  rows: { value: number | null; source: string | null }[]
  /** Whether a value was expected that day: null when it cannot be told (no coverage rule or collector signal). */
  expected: boolean | null
  /** Whether the collector was provably running that day; null without a signal. */
  collectorAlive: boolean | null
}

export interface QualityVerdict {
  state: QualityState
  reason: string
  /** More than one row on a day of a daily series, whether or not they agree. */
  duplicate: boolean
}

export function classifyDay(e: DayEvidence, series: Pick<SeriesContract, 'cadence' | 'plausible'>): QualityVerdict {
  const values = e.rows.map((r) => r.value).filter(finite)
  const duplicate = series.cadence === 'daily' && e.rows.length > 1
  if (values.length === 0) {
    if (e.expected === null) return { state: 'unknown', reason: 'no coverage rule or collector signal says whether a value was due', duplicate }
    if (!e.expected) return { state: 'valid', reason: 'nothing was due', duplicate }
    const why = e.collectorAlive === true ? 'the collector was running' : e.collectorAlive === false ? 'the collector was not running' : 'collector state unknown'
    return { state: 'missing', reason: `a value was due and none arrived (${why})`, duplicate }
  }
  if (series.plausible) {
    const [lo, hi] = series.plausible
    const bad = values.filter((v) => v < lo || v > hi)
    if (bad.length) return { state: 'invalid', reason: `${bad.length} value(s) outside ${lo}-${hi}`, duplicate }
  }
  if (series.cadence === 'daily' && new Set(values).size > 1) {
    return { state: 'conflict', reason: `${values.length} rows disagree (${Math.min(...values)}-${Math.max(...values)})`, duplicate }
  }
  return { state: 'valid', reason: duplicate ? 'duplicate rows agree' : 'checks passed', duplicate }
}

// --- Validation ---

const SEMVER = /^\d+\.\d+\.\d+$/
const SNAKE = /^[a-z][a-z0-9_]*$/

/** Problems that make the contract file unfit to ship; empty = valid. `registryKeys` are the keys of metric-registry.json. */
export function validateSeriesFile(file: SeriesFile, registryKeys: string[]): string[] {
  const problems: string[] = []
  if (!SEMVER.test(file.seriesVersion ?? '')) problems.push('seriesVersion is not semver')
  if (!Number.isInteger(file.defaultUtcOffsetMin) || Math.abs(file.defaultUtcOffsetMin) > 14 * 60) problems.push('defaultUtcOffsetMin out of range')
  const keys = new Set(registryKeys)
  const ids = new Set<string>()
  for (const s of file.series ?? []) {
    const id = s.id || '(no id)'
    if (!SNAKE.test(s.id ?? '')) problems.push(`${id}: id must be snake_case`)
    if (ids.has(s.id)) problems.push(`${id}: duplicate id`)
    ids.add(s.id)
    if (s.metric !== null && !keys.has(s.metric)) problems.push(`${id}: metric ${s.metric} is not in metric-registry.json`)
    if (!s.label?.trim() || typeof s.unit !== 'string') problems.push(`${id}: label and unit are required`)
    if (!SNAKE.test(s.table ?? '') || !SNAKE.test(s.column ?? '')) problems.push(`${id}: table and column must be snake_case`)
    if (!(CADENCES as readonly string[]).includes(s.cadence)) problems.push(`${id}: unknown cadence ${s.cadence}`)
    if (!(METHODS as readonly string[]).includes(s.day)) problems.push(`${id}: unknown day method ${s.day}`)
    if (!(METHODS as readonly string[]).includes(s.period)) problems.push(`${id}: unknown period method ${s.period}`)
    if (s.cadence === 'daily' && s.day === 'sum') problems.push(`${id}: a daily series must not sum rows within a day (duplicates would double it)`)
    if (s.longGrain !== 'day' && s.longGrain !== 'week') problems.push(`${id}: longGrain must be day or week`)
    if (s.plausible !== null && !(Array.isArray(s.plausible) && s.plausible.length === 2 && s.plausible.every(Number.isFinite) && s.plausible[0] < s.plausible[1])) {
      problems.push(`${id}: plausible must be null or [min, max] with min < max`)
    }
    const t = s.time
    if (!t || !SNAKE.test(t.column ?? '')) problems.push(`${id}: time.column is required`)
    else {
      if (!(TIME_KINDS as readonly string[]).includes(t.kind)) problems.push(`${id}: unknown time kind ${t.kind}`)
      if (t.offsetColumn !== undefined && (t.kind !== 'utc' && !(t.exceptions ?? []).some((x) => x.kind === 'utc'))) problems.push(`${id}: offsetColumn only applies to utc times`)
      let prev = ''
      for (const ex of t.exceptions ?? []) {
        if (!isIsoDate(ex.before)) problems.push(`${id}: exception date ${ex.before} is not YYYY-MM-DD`)
        if (!(TIME_KINDS as readonly string[]).includes(ex.kind)) problems.push(`${id}: unknown exception kind ${ex.kind}`)
        if (!ex.note?.trim()) problems.push(`${id}: an exception needs a note`)
        if (ex.before <= prev) problems.push(`${id}: exceptions must be in ascending date order`)
        prev = ex.before
      }
    }
    for (const [col, vals] of Object.entries(s.filters ?? {})) {
      if (!SNAKE.test(col) || !Array.isArray(vals) || vals.length === 0) problems.push(`${id}: filter ${col} needs a column and at least one value`)
    }
    if (typeof s.hasSource !== 'boolean') problems.push(`${id}: hasSource must be a boolean`)
  }
  return problems
}
