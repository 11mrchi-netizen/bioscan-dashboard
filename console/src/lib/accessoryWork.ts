// Accessory work in the console: the helpers behind AccessoryWork.tsx (complete rows, what is saved, one-line summaries).
import { type Accessory, accessoryLine, ALL_SESSIONS, type LoadDto } from '../../../supabase/functions/_shared/training-core/index.ts'

export const loadValue = (l: LoadDto): number | undefined => (l.kind === 'rpe' ? l.rpe : l.kind === 'pct_1rm' ? l.value : l.kind === 'fixed' ? l.kg : undefined)

export const COMMON_ACCESSORIES = [
  'Curl', 'Hammer Curl', 'Triceps Pushdown', 'Overhead Triceps Extension', 'Lateral Raise', 'Face Pull', 'Rear Delt Fly', 'Calf Raise',
  'Leg Curl', 'Leg Extension', 'Back Extension', 'Hanging Leg Raise', 'Ab Wheel', 'Plank', 'Farmer Carry', 'Nordic Curl', 'Tibialis Raise',
  'Pallof Press', 'Dead Bug', 'Copenhagen Plank', 'Band Pull-apart', 'Split Squat', 'Single-leg RDL', 'Chest-supported Row',
]

/** A complete accessory: named, at least one set, a valid range and load. */
export function accessoryOk(a: Accessory): boolean {
  const v = loadValue(a.load)
  const loadOk = a.load.kind === 'rpe' ? v != null && v >= 1 && v <= 10 : a.load.kind === 'pct_1rm' ? v != null && v > 0 && v <= 100 : a.load.kind === 'fixed' ? v != null && v >= 0 : true
  return a.exercise.trim() !== '' && a.sets > 0 && (!a.reps || (a.reps.min > 0 && a.reps.max >= a.reps.min)) && loadOk
}

/** Only complete accessories, with empty sessions dropped: what is saved. */
export function cleanAccessories(map: Record<string, Accessory[]>): Record<string, Accessory[]> {
  const out: Record<string, Accessory[]> = {}
  for (const [k, list] of Object.entries(map)) {
    const ok = list.filter(accessoryOk).map((a) => ({ ...a, exercise: a.exercise.trim(), note: a.note?.trim() || null }))
    if (ok.length > 0) out[k] = ok
  }
  return out
}

/** One line per session that has accessory work: "D1 · Curl 3 x 10-12 @ RPE 8, …". */
export function accessorySummary(map: Record<string, Accessory[]>): string[] {
  return Object.entries(cleanAccessories(map)).map(([id, list]) => `${id === ALL_SESSIONS ? 'Every session' : id}: ${list.map((a) => `${a.exercise} ${accessoryLine(a)}`).join(', ')}`)
}
