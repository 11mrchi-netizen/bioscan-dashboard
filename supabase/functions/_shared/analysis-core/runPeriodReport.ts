// I/O for the weekly and monthly reports (periodReport.ts), against the same duck-typed Supabase client as the other run*
// modules so it is tested in Node with a fake and runs unchanged in analysis-run (group `reports`, nightly at 20:48 UTC,
// 04:48 in UTC+8).
//
// Each night, for the last completed week (Monday to Sunday) and the last completed month on the person's calendar, a
// report is written when none is stored yet, and rewritten for three days after the period ends, so data that syncs late
// (Sunday's sleep, a meal logged the next morning) still lands in it. After that a report stays as it was written.
// One row per period in analysis_results: kind `period_report`, key `week:<monday>` or `month:<yyyy-mm>`, as_of the
// period's last day.
import { gutDayScore, type GutCheckinRow } from './mealExposures.ts'
import { fetchAll } from './paging.ts'
import {
  adherenceLine, buildReport, convertUnit, type DailySeries, foodStool, historyOf, itemsInPeriod, lastCompleted, type LabLine, type LabMetricRead,
  labVerdict, type MealProfileRead, mealQuality, metricLine, microSection, MICRO_REFERENCES, type PeriodReport, periodKey, parsePeriodKey, previousOf, REPORT_KIND,
  REPORT_METRICS, regimenItem, withHistory, type ReportGrain, type ReportPeriod, type SeriesInfo, STOOL_SERIES,
} from './periodReport.ts'
import { localDateAt, type Admin } from './runRelationships.ts'
import { addDays, aggregateDays, localDateOf, type SeriesContract, type SeriesFile } from './seriesContract.ts'
import { CORE_VERSION } from './version.ts'
import { foodTriggers, TRIGGERS, type FoodTriggerSection } from './foodTriggers.ts'
import { energyAvailability, sessionFuel, trainingFuelSection, type TfBody, type TfFuel, type TfMeal, type TfSession, type TrainingFuelSection } from './trainingFuel.ts'

/** Days after a period's end during which its report is rewritten each night. */
export const REWRITE_DAYS = 3

// deno-lint-ignore no-explicit-any
function withFilters(q: any, s: SeriesContract): any {
  for (const [c, vals] of Object.entries(s.filters ?? {})) q = q.in(c, vals)
  return q
}

/** One series of the contract as one value per local day in [start, end] (the day rule of the contract). */
export async function readDaily(admin: Admin, userId: string, s: SeriesContract, start: string, end: string, defaultOffset: number): Promise<DailySeries> {
  const t = s.time.column
  const exact = s.time.kind === 'local_date' && !(s.time.exceptions ?? []).length
  const cols = [...new Set(['id', t, s.column, ...(s.time.offsetColumn ? [s.time.offsetColumn] : [])])].join(', ')
  const from = exact ? start : `${addDays(start, -1)}T00:00:00`
  const to = exact ? end : `${addDays(end, 1)}T23:59:59.999`
  const { rows, error } = await fetchAll<Record<string, unknown>>((a, b) =>
    withFilters(admin.from(s.table).select(cols).eq('user_id', userId).gte(t, from).lte(t, to), s).order(t).order('id').range(a, b))
  if (error) throw new Error(`${s.id}: ${error}`)
  const obs = []
  for (const r of rows) {
    const offset = s.time.offsetColumn ? ((r[s.time.offsetColumn] as number | null) ?? null) : null
    const date = localDateOf(r[t] as string | null, s.time, offset, defaultOffset)?.date
    if (!date || date < start || date > end) continue
    const v = r[s.column]
    const n = v === null || v === undefined || v === '' ? null : Number(v)
    if (n === null || !Number.isFinite(n)) continue
    if (s.plausible && (n < s.plausible[0] || n > s.plausible[1])) continue
    if (s.zeroIsSuspect && n === 0) continue
    obs.push({ date, value: n, source: null })
  }
  const out: DailySeries = new Map()
  for (const d of aggregateDays(obs, s.day)) if (d.value !== null) out.set(d.date, d.value)
  return out
}

