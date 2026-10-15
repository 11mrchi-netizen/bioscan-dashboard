// Analysis Layer 3 / Phase 3: the versioned hypothesis catalogue. Each entry is a question fixed in advance, so nothing is
// found by searching. Changing an entry (or adding one) bumps CATALOGUE_VERSION; results are stored per engine version.
//
// Analysis review phase D (catalogue 2.0.0): every question carries its no-effect zone, set here before any data is
// seen: the smallest change that would matter (HRV 2%, resting heart rate 1 bpm, sleep 10 minutes, energy half a point,
// per unit of a continuous exposure). Labels name the pair without a verb, so a question never asserts a cause. New
// questions use the phase B capture: late meals, high-stress days and evening training.
//
// Left out on purpose: "supplement started -> outcome". A step exposure against a trending series is confounded by time, so
// supplements are tested as pre-registered N-of-1 experiments (Phase 5) with their own design, not here.
import type { Hypothesis } from './relationship.ts'

export const CATALOGUE_VERSION = '2.0.0'

const EXPLICIT_MODEL = 'A day counts as none only when the evening check-in says so (or, before the first check-in, when food was logged that day); other days are left out.'
const DEVICE_MODEL = 'Sessions come from the watch; days with a watch record and no session count as none.'
const RECOVERY_MODEL = 'Recovery sessions count as none only from the day you first logged one; sessions are never assumed from a schedule.'
const VALUE_MODEL = 'Only days with a recorded value are used; missing days are left out, never zero.'
const STRESS_MODEL = 'A high-stress day is a 4 or 5 in the evening check-in or the wellbeing log; days with neither are left out.'

const HRV = { target: 'hrv', targetTransform: 'ln' as const, targetUnit: 'ms', noEffect: 2, outcome: 'hrv' as const }
const RHR = { target: 'rhr', targetUnit: 'bpm', noEffect: 1, outcome: 'rhr' as const }
const SLEEP = { target: 'sleep_hours', targetScale: 60, targetUnit: 'min', noEffect: 10, outcome: 'sleep' as const }

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
]
