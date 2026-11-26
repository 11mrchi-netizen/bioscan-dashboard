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
// Meal timing and the gut (catalogue 2.3.0, docs/nutrition/timing-and-gut.md): continuous meal timing (hours from the
// last meal to sleep, kcal in the 3 h before sleep) instead of only the 21:00 flag, which was on most days; daily carbs
// and protein; and a new outcome group, GUT, the daily digestion score (mealExposures.ts), against food, stress, sleep,
// training and travel.
//
// Left out on purpose: "supplement started -> outcome". A step exposure against a trending series is confounded by time, so
// supplements are tested as pre-registered N-of-1 experiments (Phase 5) with their own design, not here.
import type { Hypothesis } from './relationship.ts'

export const CATALOGUE_VERSION = '2.5.0'

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
// Five points on the 0-100 digestion score: a type 5 instead of a 4, or one gut symptom ticked, moves it 5 to 10.
const GUT = { target: 'gut_day_score', targetUnit: 'points', noEffect: 5, outcome: 'gut' as const }
const WATCH_STRESS_MODEL = 'The watch\'s own all-day stress (Zepp, 1-100) averaged over the local day, from days with at least 12 readings; days without are left out, never zero.'
const HR_DIP = { target: 'hr_dip', targetUnit: 'points', noEffect: 3, outcome: 'sleep' as const }
const DEEP_SHARE = { target: 'deep_share', targetUnit: 'points', noEffect: 2, outcome: 'sleep' as const }
const CAFFEINE_BOARD_MODEL = 'Caffeine from the logged food and drink, decayed with a 5-hour half-life to that night\'s bedtime; a day with meals and no caffeine counts as zero, a day without meals or a bedtime is left out.'
const MEAL_MODEL = 'Only days with meals logged are used (a day before 04:00 belongs to the evening before); timing also needs that night\'s bedtime from the watch. Missing days are left out, never zero.'
const GUT_NOTE = ' The digestion score needs a stool logged or the evening gut questions answered that day; other days are left out.'
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

