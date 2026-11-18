// Port of generate/Accessories.kt: accessory work the lifter adds to a strength module's sessions on top of the book's
// slots (curls 3 x 10-12 @ RPE 8 on session A). Full in normal and peak weeks, half the sets (rounded up) in deload, taper
// and easy weeks, none in test weeks. Stored in choices.clusters.<module>.accessories as
// { "<session id>" | "*": [ {exercise, sets, reps, load, rest_s, note} ] }; "*" means every session of the module.

import { isFixed, type LoadDto, type NumRange, toNumRange } from './definitions.ts'
import type { ModuleChoice } from './generator.ts'

export const ALL_SESSIONS = '*'

export interface Accessory { exercise: string; sets: number; reps: NumRange | null; load: LoadDto; restS: number | null; note: string | null }

/** The accessories of a session: the ones for every session first, then the session's own. */
export function accessoriesFor(choice: ModuleChoice, sessionId: string | null | undefined): Accessory[] {
  return [...(choice.accessories?.[ALL_SESSIONS] ?? []), ...(sessionId != null ? choice.accessories?.[sessionId] ?? [] : [])]
}

/** Sets of an accessory in a week of the given variant-week kind, or 0 when it is left out. */
export function accessorySets(sets: number, weekKind: string): number {
  if (weekKind === 'test') return 0
  if (weekKind === 'deload' || weekKind === 'taper' || weekKind === 'easy') return Math.floor((sets + 1) / 2)
  return sets
}

const LOAD_KINDS: Record<string, (l: LoadDto) => boolean> = {
  pct_1rm: (l) => l.value != null, pct_tm: (l) => l.value != null, pct_max_reps: (l) => l.value != null,
  work_up_rm: (l) => l.min_rm != null && l.max_rm != null, fixed: (l) => l.kg != null, rpe: (l) => l.rpe != null, bodyweight: () => true, none: () => true,
}

const num = (x: unknown): number | undefined => (typeof x === 'number' && Number.isFinite(x) ? x : undefined)
const int = (x: unknown): number | undefined => (typeof x === 'number' && Number.isInteger(x) ? x : undefined)

/** Reads choices.clusters.<module>.accessories; anything unreadable is dropped, never thrown. */
export function accessoriesFrom(x: unknown): Record<string, Accessory[]> {
  if (!x || typeof x !== 'object' || Array.isArray(x)) return {}
  const out: Record<string, Accessory[]> = {}
  for (const [session, v] of Object.entries(x as Record<string, unknown>)) {
    if (!Array.isArray(v)) continue
    const list: Accessory[] = []
    for (const a of v) {
      if (!a || typeof a !== 'object') continue
      const o = a as Record<string, unknown>
      const exercise = typeof o.exercise === 'string' ? o.exercise.trim() : ''
      const sets = int(o.sets)
      if (!exercise || sets == null || sets <= 0) continue
      const lo = (o.load && typeof o.load === 'object' ? o.load : {}) as Record<string, unknown>
      const load: LoadDto = { kind: typeof lo.kind === 'string' ? lo.kind : 'none' }
      if (num(lo.value) != null) load.value = num(lo.value)
      if (int(lo.min_rm) != null) load.min_rm = int(lo.min_rm)
      if (int(lo.max_rm) != null) load.max_rm = int(lo.max_rm)
      if (num(lo.kg) != null) load.kg = num(lo.kg)
      if (num(lo.rpe) != null) load.rpe = num(lo.rpe)
      if (!LOAD_KINDS[load.kind]?.(load)) continue
      const reps = toNumRange(o.reps)
      list.push({ exercise, sets, reps: reps && reps.min <= reps.max ? reps : null, load, restS: int(o.rest_s) ?? null, note: typeof o.note === 'string' && o.note.trim() ? o.note : null })
    }
    if (list.length > 0) out[session] = list
  }
  return out
}

const rangeJson = (r: NumRange): number | NumRange => (isFixed(r) ? r.min : { min: r.min, max: r.max })

export function accessoriesJson(map: Record<string, Accessory[]>): Record<string, unknown[]> {
  const out: Record<string, unknown[]> = {}
  for (const [session, list] of Object.entries(map)) {
    if (list.length === 0) continue
    out[session] = list.map((a) => ({
      exercise: a.exercise, sets: a.sets, ...(a.reps ? { reps: rangeJson(a.reps) } : {}), load: { ...a.load },
      ...(a.restS != null ? { rest_s: a.restS } : {}), ...(a.note ? { note: a.note } : {}),
    }))
  }
  return out
}

/** "3 x 10-12 @ RPE 8" */
export function accessoryLine(a: Accessory): string {
  const n = (x: number) => String(x)
  const reps = a.reps ? (isFixed(a.reps) ? n(a.reps.min) : `${n(a.reps.min)}-${n(a.reps.max)}`) : null
  const l = a.load
  const load = l.kind === 'rpe' && l.rpe != null ? `RPE ${n(l.rpe)}` : l.kind === 'pct_1rm' && l.value != null ? `${n(l.value)}% 1RM` : l.kind === 'fixed' && l.kg != null ? `${n(l.kg)} kg` : l.kind === 'bodyweight' ? 'bodyweight' : null
  return `${a.sets}${reps ? ` x ${reps}` : ' sets'}${load ? ` @ ${load}` : ''}`
}