/**
 * Stool logs (local wall times) per day: the share of normal (Bristol 3-5) and loose (6-7) stools, mean discomfort, and
 * the daily digestion score (mealExposures.ts, with the evening check-in's gut questions, read softly).
 */
async function readStool(admin: Admin, userId: string, start: string, end: string): Promise<{ normal: DailySeries; loose: DailySeries; discomfort: DailySeries; gut: DailySeries }> {
  const { rows, error } = await fetchAll<{ occurred_at: string; bristol_type: number | null; discomfort: number | null; complete_evacuation: string | null }>((a, b) =>
    admin.from('stool_log').select('id, occurred_at, bristol_type, discomfort, complete_evacuation').eq('user_id', userId)
      .gte('occurred_at', `${start}T00:00:00`).lte('occurred_at', `${end}T23:59:59.999`).order('occurred_at').order('id').range(a, b))
  if (error) throw new Error(`stool_log: ${error}`)
  const byDay = new Map<string, { n: number; normal: number; loose: number; disc: number[] }>()
  for (const r of rows) {
    const d = String(r.occurred_at).slice(0, 10)
    const e = byDay.get(d) ?? { n: 0, normal: 0, loose: 0, disc: [] }
    if (r.bristol_type !== null && r.bristol_type !== undefined) {
      e.n++
      if (r.bristol_type >= 3 && r.bristol_type <= 5) e.normal++
      if (r.bristol_type >= 6) e.loose++
    }
    if (r.discomfort !== null && r.discomfort !== undefined) e.disc.push(Number(r.discomfort))
    byDay.set(d, e)
  }
  const normal: DailySeries = new Map()
  const loose: DailySeries = new Map()
  const discomfort: DailySeries = new Map()
  for (const [d, e] of byDay) {
    if (e.n) { normal.set(d, (e.normal / e.n) * 100); loose.set(d, (e.loose / e.n) * 100) }
    if (e.disc.length) discomfort.set(d, e.disc.reduce((a, b) => a + b, 0) / e.disc.length)
  }
  const ck = await fetchAll<GutCheckinRow & { id?: number }>((a, b) =>
    admin.from('evening_checkins').select('date, gut_comfort, gut_symptoms').eq('user_id', userId).gte('date', start).lte('date', end).order('date').range(a, b))
  const axis: string[] = []
  for (let d = start; d <= end; d = addDays(d, 1)) axis.push(d)
  const scores = gutDayScore(axis, rows, ck.error ? [] : ck.rows)
  const gut: DailySeries = new Map()
  axis.forEach((d, i) => { const v = scores[i]; if (v !== null) gut.set(d, v) })
  return { normal, loose, discomfort, gut }
}

/** The food trigger screen over the 180 days to [end]: meal profiles' groups and tags against the stool days. */
async function readFoodTriggers(admin: Admin, userId: string, end: string): Promise<FoodTriggerSection> {
  const from = addDays(end, -(TRIGGERS.lookbackDays - 1))
  const [meals, stool] = await Promise.all([
    fetchAll<Record<string, unknown>>((a, b) => admin.from('meal_profiles').select('meal_id, logged_at, food_groups, tags').eq('user_id', userId)
      .gte('logged_at', `${addDays(from, -2)}T00:00:00`).lte('logged_at', `${end}T23:59:59.999`).order('logged_at').order('meal_id').range(a, b)),
    fetchAll<Record<string, unknown>>((a, b) => admin.from('stool_log').select('id, occurred_at, bristol_type').eq('user_id', userId)
      .gte('occurred_at', `${from}T00:00:00`).lte('occurred_at', `${end}T23:59:59.999`).order('occurred_at').order('id').range(a, b)),
  ])
  for (const r of [meals, stool]) if (r.error) throw new Error(`food triggers: ${r.error}`)
  return foodTriggers(
    meals.rows.map((r) => ({ at: String(r.logged_at), groups: (r.food_groups as string[] | null) ?? null, tags: (r.tags as string[] | null) ?? null })),
    stool.rows.map((r) => ({ at: String(r.occurred_at), bristol: r.bristol_type === null || r.bristol_type === undefined ? null : Number(r.bristol_type) })),
    from, end)
}

