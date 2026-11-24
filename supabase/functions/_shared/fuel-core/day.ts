// Port of the Android domain/fuel/DayFuelPlan.kt: a day's carbohydrate, protein, fat and energy targets, shaped by the training of
// the next 72 hours. Pure arithmetic over a PlannedDay[] (the training of each day), a WeekEnergy and a weight; deterministic.
// Parity with Kotlin is by fixture (DayFuelPlanParityTest writes fixtures/day-input.json and fixtures/day-expected.json), the
// text of every reason and note included, so number formatting follows Java's Formatter (see fmt).
//
// Not here: building the PlannedDay from events, planned sessions, the calendar and recordings (PlannedDay.kt, SessionDemand.kt,
// SessionMatching.kt), the week's energy (FuelPlanRepository), and the coded meals (MealCodes.kt).

export type EventPriority = 'A' | 'B' | 'C'
export type Intensity = 'EASY' | 'MODERATE' | 'HARD' | 'LONG' | 'RACE'
const INTENSITY_ORDER: Intensity[] = ['EASY', 'MODERATE', 'HARD', 'LONG', 'RACE']
const intensityOrdinal = (i: Intensity) => INTENSITY_ORDER.indexOf(i)
export const isKeyIntensity = (i: Intensity) => i === 'HARD' || i === 'LONG' || i === 'RACE'

export type PlanSource = 'EVENT' | 'PLANNED_SESSION' | 'CALENDAR' | 'RECORDED'
export type EventKind = 'RACE' | 'LONG_SESSION' | 'MATCH' | 'HIKE_DAY' | 'OTHER_HARD_DAY'

/** The phone's PlannedItem, as it serialises. */
export interface PlannedItem {
  source: PlanSource
  sourceId: string
  title: string
  /** exercise_sessions type: run, ride, hike, strength, swim, other. */
  type: string
  /** Kotlin ZonedDateTime.toString(), "2026-11-24T08:00+08:00[Asia/Taipei]"; null when only the day is known. */
  start: string | null
  durationMin: number
  intensity: Intensity
  intensityReason: string
  kcal: number | null
  recorded: boolean
  eventId: number | null
  eventKind: EventKind | null
  eventPriority: EventPriority | null
  distanceKm: number | null
  planRef: string | null
  planTitle: string | null
  matchConfidence: number | null
  matchStored: boolean
  matchManual: boolean
}
export interface CalendarReview { eventId: string; title: string; description: string | null; start: string; end: string | null; clockMin: number }
export interface PlannedDay { date: string; items: PlannedItem[]; reviews: CalendarReview[]; plans: PlannedItem[] }

export interface WeekEnergy {
  dailyKcal: number | null
  proteinG: number | null
  fromGoal: boolean
  deficit?: boolean
  observedMaintenance?: boolean
}

export type DayClass = 'LOW' | 'MEDIUM' | 'HIGH' | 'LOADING'
export interface CarbBand { lowGPerKg: number; highGPerKg: number; name: string }

export const BAND_LIGHT: CarbBand = { lowGPerKg: 3, highGPerKg: 5, name: 'light day' }
export const BAND_MODERATE: CarbBand = { lowGPerKg: 5, highGPerKg: 7, name: 'up to 90 minutes of training' }
export const BAND_HIGH: CarbBand = { lowGPerKg: 6, highGPerKg: 10, name: '1.5-4 hours of training' }
export const BAND_VERY_HIGH: CarbBand = { lowGPerKg: 8, highGPerKg: 12, name: 'over 4 hours of training' }
export const BAND_LOADING: CarbBand = { lowGPerKg: 10, highGPerKg: 12, name: 'carbohydrate loading' }
const BAND_STEPS = [BAND_LIGHT, BAND_MODERATE, BAND_HIGH, BAND_VERY_HIGH]

export const DEFAULT_PROTEIN_G_PER_KG = 1.8
export const FAT_FLOOR_SHARE = 0.2
export const LOADING_RACE_MIN = 90
export const LOW_ENERGY_AVAILABILITY = 30
/** The most energy one day's plan asks for, kcal. */
export const MAX_DAY_KCAL = 4500
export const MODERATE_BAND_MAX_MIN = 90
const KCAL_PER_G_PROTEIN = 4
const KCAL_PER_G_CARBS = 4
const KCAL_PER_G_FAT = 9

