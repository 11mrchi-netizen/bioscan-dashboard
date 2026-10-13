// Endurance Fueling & Personal Experiments (06.3.6): structured N-of-1 fueling experiments. Erasable TypeScript,
// no I/O. Reuses the existing Intervention & Protocol Intelligence lifecycle as-is -- an experiment is still a
// `public.interventions` row (`domain='nutrition'`, `subject_type='fueling_recipe'`) plus a `public.experiments`
// row or an `analysis_results` kind `intervention_response` via `interventionResponse.ts` (08.6's "existing
// personal-response analysis framework", which DAV-385 names explicitly). No new experiment lifecycle, no new
// table: the only genuinely new work is the fueling-specific *eligibility* layer below, which this module
// provides and nothing else.
//
// Condition such as "changing carbohydrate intake rate while holding recipe, caffeine and sodium stable" needs an
// activity to actually qualify (right type, long enough) and to not have *deviated* from what the protocol says
// was held constant -- a run where caffeine was accidentally doubled is not a clean baseline/intervention data
// point, even if it happened within the declared phase window. `evaluateEligibility` names exactly why an
// activity was excluded (DAV-385: "track activity eligibility... deviations").
import { summariseFueling } from './fuelingSummary.ts'
import type { FuelingSummary } from './fuelingSummary.ts'
import { proposeProtocolAction } from './protocolRules.ts'
import type { ProtocolAction, ProtocolRuleInputs } from './protocolRules.ts'

export interface HeldStableTarget {
  caffeineMgPerHour: number | null
  sodiumMgPerHour: number | null
  /** Fractional tolerance around each target, e.g. 0.2 = ±20%. */
  toleranceFraction: number
}

export interface EligibilityCriteria {
  activityTypes: string[]
  minDurationSeconds: number
  heldStable: HeldStableTarget
}

export interface ActivityFuelingRecord {
  date: string
  activityType: string
  durationSeconds: number
  summary: FuelingSummary
}

export type EligibilityReason = 'eligible' | 'wrong_type' | 'too_short' | 'unknown_carb_rate' | 'caffeine_deviation' | 'sodium_deviation'

export interface EligibilityResult {
  date: string
  eligible: boolean
  reason: EligibilityReason
  carbsPerHour: number | null
}

function withinTolerance(actual: number | null, target: number | null, toleranceFraction: number): boolean {
  if (target === null) return true // not held constant by this protocol
  if (actual === null) return false // can't confirm it was held stable -- treated as a deviation, not a pass
  return Math.abs(actual - target) <= Math.abs(target) * toleranceFraction
}

/** Whether one activity qualifies as a clean baseline/intervention data point, and why not when it doesn't. */
export function evaluateEligibility(activity: ActivityFuelingRecord, criteria: EligibilityCriteria): EligibilityResult {
  const carbsPerHour = activity.summary.carbsPerHour
  const base = { date: activity.date, carbsPerHour }
  if (!criteria.activityTypes.includes(activity.activityType)) return { ...base, eligible: false, reason: 'wrong_type' }
  if (activity.durationSeconds < criteria.minDurationSeconds) return { ...base, eligible: false, reason: 'too_short' }
  if (carbsPerHour === null) return { ...base, eligible: false, reason: 'unknown_carb_rate' }
  const caffeinePerHour = activity.summary.actualTotals.caffeineMg === null || activity.durationSeconds <= 0
    ? null
    : activity.summary.actualTotals.caffeineMg * (3600 / activity.durationSeconds)
  if (!withinTolerance(caffeinePerHour, criteria.heldStable.caffeineMgPerHour, criteria.heldStable.toleranceFraction)) {
    return { ...base, eligible: false, reason: 'caffeine_deviation' }
  }
  const sodiumPerHour = activity.summary.sodiumPerHour
  if (!withinTolerance(sodiumPerHour, criteria.heldStable.sodiumMgPerHour, criteria.heldStable.toleranceFraction)) {
    return { ...base, eligible: false, reason: 'sodium_deviation' }
  }
  return { ...base, eligible: true, reason: 'eligible' }
}