/** The meal profile of each meal from [start] to [end] (meals.logged_at is the phone's wall clock: its digits are local). */
async function readMealProfiles(admin: Admin, userId: string, start: string, end: string): Promise<MealProfileRead[]> {
  const { rows, error } = await fetchAll<Record<string, unknown>>((a, b) =>
    admin.from('meal_profiles').select('meal_id, logged_at, calories, protein_g, fiber_g, energy_density_kcal_g, glycemic_load_est, ultra_processed_pct')
      .eq('user_id', userId).gte('logged_at', `${start}T00:00:00`).lte('logged_at', `${end}T23:59:59.999`).order('logged_at').order('meal_id').range(a, b))
  if (error) throw new Error(`meal_profiles: ${error}`)
  const n = (v: unknown) => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))
  return rows.map((r) => ({
    date: String(r.logged_at).slice(0, 10), calories: n(r.calories), proteinG: n(r.protein_g), fiberG: n(r.fiber_g),
    energyDensity: n(r.energy_density_kcal_g), glycemicLoad: n(r.glycemic_load_est), ultraProcessedPct: n(r.ultra_processed_pct),
  }))
}

/** Meals, sessions, in-session fuel and body composition from [start] to [end] for food against training (trainingFuel.ts). */
async function readTrainingFuel(admin: Admin, userId: string, start: string, end: string, weightKg: number | null): Promise<TrainingFuelSection> {
  const from = `${addDays(start, -1)}T00:00:00`, to = `${end}T23:59:59.999`
  const [meals, sessions, body] = await Promise.all([
    fetchAll<Record<string, unknown>>((a, b) => admin.from('meals').select('id, logged_at, calories, carbs_g, protein_g').eq('user_id', userId)
      .gte('logged_at', from).lte('logged_at', `${addDays(end, 1)}T03:59:59.999`).order('logged_at').order('id').range(a, b)),
    fetchAll<Record<string, unknown>>((a, b) => admin.from('exercise_sessions').select('id, start_time, type, duration_min, calories_active').eq('user_id', userId)
      .gte('start_time', from).lte('start_time', to).order('start_time').order('id').range(a, b)),
    fetchAll<Record<string, unknown>>((a, b) => admin.from('body_metrics').select('id, date, weight_kg, body_fat_pct').eq('user_id', userId)
      .gte('date', addDays(start, -60)).lte('date', end).order('date').order('id').range(a, b)),
  ])
  for (const r of [meals, sessions, body]) if (r.error) throw new Error(`training fuel: ${r.error}`)
  const num = (v: unknown) => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))
  const ms: TfMeal[] = meals.rows.map((r) => ({ at: String(r.logged_at), kcal: num(r.calories), carbsG: num(r.carbs_g), proteinG: num(r.protein_g) }))
  const ss: TfSession[] = sessions.rows.map((r) => ({ id: Number(r.id), at: String(r.start_time), type: (r.type as string | null) ?? null, durationMin: num(r.duration_min), activeKcal: num(r.calories_active) }))
  const bs: TfBody[] = body.rows.map((r) => ({ date: String(r.date), weightKg: num(r.weight_kg), bodyFatPct: num(r.body_fat_pct) }))
  // In-session fuel, read softly: the fuelling log is optional.
  let fuel: TfFuel[] = []
  if (ss.length) {
    const f = await admin.from('fueling_intake_log').select('exercise_session_id, carbs_g, status').eq('user_id', userId).in('exercise_session_id', ss.map((s) => s.id))
    if (!f.error) fuel = ((f.data ?? []) as Record<string, unknown>[]).map((r) => ({ sessionId: Number(r.exercise_session_id), carbsG: num(r.carbs_g), status: (r.status as string | null) ?? null }))
  }
  return trainingFuelSection(sessionFuel(ss, ms, fuel), energyAvailability(ms, ss, bs), start, end, weightKg)
}

/** nutrient_intake rows that are food; supplement doses are written there too (source_type `supplement`, source_id the
 * supplement_log row). */
export const FOOD_SOURCES = ['meal_item', 'fueling_intake']

export interface MicroReads {
  food: Map<string, DailySeries>
  foodDays: Set<string>
  supps: Map<string, DailySeries>
  /** Per nutrient, the supplement_log ids of the doses that brought it (named by the caller). */
  suppLogIds: Map<string, Set<number>>
}

