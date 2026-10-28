// Endurance Fueling & Personal Experiments (06.3.4): per-activity fueling summary and timeline. Erasable
// TypeScript, no I/O. A single, stable contract a mobile screen and the Analysis Console both read, rather than
// recomputing the same totals independently in multiple places (DAV-383's own acceptance criterion).
//
// Only 'consumed'/'partial' events count toward actual totals -- 'planned' intake is summarised separately
// (DAV-382: "planned items never count as consumed") and 'skipped' events are counted but excluded from both
// totals, so a timeline can still show what was skipped and when. De-duplication is the caller's/database's job
// (fueling_intake_log's own id is the identity); this function is a pure fold over whatever event list it's given.
export type FuelingEventStatus = 'planned' | 'consumed' | 'skipped' | 'partial'

export interface FuelingEvent {
  id: number
  status: FuelingEventStatus
  elapsedSeconds: number
  carbsG: number | null
  fluidMl: number | null
  sodiumMg: number | null
  caffeineMg: number | null
}

export interface NutrientTotals {
  carbsG: number | null
  fluidMl: number | null
  sodiumMg: number | null
  caffeineMg: number | null
}

export interface NutrientKnownCounts {
  carbsG: number
  fluidMl: number
  sodiumMg: number
  caffeineMg: number
}

export interface IntakeGap {
  startSeconds: number
  endSeconds: number
}

export interface CaffeineDose {
  elapsedSeconds: number
  caffeineMg: number
}

export interface FuelingSummary {
  durationKnown: boolean
  durationSeconds: number | null
  eventCount: number
  plannedCount: number
  skippedCount: number
  actualTotals: NutrientTotals
  /** Known-value count per nutrient out of `eventCount` (consumed+partial only) -- completeness, not a statistical confidence. */
  actualKnownCounts: NutrientKnownCounts
  plannedTotals: NutrientTotals
  /** Consumed/partial intake before the start (a pre-workout, a gel at the line): in `actualTotals`, but not in the per-hour rates. */
  preStartTotals: NutrientTotals
  carbsPerHour: number | null
  fluidPerHour: number | null
  sodiumPerHour: number | null
  /** Ordered by elapsed time; only events with a known, nonzero caffeine dose. */
  caffeineDoses: CaffeineDose[]
  gaps: IntakeGap[]
}

const sumKnown = (values: (number | null)[]): number | null => {
  const known = values.filter((v): v is number => v !== null)
  return known.length > 0 ? known.reduce((a, b) => a + b, 0) : null
}

const countKnown = (values: (number | null)[]): number => values.filter((v) => v !== null).length

function totalsOf(events: FuelingEvent[]): NutrientTotals {
  return {
    carbsG: sumKnown(events.map((e) => e.carbsG)),
    fluidMl: sumKnown(events.map((e) => e.fluidMl)),
    sodiumMg: sumKnown(events.map((e) => e.sodiumMg)),
    caffeineMg: sumKnown(events.map((e) => e.caffeineMg)),
  }
}

function knownCountsOf(events: FuelingEvent[]): NutrientKnownCounts {
  return {
    carbsG: countKnown(events.map((e) => e.carbsG)),
    fluidMl: countKnown(events.map((e) => e.fluidMl)),
    sodiumMg: countKnown(events.map((e) => e.sodiumMg)),
    caffeineMg: countKnown(events.map((e) => e.caffeineMg)),
  }
}

// null/zero/unknown duration must never produce a misleading rate -- a per-hour figure with no real "hour" behind it.
const perHour = (total: number | null, durationSeconds: number | null): number | null =>
  total === null || durationSeconds === null || durationSeconds <= 0 ? null : total * (3600 / durationSeconds)

export const DEFAULT_GAP_BUCKET_SECONDS = 1800

/**
 * Elapsed-time buckets (default 30 min, configurable) with no consumed/partial event in them, within
 * [0, durationSeconds]. A descriptive structural report, not a judgment -- the caller decides what, if anything,
 * a gap means for a particular activity. The final bucket's upper edge is inclusive of `durationSeconds` itself,
 * so an event logged exactly at the finish line still closes that bucket's gap.
 */
function gapsOf(events: FuelingEvent[], durationSeconds: number | null, bucketSeconds: number): IntakeGap[] {
  if (durationSeconds === null || durationSeconds <= 0) return []
  const actual = events.filter((e) => e.status === 'consumed' || e.status === 'partial')
  const gaps: IntakeGap[] = []
  for (let start = 0; start < durationSeconds; start += bucketSeconds) {
    const end = Math.min(start + bucketSeconds, durationSeconds)
    const inBucket = end === durationSeconds
      ? (s: number) => s >= start && s <= end
      : (s: number) => s >= start && s < end
    if (!actual.some((e) => inBucket(e.elapsedSeconds))) gaps.push({ startSeconds: start, endSeconds: end })
  }
  return gaps
}

/**
 * A stable per-activity fueling summary from its full event list and known duration (null when the activity's own
 * duration is unknown or still in progress). Reconciles exactly to the underlying event list: every total is a
 * fold over the same events a caller could list and add up by hand.
 */
export function summariseFueling(events: FuelingEvent[], durationSeconds: number | null, gapBucketSeconds = DEFAULT_GAP_BUCKET_SECONDS): FuelingSummary {
  const actual = events.filter((e) => e.status === 'consumed' || e.status === 'partial')
  const planned = events.filter((e) => e.status === 'planned')
  const actualTotals = totalsOf(actual)
  // Rates are per hour of the activity itself, so intake before the start (negative elapsed time) stays out of them.
  const inRunTotals = totalsOf(actual.filter((e) => e.elapsedSeconds >= 0))
  return {
    durationKnown: durationSeconds !== null && durationSeconds > 0,
    durationSeconds,
    eventCount: actual.length,
    plannedCount: planned.length,
    skippedCount: events.filter((e) => e.status === 'skipped').length,
    actualTotals,
    actualKnownCounts: knownCountsOf(actual),
    plannedTotals: totalsOf(planned),
    preStartTotals: totalsOf(actual.filter((e) => e.elapsedSeconds < 0)),
    carbsPerHour: perHour(inRunTotals.carbsG, durationSeconds),
    fluidPerHour: perHour(inRunTotals.fluidMl, durationSeconds),
    sodiumPerHour: perHour(inRunTotals.sodiumMg, durationSeconds),
    caffeineDoses: actual
      .filter((e): e is FuelingEvent & { caffeineMg: number } => e.caffeineMg !== null && e.caffeineMg > 0)
      .map((e) => ({ elapsedSeconds: e.elapsedSeconds, caffeineMg: e.caffeineMg }))
      .sort((a, b) => a.elapsedSeconds - b.elapsedSeconds),
    gaps: gapsOf(events, durationSeconds, gapBucketSeconds),
  }
}
