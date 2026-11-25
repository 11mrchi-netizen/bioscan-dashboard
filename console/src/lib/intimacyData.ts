// Me › INTIMACY's reads and writes. Read straight from the tables, as the phone's page does (IntimacyRepository.kt): the page
// is opened rarely and must show an entry saved a moment ago. Writes change only the columns the page edits, so a field the
// phone owns is never overwritten from here.
import { supabase } from './supabase'
import type { Encounter, Person, SoloDay, StiTest } from './intimacy'

export const ENCOUNTER_COLUMNS =
  'id,date,status,occurred_at,encounter_type,location_type,duration_min,activities,my_rating,notes,person_id,place_id,sexual,format,' +
  'positions,toys,protection,ejaculation_location,rounds,orgasm_count,orgasm_intensity,sheet_encounter_id'
export const PERSON_COLUMNS = 'id,name,period,score,where_met,relationship,gender,country,age_range,body_type,activities,interested_in,first_met_date,availability,notes'
const STI_COLUMNS = 'id,test_date,infection,result,site,lab_name,treated_on,notes'

/** Every row of a small table, a page of 1,000 at a time (PostgREST's cap), in a stable order. */
async function all<R>(table: string, select: string, order: string): Promise<R[]> {
  const out: R[] = []
  for (let from = 0; ; from += 1000) {
    const r = await supabase.from(table).select(select).order(order).order('id').range(from, from + 999)
    if (r.error) throw new Error(`${table}: ${r.error.message}`)
    const rows = (r.data ?? []) as R[]
    out.push(...rows)
    if (rows.length < 1000) return out
  }
}

export interface IntimacyData {
  encounters: Encounter[]
  people: Person[]
  solo: SoloDay[]
  /** null with the reason when the table cannot be read (before its migration reaches a database) */
  tests: StiTest[] | null
  testsError: string | null
  places: Map<string, string>
}

export async function loadIntimacy(): Promise<IntimacyData> {
  const [encounters, people, solo, tests, places] = await Promise.all([
    all<Encounter>('encounters', ENCOUNTER_COLUMNS, 'date'),
    all<Person>('people', PERSON_COLUMNS, 'name'),
    supabase.from('sexual_activity_daily').select('id,date,instances,notes').eq('activity_type', 'masturbation').order('date').range(0, 4999),
    supabase.from('sti_tests').select(STI_COLUMNS).order('test_date', { ascending: false }),
    supabase.from('places').select('id,label,osm_name'),
  ])
  if (solo.error) throw new Error(`sexual_activity_daily: ${solo.error.message}`)
  return {
    encounters: encounters.map((e) => ({ ...e, sexual: !!e.sexual })),
    people,
    solo: ((solo.data ?? []) as SoloDay[]).map((s) => ({ ...s, instances: Array.isArray(s.instances) ? s.instances : [] })),
    tests: tests.error ? null : (tests.data ?? []) as StiTest[],
    testsError: tests.error?.message ?? null,
    places: new Map(((places.data ?? []) as { id: string; label: string | null; osm_name: string | null }[]).map((p) => [p.id, p.label ?? p.osm_name ?? 'a place with no name'])),
  }
}

// ---- the daily series the Effects view reads ----

export interface Outcome { id: string; label: string; unit: string; hue: string; metric?: string; better: 'higher' | 'lower' | null; days: Map<string, number> }

/** Per local date, the mean of the rows of that date (two sleep sources on one night count once). */
function byDay(rows: Record<string, unknown>[], dateCol: string, col: string): Map<string, number> {
  const acc = new Map<string, number[]>()
  for (const r of rows) {
    const v = r[col], d = r[dateCol]
    if (typeof d !== 'string' || v == null || !Number.isFinite(Number(v))) continue
    const k = d.slice(0, 10)
    acc.set(k, [...(acc.get(k) ?? []), Number(v)])
  }
  return new Map([...acc].map(([k, vs]) => [k, vs.reduce((s, x) => s + x, 0) / vs.length]))
}