/** Micronutrients per local day (nutrient_intake holds the phone's wall clock), food and supplements apart, in each reference's unit. */
async function readMicros(admin: Admin, userId: string, start: string, end: string): Promise<MicroReads> {
  const keys = MICRO_REFERENCES.map((m) => m.key)
  const { rows, error } = await fetchAll<{ logged_at: string; nutrient: string; amount: number | null; unit: string | null; source_type: string | null; source_id: number | null }>((a, b) =>
    admin.from('nutrient_intake').select('id, logged_at, nutrient, amount, unit, source_type, source_id').eq('user_id', userId).in('nutrient', [...keys, 'calories'])
      .gte('logged_at', `${start}T00:00:00`).lte('logged_at', `${end}T23:59:59.999`).order('logged_at').order('id').range(a, b))
  if (error) throw new Error(`nutrient_intake: ${error}`)
  const out: MicroReads = { food: new Map(), foodDays: new Set(), supps: new Map(), suppLogIds: new Map() }
  for (const r of rows) {
    const d = String(r.logged_at).slice(0, 10)
    const isFood = FOOD_SOURCES.includes(String(r.source_type ?? 'meal_item'))
    const isSupp = r.source_type === 'supplement'
    if (!isFood && !isSupp) continue
    if (isFood) out.foodDays.add(d)
    const ref = MICRO_REFERENCES.find((m) => m.key === r.nutrient)
    if (!ref || r.amount === null || !Number.isFinite(Number(r.amount))) continue
    const v = convertUnit(Number(r.amount), r.unit ?? ref.unit, ref.unit)
    if (v === null) continue
    const target = isFood ? out.food : out.supps
    const s = target.get(ref.key) ?? new Map()
    s.set(d, (s.get(d) ?? 0) + v)
    target.set(ref.key, s)
    if (isSupp && r.source_id !== null) {
      const ids = out.suppLogIds.get(ref.key) ?? new Set<number>()
      ids.add(Number(r.source_id))
      out.suppLogIds.set(ref.key, ids)
    }
  }
  return out
}

interface ResultRow { key: string; state: string; payload: Record<string, unknown>; calculated_at: string }

async function latestResults(admin: Admin, userId: string, kind: string): Promise<Map<string, ResultRow>> {
  const { data, error } = await admin.from('analysis_results').select('key, state, payload, calculated_at').eq('user_id', userId).eq('kind', kind)
  if (error) return new Map()
  const out = new Map<string, ResultRow>()
  for (const r of (data ?? []) as ResultRow[]) {
    const had = out.get(r.key)
    if (!had || String(r.calculated_at) > String(had.calculated_at)) out.set(r.key, r)
  }
  return out
}

type Metricish = { metric: string; label: string; verdict?: string | null; state?: string; role?: string; gap?: { value: number; unit: string } | null; diff?: { value: number; unit: string } | null }

/** An engine's metric reads in one vocabulary: a change was found, none was, or it is not decided yet. */
export function readsOf(metrics: Metricish[] | undefined): LabMetricRead[] {
  return (metrics ?? []).filter((m) => m.role !== 'secondary').map((m) => {
    const v = m.verdict ?? null
    const outcome = v === 'clear' || v === 'signal_observed' || v === 'effect_detected' || v === 'clear_benefit' || v === 'clear_harm'
      ? 'change' : v === 'negligible' || v === 'no_signal' || v === 'no_detectable_effect' ? 'none' : 'undecided'
    const d = m.gap ?? m.diff ?? null
    return { metric: m.metric, label: m.label, outcome, diff: d ? d.value : null, unit: d?.unit ?? '' }
  })
}

