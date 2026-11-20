// Home blood pressure, as the phone works it out (android domain/bp/BloodPressure.kt; risk profile R0). Keep the two in
// step: readings within 15 minutes are one sitting; a gap of more than 3 days starts a new series; the first day of a
// series is left out of its mean, and at least 3 days must remain. Categories: ACC/AHA 2017, the higher one wins.
// occurred_at is a logged time: its digits are local, read as they are.

export interface BpRow { id: number; occurred_at: string; systolic: number; diastolic: number; pulse: number | null; source: string }
export interface Sitting { start: string; date: string; systolic: number; diastolic: number; n: number }
export interface Series { days: string[]; counted: string[]; usable: boolean; systolic: number | null; diastolic: number | null; readings: number }
export type Category = 'Normal' | 'Elevated' | 'Stage 1 hypertension' | 'Stage 2 hypertension'

const SITTING_GAP_MIN = 15
const SERIES_GAP_DAYS = 3
const MIN_USABLE_DAYS = 3

const wallMs = (s: string) => Date.parse(s.slice(0, 19) + 'Z')
const dayMs = (d: string) => Date.parse(d + 'T00:00:00Z')

export function categorize(sys: number, dia: number): Category {
  const s = Math.round(sys)
  const d = Math.round(dia)
  if (s >= 140 || d >= 90) return 'Stage 2 hypertension'
  if (s >= 130 || d >= 80) return 'Stage 1 hypertension'
  if (s >= 120) return 'Elevated'
  return 'Normal'
}

export function sittings(rows: BpRow[]): Sitting[] {
  const sorted = [...rows].sort((a, b) => wallMs(a.occurred_at) - wallMs(b.occurred_at))
  const groups: BpRow[][] = []
  for (const r of sorted) {
    const last = groups.at(-1)?.at(-1)
    if (last && wallMs(r.occurred_at) - wallMs(last.occurred_at) <= SITTING_GAP_MIN * 60000) groups.at(-1)!.push(r)
    else groups.push([r])
  }
  return groups.map((g) => ({
    start: g[0].occurred_at, date: g[0].occurred_at.slice(0, 10), n: g.length,
    systolic: g.reduce((a, r) => a + r.systolic, 0) / g.length, diastolic: g.reduce((a, r) => a + r.diastolic, 0) / g.length,
  }))
}

export function series(rows: BpRow[]): Series[] {
  const days = [...new Set(rows.map((r) => r.occurred_at.slice(0, 10)))].sort()
  const runs: string[][] = []
  for (const d of days) {
    const last = runs.at(-1)?.at(-1)
    if (last && dayMs(d) - dayMs(last) <= SERIES_GAP_DAYS * 86400000) runs.at(-1)!.push(d)
    else runs.push([d])
  }
  return runs.map((ds) => {
    const counted = ds.length > 1 ? ds.slice(1) : ds
    const inCount = rows.filter((r) => counted.includes(r.occurred_at.slice(0, 10)))
    const usable = ds.length > 1 && counted.length >= MIN_USABLE_DAYS
    return {
      days: ds, counted, usable, readings: inCount.length,
      systolic: inCount.length ? inCount.reduce((a, r) => a + r.systolic, 0) / inCount.length : null,
      diastolic: inCount.length ? inCount.reduce((a, r) => a + r.diastolic, 0) / inCount.length : null,
    }
  })
}
