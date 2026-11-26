// Food against training (step 3 of the nutrition proposal, docs/nutrition/food-and-training.md). Pure, no I/O.
//
// Per session (20 minutes or more, walks and unclassified activity left out):
//   hoursSinceMeal   hours from the last meal of 50 kcal or more (within 18 h) to the start; null when none in 18 h.
//   fasted           no meal of 50 kcal or more in the 4 h before the start and the last one at least 8 h before
//                    (or none in 18 h). A morning session before breakfast is the usual case.
//   carbsBefore      carbs in the 4 h before the start (Thomas 2016: 1-4 g/kg in the 1-4 h before a key session).
//   carbsDuring      carbs consumed during it, from the fuelling log (consumed or partial entries tied to the session).
//   proteinAfter / carbsAfter   in the 2 h after the end (about 0.3 g/kg protein; carbs for refuelling after long ones).
//
// Per day, energy availability (Loucks): (intake - exercise energy) / fat-free mass, kcal per kg FFM. Exercise energy is the
// watch's active kcal, which watches tend to overstate, so the value is an estimate. Under 30 is low (the RED-S line),
// 30-45 reduced, 45 or more adequate. Only days with food logged count; fat-free mass is the latest weight and body fat on
// or before the day (within 60 days).
//
// Logged times (meals.logged_at, exercise_sessions.start_time) are the phone's wall clock labelled +00: their digits are
// local, so they are compared as they are.

export const TRAINING_FUEL = {
  minSessionMin: 20,
  beforeHours: 4,
  afterHours: 2,
  lookbackHours: 18,
  fastedHours: 8,
  longSessionMin: 90,
  mealMinKcal: 50,
  proteinPerKgAfter: 0.3,
  defaultProteinAfterG: 25,
  eaLow: 30,
  eaAdequate: 45,
  ffmMaxAgeDays: 60,
}

export const NON_TRAINING = ['walk', 'other']

export interface TfMeal { at: string; kcal: number | null; carbsG: number | null; proteinG: number | null }
export interface TfSession { id: number; at: string; type: string | null; durationMin: number | null; activeKcal: number | null }
export interface TfFuel { sessionId: number; carbsG: number | null; status: string | null }
export interface TfBody { date: string; weightKg: number | null; bodyFatPct: number | null }

export interface SessionFuel {
  id: number
  date: string
  type: string
  durationMin: number
  long: boolean
  hoursSinceMeal: number | null
  fasted: boolean
  carbsBefore: number
  carbsDuring: number | null
  proteinAfter: number
  carbsAfter: number
}

const H = 3600000
const ms = (stamp: string) => Date.parse(String(stamp).slice(0, 19).replace(' ', 'T') + 'Z')
const n = (v: number | null | undefined) => (v === null || v === undefined || !Number.isFinite(v) ? 0 : v)
const r1 = (v: number) => Math.round(v * 10) / 10