export interface DayFuelPlan {
  date: string
  dayClass: DayClass
  band: CarbBand
  carbGPerKg: number
  carbG: number
  proteinG: number
  fatG: number | null
  kcal: number | null
  reasons: string[]
  notes: string[]
  loading: boolean
  energyAvailability: number | null
  sessionKcal: number
}

// ---- Kotlin's rounding and number text ----

/** Kotlin Double.roundToInt(): half up (Math.round), NaN is 0. */
export const roundToInt = (v: number) => (Number.isNaN(v) ? 0 : Math.round(v))

/**
 * Java's String.format("%.<digits>f"): HALF_UP on the decimal digits of the value's shortest representation (not on the binary
 * value, which is what toFixed does: (1.005).toFixed(2) is "1.00", Java gives "1.01").
 */
export function fmt(v: number, digits: number): string {
  if (!Number.isFinite(v)) return String(v)
  const neg = v < 0 || Object.is(v, -0)
  let s = String(Math.abs(v))
  if (/e/i.test(s)) s = Math.abs(v).toFixed(20).replace(/0+$/, '')
  let [int, frac = ''] = s.split('.')
  let carry = false
  if (frac.length > digits) {
    carry = frac.charCodeAt(digits) >= 53 // '5'
    frac = frac.slice(0, digits)
  } else frac = frac.padEnd(digits, '0')
  let digitsStr = int + frac
  if (carry) {
    const a = digitsStr.split('')
    let i = a.length - 1
    while (i >= 0) {
      if (a[i] === '9') { a[i] = '0'; i-- } else { a[i] = String(Number(a[i]) + 1); break }
    }
    digitsStr = (i < 0 ? '1' : '') + a.join('')
  }
  const intLen = digitsStr.length - digits
  const out = digits > 0 ? `${digitsStr.slice(0, intLen)}.${digitsStr.slice(intLen)}` : digitsStr
  const zero = /^0*\.?0*$/.test(out)
  return neg && !zero ? `-${out}` : out
}

const trunc = Math.trunc
const lc = (s: string) => s.toLowerCase()

// ---- dates ----

const dateMs = (d: string) => Date.UTC(Number(d.slice(0, 4)), Number(d.slice(5, 7)) - 1, Number(d.slice(8, 10)))
const plusDays = (d: string, n: number) => new Date(dateMs(d) + n * 86400000).toISOString().slice(0, 10)

/** The local hour of a Kotlin ZonedDateTime.toString(). */
export const startHour = (start: string | null | undefined): number | null => (start ? Number(start.slice(11, 13)) : null)

// ---- the day ----

const trainingMin = (d: PlannedDay) => d.items.reduce((a, i) => a + i.durationMin, 0)
export const sessionKcal = (d: PlannedDay) => d.items.reduce((a, i) => a + (i.kcal ?? 0), 0)
const isRest = (d: PlannedDay) => d.items.length === 0
/** The hardest session's class; the first of equals, like Kotlin's maxByOrNull. */
export function hardest(d: PlannedDay): Intensity | null {
  let best: PlannedItem | null = null
  for (const i of d.items) if (best === null || intensityOrdinal(i.intensity) > intensityOrdinal(best.intensity)) best = i
  return best ? best.intensity : null
}

/** The band a day's training puts it in, before the 72-hour adjustment. */
export function bandForTraining(day: PlannedDay): CarbBand {
  const minutes = trainingMin(day)
  const h = hardest(day)
  if (isRest(day)) return BAND_LIGHT
  if (minutes > 240) return BAND_VERY_HIGH
  if (minutes > MODERATE_BAND_MAX_MIN) return BAND_HIGH
  if (minutes < 45 && h === 'EASY') return BAND_LIGHT
  return BAND_MODERATE
}

const stepUp = (band: CarbBand): CarbBand => {
  const i = BAND_STEPS.indexOf(band)
  return BAND_STEPS[i + 1] ?? band
}

