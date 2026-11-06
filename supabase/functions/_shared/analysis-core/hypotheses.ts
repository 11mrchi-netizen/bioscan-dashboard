// Analysis Layer 3 / Phase 3: the versioned hypothesis catalogue. Each entry is a question fixed in advance, so nothing is
// found by searching. Changing an entry (or adding one) bumps CATALOGUE_VERSION; results are stored per engine version.
//
// Analysis review phase D (catalogue 2.0.0): every question carries its no-effect zone, set here before any data is
// seen: the smallest change that would matter (HRV 2%, resting heart rate 1 bpm, sleep 10 minutes, energy half a point,
// per unit of a continuous exposure). Labels name the pair without a verb, so a question never asserts a cause. New
// questions use the phase B capture: late meals, high-stress days and evening training.
//
// Routine intelligence R4 (catalogue 2.1.0, docs/routine-intelligence/01-research-and-plan.md section 5.1): ten questions
// on phone use, daylight outdoors, home and travel days and commutes, from the series in routineExposures.ts. Their
// outcomes stay within the evidence board's four groups; the plan's commute-and-evening-stress question is asked of
// that night's sleep instead, since stress is an exposure here, not an outcome.
//
// Morning scores (catalogue 2.2.0): the same evening exposures asked of next-morning RECOVERY and SLEEP, the app's own
// domain scores (score_history; 80 is a typical morning, the scale runs to 150). CHARGE is not an outcome: alcohol,
// late caffeine, felt stress and late screens are CHARGE inputs through FUEL and BALANCE, so a link would be partly
// built in. RECOVERY (HRV, resting HR, night stress, soreness, energy) and SLEEP hold none of these exposures.
//
// Left out on purpose: "supplement started -> outcome". A step exposure against a trending series is confounded by time, so
// supplements are tested as pre-registered N-of-1 experiments (Phase 5) with their own design, not here.
import type { Hypothesis } from './relationship.ts'

export const CATALOGUE_VERSION = '2.2.0'

const EXPLICIT_MODEL = 'A day counts as none only when the evening check-in says so (or, before the first check-in, when food was logged that day); other days are left out.'
const DEVICE_MODEL = 'Sessions come from the watch; days with a watch record and no session count as none.'
const RECOVERY_MODEL = 'Recovery sessions count as none only from the day you first logged one; sessions are never assumed from a schedule.'
const VALUE_MODEL = 'Only days with a recorded value are used; missing days are left out, never zero.'
const STRESS_MODEL = 'A high-stress day is a 4 or 5 in the evening check-in or the wellbeing log; days with neither are left out.'
const PHONE_MODEL = 'Measured from the phone\'s own usage events; days without a reading (Usage access off, no sleep record for the night) are left out.'
const MOBILITY_MODEL = 'From the places and trips the phone recorded; days less than 80% covered by them are left out, never counted as home.'
const COMMUTE_MODEL = 'Only commute days (a trip between a Home and a Work place) are compared with each other; other days are left out.'

const HRV = { target: 'hrv', targetTransform: 'ln' as const, targetUnit: 'ms', noEffect: 2, outcome: 'hrv' as const }
const RHR = { target: 'rhr', targetUnit: 'bpm', noEffect: 1, outcome: 'rhr' as const }
const SLEEP = { target: 'sleep_hours', targetScale: 60, targetUnit: 'min', noEffect: 10, outcome: 'sleep' as const }
const ENERGY = { target: 'energy', ordinal: true, targetUnit: 'points', noEffect: 0.5, outcome: 'energy' as const }
// Five points on the 0-150 score scale: about a quarter of the distance from a typical morning (80) to the top of normal.
const RECOVERY_SCORE = { target: 'recovery_score', targetUnit: 'points', noEffect: 5, outcome: 'recovery_score' as const }
const SLEEP_SCORE = { target: 'sleep_score', targetUnit: 'points', noEffect: 5, outcome: 'sleep_score' as const }
const SCORE_NOTE = ' Scores come from the app; mornings it could not score are left out.'

