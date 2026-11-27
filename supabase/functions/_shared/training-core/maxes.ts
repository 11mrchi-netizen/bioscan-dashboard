// Ports of domain/training/MovementKey.kt, MaxEstimator.kt and MaxResolver.kt: which max a prescription is built from.
// A recorded max and an estimate from logged sets compete on recency (a recorded max wins a tie); every result says
// where it came from.

import { type LocalDate, plusDays } from './dates.ts'

const DROP = new Set(['barbell', 'standing', 'and', 'the', 'a', 'bb', 'with'])
const WORD_MAP: Record<string, string> = {
  military: 'overhead', shoulder: 'overhead', inclined: 'incline',
  ups: 'up', squats: 'squat', presses: 'press', rows: 'row', dips: 'dip', lunges: 'lunge', deadlifts: 'deadlift',
  pulls: 'pull', dl: 'deadlift',
}
const PHRASE_MAP: [string, string][] = [
  ['chest press', 'bench press'], ['bent over row', 'row'], ['bent over barbell row', 'row'], ['standing barbell press', 'overhead press'],
]

/** Folds the spellings of one exercise together ("Standing Military Press" = "Overhead Press"); unknown names keep their own key. */
export function movementKey(name: string): string {
  let s = name.replace(/\([A-Z]{2,5}\)/g, ' ').toLowerCase()
  s = s.replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim()
  // "Trapbar", "Trap-Bar", "Hex Bar" and "Trap Bar" are one bar; likewise "Pushups" / "Push-up".
  s = s.replaceAll('trapbar', 'trap bar').replaceAll('hexbar', 'trap bar').replaceAll('hex bar', 'trap bar')
  s = s.replaceAll('pushups', 'push up').replaceAll('pushup', 'push up')
  s = s.replaceAll('pullups', 'pull up').replaceAll('pullup', 'pull up').replaceAll('chinups', 'chin up').replaceAll('chinup', 'chin up')
  for (const [from, to] of PHRASE_MAP) s = s.replaceAll(from, to)
  const tokens = s.split(' ').filter((t) => t !== '' && !DROP.has(t)).map((t) => WORD_MAP[t] ?? t)
  return [...new Set(tokens)].sort().join(' ')
}

export type MaxSource = 'test' | 'implied_logged_percent' | 'estimate' | 'manual' | 'progression'

export interface DatedSet { date: LocalDate; reps: number; weightKg: number; percentOneRm?: number | null }
export interface MaxEstimate { kg: number; source: MaxSource; asOf: LocalDate; basedOnSets: number }

const MAX_REPS_FOR_ONE_RM_ESTIMATE = 12
export const epley = (reps: number, kg: number): number | null => (reps >= 1 && reps <= MAX_REPS_FOR_ONE_RM_ESTIMATE ? kg * (1 + reps / 30) : null)

export function median(values: number[]): number {
  if (values.length === 0) return 0
  const s = [...values].sort((a, b) => a - b)
  const mid = Math.floor(s.length / 2)
  return s.length % 2 === 0 ? (s[mid - 1] + s[mid]) / 2 : s[mid]
}

/** 1. the percent of 1RM the lifter logged (weight / percent, median of the latest such day); 2. Epley on the best set, reps <= 12. */
export function estimateMaxFromHistory(sets: DatedSet[], today: LocalDate, lookbackDays = 84): MaxEstimate | null {
  const from = plusDays(today, -lookbackDays)
  const recent = sets.filter((s) => s.date <= today && s.date >= from && s.weightKg > 0)
  const withPercent = recent.filter((s) => (s.percentOneRm ?? 0) > 0)
  if (withPercent.length > 0) {
    const day = withPercent.reduce((m, s) => (s.date > m ? s.date : m), withPercent[0].date)
    const implied = withPercent.filter((s) => s.date === day).map((s) => s.weightKg / ((s.percentOneRm as number) / 100))
    return { kg: median(implied), source: 'implied_logged_percent', asOf: day, basedOnSets: implied.length }
  }
  let best: [number, LocalDate] | null = null
  for (const s of recent) {
    const e = epley(s.reps, s.weightKg)
    if (e != null && (best == null || e > best[0])) best = [e, s.date]
  }
  return best ? { kg: best[0], source: 'estimate', asOf: best[1], basedOnSets: 1 } : null
}

export interface MaxEntry { oneRmKg?: number | null; trainingMaxKg?: number | null; maxReps?: number | null }
export interface RecordedMax { movementKey: string; name: string; kind: string; value: number; asOf: LocalDate; source: string }
export interface ResolvedMax { key: string; name: string; entry: MaxEntry; source: string; asOf: LocalDate | null; basedOn: string }

const ONE_RM_KINDS = new Set(['1rm', 'e1rm', 'rm_derived'])

const latest = (rows: RecordedMax[]): RecordedMax | undefined => rows.reduce<RecordedMax | undefined>((m, r) => (!m || r.asOf > m.asOf ? r : m), undefined)

export function resolveMaxes(recorded: RecordedMax[], logged: Record<string, DatedSet[]>, names: Record<string, string>, today: LocalDate): Record<string, ResolvedMax> {
  const keys = new Set([...recorded.map((r) => r.movementKey), ...Object.keys(logged)])
  const out: Record<string, ResolvedMax> = {}
  for (const key of keys) {
    const rows = recorded.filter((r) => r.movementKey === key)
    const oneRm = latest(rows.filter((r) => ONE_RM_KINDS.has(r.kind)))
    const maxReps = latest(rows.filter((r) => r.kind === 'max_reps'))
    const training = latest(rows.filter((r) => r.kind === 'training_max'))
    const estimate = logged[key] ? estimateMaxFromHistory(logged[key], today) : null
    const useRecorded = oneRm != null && (estimate == null || !(estimate.asOf > oneRm.asOf))
    out[key] = {
      key,
      name: names[key] ?? rows[0]?.name ?? key,
      entry: { oneRmKg: useRecorded ? oneRm!.value : estimate?.kg ?? null, trainingMaxKg: training?.value ?? null, maxReps: maxReps ? Math.trunc(maxReps.value) : null },
      source: useRecorded ? oneRm!.source : estimate ? estimate.source : 'none',
      asOf: useRecorded ? oneRm!.asOf : estimate?.asOf ?? null,
      basedOn: useRecorded ? `recorded ${oneRm!.source} ${oneRm!.asOf}`
        : estimate ? `${estimate.source.replaceAll('_', ' ')}, ${estimate.basedOnSets} set(s) on ${estimate.asOf}` : 'no data',
    }
  }
  return out
}
