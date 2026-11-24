// What the fuel engine (supabase/functions/_shared/fuel-core) is fed with, built from the rows the database holds: the web's version of the
// phone's PlannedDayRepository / FuelPlanRepository reads. Pure (rows in, engine inputs out) so it is testable; fuelDay.ts fetches the rows.
// Logged times (exercise_sessions.start_time, meals.logged_at) are the phone's wall clock labelled +00: their digits are local, so a
// session's start is the zone's instant for those digits. Real instants (calendar_events, athlete_events.start_time) are parsed as they are.
import {
  atTime, type CalendarOverride, type CalendarSessionInput, demandHistory, type DemandHistory, type EventInput, fatFreeMass, type PlannedSessionInput, type RecordedSessionInput,
  resolvePlannedDays, type StoredLink, weekEnergyOf, zdtString, aggregateMealsByDay, type MealRow, planInWeekBlocks, type DayFuelPlan, type PlannedDay, type WeekEnergy,
  type EventKind, type EventPriority, type EventSport, type EventStatus, type NutritionGoals,
  checkIntakeHistory, type IntakeHistoryResult, type LoggedDay, codeMeals, DEFAULT_MEAL_TIMES, DEFAULT_TOLERATED_G_PER_H, type DayMeals, type MealTimes,
} from '../../../supabase/functions/_shared/fuel-core/index.ts'
import { restingHrAsOf, robustMaxHr } from '../../../supabase/functions/_shared/analysis-core/trainingLoad.ts'

export interface EventRow {
  id: number; name: string; kind: string | null; sport: string | null; priority: string | null; event_date: string; start_time: string | null; status: string | null
  target_time_min: number | null; expected_duration_min: number | null; distance_km: number | null; course_profile: (number | null)[][] | null
  calendar_event_id: string | null; exercise_session_id: number | null
}
export interface PlannedRow { id: number; scheduled_date: string; start_time: string | null; duration_min: number | null; domain: string | null; title: string; gcal_event_id: string | null; exercise_session_id: number | null }
export interface CalendarRow { event_id: string; title: string | null; description: string | null; start_time: string | null; end_time: string | null; color_id: string | null }
export interface SessionRow { id: number; type: string; start_time: string; duration_min: number | null; calories_active: number | null; avg_hr: number | null; rpe: number | null; distance_km: number | null; elevation_gain_m: number | null }
export interface OverrideRow { event_id: string; treat_as: string; duration_min: number | null; intensity: string | null }
export interface LinkRow { exercise_session_id: number; plan_ref: string | null; plan_title: string | null; source: string | null; confidence: number | null }
export interface BodyRow { date: string; weight_kg: number | null; lean_mass_kg: number | null; body_fat_pct: number | null; height_cm?: number | null }
export interface ProfileRow { sex: string | null; date_of_birth: string | null; nutrition_goals: NutritionGoals | null }

export interface FuelRows {
  events: EventRow[]; planned: PlannedRow[]; calendar: CalendarRow[]
  /** training sessions of the last 180 days before the first day, walks left out */
  recorded: SessionRow[]
  /** session maxima (max_hr > 0) and daily resting heart rates of the last 3 years: [date, value] */
  maxima: [string, number][]; resting: [string, number][]
  overrides: OverrideRow[]; links: LinkRow[]
  profile: ProfileRow | null
  /** the latest 30 body rows, newest first */
  body: BodyRow[]
  /** the latest height on record */
  heightCm: number | null
  /** minutes of training (walks left out) of the 28 days to today */
  trainingMin28: number
  meals: MealRow[]
}

const EVENT_KIND: Record<string, EventKind> = { race: 'RACE', long_session: 'LONG_SESSION', match: 'MATCH', hike_day: 'HIKE_DAY', other_hard_day: 'OTHER_HARD_DAY' }
const EVENT_SPORT: Record<string, EventSport> = { run: 'RUN', trail_run: 'TRAIL_RUN', ride: 'RIDE', hike: 'HIKE', swim: 'SWIM', triathlon: 'TRIATHLON', team_sport: 'TEAM_SPORT', other: 'OTHER' }
const EVENT_STATUS: Record<string, EventStatus> = { planned: 'PLANNED', done: 'DONE', dns: 'DNS', cancelled: 'CANCELLED' }
const PRIORITY: Record<string, EventPriority> = { A: 'A', B: 'B', C: 'C' }