function mealAndGutQuestions(): Hypothesis[] {
  return [
    {
      id: 'meal_gap_sleep', label: 'Hours from the last meal to sleep and that night\'s sleep', cause: 'meal_gap_h', lag: 1, exposure: 'continuous', ...SLEEP,
      causeText: 'Each extra hour between the last meal and falling asleep', targetText: 'that night\'s sleep', expectedSign: 0, exposureModel: MEAL_MODEL,
    },
    {
      id: 'meal_gap_hrv', label: 'Hours from the last meal to sleep and next-morning HRV', cause: 'meal_gap_h', lag: 1, exposure: 'continuous', ...HRV,
      causeText: 'Each extra hour between the last meal and falling asleep', targetText: 'next-morning HRV', expectedSign: 1, exposureModel: MEAL_MODEL,
    },
    {
      id: 'meal_gap_recovery_score', label: 'Hours from the last meal to sleep and next-morning RECOVERY', cause: 'meal_gap_h', lag: 1, exposure: 'continuous', ...RECOVERY_SCORE,
      causeText: 'Each extra hour between the last meal and falling asleep', targetText: 'next-morning RECOVERY score', expectedSign: 1, exposureModel: MEAL_MODEL + SCORE_NOTE,
    },
    {
      id: 'kcal_before_bed_sleep_score', label: 'Food in the 3 hours before sleep and that night\'s SLEEP score', cause: 'kcal_before_bed', lag: 1, exposure: 'continuous', causeScale: 250, ...SLEEP_SCORE,
      causeText: 'Each 250 kcal eaten in the 3 hours before falling asleep', targetText: 'that night\'s SLEEP score', expectedSign: -1, exposureModel: MEAL_MODEL + SCORE_NOTE,
    },
    {
      id: 'kcal_before_bed_hrv', label: 'Food in the 3 hours before sleep and next-morning HRV', cause: 'kcal_before_bed', lag: 1, exposure: 'continuous', causeScale: 250, ...HRV,
      causeText: 'Each 250 kcal eaten in the 3 hours before falling asleep', targetText: 'next-morning HRV', expectedSign: -1, exposureModel: MEAL_MODEL,
    },
    {
      id: 'carbs_day_sleep', label: 'Carbohydrate eaten and that night\'s sleep', cause: 'carbs_day', lag: 1, exposure: 'continuous', causeScale: 50, ...SLEEP,
      causeText: 'Each 50 g more carbohydrate in the day', targetText: 'that night\'s sleep', expectedSign: 0, exposureModel: MEAL_MODEL,
    },
    {
      id: 'protein_day_recovery_score', label: 'Protein eaten and next-morning RECOVERY', cause: 'protein_day', lag: 1, exposure: 'continuous', causeScale: 30, ...RECOVERY_SCORE,
      causeText: 'Each 30 g more protein in the day', targetText: 'next-morning RECOVERY score', expectedSign: 0, exposureModel: MEAL_MODEL + SCORE_NOTE,
    },
    // GUT: the next day's digestion for food (transit takes about a day), the same day's for stress, sleep, training and travel.
    {
      id: 'fiber_gut', label: 'Fibre eaten and the next day\'s digestion', cause: 'fiber_day', lag: 1, exposure: 'continuous', causeScale: 10, ...GUT,
      causeText: 'Each 10 g more fibre in the day', targetText: 'the next day\'s digestion score', expectedSign: 1,
      exposureModel: MEAL_MODEL + ' Fibre counts only on days where most of the energy logged carries a fibre value.' + GUT_NOTE,
    },
    {
      id: 'fat_gut', label: 'Fat eaten and the next day\'s digestion', cause: 'fat_day', lag: 1, exposure: 'continuous', causeScale: 30, ...GUT,
      causeText: 'Each 30 g more fat in the day', targetText: 'the next day\'s digestion score', expectedSign: -1, exposureModel: MEAL_MODEL + GUT_NOTE,
    },
    {
      id: 'kcal_before_bed_gut', label: 'Food in the 3 hours before sleep and the next day\'s digestion', cause: 'kcal_before_bed', lag: 1, exposure: 'continuous', causeScale: 250, ...GUT,
      causeText: 'Each 250 kcal eaten in the 3 hours before falling asleep', targetText: 'the next day\'s digestion score', expectedSign: -1, exposureModel: MEAL_MODEL + GUT_NOTE,
    },
    {
      id: 'alcohol_gut', label: 'Alcohol and the next day\'s digestion', cause: 'alcohol_any', lag: 1, exposure: 'binary', ...GUT,
      causeText: 'A day with alcohol', targetText: 'the next day\'s digestion score', expectedSign: -1, exposureModel: EXPLICIT_MODEL + GUT_NOTE,
    },
    {
      id: 'stress_gut', label: 'A high-stress day and that day\'s digestion', cause: 'stress_high', lag: 0, exposure: 'binary', ...GUT,
      causeText: 'A high-stress day', targetText: 'that day\'s digestion score', expectedSign: -1, exposureModel: STRESS_MODEL + GUT_NOTE,
    },
    {
      id: 'sleep_gut', label: 'Sleep duration and that day\'s digestion', cause: 'sleep_hours', lag: 0, exposure: 'continuous', ...GUT,
      causeText: 'Each extra hour of sleep the night before', targetText: 'that day\'s digestion score', expectedSign: 1, exposureModel: VALUE_MODEL + GUT_NOTE,
    },
    {
      id: 'training_gut', label: 'Training minutes and that day\'s digestion', cause: 'training_min', lag: 0, exposure: 'continuous', causeScale: 30, ...GUT,
      causeText: 'Each 30 more minutes of training', targetText: 'that day\'s digestion score', expectedSign: 0, exposureModel: DEVICE_MODEL + GUT_NOTE,
    },
    {
      id: 'travel_gut', label: 'A travel day and that day\'s digestion', cause: 'travel_day', lag: 0, exposure: 'binary', ...GUT,
      causeText: 'A travel day', targetText: 'that day\'s digestion score', expectedSign: -1, exposureModel: MOBILITY_MODEL + GUT_NOTE,
    },
    // Catalogue 2.4.0 (a fuller gut picture): caffeine and long sessions.
    {
      id: 'caffeine_gut', label: 'Caffeine and that day\'s digestion', cause: 'caffeine_any', lag: 0, exposure: 'binary', ...GUT,
      causeText: 'A day with caffeine', targetText: 'that day\'s digestion score', expectedSign: 0,
      exposureModel: EXPLICIT_MODEL + ' Caffeine is a meal item with caffeine or a meal whose foods are tagged caffeine (coffee, tea, cola, energy drinks).' + GUT_NOTE,
    },
    {
      id: 'long_session_gut', label: 'A long session and that day\'s digestion', cause: 'long_session', lag: 0, exposure: 'binary', ...GUT,
      causeText: 'A day with a session of 90 minutes or more', targetText: 'that day\'s digestion score', expectedSign: -1, exposureModel: DEVICE_MODEL + GUT_NOTE,
    },
    {
      id: 'long_session_gut_next', label: 'A long session and the next day\'s digestion', cause: 'long_session', lag: 1, exposure: 'binary', ...GUT,
      causeText: 'A day with a session of 90 minutes or more', targetText: 'the next day\'s digestion score', expectedSign: -1, exposureModel: DEVICE_MODEL + GUT_NOTE,
    },
  ]
}