function positionInBand(day: PlannedDay): number {
  switch (hardest(day)) {
    case null: case 'EASY': return 0
    case 'MODERATE': return 0.25
    case 'HARD': case 'LONG': return 0.5
    case 'RACE': return 0.75
  }
}

/** The A or B race over LOADING_RACE_MIN minutes on date + 1 or date + 2 that date loads for, if any. */
export function loadingFor(date: string, days: PlannedDay[]): PlannedItem | null {
  const d1 = plusDays(date, 1)
  const d2 = plusDays(date, 2)
  for (const d of days) {
    if (d.date !== d1 && d.date !== d2) continue
    for (const i of d.items) if (i.intensity === 'RACE' && i.eventPriority !== 'C' && i.durationMin > LOADING_RACE_MIN) return i
  }
  return null
}

const keyMorningSession = (day: PlannedDay | null): PlannedItem | null =>
  day?.items.find((i) => isKeyIntensity(i.intensity) && (startHour(i.start) ?? 12) < 12) ?? null

/** The day's energy before any ceiling: the week's average plus this day's sessions minus the week's mean session energy. */
export function rawDayEnergy(day: PlannedDay, week: PlannedDay[], energy: WeekEnergy): number | null {
  if (energy.dailyKcal == null) return null
  const meanSession = week.length === 0 ? sessionKcal(day) : week.reduce((a, d) => a + sessionKcal(d), 0) / week.length
  return energy.dailyKcal + sessionKcal(day) - meanSession
}

export interface SpreadEnergy { kcal: number | null; notes: string[] }

const dayWord = (offset: number) => (offset === 1 ? 'the day after' : offset === -1 ? 'the day before' : offset === 2 ? 'two days after' : offset === -2 ? 'two days before' : 'another day')

/**
 * Applies MAX_DAY_KCAL to raw (one value per day, in date order): the energy a day has over the ceiling moves to the day after,
 * the day before, two days after and two days before, as far as each has room under the same ceiling. Days before firstMovable
 * (already past) take nothing from the days after them. What finds no room is dropped.
 */
export function spreadDayEnergy(raw: (number | null)[], firstMovable = 0, cap = MAX_DAY_KCAL): SpreadEnergy[] {
  const kcal = [...raw]
  const notes: string[][] = raw.map(() => [])
  for (let i = 0; i < raw.length; i++) {
    const r = raw[i]
    if (r == null) continue
    if (r <= cap) continue
    let excess = r - cap
    kcal[i] = cap
    const moved: string[] = []
    for (const offset of [1, -1, 2, -2]) {
      if (excess <= 0.5) break
      const j = i + offset
      if (j < 0 || j >= raw.length || (j < firstMovable && i >= firstMovable)) continue
      const have = kcal[j]
      if (have == null) continue
      const take = Math.max(Math.min(excess, cap - have), 0)
      if (take < 1) continue
      kcal[j] = have + take
      excess -= take
      moved.push(`${roundToInt(take)} to ${dayWord(offset)}`)
      notes[j].push(`+${roundToInt(take)} kcal moved here from ${dayWord(-offset)}'s ${roundToInt(r)}-kcal day, so no day asks for more than ${roundToInt(cap)}.`)
    }
    let t = `Capped at ${roundToInt(cap)} kcal (the training asks for ${roundToInt(r)})`
    if (moved.length) t += `: ${moved.join(', ')}`
    notes[i].push(`${t}.`)
  }
  return raw.map((_, i) => ({ kcal: kcal[i], notes: notes[i] }))
}