const plusDays = (d: string, n: number) => new Date(Date.UTC(Number(d.slice(0, 4)), Number(d.slice(5, 7)) - 1, Number(d.slice(8, 10)) + n)).toISOString().slice(0, 10)

export function eventInput(e: EventRow): EventInput {
  const course = (e.course_profile ?? []).map((p) => p[0]).filter((x): x is number => x != null)
  return {
    id: e.id, name: e.name, kind: EVENT_KIND[e.kind ?? ''] ?? 'OTHER_HARD_DAY', sport: EVENT_SPORT[e.sport ?? ''] ?? 'OTHER', priority: PRIORITY[e.priority ?? ''] ?? 'B',
    date: e.event_date, start: e.start_time, status: EVENT_STATUS[e.status ?? ''] ?? 'PLANNED', plannedDurationMin: e.target_time_min ?? e.expected_duration_min,
    distanceKm: e.distance_km, courseEndM: course.length ? course[course.length - 1] : null, calendarEventId: e.calendar_event_id,
  }
}

/** A logged time (local digits) as the zone's instant, written with its offset. */
export function loggedInstant(iso: string, zone: string): string | null {
  const t = iso.trim().replace(' ', 'T')
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(t)) return null
  return zdtString(atTime(t.slice(0, 10), t.slice(11, 19).padEnd(8, ':00').slice(0, 8), zone), zone)
}

/** Full years between a date of birth and a date (java.time.Period.between(...).years). */
export function ageYears(dob: string, asOf: string): number {
  let y = Number(asOf.slice(0, 4)) - Number(dob.slice(0, 4))
  if (asOf.slice(5) < dob.slice(5)) y -= 1
  return y
}

export interface FuelDays {
  today: string; todayIdx: number; days: PlannedDay[]; plans: DayFuelPlan[]; energy: WeekEnergy; weightKg: number; fatFreeMassKg: number | null
  /** each day's meals, coded under the settings given */
  meals: DayMeals[]
  /** the days before against what was planned (the phone's check before a race), today onward not included */
  history: IntakeHistoryResult
}

/** The phone keeps these on the phone only (FuelSettingsStore); the web uses the same defaults until it has its own. */
export interface FuelSettings { meals: MealTimes; trainLow: boolean; toleratedGPerHour: number }
export const DEFAULT_FUEL_SETTINGS: FuelSettings = { meals: DEFAULT_MEAL_TIMES, trainLow: false, toleratedGPerHour: DEFAULT_TOLERATED_G_PER_H }

/** The window the phone plans over: three days back (for the intake check) and the horizon ahead, as FuelPlanRepository.computeToday. */
export const DAYS_BACK = 3
export const DAYS_AHEAD = 14 + 3

