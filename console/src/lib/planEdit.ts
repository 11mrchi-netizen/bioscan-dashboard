// Editing the plan before it starts (follow-up redesign): the rules and the arithmetic, pure. The database enforces the same rules
// (supabase/migrations/20261124063309_plan_edit.sql); these only say so early and build what is sent.
//   * a session can be changed while it is still planned, not linked to a recording, dated today or later, in a block that has not ended;
//   * a move changes the date only (original_date stays), time, length and a note are free; the title is NOT editable: it is the key
//     the phone maps a session to its program with, and part of its Zepp template's name;
//   * a prescription change is a new revision (the database does that); here: sets, reps and the load of each lift.
import { loadJson, resolveLoad, type LoadSpec, type ResolveContext } from '../../../supabase/functions/_shared/training-core/index.ts'

export interface SessionLike { status: string; exercise_session_id: number | null; scheduled_date: string }

/** Why a session cannot be changed (null: it can). `blockStatus` is the block's. */
export function whyLocked(s: SessionLike, blockStatus: string | null, today: string): string | null {
  if (s.status === 'superseded') return 'This session is set aside.'
  if (s.status === 'confirmed') return 'This session is confirmed: it was done, or matched to a recording.'
  if (s.status === 'skipped') return 'This session was skipped.'
  if (s.status !== 'planned') return `This session is ${s.status}.`
  if (s.exercise_session_id != null) return 'This session is linked to a recording.'
  if (s.scheduled_date < today) return 'This session is dated before today.'
  if (blockStatus === 'completed' || blockStatus === 'abandoned') return 'Its block has ended.'
  return null
}

export interface SessionForm { date: string; time: string; duration: string; note: string }
export interface SessionValues { scheduled_date: string; start_time: string | null; duration_min: number | null; note?: string | null }

const hhmm = (t: string | null) => (t ? t.slice(0, 5) : '')

export function formOf(v: SessionValues): SessionForm {
  return { date: v.scheduled_date, time: hhmm(v.start_time), duration: v.duration_min == null ? '' : String(v.duration_min), note: v.note ?? '' }
}

/** The changes to send: only what differs from [orig]. Throws with a reason the person can act on; empty when nothing changed. */
export function sessionChanges(orig: SessionValues, form: SessionForm, today: string): Record<string, string | number | null> {
  const out: Record<string, string | number | null> = {}
  if (form.date !== orig.scheduled_date) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(form.date) || Number.isNaN(Date.parse(`${form.date}T12:00:00Z`))) throw new Error('The date is not a date.')
    if (form.date < today) throw new Error('The new date must be today or later.')
    out.scheduled_date = form.date
  }
  if (form.time !== hhmm(orig.start_time)) {
    if (form.time !== '' && !/^([01]\d|2[0-3]):[0-5]\d$/.test(form.time)) throw new Error('The time is not a time (HH:MM).')
    out.start_time = form.time === '' ? null : form.time
  }
  const dur = form.duration.trim()
  if (dur !== (orig.duration_min == null ? '' : String(orig.duration_min))) {
    if (dur !== '' && !(/^\d+$/.test(dur) && Number(dur) >= 5 && Number(dur) <= 600)) throw new Error('The length is 5 to 600 minutes.')
    out.duration_min = dur === '' ? null : Number(dur)
  }
  const note = form.note.trim()
  if (note !== (orig.note ?? '')) {
    if (note.length > 500) throw new Error('A note is at most 500 characters.')
    out.note = note === '' ? null : note
  }
  return out
}

// ---- prescription ----

export type PrescriptionItem = Record<string, unknown>
const MAX_BASED = new Set(['pct_1rm', 'added', 'pct_max_reps', 'unresolved'])
export type LoadChoice = 'planned' | 'fixed' | 'pct' | 'rpe'

