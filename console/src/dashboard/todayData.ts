// What Today reads for a day, all of it written by the phone: the kept scores (score_snapshots, with every
// input since 6 Oct 2026), the scoring history (score_history, numbers only, since Mar 2026) and the day's plan as TODAY
// drew it (today_plans, from 18 Nov 2026), and what was logged that day, read live so the plan stays current
// (todayLive.ts lays it over the saved plan). No score is recomputed here.
import { supabase } from '../lib/supabase'
import type { DayScores, DomainKey } from '../lib/scoreText'
import { plusDays } from '../lib/dates'
import type { DayLogs } from './todayLive'

export interface HistoryDay { day: string; charge: number | null; recovery: number | null; sleep: number | null; load: number | null; fuel: number | null; balance: number | null }

/** One row of TODAY'S PLAN (data/TodayPlanPublisher.kt, plan.v = 1). */
export interface PlanRowJson { time?: string; title: string; detail?: string; done: boolean; kind: 'MEAL' | 'SESSION' | 'SUPPLEMENTS' | 'CLOSE'; kcal?: number; duration_min?: number }
export interface SoFarJson { kcal: number; target_kcal?: number; carbs_g: number; protein_g: number; fat_g: number }
export interface DayPlan { rows: PlanRowJson[]; so_far?: SoFarJson; builtAt: string }

export interface TodayData { history: HistoryDay[]; kept: Record<string, number>; scores: DayScores | null; plan: DayPlan | null; logs: DayLogs | null }

export async function loadToday(day: string, from: string): Promise<TodayData> {
  const [h, k, s, p, logs] = await Promise.all([
    supabase.from('score_history').select('day,charge,recovery,sleep,load,fuel,balance').gte('day', from).lte('day', day).order('day'),
    supabase.from('score_snapshots').select('day,charge:scores->charge->score').gte('day', from).lte('day', day),
    supabase.from('score_snapshots').select('scores').eq('day', day).maybeSingle(),
    supabase.from('today_plans').select('plan,built_at').eq('day', day).maybeSingle(),
    // What was logged that day, read live: the saved plan stops at the last time TODAY was open on the phone.
    loadDayLogs(day).catch((e: unknown) => { console.error('[BioScan] day logs:', e); return null }),
  ])
  if (h.error) throw new Error(`score_history: ${h.error.message}`)
  // The kept scores and the plan are extras: a day without them still shows its numbers.
  const scores = !s.error && s.data ? (s.data as { scores: DayScores }).scores : null
  const pd = !p.error && p.data ? (p.data as { plan: { rows?: PlanRowJson[]; so_far?: SoFarJson }; built_at: string }) : null
  const kept: Record<string, number> = {}
  if (!k.error) for (const x of (k.data ?? []) as { day: string; charge: number | null }[]) if (typeof x.charge === 'number') kept[x.day] = x.charge
  return {
    history: (h.data ?? []) as HistoryDay[], kept,
    scores,
    plan: pd ? { rows: pd.plan.rows ?? [], so_far: pd.plan.so_far, builtAt: pd.built_at } : null,
    logs,
  }
}

/** The day's meals, workouts, supplements, routines, breathwork and evening check-ins, as they are in Supabase now. */
export async function loadDayLogs(day: string): Promise<DayLogs> {
  // Logged times are the phone's wall clock labelled +00: the local day is the UTC-labelled day of their digits.
  const lo = `${day}T00:00:00+00:00`, hi = `${plusDays(day, 1)}T00:00:00+00:00`
  const [meals, sessions, supps, routines, acts, breath, checkins] = await Promise.all([
    supabase.from('meals').select('id,logged_at,meal_slot,calories,carbs_g,protein_g,fat_g,description').gte('logged_at', lo).lt('logged_at', hi).order('logged_at'),
    supabase.from('exercise_sessions').select('start_time,type,duration_min').gte('start_time', lo).lt('start_time', hi).order('start_time'),
    supabase.from('supplement_log').select('taken_at,created_at').gte('taken_at', lo).lt('taken_at', hi),
    supabase.from('routine_logs').select('activity_id,started_at,minutes,status').eq('date', day),
    supabase.from('routine_activities').select('id,name'),
    supabase.from('breathwork_sessions').select('started_at,duration_s,pattern_name').gte('started_at', lo).lt('started_at', hi),
    supabase.from('evening_checkins').select('date').in('date', [plusDays(day, -1), day]),
  ])
  for (const [name, r] of [['meals', meals], ['exercise_sessions', sessions], ['supplement_log', supps]] as const) {
    if (r.error) throw new Error(`${name}: ${r.error.message}`)
  }
  // Routines, breathwork and check-ins are extras: a failed read leaves them out rather than the whole log.
  const names = new Map(((acts.error ? [] : acts.data) ?? []).map((a: { id: number; name: string }) => [a.id, a.name]))
  const days = new Set(((checkins.error ? [] : checkins.data) ?? []).map((c: { date: string }) => c.date))
  type RoutineRow = { activity_id: number | null; started_at: string | null; minutes: number | null; status: string | null }
  return {
    meals: meals.data ?? [],
    sessions: sessions.data ?? [],
    supplements: supps.data ?? [],
    routines: ((routines.error ? [] : routines.data) as RoutineRow[] ?? [])
      .filter((x) => x.status !== 'skipped')
      .map((x) => ({ started_at: x.started_at, minutes: x.minutes, name: x.activity_id != null ? names.get(x.activity_id) ?? null : null })),
    breathwork: (breath.error ? [] : breath.data) ?? [],
    closed: days.has(day),
    closedBefore: days.has(plusDays(day, -1)),
  }
}

const HISTORY_KEY: Record<DomainKey, keyof HistoryDay> = { SLEEP: 'sleep', RECOVERY: 'recovery', LOAD: 'load', FUEL: 'fuel', BALANCE: 'balance' }

/** The day's numbers as the phone shows them: the kept morning when there is one, else the scoring history. */
export function shownScore(d: TodayData, day: string, key: DomainKey | 'CHARGE'): number | null {
  if (d.scores) return key === 'CHARGE' ? d.scores.charge.score ?? null : d.scores.domains[key]?.score ?? null
  const h = d.history.find((x) => x.day === day)
  if (!h) return null
  return (key === 'CHARGE' ? h.charge : (h[HISTORY_KEY[key]] as number | null)) ?? null
}

/** CHARGE per day for the strip, as the phone's strip shows it: the kept morning, else the scoring history. */
export function stripCharge(d: TodayData, day: string): number | null {
  return d.kept[day] ?? d.history.find((x) => x.day === day)?.charge ?? null
}