function morningScoreQuestions(): Hypothesis[] {
  const exposures: { cause: string; key: string; causeText: string; model: string; sign: -1 | 0 | 1; recovery: boolean; sleep: boolean; label: string }[] = [
    { cause: 'alcohol_any', key: 'alcohol', label: 'Alcohol', causeText: 'A day with alcohol', model: EXPLICIT_MODEL, sign: -1, recovery: true, sleep: true },
    { cause: 'caffeine_pm_any', key: 'caffeine_pm', label: 'Caffeine after 14:00', causeText: 'A day with caffeine after 14:00', model: EXPLICIT_MODEL, sign: -1, recovery: false, sleep: true },
    { cause: 'late_meal', key: 'late_meal', label: 'A meal after 21:00', causeText: 'A day with a meal after 21:00', model: EXPLICIT_MODEL, sign: -1, recovery: true, sleep: true },
    { cause: 'stress_high', key: 'stress', label: 'A high-stress day', causeText: 'A high-stress day', model: STRESS_MODEL, sign: -1, recovery: true, sleep: true },
    { cause: 'evening_training', key: 'evening_training', label: 'Training after 19:00', causeText: 'A day with training after 19:00', model: DEVICE_MODEL, sign: 0, recovery: true, sleep: true },
    { cause: 'training_day', key: 'training', label: 'A training day', causeText: 'A training day', model: DEVICE_MODEL, sign: 0, recovery: true, sleep: false },
  ]
  return exposures.flatMap((e) => [
    ...(e.recovery ? [{
      id: `${e.key}_recovery_score`, label: `${e.label} and next-morning RECOVERY`, cause: e.cause, lag: 1, exposure: 'binary' as const, ...RECOVERY_SCORE,
      causeText: e.causeText, targetText: 'next-morning RECOVERY score', expectedSign: e.sign, exposureModel: e.model + SCORE_NOTE,
    }] : []),
    ...(e.sleep ? [{
      id: `${e.key}_sleep_score`, label: `${e.label} and that night's SLEEP score`, cause: e.cause, lag: 1, exposure: 'binary' as const, ...SLEEP_SCORE,
      causeText: e.causeText, targetText: 'that night\'s SLEEP score', expectedSign: e.sign, exposureModel: e.model + SCORE_NOTE,
    }] : []),
  ])
}

