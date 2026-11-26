// Catalogue 2.5.0: richer sleep outcomes and a caffeine dose, from tables the engine did not read before.
//
// Outcomes (one value per wake date, the date sleep_daily carries):
//   hr_dip        the night's heart-rate dip below the daytime level, % (hr_dip_nightly.dip_pct, from Jul 2026)
//   deep_share    deep sleep as a share of the stages' total, % (sleep_daily deep, rem and light minutes)
//   bedtime_min   minutes from 18:00 to going to bed, local (a real instant, read with the day's UTC offset)
//   spo2          the night's mean blood-oxygen saturation, % (wearable_daily.spo2_avg)
// Exposure (one value per eating day, null on a day without meals):
//   caffeine_board_bed  mg of caffeine still on board at that night's bedtime: every logged item's mg, decayed with the
//                       5 h half-life the Today curve uses (substanceCurves.ts), 0 on a day with meals and no caffeine.
//                       A dose version of "caffeine after 14:00", which was on 9 days and never settled.

import type { Num } from './stats.ts'
import { eatingDay, wallMs } from './mealExposures.ts'

export const CAFFEINE_HALF_LIFE_H = 5
const HOUR = 3600000
const num = (v: unknown): number | null => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

export interface CaffeineMealRow { logged_at: string; meal_items: { caffeine_mg: number | null }[] | null }
export interface SleepStageRow { date: string; deep_min?: number | null; rem_min?: number | null; light_min?: number | null; bedtime?: string | null }

function shift(date: string, days: number): string {
  return new Date(Date.parse(date + 'T00:00:00Z') + days * 86400000).toISOString().slice(0, 10)
}

/** mg of caffeine on board at each eating day's bedtime (the sleep row dated the next day), decayed from every dose that day. */
export function caffeineBoardSeries(axis: string[], meals: CaffeineMealRow[], sleep: { date: string; bedtime?: string | null }[], bedtimeLocalMs: (stamp: string) => number | null): Num[] {
  const doses = new Map<string, { t: number; mg: number }[]>()
  const mealDays = new Set<string>()
  for (const m of meals) {
    const t = wallMs(m.logged_at)
    if (!Number.isFinite(t)) continue
    const d = eatingDay(t)
    mealDays.add(d)
    for (const it of m.meal_items ?? []) {
      const mg = num(it.caffeine_mg)
      if (mg === null || mg <= 0) continue
      const list = doses.get(d) ?? []
      list.push({ t, mg })
      doses.set(d, list)
    }
  }
  const bed = new Map<string, number>()
  for (const s of sleep) {
    if (!s.bedtime) continue
    const ms = bedtimeLocalMs(s.bedtime)
    if (ms !== null && Number.isFinite(ms)) bed.set(s.date, ms)
  }
  return axis.map((d) => {
    if (!mealDays.has(d)) return null
    const b = bed.get(shift(d, 1))
    if (b === undefined) return null
    let board = 0
    for (const x of doses.get(d) ?? []) if (x.t <= b) board += x.mg * 0.5 ** ((b - x.t) / HOUR / CAFFEINE_HALF_LIFE_H)
    return Math.round(board)
  })
}

/** The sleep outcome series on [axis]. [dips] are hr_dip_nightly rows, [spo2] wearable rows. Pure. */
export function sleepOutcomeSeries(
  axis: string[],
  sleep: SleepStageRow[],
  dips: { date: string; dip_pct: number | null }[],
  spo2: { date: string; spo2_avg?: number | null }[],
  bedtimeLocalMs: (stamp: string) => number | null,
): Record<string, Num[]> {
  const sleepBy = new Map(sleep.map((s) => [s.date, s]))
  const dipBy = new Map(dips.map((r) => [r.date, num(r.dip_pct)]))
  const spoBy = new Map(spo2.map((r) => [r.date, num(r.spo2_avg)]))
  return {
    hr_dip: axis.map((d) => { const v = dipBy.get(d) ?? null; return v !== null && v > 0 && v < 100 ? v : null }),
    deep_share: axis.map((d) => {
      const s = sleepBy.get(d)
      const deep = num(s?.deep_min), rem = num(s?.rem_min), light = num(s?.light_min)
      if (deep === null || rem === null || light === null || deep + rem + light < 120) return null
      return Math.round((deep / (deep + rem + light)) * 1000) / 10
    }),
    bedtime_min: axis.map((d) => {
      const b = sleepBy.get(d)?.bedtime
      const ms = b ? bedtimeLocalMs(b) : null
      if (ms === null || !Number.isFinite(ms)) return null
      const minOfDay = Math.floor((ms % 86400000) / 60000)
      return (minOfDay - 18 * 60 + 1440) % 1440
    }),
    spo2: axis.map((d) => { const v = spoBy.get(d) ?? null; return v !== null && v >= 80 && v <= 100 ? v : null }),
  }
}