/** Every training session with the food around it, oldest first. */
export function sessionFuel(sessions: TfSession[], meals: TfMeal[], fuel: TfFuel[] = []): SessionFuel[] {
  const ms0 = meals.map((m) => ({ ...m, t: ms(m.at) })).filter((m) => Number.isFinite(m.t)).sort((a, b) => a.t - b.t)
  const during = new Map<number, number>()
  for (const f of fuel) {
    if (f.status !== 'consumed' && f.status !== 'partial') continue
    during.set(f.sessionId, (during.get(f.sessionId) ?? 0) + n(f.carbsG))
  }
  const out: SessionFuel[] = []
  for (const s of sessions) {
    const dur = s.durationMin ?? 0
    if (dur < TRAINING_FUEL.minSessionMin || NON_TRAINING.includes(String(s.type ?? ''))) continue
    const start = ms(s.at)
    if (!Number.isFinite(start)) continue
    const end = start + dur * 60000
    const before = ms0.filter((m) => m.t <= start && m.t > start - TRAINING_FUEL.lookbackHours * H && n(m.kcal) >= TRAINING_FUEL.mealMinKcal)
    const last = before.at(-1)
    const hoursSince = last ? r1((start - last.t) / H) : null
    const in4h = ms0.filter((m) => m.t <= start && m.t >= start - TRAINING_FUEL.beforeHours * H)
    const after = ms0.filter((m) => m.t >= end && m.t <= end + TRAINING_FUEL.afterHours * H)
    out.push({
      id: s.id, date: String(s.at).slice(0, 10), type: String(s.type ?? 'session'), durationMin: Math.round(dur),
      long: dur >= TRAINING_FUEL.longSessionMin,
      hoursSinceMeal: hoursSince,
      fasted: hoursSince === null || hoursSince >= TRAINING_FUEL.fastedHours,
      carbsBefore: Math.round(in4h.reduce((a, m) => a + n(m.carbsG), 0)),
      carbsDuring: during.has(s.id) ? Math.round(during.get(s.id)!) : null,
      proteinAfter: Math.round(after.reduce((a, m) => a + n(m.proteinG), 0)),
      carbsAfter: Math.round(after.reduce((a, m) => a + n(m.carbsG), 0)),
    })
  }
  return out.sort((a, b) => a.date.localeCompare(b.date) || a.id - b.id)
}

/** Fat-free mass on [date]: the latest weight with body fat on or before it, within 60 days; null without one. */
export function ffmOn(body: TfBody[], date: string): number | null {
  const sorted = body.filter((b) => b.date <= date && b.weightKg !== null && b.bodyFatPct !== null).sort((a, b) => a.date.localeCompare(b.date))
  const last = sorted.at(-1)
  if (!last) return null
  const age = (Date.parse(date + 'T00:00:00Z') - Date.parse(last.date + 'T00:00:00Z')) / 86400000
  if (age > TRAINING_FUEL.ffmMaxAgeDays) return null
  const ffm = (last.weightKg as number) * (1 - (last.bodyFatPct as number) / 100)
  return ffm >= 25 && ffm <= 150 ? r1(ffm) : null
}

export interface DayEnergy { date: string; intakeKcal: number; exerciseKcal: number; ffmKg: number; ea: number }

/** Energy availability for each day with food logged and a known fat-free mass. */
export function energyAvailability(meals: TfMeal[], sessions: TfSession[], body: TfBody[]): DayEnergy[] {
  const intake = new Map<string, number>()
  for (const m of meals) {
    const d = String(m.at).slice(0, 10)
    intake.set(d, (intake.get(d) ?? 0) + n(m.kcal))
  }
  const ex = new Map<string, number>()
  for (const s of sessions) {
    // Purposeful exercise only (Loucks): walks and unclassified activity are everyday movement, not exercise energy.
    if (NON_TRAINING.includes(String(s.type ?? ''))) continue
    const d = String(s.at).slice(0, 10)
    ex.set(d, (ex.get(d) ?? 0) + n(s.activeKcal))
  }
  const out: DayEnergy[] = []
  for (const [d, kcal] of [...intake].sort(([a], [b]) => a.localeCompare(b))) {
    if (kcal <= 0) continue
    const ffm = ffmOn(body, d)
    if (ffm === null) continue
    const e = ex.get(d) ?? 0
    out.push({ date: d, intakeKcal: Math.round(kcal), exerciseKcal: Math.round(e), ffmKg: r1(ffm), ea: r1((kcal - e) / ffm) })
  }
  return out
}

export type EaBand = 'low' | 'reduced' | 'adequate'
export const eaBand = (ea: number): EaBand => (ea < TRAINING_FUEL.eaLow ? 'low' : ea < TRAINING_FUEL.eaAdequate ? 'reduced' : 'adequate')

// --- The report section ---

