// Reads for the web's fuel plan: the same tables, windows and caps as the phone's PlannedDayRepository / FuelPlanRepository, then
// buildFuelDays (fuelInputs.ts) turns the rows into the plan. Read only: nothing here writes (fuel_plans stays the phone's record).
import { supabase } from './supabase'
import { localToday } from './dates'
import { buildFuelDays, DAYS_AHEAD, DAYS_BACK, type BodyRow, type FuelDays, type FuelRows, type SessionRow } from './fuelInputs'
import { loadFuelSettings } from './fuelSettingsData'

const PAGE = 1000
const plusDays = (d: string, n: number) => new Date(Date.UTC(Number(d.slice(0, 4)), Number(d.slice(5, 7)) - 1, Number(d.slice(8, 10)) + n)).toISOString().slice(0, 10)

async function paged<T>(table: string, cols: string, order: string, where: (q: any) => any): Promise<T[]> { // eslint-disable-line @typescript-eslint/no-explicit-any
  const out: T[] = []
  for (let start = 0; start < 100 * PAGE; start += PAGE) {
    const r = await where(supabase.from(table).select(cols)).order(order, { ascending: true }).range(start, start + PAGE - 1)
    if (r.error) throw new Error(`${table}: ${r.error.message}`)
    const rows = (r.data ?? []) as T[]
    out.push(...rows)
    if (rows.length < PAGE) break
  }
  return out
}

async function must<T>(q: PromiseLike<{ data: T | null; error: { message: string } | null }>, what: string): Promise<T> {
  const r = await q
  if (r.error) throw new Error(`${what}: ${r.error.message}`)
  return r.data as T
}
/** A table that may not exist yet (a migration not applied): empty then. */
async function maybe<T>(q: PromiseLike<{ data: T[] | null; error: { message: string } | null }>): Promise<T[]> {
  const r = await q
  return r.error ? [] : (r.data ?? [])
}

export async function loadFuelRows(today: string, zone: string): Promise<FuelRows> {
  const from = plusDays(today, -DAYS_BACK)
  const to = plusDays(from, DAYS_BACK + DAYS_AHEAD)
  const zoneStart = (d: string) => new Date(Date.parse(`${d}T00:00:00Z`) - offsetAt(d, zone) * 60000).toISOString()
  const threeYears = plusDays(today, -3 * 365)
  const sessionCols = 'id,type,start_time,duration_min,calories_active,avg_hr,rpe,distance_km,elevation_gain_m'
  const [events, planned, calendar, recorded, maxima, resting, overrides, links, profile, body, height, training, meals] = await Promise.all([
    must(supabase.from('athlete_events').select('id,name,kind,sport,priority,event_date,start_time,status,target_time_min,expected_duration_min,distance_km,course_profile,calendar_event_id,exercise_session_id').order('event_date', { ascending: false }).limit(500), 'athlete_events'),
    must(supabase.from('planned_sessions').select('id,scheduled_date,start_time,duration_min,domain,title,gcal_event_id,exercise_session_id').gte('scheduled_date', from).lt('scheduled_date', to).in('status', ['planned', 'confirmed']), 'planned_sessions'),
    must(supabase.from('calendar_events').select('event_id,title,description,start_time,end_time,color_id').eq('is_cancelled', false).gte('start_time', zoneStart(from)).lt('start_time', zoneStart(to)), 'calendar_events'),
    paged<SessionRow>('exercise_sessions', sessionCols, 'start_time', (q) => q.gte('start_time', `${plusDays(from, -180)}T00:00:00Z`).neq('type', 'walk')),
    paged<{ start_time: string; max_hr: number }>('exercise_sessions', 'start_time,max_hr', 'start_time', (q) => q.gte('start_time', threeYears).gt('max_hr', 0)),
    paged<{ date: string; rhr: number }>('wearable_daily', 'date,rhr', 'date', (q) => q.gte('date', threeYears).gt('rhr', 0)),
    maybe(supabase.from('calendar_event_overrides').select('event_id,treat_as,duration_min,intensity')),
    maybe(supabase.from('session_plan_links').select('exercise_session_id,plan_ref,plan_title,source,confidence')),
    supabase.from('user_profile').select('sex,date_of_birth,nutrition_goals').maybeSingle().then((r) => (r.error ? null : r.data)),
    must(supabase.from('body_metrics').select('date,weight_kg,lean_mass_kg,body_fat_pct').order('date', { ascending: false }).limit(30), 'body_metrics'),
    supabase.from('body_metrics').select('height_cm').not('height_cm', 'is', null).order('date', { ascending: false }).limit(1).then((r) => (r.data?.[0] as { height_cm: number } | undefined)?.height_cm ?? null),
    must(supabase.from('exercise_sessions').select('duration_min').gte('start_time', plusDays(today, -28)).gt('duration_min', 0).neq('type', 'walk'), 'exercise_sessions'),
    must(supabase.from('meals').select('logged_at,calories,protein_g,fat_g,carbs_g,meal_slot').order('logged_at', { ascending: false }).limit(5000), 'meals'),
  ])
  return {
    events: events as FuelRows['events'], planned: planned as FuelRows['planned'], calendar: calendar as FuelRows['calendar'], recorded,
    maxima: maxima.map((r) => [r.start_time.slice(0, 10), r.max_hr] as [string, number]), resting: resting.map((r) => [r.date, r.rhr] as [string, number]),
    overrides: overrides as FuelRows['overrides'], links: links as FuelRows['links'], profile: profile as FuelRows['profile'], body: body as BodyRow[], heightCm: height,
    trainingMin28: (training as { duration_min: number }[]).reduce((a, r) => a + (r.duration_min ?? 0), 0), meals: meals as FuelRows['meals'],
  }
}

/** The zone's offset from UTC at local midnight of a date, minutes. */
function offsetAt(date: string, zone: string): number {
  const t = Date.parse(`${date}T00:00:00Z`)
  const p = new Intl.DateTimeFormat('en-US', { timeZone: zone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).formatToParts(new Date(t))
  const o: Record<string, string> = {}
  for (const x of p) o[x.type] = x.value
  return Math.round((Date.UTC(Number(o.year), Number(o.month) - 1, Number(o.day), Number(o.hour) % 24, Number(o.minute), Number(o.second)) - t) / 60000)
}

/** Today's fuel plan and the days around it, under the phone's fuel settings; null while no weight is on record. */
export async function loadFuelDays(now: Date = new Date()): Promise<FuelDays | null> {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
  const today = localToday(now)
  const [rows, settingsRead] = await Promise.all([loadFuelRows(today, zone), loadFuelSettings()])
  const days = buildFuelDays(rows, today, now.toISOString(), zone, settingsRead.settings)
  return days ? { ...days, settingsFromPhone: settingsRead.fromPhone } : null
}