export async function loadOutcomes(from: string): Promise<{ outcomes: Outcome[]; missing: string[] }> {
  const [w, s, wb, sc] = await Promise.all([
    supabase.from('wearable_daily').select('date,hrv,rhr').gte('date', from).range(0, 4999),
    supabase.from('sleep_daily').select('date,hours,score,deep_min').gte('date', from).range(0, 4999),
    supabase.from('wellbeing_daily').select('date,energy,mood,stress,morning_erection_quality,arousal_level').gte('date', from).range(0, 4999),
    supabase.from('score_history').select('day,charge').gte('day', from).range(0, 4999),
  ])
  const missing = [w.error && 'wearable_daily', s.error && 'sleep_daily', wb.error && 'wellbeing_daily', sc.error && 'score_history'].filter(Boolean) as string[]
  const W = (w.data ?? []) as Record<string, unknown>[], S = (s.data ?? []) as Record<string, unknown>[]
  const B = (wb.data ?? []) as Record<string, unknown>[], C = (sc.data ?? []) as Record<string, unknown>[]
  const outcomes: Outcome[] = [
    { id: 'hrv', label: 'Overnight HRV', unit: 'ms', hue: 'm-hrv', metric: 'series:hrv', better: 'higher', days: byDay(W, 'date', 'hrv') },
    { id: 'rhr', label: 'Resting heart rate', unit: 'bpm', hue: 'm-resting-hr', metric: 'series:rhr', better: 'lower', days: byDay(W, 'date', 'rhr') },
    { id: 'sleep_hours', label: 'Sleep', unit: 'h', hue: 'm-sleep-duration', metric: 'series:sleep_hours', better: null, days: byDay(S, 'date', 'hours') },
    { id: 'sleep_score', label: 'Sleep score', unit: '', hue: 'm-sleep-score', metric: 'series:sleep_score', better: 'higher', days: byDay(S, 'date', 'score') },
    { id: 'sleep_deep', label: 'Deep sleep', unit: 'min', hue: 'm-stage-deep', metric: 'series:sleep_deep_min', better: null, days: byDay(S, 'date', 'deep_min') },
    { id: 'mood', label: 'Mood', unit: '/10', hue: 'm-mood', metric: 'series:mood', better: 'higher', days: byDay(B, 'date', 'mood') },
    { id: 'energy', label: 'Energy', unit: '/10', hue: 'm-energy', metric: 'series:energy', better: 'higher', days: byDay(B, 'date', 'energy') },
    { id: 'stress', label: 'Stress', unit: '/10', hue: 'm-stress', metric: 'series:stress', better: 'lower', days: byDay(B, 'date', 'stress') },
    { id: 'erection', label: 'Morning erection', unit: '/10', hue: 'm-arousal', metric: 'recovery.morning_erection', better: 'higher', days: byDay(B, 'date', 'morning_erection_quality') },
    { id: 'arousal', label: 'Arousal', unit: '/10', hue: 'm-arousal', metric: 'recovery.arousal', better: 'higher', days: byDay(B, 'date', 'arousal_level') },
    { id: 'charge', label: 'CHARGE', unit: '', hue: 'ft-charge-b', metric: 'charge', better: 'higher', days: byDay(C, 'day', 'charge') },
  ]
  return { outcomes, missing }
}

// ---- writes ----

export type EncounterTagPatch = Partial<Pick<Encounter, 'format' | 'activities' | 'positions' | 'toys' | 'protection' | 'ejaculation_location' | 'my_rating'>>

export async function updateEncounter(id: number, patch: EncounterTagPatch): Promise<void> {
  const r = await supabase.from('encounters').update(patch).eq('id', id)
  if (r.error) throw new Error(r.error.message)
}

/** The profile fields the partner page edits (the phone's NewPartnerSheet carries the same). */
export type PersonPatch = Partial<Omit<Person, 'id'>>

export async function updatePerson(id: number, patch: PersonPatch): Promise<void> {
  const r = await supabase.from('people').update(patch).eq('id', id)
  if (r.error) throw new Error(r.error.message)
}

export interface NewStiTest { test_date: string; infection: string; result: string; site: string | null; lab_name: string | null; notes: string | null }

export async function addStiTests(rows: NewStiTest[]): Promise<void> {
  const r = await supabase.from('sti_tests').insert(rows)
  if (r.error) throw new Error(r.error.message)
}

export async function setStiTreated(id: number, on: string | null): Promise<void> {
  const r = await supabase.from('sti_tests').update({ treated_on: on }).eq('id', id)
  if (r.error) throw new Error(r.error.message)
}

export async function deleteStiTest(id: number): Promise<void> {
  const r = await supabase.from('sti_tests').delete().eq('id', id)
  if (r.error) throw new Error(r.error.message)
}