export interface TrainingFuelSection {
  sessions: SessionFuel[]
  /** The protein one meal should carry after a session, g (0.3 g/kg). */
  proteinDoseG: number
  counts: { sessions: number; fasted: number; long: number; longFastedNoFuel: number; proteinAfterOk: number }
  energy: { days: number; low: number; reduced: number; adequate: number; median: number | null; lowest: DayEnergy[] }
}

/** The period's sessions and energy availability, summed up for the report. */
export function trainingFuelSection(all: SessionFuel[], energy: DayEnergy[], start: string, end: string, weightKg: number | null): TrainingFuelSection {
  const dose = weightKg !== null && weightKg >= 30 && weightKg <= 250 ? Math.round(TRAINING_FUEL.proteinPerKgAfter * weightKg) : TRAINING_FUEL.defaultProteinAfterG
  const sessions = all.filter((s) => s.date >= start && s.date <= end)
  const days = energy.filter((d) => d.date >= start && d.date <= end)
  const eas = days.map((d) => d.ea).sort((a, b) => a - b)
  const median = eas.length ? (eas.length % 2 ? eas[(eas.length - 1) / 2] : r1((eas[eas.length / 2 - 1] + eas[eas.length / 2]) / 2)) : null
  return {
    sessions,
    proteinDoseG: dose,
    counts: {
      sessions: sessions.length,
      fasted: sessions.filter((s) => s.fasted).length,
      long: sessions.filter((s) => s.long).length,
      longFastedNoFuel: sessions.filter((s) => s.long && s.fasted && !s.carbsDuring).length,
      proteinAfterOk: sessions.filter((s) => s.proteinAfter >= dose).length,
    },
    energy: {
      days: days.length,
      low: days.filter((d) => eaBand(d.ea) === 'low').length,
      reduced: days.filter((d) => eaBand(d.ea) === 'reduced').length,
      adequate: days.filter((d) => eaBand(d.ea) === 'adequate').length,
      median,
      lowest: [...days].sort((a, b) => a.ea - b.ea).slice(0, 3).filter((d) => eaBand(d.ea) === 'low'),
    },
  }
}

export interface TrainingFuelSuggestion { id: string; priority: 1 | 2 | 3; text: string; because: string }

/** Fixed rules (the reports take no advice from a language model). */
export function trainingFuelSuggestions(t: TrainingFuelSection, grain: 'week' | 'month'): TrainingFuelSuggestion[] {
  const out: TrainingFuelSuggestion[] = []
  const lowNeeded = grain === 'week' ? 2 : 6
  if (t.energy.low >= lowNeeded) {
    out.push({
      id: 'energy_low', priority: 1,
      text: `Energy availability was low (under ${TRAINING_FUEL.eaLow} kcal per kg of fat-free mass) on ${t.energy.low} days: on long or double days add a carb-rich meal or snack, and do not diet on them.`,
      because: `${t.energy.low} of ${t.energy.days} days low (estimated from watch kcal)`,
    })
  }
  if (t.counts.longFastedNoFuel > 0) {
    out.push({
      id: 'long_fasted', priority: 2,
      text: `${t.counts.longFastedNoFuel} long session${t.counts.longFastedNoFuel === 1 ? '' : 's'} (90 min or more) started fasted with no fuel logged during: eat 1-4 g of carbs per kg in the 1-4 h before, or take 30-60 g an hour during.`,
      because: `${t.counts.longFastedNoFuel} of ${t.counts.long} long sessions`,
    })
  }
  if (t.counts.sessions >= 3 && t.counts.proteinAfterOk < t.counts.sessions / 2) {
    out.push({
      id: 'protein_after', priority: 3,
      text: `Only ${t.counts.proteinAfterOk} of ${t.counts.sessions} sessions had ${t.proteinDoseG} g of protein in the 2 h after: have a protein-rich meal or shake soon after training.`,
      because: `${t.counts.proteinAfterOk} of ${t.counts.sessions} sessions`,
    })
  }
  return out
}
