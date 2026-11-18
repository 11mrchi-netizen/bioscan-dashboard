// What Dashboard › Today reads for a day, all of it written by the phone: the kept scores (score_snapshots, with every
// input since 6 Oct 2026), the scoring history (score_history, numbers only, since Mar 2026) and the day's plan as TODAY
// drew it (today_plans, from 18 Nov 2026). Nothing is recomputed here.
import { supabase } from '../lib/supabase'
import type { DayScores, DomainKey } from '../lib/scoreText'

export interface HistoryDay { day: string; charge: number | null; recovery: number | null; sleep: number | null; load: number | null; fuel: number | null; balance: number | null }

/** One row of TODAY'S PLAN (data/TodayPlanPublisher.kt, plan.v = 1). */
export interface PlanRowJson { time?: string; title: string; detail?: string; done: boolean; kind: 'MEAL' | 'SESSION' | 'SUPPLEMENTS' | 'CLOSE'; kcal?: number; duration_min?: number }
export interface SoFarJson { kcal: number; target_kcal?: number; carbs_g: number; protein_g: number; fat_g: number }
export interface DayPlan { rows: PlanRowJson[]; so_far?: SoFarJson; builtAt: string }

export interface TodayData { history: HistoryDay[]; kept: Record<string, number>; scores: DayScores | null; plan: DayPlan | null }

export async function loadToday(day: string, from: string): Promise<TodayData> {
  const [h, k, s, p] = await Promise.all([
    supabase.from('score_history').select('day,charge,recovery,sleep,load,fuel,balance').gte('day', from).lte('day', day).order('day'),
    supabase.from('score_snapshots').select('day,charge:scores->charge->score').gte('day', from).lte('day', day),
    supabase.from('score_snapshots').select('scores').eq('day', day).maybeSingle(),
    supabase.from('today_plans').select('plan,built_at').eq('day', day).maybeSingle(),
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
