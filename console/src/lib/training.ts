// Training load and VO2max helpers for the Training workspace. Analysis review A5: these now match the Android app's
// load model (domain/EnduranceLoad.kt, TrainingLoadEvaluation.kt) so the two apps show the same fitness numbers.

export interface SessionLike {
  duration_minutes: number | null
  rpe?: number | null
  avg_hr?: number | null
  max_hr?: number | null
}

// Banister TRIMP constants, as on Android (EnduranceLoad.kt).
const TRIMP_COEFFICIENT = 0.64
const TRIMP_EXPONENT = 1.92

/**
 * One session's load: session RPE x minutes when an RPE was logged, otherwise Banister's TRIMP from average and maximum
 * heart rate against the latest resting heart rate, otherwise null (the session is left out, never guessed).
 * The old helper scored by activity name with keys (interval, tempo...) that stored types (run, ride) never contain, so
 * every session counted as 100 per hour.
 */
export function sessionLoad(s: SessionLike, restingHr: number | null): number | null {
  const duration = s.duration_minutes
  if (duration == null || duration <= 0) return null
  if (s.rpe != null && s.rpe > 0) return s.rpe * duration
  const avg = s.avg_hr
  const max = s.max_hr
  if (avg == null || max == null || restingHr == null || max <= restingHr) return null
  const ratio = (avg - restingHr) / (max - restingHr)
  if (ratio <= 0) return null
  return duration * ratio * TRIMP_COEFFICIENT * Math.exp(TRIMP_EXPONENT * ratio)
}

/**
 * VO2max estimate from one effort (Daniels & Gilbert): the oxygen cost of running at v metres per minute, divided by the
 * share of VO2max that can be held for t minutes. The old helper passed km/h into a formula built for metres per minute
 * (12 km/h came out near -2.4) and skipped the duration term. Meaningful only for hard, steady efforts of roughly 4 to
 * 120 minutes; easy runs underestimate.
 */
export function vo2maxFromPace(distKm: number, durMin: number): number | null {
  if (!distKm || !durMin || distKm <= 0 || durMin < 4 || durMin > 240) return null
  const v = (distKm * 1000) / durMin
  const cost = -4.6 + 0.182258 * v + 0.000104 * v * v
  const fraction = 0.8 + 0.1894393 * Math.exp(-0.012778 * durMin) + 0.2989558 * Math.exp(-0.1932605 * durMin)
  const est = cost / fraction
  return Number.isFinite(est) && est > 0 ? Math.round(est * 10) / 10 : null
}

/**
 * Exponentially weighted average with an N-day span, alpha = 2 / (N + 1): the convention Android's dualEwma uses for
 * CTL (42) and ATL (7). The old helper treated N as a half-life, so a "42-day" CTL here averaged over about 60 days.
 */
export function ema(values: number[], spanDays: number): number[] {
  const alpha = 2 / (spanDays + 1)
  let acc = 0
  return values.map((v) => {
    acc = acc + alpha * (v - acc)
    return Math.round(acc * 10) / 10
  })
}