async function readLab(admin: Admin, userId: string, period: ReportPeriod, adherenceById: Map<number, number | null>): Promise<LabLine[]> {
  const out: LabLine[] = []
  const iv = await admin.from('interventions').select('id, name, lifecycle_state, started_at, ended_at, supplement_id').eq('user_id', userId)
  if (!iv.error) {
    const its = await latestResults(admin, userId, 'intervention_its')
    const resp = await latestResults(admin, userId, 'intervention_response')
    for (const r of (iv.data ?? []) as { id: string; name: string; lifecycle_state: string; started_at: string | null; ended_at: string | null; supplement_id: number | null }[]) {
      if (['archived', 'abandoned', 'draft'].includes(r.lifecycle_state)) continue
      if (r.ended_at && r.ended_at < period.start) continue
      if (r.started_at && r.started_at > period.end) continue
      const itsReads = readsOf((its.get(r.id)?.payload?.metrics as Metricish[] | undefined))
      const respReads = readsOf((resp.get(r.id)?.payload?.metrics as Metricish[] | undefined))
      const reads = itsReads.some((x) => x.outcome !== 'undecided') ? itsReads : respReads.length ? respReads : itsReads
      const adherence = r.supplement_id !== null ? (adherenceById.get(r.supplement_id) ?? null) : null
      const v = r.started_at ? labVerdict(reads, adherence) : { verdict: 'too_early' as const, detail: 'Still in its baseline; not started yet.' }
      out.push({ source: 'intervention', id: r.id, name: r.name, ...v, supplementId: r.supplement_id, href: '#/lab/experiments?view=interventions' })
    }
  }
  const ex = await admin.from('experiments').select('id, name, status, supplement_id, deleted_at').eq('user_id', userId)
  if (!ex.error) {
    const res = await latestResults(admin, userId, 'experiment')
    for (const r of (ex.data ?? []) as { id: string; name: string; status: string | null; supplement_id: number | null; deleted_at: string | null }[]) {
      if (r.deleted_at || r.status === 'abandoned') continue
      const p = res.get(r.id)?.payload as { stage?: string; adherence?: { rate: number | null }; metrics?: Metricish[] } | undefined
      if (!p || p.stage === 'before') continue
      const v = labVerdict(readsOf(p.metrics), p.adherence?.rate ?? null)
      out.push({ source: 'experiment', id: r.id, name: r.name, ...v, supplementId: r.supplement_id, href: '#/lab/experiments' })
    }
  }
  return out
}