/** The plan for day. week holds the days whose session energy averages out to the goal; next is the day after day. */
export function planFuelDay(
  day: PlannedDay, next: PlannedDay | null, week: PlannedDay[], energy: WeekEnergy, weightKg: number,
  fatFreeMassKg: number | null = null, allDays: PlannedDay[] = week, energyKcal: number | null = null, energyNotes: string[] = [],
): DayFuelPlan {
  const reasons: string[] = []
  const notes: string[] = []

  const loadingRace = loadingFor(day.date, allDays)
  let band = bandForTraining(day)
  const ownBand = band
  reasons.push(
    isRest(day)
      ? `Rest day: ${trunc(BAND_LIGHT.lowGPerKg)}-${trunc(BAND_LIGHT.highGPerKg)} g/kg.`
      : `${day.items.map((i) => `${i.title} (${roundToInt(i.durationMin)} min, ${lc(i.intensity)})`).join(' + ')}: ${band.name}.`,
  )
  let position = positionInBand(day)
  const keyTomorrow = keyMorningSession(next)
  if (loadingRace == null && keyTomorrow != null && band !== BAND_VERY_HIGH) {
    const own = band.lowGPerKg + (band.highGPerKg - band.lowGPerKg) * position
    band = stepUp(band)
    position = Math.max((own - band.lowGPerKg) / (band.highGPerKg - band.lowGPerKg), 0)
    reasons.push(`Tomorrow morning: ${keyTomorrow.title} (${lc(keyTomorrow.intensity)}), so a step higher today.`)
  }
  if (loadingRace != null) {
    band = BAND_LOADING
    position = 0.5
    reasons.push(`Carbohydrate loading for ${loadingRace.title}: ${trunc(BAND_LOADING.lowGPerKg)}-${trunc(BAND_LOADING.highGPerKg)} g/kg.`)
    notes.push('Low in fibre and fat the day before the race: easier on the gut.')
  }
  let dayClass: DayClass
  if (loadingRace != null) dayClass = 'LOADING'
  else {
    const g = band.lowGPerKg + (band.highGPerKg - band.lowGPerKg) * position
    dayClass = g < 5 ? 'LOW' : g < 8 ? 'MEDIUM' : 'HIGH'
  }
  let carbPerKg = band.lowGPerKg + (band.highGPerKg - band.lowGPerKg) * position
  let carbG = carbPerKg * weightKg
  const proteinG = energy.proteinG ?? DEFAULT_PROTEIN_G_PER_KG * weightKg
  const sessKcal = sessionKcal(day)

  notes.push(...energyNotes)
  let kcal: number | null = null
  if (energy.dailyKcal != null) {
    if (energyKcal != null) kcal = energyKcal
    else {
      const raw = rawDayEnergy(day, week, energy)
      if (raw != null) {
        if (raw > MAX_DAY_KCAL) notes.push(`Capped at ${roundToInt(MAX_DAY_KCAL)} kcal: a day cannot realistically take ${roundToInt(raw)}.`)
        kcal = Math.min(raw, MAX_DAY_KCAL)
      }
    }
  }
  let fatG: number | null = null
  if (kcal != null) {
    const fatFloorG = (FAT_FLOOR_SHARE * kcal) / KCAL_PER_G_FAT
    const left = kcal - proteinG * KCAL_PER_G_PROTEIN - carbG * KCAL_PER_G_CARBS
    if (left / KCAL_PER_G_FAT >= fatFloorG) {
      fatG = left / KCAL_PER_G_FAT
    } else {
      const carbRoom = (kcal * (1 - FAT_FLOOR_SHARE) - proteinG * KCAL_PER_G_PROTEIN) / KCAL_PER_G_CARBS
      const bandLowG = (loadingRace != null ? band : ownBand).lowGPerKg * weightKg
      if (carbRoom >= bandLowG && loadingRace == null) {
        carbG = carbRoom
        carbPerKg = carbG / weightKg
        fatG = (FAT_FLOOR_SHARE * kcal) / KCAL_PER_G_FAT
        notes.push(`Carbohydrate kept at ${fmt(carbPerKg, 1)} g/kg so fat stays at 20 % of energy.`)
      } else {
        const needed = (proteinG * KCAL_PER_G_PROTEIN + carbG * KCAL_PER_G_CARBS) / (1 - FAT_FLOOR_SHARE)
        const ceiling = Math.max(kcal, MAX_DAY_KCAL)
        if (needed > ceiling) {
          carbG = Math.max((ceiling * (1 - FAT_FLOOR_SHARE) - proteinG * KCAL_PER_G_PROTEIN) / KCAL_PER_G_CARBS, 0)
          carbPerKg = carbG / weightKg
          notes.push(`Carbohydrate held at ${fmt(carbPerKg, 1)} g/kg so the day stays at ${roundToInt(ceiling)} kcal, the most a day asks for.`)
        } else {
          notes.push(
            loadingRace != null
              ? `Loading day: ${roundToInt(needed)} kcal, above the usual ${roundToInt(kcal)}. Planned, not a surplus.`
              : `Energy raised to ${roundToInt(needed)} kcal so the day's training is fuelled (the goal's ${roundToInt(kcal)} leave too little).`,
          )
        }
        kcal = Math.min(needed, ceiling)
        fatG = (FAT_FLOOR_SHARE * kcal) / KCAL_PER_G_FAT
      }
    }
  }

  const h = hardest(day)
  if (energy.deficit && loadingRace == null && h != null && isKeyIntensity(h)) {
    notes.push("Deficit goal on a key training day: the calories come off fat, not the training's carbohydrate.")
  }
  const ea = kcal != null && fatFreeMassKg != null && fatFreeMassKg > 0 ? (kcal - sessKcal) / fatFreeMassKg : null
  if (ea != null && ea < LOW_ENERGY_AVAILABILITY) {
    notes.push(`Energy left after training is ${fmt(ea, 0)} kcal per kg of fat-free mass, under 30: kept there for long, it costs health and adaptation (IOC REDs consensus 2023).`)
  }
  if (energy.dailyKcal == null) notes.push('No calorie goal or maintenance estimate: carbohydrate and protein only.')

  return { date: day.date, dayClass, band, carbGPerKg: carbPerKg, carbG, proteinG, fatG, kcal, reasons, notes, loading: loadingRace != null, energyAvailability: ea, sessionKcal: sessKcal }
}