export const HYPOTHESES: Hypothesis[] = [
  {
    id: 'alcohol_hrv', label: 'Alcohol and next-morning HRV', cause: 'alcohol_any', lag: 1, exposure: 'binary', ...HRV,
    causeText: 'A day with alcohol', targetText: 'next-morning HRV', expectedSign: -1, exposureModel: EXPLICIT_MODEL,
  },
  {
    id: 'alcohol_rhr', label: 'Alcohol and next-morning resting heart rate', cause: 'alcohol_any', lag: 1, exposure: 'binary', ...RHR,
    causeText: 'A day with alcohol', targetText: 'next-morning resting heart rate', expectedSign: 1, exposureModel: EXPLICIT_MODEL,
  },
  {
    id: 'alcohol_sleep', label: 'Alcohol and that night\'s sleep', cause: 'alcohol_any', lag: 1, exposure: 'binary', ...SLEEP,
    causeText: 'A day with alcohol', targetText: 'that night\'s sleep', expectedSign: -1, exposureModel: EXPLICIT_MODEL,
  },
  {
    id: 'caffeine_pm_sleep', label: 'Caffeine after 14:00 and that night\'s sleep', cause: 'caffeine_pm_any', lag: 1, exposure: 'binary', ...SLEEP,
    causeText: 'A day with caffeine after 14:00', targetText: 'that night\'s sleep', expectedSign: -1, exposureModel: EXPLICIT_MODEL,
  },
  {
    id: 'late_meal_sleep', label: 'A meal after 21:00 and that night\'s sleep', cause: 'late_meal', lag: 1, exposure: 'binary', ...SLEEP,
    causeText: 'A day with a meal after 21:00', targetText: 'that night\'s sleep', expectedSign: -1, exposureModel: EXPLICIT_MODEL,
  },
  {
    id: 'late_meal_hrv', label: 'A meal after 21:00 and next-morning HRV', cause: 'late_meal', lag: 1, exposure: 'binary', ...HRV,
    causeText: 'A day with a meal after 21:00', targetText: 'next-morning HRV', expectedSign: -1, exposureModel: EXPLICIT_MODEL,
  },
  {
    id: 'stress_sleep', label: 'A high-stress day and that night\'s sleep', cause: 'stress_high', lag: 1, exposure: 'binary', ...SLEEP,
    causeText: 'A high-stress day', targetText: 'that night\'s sleep', expectedSign: -1, exposureModel: STRESS_MODEL,
  },
  {
    id: 'evening_training_sleep', label: 'Training after 19:00 and that night\'s sleep', cause: 'evening_training', lag: 1, exposure: 'binary', ...SLEEP,
    causeText: 'A day with training after 19:00', targetText: 'that night\'s sleep', expectedSign: 0, exposureModel: DEVICE_MODEL,
  },
  {
    id: 'training_hrv', label: 'A training day and next-morning HRV', cause: 'training_day', lag: 1, exposure: 'binary', ...HRV,
    causeText: 'A training day', targetText: 'next-morning HRV', expectedSign: 0, exposureModel: DEVICE_MODEL,
  },
  {
    id: 'recovery_hrv', label: 'A recovery session and next-morning HRV', cause: 'recovery_any', lag: 1, exposure: 'binary', ...HRV,
    causeText: 'A day with a recovery session', targetText: 'next-morning HRV', expectedSign: 1, exposureModel: RECOVERY_MODEL,
  },
  {
    id: 'sleep_hrv', label: 'Sleep duration and same-morning HRV', cause: 'sleep_hours', lag: 0, exposure: 'continuous', ...HRV,
    causeText: 'Each extra hour of sleep', targetText: 'same-morning HRV', expectedSign: 1, exposureModel: VALUE_MODEL,
  },
  {
    id: 'steps_hrv', label: 'Steps and next-morning HRV', cause: 'steps', lag: 1, exposure: 'continuous', causeScale: 1000, ...HRV, noEffect: 1,
    causeText: 'Each 1,000 more steps', targetText: 'next-morning HRV', expectedSign: 0, exposureModel: VALUE_MODEL,
  },
  {
    id: 'sleep_energy', label: 'Sleep duration and same-day energy', cause: 'sleep_hours', target: 'energy', lag: 0, exposure: 'continuous', ordinal: true,
    targetUnit: 'points', noEffect: 0.5, outcome: 'energy',
    causeText: 'Each extra hour of sleep', targetText: 'same-day energy (0-10)', expectedSign: 1, exposureModel: VALUE_MODEL,
  },
  // Routine intelligence R4. Lag 1 throughout: a phone day's evening and night end in the next morning's sleep row.
  {
    id: 'screen_bed_sleep', label: 'Phone use in the hour before sleep and that night\'s sleep', cause: 'screen_bed_min', lag: 1, exposure: 'continuous', causeScale: 15, ...SLEEP,
    causeText: 'Each 15 more minutes on the phone in the hour before sleep', targetText: 'that night\'s sleep', expectedSign: -1, exposureModel: PHONE_MODEL,
  },
  {
    id: 'screen_bed_hrv', label: 'Phone use in the hour before sleep and next-morning HRV', cause: 'screen_bed_min', lag: 1, exposure: 'continuous', causeScale: 15, ...HRV,
    causeText: 'Each 15 more minutes on the phone in the hour before sleep', targetText: 'next-morning HRV', expectedSign: -1, exposureModel: PHONE_MODEL,
  },
  {
    id: 'night_phone_energy', label: 'The phone unlocked during the night and next-day energy', cause: 'night_phone', lag: 1, exposure: 'binary', ...ENERGY,
    causeText: 'A night with the phone unlocked after sleep onset', targetText: 'next-day energy (0-10)', expectedSign: -1, exposureModel: PHONE_MODEL,
  },
  {
    id: 'screen_pm_social_sleep', label: 'Social and video apps after 21:00 and that night\'s sleep', cause: 'screen_pm_social', lag: 1, exposure: 'continuous', causeScale: 30, ...SLEEP,
    causeText: 'Each 30 more minutes of social or video apps after 21:00', targetText: 'that night\'s sleep', expectedSign: -1, exposureModel: PHONE_MODEL,
  },
  {
    id: 'outdoor_daylight_sleep', label: 'Time outdoors in daylight and that night\'s sleep', cause: 'outdoor_daylight_min', lag: 1, exposure: 'continuous', causeScale: 30, ...SLEEP,
    causeText: 'Each 30 more minutes outdoors in daylight (estimated)', targetText: 'that night\'s sleep', expectedSign: 1, exposureModel: MOBILITY_MODEL,
  },
  {
    id: 'home_day_energy', label: 'A day at home and next-day energy', cause: 'home_day', lag: 1, exposure: 'binary', ...ENERGY,
    causeText: 'A day spent mostly at home', targetText: 'next-day energy (0-10)', expectedSign: 0, exposureModel: MOBILITY_MODEL,
  },
  {
    id: 'travel_day_hrv', label: 'A travel day and next-morning HRV', cause: 'travel_day', lag: 1, exposure: 'binary', ...HRV,
    causeText: 'A day more than 50 km from home', targetText: 'next-morning HRV', expectedSign: -1, exposureModel: MOBILITY_MODEL,
  },
  {
    id: 'travel_day_rhr', label: 'A travel day and next-morning resting heart rate', cause: 'travel_day', lag: 1, exposure: 'binary', ...RHR,
    causeText: 'A day more than 50 km from home', targetText: 'next-morning resting heart rate', expectedSign: 1, exposureModel: MOBILITY_MODEL,
  },
  {
    id: 'active_commute_hrv', label: 'An active commute and next-morning HRV', cause: 'active_commute', lag: 1, exposure: 'binary', ...HRV,
    causeText: 'A commute day with 10 or more minutes on foot or on a bicycle', targetText: 'next-morning HRV', expectedSign: 0, exposureModel: COMMUTE_MODEL,
  },
  {
    id: 'commute_long_sleep', label: 'A longer commute than usual and that night\'s sleep', cause: 'commute_long', lag: 1, exposure: 'binary', ...SLEEP,
    causeText: 'A commute at least a quarter longer than the usual of the last four weeks', targetText: 'that night\'s sleep', expectedSign: 0, exposureModel: COMMUTE_MODEL,
  },
  ...morningScoreQuestions(),
]

