// Analysis Layer 3 / Phase 3: the versioned hypothesis catalogue. Each entry is a question fixed in advance, so the family
// size for the multiple-comparison correction is known and nothing is found by searching. Changing an entry (or adding one)
// bumps CATALOGUE_VERSION; results are stored per engine version.
//
// Left out on purpose: "supplement started -> outcome". A step exposure against a trending series is confounded by time, so
// supplements are tested as pre-registered N-of-1 experiments (Phase 5) with their own design, not here.
import type { Hypothesis } from './relationship.ts'

export const CATALOGUE_VERSION = '1.0.0'

const ALCOHOL_MODEL = 'Days without a drink count as zero only on days you logged food; days with no food log are left out.'
const DEVICE_MODEL = 'Sessions come from the watch; days with a watch record and no session count as zero.'
const RECOVERY_MODEL = 'Recovery sessions count as zero only from the day you first logged one; sessions are never assumed from a schedule.'
const CAFFEINE_MODEL = 'A day counts as zero only when you logged food that day; days with no food log are left out.'
const VALUE_MODEL = 'Only days with a recorded value are used; missing days are left out, never zero.'

export const HYPOTHESES: Hypothesis[] = [
  {
    id: 'alcohol_hrv', label: 'Does alcohol lower next-day HRV?', cause: 'alcohol_any', target: 'hrv', lag: 1, exposure: 'binary', targetTransform: 'ln',
    causeText: 'A day with alcohol', targetText: 'next-day HRV', targetUnit: 'ms', expectedSign: -1, exposureModel: ALCOHOL_MODEL,
  },
  {
    id: 'alcohol_rhr', label: 'Does alcohol raise next-day resting heart rate?', cause: 'alcohol_any', target: 'rhr', lag: 1, exposure: 'binary',
    causeText: 'A day with alcohol', targetText: 'next-day resting heart rate', targetUnit: 'bpm', expectedSign: 1, exposureModel: ALCOHOL_MODEL,
  },
  {
    id: 'alcohol_sleep', label: 'Does alcohol shorten the following night?', cause: 'alcohol_any', target: 'sleep_hours', lag: 1, exposure: 'binary',
    causeText: 'A day with alcohol', targetText: 'the following night\'s sleep', targetUnit: 'h', expectedSign: -1, exposureModel: ALCOHOL_MODEL,
  },
  {
    id: 'sleep_hrv', label: 'Does more sleep raise HRV the same morning?', cause: 'sleep_hours', target: 'hrv', lag: 0, exposure: 'continuous', targetTransform: 'ln',
    causeText: 'One extra hour of sleep', targetText: 'same-morning HRV', targetUnit: 'ms', expectedSign: 1, exposureModel: VALUE_MODEL,
  },
  {
    id: 'training_hrv', label: 'Does a training day change next-day HRV?', cause: 'training_day', target: 'hrv', lag: 1, exposure: 'binary', targetTransform: 'ln',
    causeText: 'A training day', targetText: 'next-day HRV', targetUnit: 'ms', expectedSign: 0, exposureModel: DEVICE_MODEL,
  },
  {
    id: 'recovery_hrv', label: 'Do recovery sessions raise next-day HRV?', cause: 'recovery_any', target: 'hrv', lag: 1, exposure: 'binary', targetTransform: 'ln',
    causeText: 'A day with a recovery session', targetText: 'next-day HRV', targetUnit: 'ms', expectedSign: 1, exposureModel: RECOVERY_MODEL,
  },
  {
    id: 'caffeine_pm_sleep', label: 'Does afternoon caffeine shorten the following night?', cause: 'caffeine_pm_any', target: 'sleep_hours', lag: 1, exposure: 'binary',
    causeText: 'A day with caffeine after 14:00', targetText: 'the following night\'s sleep', targetUnit: 'h', expectedSign: -1, exposureModel: CAFFEINE_MODEL,
  },
  {
    id: 'steps_hrv', label: 'Do more steps change next-day HRV?', cause: 'steps', target: 'hrv', lag: 1, exposure: 'continuous', targetTransform: 'ln', causeScale: 1000,
    causeText: '1,000 more steps', targetText: 'next-day HRV', targetUnit: 'ms', expectedSign: 0, exposureModel: VALUE_MODEL,
  },
  {
    id: 'sleep_energy', label: 'Does more sleep lift same-day energy?', cause: 'sleep_hours', target: 'energy', lag: 0, exposure: 'continuous', ordinal: true,
    causeText: 'One extra hour of sleep', targetText: 'same-day energy (0-10)', targetUnit: 'points', expectedSign: 1, exposureModel: VALUE_MODEL,
  },
]
