// Correcting a recorded workout from the web (follow-up redesign). The phone's rule (android data/AddEntryRepository.kt
// updateExerciseDetails, ui/screens/EntryExerciseForms.kt, domain/StrengthImport.kt editedDetails), for EVERY source: what the watch or
// Health Connect measured (times, distance, heart rate, a set's duration and heart rate) is never written here; what the person
// says is: the effort (RPE 0-10), notes, a run's route and run type, and a strength session's exercises and sets. Changing the
// exercises makes them the person's own (exercises_source "app"), so no Zepp sync overwrites them; saving only RPE or notes leaves a
// Zepp list following Zepp. `details` is written as the current row's details with only the keys this form owns changed, never from
// a stale copy, so keys written elsewhere (zepp_fingerprint, a later route tag) survive.

export const ROUTE_TYPES = ['road', 'trail', 'mixed', 'track'] as const
export const RUN_TYPES = ['easy', 'tempo', 'long', 'hills', 'intervals', 'race', 'recovery'] as const
export const EXERCISES_FROM_APP = 'app'

export type Details = Record<string, unknown>
export interface StrengthSet { reps: number; weight_kg: number; rpe?: number | null; rir?: number | null; percent_1rm?: number | null; duration_sec?: number | null; avg_hr?: number | null; detected_reps?: number | null; gear?: string[] | null }
export interface StrengthExercise { name: string; exercise_code?: number | null; sets: StrengthSet[] }

/** What the form holds: text per field, since a set mid-entry is routinely blank or partial. What the watch measured rides along unchanged. */
/** `gear`: null = not recorded (every set from before gear existed); a list, even an empty one, = recorded. `gearAuto`: still following the person's rules as the weight is typed. */
export interface EditableSet { reps: string; weightKg: string; rpe: string; percentOneRm: string; gear: string[] | null; gearAuto: boolean; carried: Pick<StrengthSet, 'rir' | 'duration_sec' | 'avg_hr' | 'detected_reps'> }
export interface EditableExercise { name: string; originalName: string; exerciseCode: number | null; sets: EditableSet[] }

export const blankSet = (): EditableSet => ({ reps: '', weightKg: '', rpe: '', percentOneRm: '', gear: null, gearAuto: true, carried: {} })
export const blankExercise = (): EditableExercise => ({ name: '', originalName: '', exerciseCode: null, sets: [blankSet()] })

export function exercisesOf(details: Details | null | undefined): StrengthExercise[] {
  const x = details?.exercises
  return Array.isArray(x) ? (x as StrengthExercise[]) : []
}

export function toEditable(e: StrengthExercise): EditableExercise {
  return {
    name: e.name, originalName: e.name, exerciseCode: e.exercise_code ?? null,
    sets: e.sets.map((s) => ({
      reps: String(s.reps), weightKg: String(s.weight_kg), rpe: s.rpe == null ? '' : String(s.rpe), percentOneRm: s.percent_1rm == null ? '' : String(s.percent_1rm),
      gear: Array.isArray(s.gear) ? s.gear : null, gearAuto: false,
      carried: { rir: s.rir ?? null, duration_sec: s.duration_sec ?? null, avg_hr: s.avg_hr ?? null, detected_reps: s.detected_reps ?? null },
    })),
  }
}

const intOrNull = (t: string): number | null => (/^\s*-?\d+\s*$/.test(t) ? Number(t) : null)
const numOrNull = (t: string): number | null => (t.trim() !== '' && Number.isFinite(Number(t)) ? Number(t) : null)

/** A set with no reps or weight is dropped rather than saved as 0; an exercise with no name or no valid set is dropped whole. */
export function toExercise(e: EditableExercise): StrengthExercise | null {
  if (e.name.trim() === '') return null
  const sets: StrengthSet[] = []
  for (const s of e.sets) {
    const reps = intOrNull(s.reps), weight = numOrNull(s.weightKg)
    if (reps == null || weight == null) continue
    sets.push({ reps, weight_kg: weight, rpe: intOrNull(s.rpe), rir: s.carried.rir ?? null, percent_1rm: numOrNull(s.percentOneRm), duration_sec: s.carried.duration_sec ?? null, avg_hr: s.carried.avg_hr ?? null, detected_reps: s.carried.detected_reps ?? null, ...(s.gear ? { gear: s.gear } : {}) })
  }
  if (sets.length === 0) return null
  // The Zepp code names the exercise Zepp recorded; renamed, it would point at the wrong one, so it is dropped then.
  return { name: e.name.trim(), exercise_code: e.name.trim() === e.originalName.trim() ? e.exerciseCode : null, sets }
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)
/** The comparison that decides whether exercises changed: null and absent fields are the same thing. */
const norm = (list: StrengthExercise[]) => list.map((e) => ({ name: e.name, code: e.exercise_code ?? null, sets: e.sets.map((s) => [s.reps, s.weight_kg, s.rpe ?? null, s.rir ?? null, s.percent_1rm ?? null, s.duration_sec ?? null, s.avg_hr ?? null, s.detected_reps ?? null, s.gear ?? null]) }))

export interface DetailEdits {
  /** `undefined`: leave as it is (not a run); `null`: clear. */
  routeType?: string | null
  runType?: string | null
  /** `undefined`: leave as it is (not a strength session); `[]`: clear. */
  exercises?: StrengthExercise[]
}

/** The details to write: [initial] (the row as it is now) with only what this form owns changed. */
export function editedDetails(initial: Details | null | undefined, edits: DetailEdits): Details {
  const out: Details = { ...(initial ?? {}) }
  const setOrClear = (key: string, v: string | null | undefined) => { if (v === undefined) return; if (v === null || v === '') delete out[key]; else out[key] = v }
  setOrClear('route_type', edits.routeType)
  setOrClear('run_type', edits.runType)
  if (edits.exercises !== undefined) {
    const before = exercisesOf(initial)
    if (edits.exercises.length === 0) { delete out.exercises; delete out.exercises_source }
    else {
      out.exercises = edits.exercises
      if (!same(norm(before), norm(edits.exercises))) out.exercises_source = EXERCISES_FROM_APP // else it keeps what it had (zepp follows Zepp)
    }
  }
  return out
}

export interface SessionInput { rpe: string; notes: string; edits: DetailEdits }
export interface SessionPatch { rpe: number | null; notes: string | null; details: Details }

/** The columns to write for a saved form. Throws with a reason the person can act on. */
export function sessionPatch(currentDetails: Details | null | undefined, input: SessionInput): SessionPatch {
  const t = input.rpe.trim()
  let rpe: number | null = null
  if (t !== '') {
    const n = intOrNull(t)
    if (n == null || n < 0 || n > 10) throw new Error('RPE is a whole number from 0 to 10')
    rpe = n
  }
  const notes = input.notes.trim() === '' ? null : input.notes.trim()
  return { rpe, notes, details: editedDetails(currentDetails, input.edits) }
}

/** The pace of a run or the speed of anything else, from what the watch measured; null when it did not. */
export function paceOrSpeed(type: string | null, durationMin: number | null, distanceKm: number | null): string | null {
  if (!durationMin || !distanceKm || durationMin <= 0 || distanceKm <= 0) return null
  if (type === 'run' || type === 'hike' || type === 'walk') {
    const sec = Math.round((durationMin * 60) / distanceKm)
    return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')} /km`
  }
  return `${(distanceKm / (durationMin / 60)).toFixed(1)} km/h`
}