export interface DailyCarbRateSeries {
  dates: string[]
  values: (number | null)[]
  eligibility: EligibilityResult[]
}

/**
 * A derived daily "carb rate" series -- one value per calendar day in `dates`, null on a day with no eligible
 * activity, averaged when more than one eligible activity falls on the same day -- built from eligible,
 * non-deviated activities only. This series is what gets fed to `interventionResponse.ts`'s
 * `analyseInterventionResponse(['fueling_carb_rate'], ...)`: the existing, generic baseline-vs-active engine,
 * reused unchanged.
 */
export function buildDailyCarbRateSeries(activities: ActivityFuelingRecord[], criteria: EligibilityCriteria, dates: string[]): DailyCarbRateSeries {
  const eligibility = activities.map((a) => evaluateEligibility(a, criteria))
  const byDate = new Map<string, number[]>()
  activities.forEach((a, i) => {
    const e = eligibility[i]
    if (e.eligible && e.carbsPerHour !== null) {
      const list = byDate.get(a.date) ?? []
      list.push(e.carbsPerHour)
      byDate.set(a.date, list)
    }
  })
  const values = dates.map((d) => {
    const vals = byDate.get(d)
    return vals && vals.length > 0 ? vals.reduce((a, b) => a + b, 0) / vals.length : null
  })
  return { dates, values, eligibility }
}

export const MIN_ELIGIBLE_ACTIVITIES_PER_PHASE = 5

/**
 * DAV-385's acceptance criterion made literal: "an experiment cannot be marked interpretable without... sufficient
 * recorded data for the configured rule." Day-count thresholds (`interventionResponse.ts`'s `MIN_WINDOW_DAYS`)
 * don't capture this on their own -- an endurance athlete doesn't run every day, so a 14-day window can contain
 * far fewer than 14 *eligible activities*. This checks activity count directly, phase by phase.
 */
export function isInterpretable(
  eligibility: EligibilityResult[],
  baselineDates: Set<string>,
  interventionDates: Set<string>,
  minEligiblePerPhase = MIN_ELIGIBLE_ACTIVITIES_PER_PHASE,
): { interpretable: boolean; baselineEligibleCount: number; interventionEligibleCount: number } {
  const eligibleDates = eligibility.filter((e) => e.eligible).map((e) => e.date)
  const baselineEligibleCount = eligibleDates.filter((d) => baselineDates.has(d)).length
  const interventionEligibleCount = eligibleDates.filter((d) => interventionDates.has(d)).length
  return {
    interpretable: baselineEligibleCount >= minEligiblePerPhase && interventionEligibleCount >= minEligiblePerPhase,
    baselineEligibleCount,
    interventionEligibleCount,
  }
}

export type FuelingVerdict = 'keep' | 'modify' | 'stop' | 'inconclusive'

const VERDICT_LABEL: Record<ProtocolAction, FuelingVerdict> = {
  maintain: 'keep',
  increase: 'modify',
  decrease: 'modify',
  pause: 'modify',
  terminate: 'stop',
  extend_evaluation: 'inconclusive',
  reassess: 'inconclusive',
}

/**
 * DAV-385's required vocabulary ("keep / modify / stop / inconclusive... not an automatic medical directive") is
 * a presentation label over 08.7's existing `proposeProtocolAction` -- never a second decision engine. The
 * person still reviews the full `ProtocolActionProposal` (action, rationale, source inputs); this label is only
 * how a fueling-experiment review screen headlines it.
 */
export function fuelingVerdict(inputs: ProtocolRuleInputs): { verdict: FuelingVerdict; action: ReturnType<typeof proposeProtocolAction> } {
  const action = proposeProtocolAction(inputs)
  return { verdict: VERDICT_LABEL[action.action], action }
}