// Catalogue 2.5.0: the watch's all-day stress, objective and recorded every day since Feb 2025, against what it should cost.
// Per 10 points of the day's mean. No sign is assumed for energy or the digestion score beyond "lower when stressed".
function watchStressQuestions(): Hypothesis[] {
  const q = (key: string, label: string, lag: number, spec: object, targetText: string, sign: -1 | 0 | 1): Hypothesis => ({
    id: `stress_watch_${key}`, label: `Watch stress and ${label}`, cause: 'stress_watch', lag, exposure: 'continuous', causeScale: 10, ...spec,
    causeText: 'Each 10 points higher day mean of the watch\'s stress', targetText, expectedSign: sign, exposureModel: WATCH_STRESS_MODEL,
  } as Hypothesis)
  return [
    q('sleep', 'that night\'s sleep', 1, SLEEP, 'that night\'s sleep', -1),
    q('sleep_score', 'that night\'s SLEEP score', 1, SLEEP_SCORE, 'that night\'s SLEEP score', -1),
    q('hrv', 'next-morning HRV', 1, HRV, 'next-morning HRV', -1),
    q('rhr', 'next-morning resting heart rate', 1, RHR, 'next-morning resting heart rate', 1),
    q('recovery_score', 'next-morning RECOVERY', 1, RECOVERY_SCORE, 'next-morning RECOVERY score', -1),
    q('energy', 'next-day energy', 1, ENERGY, 'next-day energy', -1),
    q('gut', 'that day\'s digestion', 0, GUT, 'that day\'s digestion score', -1),
  ]
}

// Catalogue 2.5.0: a caffeine dose (mg still on board at bedtime) and the sleep outcomes the engine did not read: the night's
// heart-rate dip and the deep-sleep share (both filed under SLEEP). Training and the watch's stress against the same two.
function sleepQualityQuestions(): Hypothesis[] {
  const board = (key: string, label: string, spec: object, targetText: string): Hypothesis => ({
    id: `caffeine_board_${key}`, label: `Caffeine on board at bedtime and ${label}`, cause: 'caffeine_board_bed', lag: 1, exposure: 'continuous', causeScale: 50, ...spec,
    causeText: 'Each 50 mg more caffeine still on board at bedtime', targetText, expectedSign: -1, exposureModel: CAFFEINE_BOARD_MODEL,
  } as Hypothesis)
  return [
    board('sleep', 'that night\'s sleep', SLEEP, 'that night\'s sleep'),
    board('sleep_score', 'that night\'s SLEEP score', SLEEP_SCORE, 'that night\'s SLEEP score'),
    board('hrv', 'next-morning HRV', HRV, 'next-morning HRV'),
    board('deep_share', 'the deep-sleep share', DEEP_SHARE, 'that night\'s deep-sleep share'),
    board('hr_dip', 'the night\'s heart-rate dip', HR_DIP, 'that night\'s heart-rate dip'),
    { id: 'stress_watch_hr_dip', label: 'Watch stress and the night\'s heart-rate dip', cause: 'stress_watch', lag: 1, exposure: 'continuous', causeScale: 10, ...HR_DIP,
      causeText: 'Each 10 points higher day mean of the watch\'s stress', targetText: 'that night\'s heart-rate dip', expectedSign: -1, exposureModel: WATCH_STRESS_MODEL },
    { id: 'stress_watch_deep_share', label: 'Watch stress and the deep-sleep share', cause: 'stress_watch', lag: 1, exposure: 'continuous', causeScale: 10, ...DEEP_SHARE,
      causeText: 'Each 10 points higher day mean of the watch\'s stress', targetText: 'that night\'s deep-sleep share', expectedSign: -1, exposureModel: WATCH_STRESS_MODEL },
    { id: 'kcal_before_bed_hr_dip', label: 'Food in the 3 hours before sleep and the night\'s heart-rate dip', cause: 'kcal_before_bed', lag: 1, exposure: 'continuous', causeScale: 250, ...HR_DIP,
      causeText: 'Each 250 kcal eaten in the 3 hours before sleep', targetText: 'that night\'s heart-rate dip', expectedSign: -1, exposureModel: MEAL_MODEL },
    { id: 'training_min_deep_share', label: 'Training minutes and that night\'s deep-sleep share', cause: 'training_min', lag: 1, exposure: 'continuous', causeScale: 30, ...DEEP_SHARE,
      causeText: 'Each 30 more minutes of training', targetText: 'that night\'s deep-sleep share', expectedSign: 0, exposureModel: DEVICE_MODEL },
    { id: 'training_min_hr_dip', label: 'Training minutes and the night\'s heart-rate dip', cause: 'training_min', lag: 1, exposure: 'continuous', causeScale: 30, ...HR_DIP,
      causeText: 'Each 30 more minutes of training', targetText: 'that night\'s heart-rate dip', expectedSign: 0, exposureModel: DEVICE_MODEL },
  ]
}