/** Builds one period's report from the database. */
export async function buildPeriodReport(admin: Admin, userId: string, file: SeriesFile, period: ReportPeriod, today: string): Promise<PeriodReport> {
  const history = historyOf(period)
  const prev = previousOf(period)
  const from = [history[0].start, addDays(period.end, -89)].sort()[0]
  const byId = new Map(file.series.map((s) => [s.id, s]))

  const stool = await readStool(admin, userId, from, period.end)
  const daily = new Map<string, DailySeries>([['gut_day_score', stool.gut], ['stool_normal_pct', stool.normal], ['stool_loose_pct', stool.loose], ['stool_discomfort', stool.discomfort]])
  const contractSeries = REPORT_METRICS.map((m) => byId.get(m.id)).filter((s): s is SeriesContract => !!s)
  const loaded = await Promise.all(contractSeries.map((s) => readDaily(admin, userId, s, from, period.end, file.defaultUtcOffsetMin)))
  contractSeries.forEach((s, i) => daily.set(s.id, loaded[i]))

  const metrics = REPORT_METRICS.filter((m) => daily.has(m.id)).map((m) => {
    const s = byId.get(m.id)
    const info: SeriesInfo = s ? { label: s.label, unit: s.unit, period: s.period } : { ...STOOL_SERIES[m.id], period: 'mean' }
    return metricLine(m, info, daily.get(m.id)!, period, history)
  })


  const supp = await admin.from('supplements')
    .select('id, name, kind, status, time_of_day, slots, every_n_days, schedule, start_date, end_date').eq('user_id', userId).order('name')
  if (supp.error) throw new Error(`supplements: ${supp.error.message}`)
  // Schedule history: each day is judged by the schedule in force then, not today's (supplement_changes, written by a
  // trigger on every edit; changed_at is a real instant, so it is put on the local calendar).
  const [chg, ctx] = await Promise.all([
    admin.from('supplement_changes').select('supplement_id, changed_at, changes, before, after').eq('user_id', userId)
      .lte('changed_at', `${addDays(period.end, 1)}T23:59:59.999Z`).order('changed_at'),
    admin.from('daily_context').select('date, utc_offset_min').eq('user_id', userId).order('date', { ascending: false }).limit(60),
  ])
  const ctxRows = (ctx.error ? [] : (ctx.data ?? [])) as { date: string; utc_offset_min: number | null }[]
  const byItem = new Map<number, Parameters<typeof withHistory>[1]>()
  for (const r of (chg.error ? [] : (chg.data ?? [])) as { supplement_id: number; changed_at: string; before: Record<string, unknown> | null; after: Record<string, unknown> }[]) {
    const list = byItem.get(r.supplement_id) ?? []
    list.push({ date: localDateAt(new Date(r.changed_at), ctxRows), before: r.before as never, after: r.after as never })
    byItem.set(r.supplement_id, list)
  }
  const items = itemsInPeriod(((supp.data ?? []) as Parameters<typeof regimenItem>[0][]).map(regimenItem), period)
    .map((it) => withHistory(it, byItem.get(it.id) ?? []))
  const { rows: log, error: logErr } = await fetchAll<{ id: number; supplement_id: number | null; taken_at: string }>((a, b) =>
    admin.from('supplement_log').select('id, supplement_id, taken_at').eq('user_id', userId)
      .gte('taken_at', `${prev.start}T00:00:00`).lte('taken_at', `${period.end}T23:59:59.999`).order('taken_at').order('id').range(a, b))
  if (logErr) throw new Error(`supplement_log: ${logErr}`)
  const takes = new Map<number, Map<string, number>>()
  for (const r of log) {
    if (r.supplement_id === null) continue
    const d = String(r.taken_at).slice(0, 10)
    const m = takes.get(r.supplement_id) ?? new Map<string, number>()
    m.set(d, (m.get(d) ?? 0) + 1)
    takes.set(r.supplement_id, m)
  }
  const regimen = items.map((it) => adherenceLine(it, takes.get(it.id) ?? new Map(), period, prev))
  // Micronutrients: food and the doses logged, each dose named by its regimen item (only doses inside this period).
  const micros = await readMicros(admin, userId, prev.start, period.end)
  const allNames = new Map(((supp.data ?? []) as { id: number; name: string }[]).map((r) => [r.id, r.name]))
  const logToName = new Map(log.filter((r) => r.supplement_id !== null && String(r.taken_at).slice(0, 10) >= period.start).map((r) => [Number(r.id), allNames.get(r.supplement_id!) ?? 'a supplement']))
  const suppNames = new Map([...micros.suppLogIds].map(([k, ids]) => [k, new Set([...ids].map((i) => logToName.get(i)).filter((n): n is string => !!n))]))
  const micro = microSection(micros.food, micros.foodDays, micros.supps, suppNames, period, prev)
  const lab = await readLab(admin, userId, period, new Map(regimen.map((r) => [r.id, r.rate])))

  const fsFoods = [
    { key: 'meal_fiber', label: 'Fibre', unit: 'g' }, { key: 'water_intake', label: 'Water', unit: 'mL' },
    { key: 'meal_fat', label: 'Fat', unit: 'g' }, { key: 'meal_energy', label: 'Energy intake', unit: 'kcal' },
  ].map((f) => ({ ...f, series: daily.get(f.key) ?? new Map() }))
  const fs = foodStool(fsFoods, stool.normal, period.end)

  const weights = daily.get('body_weight')
  const lastWeight = weights ? [...weights.entries()].filter(([d]) => d <= period.end).sort(([a], [b]) => a.localeCompare(b)).at(-1)?.[1] ?? null : null

  // The meal profile view may be missing on a database that has not had its migration yet: the report goes on without it.
  let meals: MealProfileRead[] | null = null
  try { meals = await readMealProfiles(admin, userId, prev.start, period.end) } catch { meals = null }
  const mq = meals ? mealQuality(meals, period, lastWeight) : undefined
  let tf: TrainingFuelSection | undefined
  try { tf = await readTrainingFuel(admin, userId, period.start, period.end, lastWeight) } catch { tf = undefined }
  let ft: FoodTriggerSection | undefined
  try { ft = await readFoodTriggers(admin, userId, period.end) } catch { ft = undefined }

  // The latest blood draw, for the repeat-test suggestion (read softly: the report goes on without it).
  let daysSinceDraw: number | null = null
  try {
    const d = await admin.from('lab_draws').select('draw_date').eq('user_id', userId).order('draw_date', { ascending: false }).limit(1)
    const last = !d.error && d.data?.[0]?.draw_date ? String(d.data[0].draw_date).slice(0, 10) : null
    if (last) daysSinceDraw = Math.round((Date.parse(today + 'T00:00:00Z') - Date.parse(last + 'T00:00:00Z')) / 86400000)
  } catch { daysSinceDraw = null }

  return buildReport({ period, today, metrics, micro, regimen, lab, foodStool: fs, mealQuality: mq, trainingFuel: tf, foodTriggers: ft, weightKg: lastWeight, daysSinceDraw })
}