export interface LiftForm {
  /** position of the lift in the prescription array */
  index: number; exercise: string; role: string
  /** the exercise to do instead ('' or the same name: no swap) */
  swapTo: string
  setsMin: string; setsMax: string; repsMin: string; repsMax: string
  load: LoadChoice; kg: string; pct: string; rpe: string
  /** how the load reads now, in words */
  loadNow: string
}

const rangeOf = (v: unknown): [number | null, number | null] => {
  if (typeof v === 'number') return [v, v]
  if (v && typeof v === 'object') { const o = v as { min?: unknown; max?: unknown }; return [typeof o.min === 'number' ? o.min : null, typeof o.max === 'number' ? o.max : null] }
  return [null, null]
}
const txt = (n: number | null) => (n == null ? '' : String(n))
const rangeJson = (min: number, max: number) => (min === max ? min : { min, max })

/** The words for a stored load: "80% of 1RM → 100 kg", "fixed 60 kg", "RPE 8", "needs a 1RM (not set)". */
export function loadWords(load: unknown): string {
  const l = (load ?? {}) as Record<string, unknown>
  switch (l.kind) {
    case 'pct_1rm': return `${l.pct}% of 1RM${typeof l.loadable_kg === 'number' ? ` → ${l.loadable_kg} kg` : ''}`
    case 'fixed': return `fixed ${l.kg} kg`
    case 'rpe': return `RPE ${l.rpe}`
    case 'added': return `${l.pct}% added${typeof l.added_kg === 'number' ? ` → +${l.added_kg} kg` : ''}`
    case 'pct_max_reps': return `${l.pct}% of max reps`
    case 'work_up_rm': return `work up to ${l.min_rm}–${l.max_rm} RM`
    case 'bodyweight': return 'bodyweight'
    case 'unresolved': return `no target yet (${String(l.missing ?? 'a max').replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase()} not set)`
    case 'none': return 'no load'
    default: return 'no load'
  }
}

export function liftForms(prescription: unknown): LiftForm[] {
  if (!Array.isArray(prescription)) return []
  const out: LiftForm[] = []
  prescription.forEach((e, index) => {
    const o = e as PrescriptionItem
    if (o?.type !== 'lift') return
    const [sMin, sMax] = rangeOf(o.sets), [rMin, rMax] = rangeOf(o.reps)
    const l = (o.load ?? {}) as Record<string, unknown>
    out.push({
      index, exercise: String(o.exercise ?? ''), role: String(o.role ?? ''), swapTo: '', setsMin: txt(sMin), setsMax: txt(sMax), repsMin: txt(rMin), repsMax: txt(rMax),
      load: 'planned', kg: l.kind === 'fixed' ? txt(l.kg as number) : '', pct: l.kind === 'pct_1rm' ? txt(l.pct as number) : '', rpe: l.kind === 'rpe' ? txt(l.rpe as number) : '',
      loadNow: loadWords(o.load),
    })
  })
  return out
}

const pos = (t: string, what: string, max: number, whole = true): number => {
  const ok = /^\d+(\.\d+)?$/.test(t.trim()) && (!whole || /^\d+$/.test(t.trim()))
  const n = Number(t)
  if (!ok || !(n > 0) || n > max) throw new Error(`${what} must be ${whole ? 'a whole number' : 'a number'} from ${whole ? 1 : '0.5'} to ${max}.`)
  return n
}

/**
 * The prescription to send: every entry as it was, the lifts with their sets, reps and load as edited. A lift left "as planned" and
 * with untouched numbers is returned exactly as it was. Throws with a reason. `oneRmKg` gives a lift's 1RM when one is recorded.
 */