/** Today's plan and the days around it, or null while no weight is known (the plan is per kg). */
export function buildFuelDays(rows: FuelRows, today: string, nowIso: string, zone: string, settings: FuelSettings = DEFAULT_FUEL_SETTINGS): FuelDays | null {
  const from = plusDays(today, -DAYS_BACK)
  const count = DAYS_BACK + DAYS_AHEAD
  const to = plusDays(from, count)
  const events = rows.events.filter((e) => e.event_date >= from && e.event_date < to).map(eventInput)
  const planned: PlannedSessionInput[] = rows.planned.map((p) => ({
    id: p.id, date: p.scheduled_date, startTime: p.start_time, durationMin: p.duration_min, domain: p.domain, title: p.title, gcalEventId: p.gcal_event_id, exerciseSessionId: p.exercise_session_id,
  }))
  const calendar: CalendarSessionInput[] = rows.calendar.filter((c) => c.start_time).map((c) => ({
    eventId: c.event_id, title: c.title ?? '', start: c.start_time as string, end: c.end_time, colorId: c.color_id, description: c.description,
  }))
  const recorded: RecordedSessionInput[] = []
  for (const r of rows.recorded) {
    const start = loggedInstant(r.start_time, zone)
    if (start) recorded.push({ id: r.id, type: r.type, start, durationMin: r.duration_min ?? 0, caloriesActive: r.calories_active, avgHr: r.avg_hr, rpe: r.rpe, distanceKm: r.distance_km, ascentM: r.elevation_gain_m })
  }
  recorded.sort((a, b) => Date.parse(a.start.replace(/\[.*\]$/, '')) - Date.parse(b.start.replace(/\[.*\]$/, '')))

  const restingHr = restingHrAsOf(rows.resting, from)
  const maxHr = robustMaxHr(rows.maxima, from)
  const latestWeight = rows.body.find((b) => b.weight_kg != null)?.weight_kg ?? null
  const history: DemandHistory = demandHistory(recorded, restingHr, maxHr, latestWeight, zone)

  const overrides: Record<string, CalendarOverride> = {}
  for (const o of rows.overrides) {
    if (o.treat_as !== 'ignore' && o.treat_as !== 'long_session') continue
    const i = o.intensity?.toUpperCase()
    overrides[o.event_id] = { eventId: o.event_id, kind: o.treat_as, durationMin: o.duration_min, intensity: (['EASY', 'MODERATE', 'HARD', 'LONG', 'RACE'].includes(i ?? '') ? i : null) as CalendarOverride['intensity'] }
  }
  // the links events and the training plan already carry, then the stored answers over them
  const links: Record<string, StoredLink> = {}
  for (const e of rows.events.filter((x) => x.exercise_session_id != null)) links[String(e.exercise_session_id)] = { sessionId: e.exercise_session_id as number, planRef: `event:${e.id}`, manual: true, planTitle: e.name }
  for (const p of rows.planned.filter((x) => x.exercise_session_id != null)) links[String(p.exercise_session_id)] = { sessionId: p.exercise_session_id as number, planRef: `planned:${p.id}`, manual: true, planTitle: p.title }
  for (const l of rows.links) links[String(l.exercise_session_id)] = { sessionId: l.exercise_session_id, planRef: l.plan_ref, manual: l.source === 'manual', confidence: l.confidence, planTitle: l.plan_title }

  const days = resolvePlannedDays(from, count, events, planned, calendar, recorded.filter((r) => localDay(r.start) >= from), history, nowIso, zone, overrides, links)

  const weightKg = rows.body.find((b) => b.weight_kg != null)?.weight_kg ?? null
  if (weightKg == null) return null
  const latestComp = rows.body.find((b) => b.lean_mass_kg != null || b.body_fat_pct != null)
  const ffm = fatFreeMass(weightKg, latestComp?.lean_mass_kg ?? null, latestComp?.body_fat_pct ?? null)
  const profile = rows.profile
  const energy = weekEnergyOf(
    profile?.nutrition_goals ?? {},
    { sex: profile?.sex ?? null, ageYears: profile?.date_of_birth ? ageYears(profile.date_of_birth, today) : null, heightCm: rows.heightCm, weightKg, weeklyTrainingMin: rows.trainingMin28 / 4 },
    aggregateMealsByDay(rows.meals), rows.body.filter((b) => b.weight_kg != null).map((b) => [b.date, b.weight_kg as number] as [string, number]), today,
  )
  const todayIdx = days.findIndex((d) => d.date === today)
  const plans = planInWeekBlocks(days, todayIdx, energy, weightKg, ffm)
  const meals = days.map((d, i) => codeMeals(plans[i], d, days[i + 1] ?? null, weightKg, zone, settings.meals, settings.trainLow, energy.deficit === true, settings.toleratedGPerHour))
  // The days before, as FuelPlanRepository.computeToday: planned plans of the days before today against the Log's full days; a race today or tomorrow;
  // this morning's weight against the median of the seven weights before it.
  const logged: LoggedDay[] = aggregateMealsByDay(rows.meals).map((d) => ({ date: d.date, mealsLogged: d.mealCount, carbG: d.carbsG, kcal: d.calories }))
  const raceSoon = days.filter((d) => d.date === today || d.date === plusDays(today, 1)).some((d) => d.items.some((i) => i.intensity === 'RACE' && i.eventPriority !== 'C'))
  const weights = rows.body.filter((b) => b.weight_kg != null)
  const morning = weights.find((b) => b.date === today)?.weight_kg ?? null
  const previous = weights.filter((b) => b.date !== today).slice(0, 7).map((b) => b.weight_kg as number).sort((a, b) => a - b)
  const usual = previous.length ? previous[Math.trunc(previous.length / 2)] : null
  const intakeHistory = checkIntakeHistory(plans.slice(0, todayIdx), logged, weightKg, raceSoon, morning, usual)
  return { today, todayIdx, days, plans, energy, weightKg, fatFreeMassKg: ffm, meals, history: intakeHistory }
}

const localDay = (zdt: string) => zdt.slice(0, 10)