async function storedKeys(admin: Admin, userId: string): Promise<Set<string>> {
  const { data } = await admin.from('analysis_results').select('key').eq('user_id', userId).eq('kind', REPORT_KIND)
  return new Set(((data ?? []) as { key: string }[]).map((r) => r.key))
}

async function writeReport(admin: Admin, userId: string, report: PeriodReport, now: Date): Promise<string | null> {
  const { error } = await admin.from('analysis_results').upsert({
    user_id: userId, kind: REPORT_KIND, key: report.key, as_of: report.end, engine_version: CORE_VERSION,
    inputs_hash: report.version, state: 'ready', confidence_have: null, confidence_need: null, payload: report,
    calculated_at: now.toISOString(),
  }, { onConflict: 'user_id,kind,key,as_of,engine_version' })
  if (error) return error.message
  // One row per period: copies written by an earlier engine version go.
  await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', REPORT_KIND).eq('key', report.key).neq('engine_version', CORE_VERSION)
  return null
}

export interface ReportsRun {
  userId: string
  today?: string
  written: string[]
  skipped: string[]
  error?: string
}

/** The periods due tonight: the last completed week and month, when not stored yet or still inside the rewrite days. */
export function duePeriods(today: string, stored: Set<string>): ReportPeriod[] {
  return (['week', 'month'] as ReportGrain[]).map((g) => lastCompleted(today, g))
    .filter((p) => !stored.has(periodKey(p)) || today <= addDays(p.end, REWRITE_DAYS))
}

/**
 * The nightly run for one user. With `keys`, exactly those periods are written instead (the on-demand action), or with
 * `weeks` / `months` the last that many; a key for a period that has not ended yet is skipped.
 */
export async function runReports(admin: Admin, userId: string, file: SeriesFile, now: Date = new Date(), opts: { keys?: string[]; weeks?: number; months?: number } = {}): Promise<ReportsRun> {
  const ctx = await admin.from('daily_context').select('date, utc_offset_min').eq('user_id', userId).order('date', { ascending: false }).limit(5)
  const today = localDateAt(now, ctx.error ? [] : (ctx.data ?? []) as { date: string; utc_offset_min: number | null }[])
  const run: ReportsRun = { userId, today, written: [], skipped: [] }
  let periods: ReportPeriod[]
  const keys = opts.keys ?? (opts.weeks !== undefined || opts.months !== undefined ? backfillKeys(today, opts.weeks ?? 0, opts.months ?? 0) : null)
  if (keys) {
    periods = []
    for (const k of keys) {
      const p = parsePeriodKey(k)
      if (p && p.end < today) periods.push(p)
      else run.skipped.push(k)
    }
  } else periods = duePeriods(today, await storedKeys(admin, userId))
  for (const p of periods) {
    try {
      const report = await buildPeriodReport(admin, userId, file, p, today)
      const err = await writeReport(admin, userId, report, now)
      if (err) return { ...run, error: err }
      run.written.push(report.key)
    } catch (e) {
      return { ...run, error: String(e) }
    }
  }
  return run
}

/** The keys of the last [weeks] weeks and [months] months before [today], newest first (for a first fill). */
export function backfillKeys(today: string, weeks: number, months: number): string[] {
  const out: string[] = []
  let w = lastCompleted(today, 'week')
  for (let i = 0; i < weeks; i++) { out.push(periodKey(w)); w = previousOf(w) }
  let m = lastCompleted(today, 'month')
  for (let i = 0; i < months; i++) { out.push(periodKey(m)); m = previousOf(m) }
  return out
}