/** Plans for every day of days (ordered by date), each with the next day for the 72-hour step and all of them as the week. */
export function planFuelDays(days: PlannedDay[], energy: WeekEnergy, weightKg: number, fatFreeMassKg: number | null = null, week: PlannedDay[] = days.slice(0, 7)): DayFuelPlan[] {
  const spread = spreadDayEnergy(days.map((d) => rawDayEnergy(d, week, energy)))
  return days.map((d, i) => planFuelDay(d, days[i + 1] ?? null, week, energy, weightKg, fatFreeMassKg, days, spread[i].kcal, spread[i].notes))
}

/**
 * Plans for days when todayIdx is today: the goal holds as the average of each block of seven days counted from today
 * (days 0-6, 7-13, ...); days before today use the first block. A day over MAX_DAY_KCAL hands its excess to the days around it.
 */
export function planInWeekBlocks(days: PlannedDay[], todayIdx: number, energy: WeekEnergy, weightKg: number, fatFreeMassKg: number | null = null): DayFuelPlan[] {
  const weeks = days.map((_, i) => {
    const block = Math.trunc(Math.max(i - todayIdx, 0) / 7)
    return days.slice(todayIdx + 7 * block).slice(0, 7)
  })
  const spread = spreadDayEnergy(days.map((d, i) => rawDayEnergy(d, weeks[i], energy)), Math.max(todayIdx, 0))
  return days.map((d, i) => planFuelDay(d, days[i + 1] ?? null, weeks[i], energy, weightKg, fatFreeMassKg, days, spread[i].kcal, spread[i].notes))
}

/** Fat-free mass from a lean-mass reading, else from weight and body fat; null without either. */
export function fatFreeMass(weightKg: number | null, leanMassKg: number | null, bodyFatPct: number | null): number | null {
  if (leanMassKg != null && leanMassKg > 0) return leanMassKg
  if (weightKg == null) return null
  return bodyFatPct != null && bodyFatPct >= 2 && bodyFatPct <= 60 ? weightKg * (1 - bodyFatPct / 100) : null
}

/** Carbohydrate in g/kg rounded for display ("6.5 G/KG"). */
export const formatGPerKg = (v: number) => `${fmt(Math.max(v, 0), 1)} G/KG`