// Catalogue 2.5.0: water logged against the next morning (litres, per half a litre). Only days with a hydration entry are used.
const HYDRATION_MODEL = 'Water logged that day; days without a hydration entry are left out, never zero.'
function hydrationQuestions(): Hypothesis[] {
  const q = (key: string, label: string, spec: object, targetText: string, sign: -1 | 0 | 1): Hypothesis => ({
    id: `hydration_${key}`, label: `Water drunk and ${label}`, cause: 'hydration_l', lag: 1, exposure: 'continuous', causeScale: 0.5, ...spec,
    causeText: 'Each extra half litre of water logged', targetText, expectedSign: sign, exposureModel: HYDRATION_MODEL,
  } as Hypothesis)
  return [
    q('rhr', 'next-morning resting heart rate', RHR, 'next-morning resting heart rate', -1),
    q('hrv', 'next-morning HRV', HRV, 'next-morning HRV', 1),
    q('energy', 'next-day energy', ENERGY, 'next-day energy', 1),
  ]
}

// Catalogue 2.5.0: the day's peak temperature (daily_context, backfilled from the weather archive) against the night after it.
const HEAT_MODEL = 'The day\'s highest temperature at home (phone, or the weather archive for earlier days); days without a value are left out.'
function heatQuestions(): Hypothesis[] {
  const q = (key: string, label: string, spec: object, targetText: string, sign: -1 | 0 | 1): Hypothesis => ({
    id: `temp_max_${key}`, label: `A hotter day and ${label}`, cause: 'temp_max_c', lag: 1, exposure: 'continuous', causeScale: 5, ...spec,
    causeText: 'Each 5 degrees warmer the day\'s high', targetText, expectedSign: sign, exposureModel: HEAT_MODEL,
  } as Hypothesis)
  return [
    q('sleep', 'that night\'s sleep', SLEEP, 'that night\'s sleep', -1),
    q('hrv', 'next-morning HRV', HRV, 'next-morning HRV', -1),
    q('rhr', 'next-morning resting heart rate', RHR, 'next-morning resting heart rate', 1),
  ]
}

// Catalogue 2.5.0: whole-day phone and movement measures (about 35 days so far; they read "not yet" until about 60).
function dayShapeQuestions(): Hypothesis[] {
  const q = (id: string, label: string, cause: string, causeScale: number, causeText: string, lag: number, spec: object, targetText: string, model: string): Hypothesis => ({
    id, label, cause, lag, exposure: 'continuous', causeScale, ...spec, causeText, targetText, expectedSign: 0, exposureModel: model,
  } as Hypothesis)
  return [
    q('screen_total_sleep', 'Screen time and that night\'s sleep', 'screen_total_min', 60, 'Each extra hour of screen time', 1, SLEEP, 'that night\'s sleep', PHONE_MODEL),
    q('screen_total_energy', 'Screen time and next-day energy', 'screen_total_min', 60, 'Each extra hour of screen time', 1, ENERGY, 'next-day energy', PHONE_MODEL),
    q('unlocks_energy', 'Phone pick-ups and next-day energy', 'unlocks_day', 20, 'Each 20 more phone unlocks', 1, ENERGY, 'next-day energy', PHONE_MODEL),
    q('mobility_entropy_energy', 'A more varied day and next-day energy', 'mobility_entropy', 0.1, 'Each 0.1 higher spread of the day across places', 1, ENERGY, 'next-day energy', MOBILITY_MODEL),
    q('new_places_energy', 'A new place visited and next-day energy', 'new_places_day', 1, 'Each new place visited', 1, ENERGY, 'next-day energy', MOBILITY_MODEL),
  ]
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
  ...mealAndGutQuestions(),
  ...watchStressQuestions(),
  ...sleepQualityQuestions(),
  ...hydrationQuestions(),
  ...heatQuestions(),
  ...dayShapeQuestions(),
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