// Analysis review phase E: questions a person registers from a hint (what came before off mornings). Each is tested only
// on days after its registration date, with the default zone of its outcome unless one was given.
export const REGISTRABLE_EXPOSURES: Record<string, { label: string; causeText: string; exposureModel: string }> = {
  alcohol_any: { label: 'Alcohol', causeText: 'A day with alcohol', exposureModel: EXPLICIT_MODEL },
  caffeine_pm_any: { label: 'Caffeine after 14:00', causeText: 'A day with caffeine after 14:00', exposureModel: EXPLICIT_MODEL },
  late_meal: { label: 'A meal after 21:00', causeText: 'A day with a meal after 21:00', exposureModel: EXPLICIT_MODEL },
  stress_high: { label: 'A high-stress day', causeText: 'A high-stress day', exposureModel: STRESS_MODEL },
  evening_training: { label: 'Training after 19:00', causeText: 'A day with training after 19:00', exposureModel: DEVICE_MODEL },
  training_day: { label: 'A training day', causeText: 'A training day', exposureModel: DEVICE_MODEL },
}

const REGISTRABLE_OUTCOMES: Record<string, { spec: typeof HRV | typeof RHR | typeof SLEEP; targetText: string }> = {
  hrv: { spec: HRV, targetText: 'next-morning HRV' },
  rhr: { spec: RHR, targetText: 'next-morning resting heart rate' },
  sleep_hours: { spec: SLEEP, targetText: 'that night\'s sleep' },
}

export interface RegisteredQuestionRow {
  id: number
  exposure: string
  outcome: string
  lag: number
  no_effect: number
  registered_on: string
  source?: string | null
  withdrawn_at?: string | null
}

export function registeredHypothesis(r: RegisteredQuestionRow): Hypothesis | null {
  const ex = REGISTRABLE_EXPOSURES[r.exposure]
  const out = REGISTRABLE_OUTCOMES[r.outcome]
  if (!ex || !out) return null
  return {
    id: `reg_${r.id}`, label: `${ex.label} and ${out.targetText}`, cause: r.exposure, lag: r.lag, exposure: 'binary', ...out.spec,
    noEffect: r.no_effect > 0 ? r.no_effect : out.spec.noEffect,
    causeText: ex.causeText, targetText: out.targetText, expectedSign: 0, exposureModel: ex.exposureModel,
  }
}
