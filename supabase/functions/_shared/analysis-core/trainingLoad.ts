// The apps' session load, for the analysis frame (analysis review phase G). The same formula as Android
// (data/AnalysisRepository.kt buildTrainingSessionLoads, domain/EnduranceLoad.kt) and the console (src/lib/training.ts):
// session RPE x minutes when an RPE was logged, otherwise Banister's TRIMP from average and maximum heart rate against the
// latest resting heart rate, otherwise nothing (the session is left out, never guessed). Erasable TypeScript, no I/O.

export interface SessionLoadInput {
  duration_min: number | null
  rpe?: number | null
  avg_hr?: number | null
  max_hr?: number | null
}

const TRIMP_COEFFICIENT = 0.64
const TRIMP_EXPONENT = 1.92

export function sessionLoad(s: SessionLoadInput, restingHr: number | null): number | null {
  const duration = s.duration_min
  if (duration == null || !(duration > 0)) return null
  if (s.rpe != null && s.rpe > 0) return s.rpe * duration
  const avg = s.avg_hr
  const max = s.max_hr
  if (avg == null || max == null || restingHr == null || max <= restingHr) return null
  const ratio = (avg - restingHr) / (max - restingHr)
  if (ratio <= 0) return null
  return duration * ratio * TRIMP_COEFFICIENT * Math.exp(TRIMP_EXPONENT * ratio)
}

/**
 * Exponentially weighted average with an N-day span (alpha = 2 / (N + 1)), the convention the apps use for fitness (42)
 * and fatigue (7), seeded with the first day that has a load, as Android's dualEwma is. From then on a missing day counts
 * as rest (zero); before it there is no value.
 */
export function spanEma(load: (number | null)[], span: number): (number | null)[] {
  const alpha = 2 / (span + 1)
  const start = load.findIndex((v) => v !== null && v !== undefined)
  if (start < 0) return load.map(() => null)
  let acc = load[start] as number
  return load.map((v, i) => {
    if (i < start) return null
    if (i > start) acc += alpha * ((v ?? 0) - acc)
    return acc
  })
}