export function buildPrescription(prescription: unknown, forms: LiftForm[], ctx: Omit<ResolveContext, 'oneRmKg'>, oneRmKg: (exercise: string) => number | null): PrescriptionItem[] {
  if (!Array.isArray(prescription)) throw new Error('This session has no prescription to change.')
  const out = (prescription as PrescriptionItem[]).map((e) => ({ ...e }))
  for (const f of forms) {
    const o = out[f.index]
    if (!o || o.type !== 'lift') throw new Error('The session changed while you were editing it: reload it.')
    const label = f.exercise || 'A lift'
    const swap = f.swapTo.trim()
    const swapped = swap !== '' && swap !== f.exercise
    const name = swapped ? swap : f.exercise
    if (swapped) {
      if (swap.length > 80) throw new Error(`${label}: an exercise name is at most 80 characters.`)
      o.exercise = swap
      delete o.projected
      // a load worked out from the old lift's max says nothing about the new one: it needs a load of its own
      if (f.load === 'planned' && MAX_BASED.has(String((o.load as { kind?: unknown } | undefined)?.kind))) {
        throw new Error(`${label} → ${swap}: choose a load for ${swap} (a fixed weight, % of its 1RM, or RPE); the planned one was worked out from the ${f.exercise} max.`)
      }
    }
    const sMin = pos(f.setsMin, `${label}: sets`, 20), sMax = f.setsMax.trim() === '' ? sMin : pos(f.setsMax, `${label}: sets`, 20)
    if (sMax < sMin) throw new Error(`${label}: the most sets cannot be fewer than the least.`)
    const rMin = pos(f.repsMin, `${label}: reps`, 100), rMax = f.repsMax.trim() === '' ? rMin : pos(f.repsMax, `${label}: reps`, 100)
    if (rMax < rMin) throw new Error(`${label}: the most reps cannot be fewer than the least.`)
    const [oS0, oS1] = rangeOf(o.sets), [oR0, oR1] = rangeOf(o.reps)
    if (!(oS0 === sMin && oS1 === sMax)) o.sets = rangeJson(sMin, sMax)
    if (!(oR0 === rMin && oR1 === rMax)) o.reps = rangeJson(rMin, rMax)
    if (f.load === 'planned') continue
    let spec: LoadSpec
    if (f.load === 'fixed') spec = { kind: 'fixed', kg: pos(f.kg, `${label}: the weight`, 1000, false) }
    else if (f.load === 'rpe') { const r = pos(f.rpe, `${label}: RPE`, 10, false); spec = { kind: 'rpe', rpe: r } }
    else {
      const pct = pos(f.pct, `${label}: the percentage`, 200, false)
      const base = oneRmKg(name)
      if (base == null) throw new Error(`${name}: there is no 1RM recorded to take ${pct}% of. Set one in MAXES on PLAN, or choose a fixed weight.`)
      spec = { kind: 'pct_1rm', pct }
      o.load = loadJson(resolveLoad({ slot: String(o.slot ?? ''), load: spec, weightedCalisthenics: false }, { ...ctx, oneRmKg: base }))
      continue
    }
    o.load = loadJson(resolveLoad({ slot: String(o.slot ?? ''), load: spec, weightedCalisthenics: false }, { ...ctx, oneRmKg: null }))
  }
  return out
}

/** Whether a built prescription differs from the stored one (the database refuses an identical one). */
export const prescriptionChanged = (before: unknown, after: unknown): boolean => JSON.stringify(before) !== JSON.stringify(after)

/** A block's start shift in days, recorded by shift_training_block in its choices; sessions then differ from original_date by it. */
export const startShiftDays = (choices: unknown): number => {
  const v = (choices as { start_shift_days?: unknown } | null)?.start_shift_days
  return typeof v === 'number' && Number.isFinite(v) ? v : 0
}

const addDay = (d: string, n: number) => { const x = new Date(`${d}T12:00:00Z`); x.setUTCDate(x.getUTCDate() + n); return x.toISOString().slice(0, 10) }
/** The date a session was moved from, or null when it was not moved by the person (a shifted block's sessions are not "moved"). */
export const movedFrom = (original: string | null, scheduled: string, shiftDays: number): string | null =>
  original && original !== scheduled && addDay(original, shiftDays) !== scheduled ? original : null
