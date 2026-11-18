// Correcting a logged entry from the Console (web transition plan, phase 4). The same tables and columns as the phone's
// edit sheets (android data/AddEntryRepository.kt update*), and only entries typed in by hand: imported and synced rows
// (Zepp, Health Connect, Notion, the scale) stay read-only. Logged times are the phone's wall clock labelled +00: a new
// time replaces the digits and keeps the day and the label, never converting anything.

export type FieldKind = 'text' | 'longtext' | 'number' | 'int' | 'time' | 'select'

export interface FieldSpec {
  key: string
  label: string
  kind: FieldKind
  options?: string[]
  min?: number
  max?: number
  /** Allowed empty (written as null). */
  optional?: boolean
}

export interface EditSpec { table: string; id: number; title: string; fields: FieldSpec[]; values: Record<string, unknown> }

/** Who typed it: only these sources are corrected or deleted here (null: rows from before the column was filled in). */
export const isManual = (source: string | null | undefined): boolean => source == null || source === 'manual'

export const MEAL_SLOTS = ['breakfast', 'lunch', 'snack', 'dinner', 'pre_session', 'recovery']

/** "2026-11-17T12:30:00+00:00" with "08:05" → "2026-11-17T08:05:00+00:00": the day and the +00 label kept. */
export function withWallTime(ts: string, hhmm: string): string {
  if (!/^\d{2}:\d{2}$/.test(hhmm)) throw new Error(`"${hhmm}" is not a time (HH:MM)`)
  const [h, m] = hhmm.split(':').map(Number)
  if (h > 23 || m > 59) throw new Error(`"${hhmm}" is not a time of day`)
  return `${ts.slice(0, 10)}T${hhmm}:00+00:00`
}

/** What the form holds for a field, as the input shows it. */
export function shown(f: FieldSpec, v: unknown): string {
  if (v == null) return ''
  if (f.kind === 'time') return String(v).slice(11, 16)
  return String(v)
}

/**
 * The columns to write: only fields whose text changed, each parsed and checked. [original] is the row as read;
 * [edited] the form's text per field. Throws with a reason the person can act on.
 */
export function buildPatch(fields: FieldSpec[], original: Record<string, unknown>, edited: Record<string, string>): Record<string, unknown> {
  const patch: Record<string, unknown> = {}
  for (const f of fields) {
    const before = shown(f, original[f.key])
    const text = (edited[f.key] ?? before).trim()
    if (text === before.trim()) continue
    if (text === '') {
      if (!f.optional) throw new Error(`${f.label} cannot be empty`)
      patch[f.key] = null
      continue
    }
    switch (f.kind) {
      case 'time':
        patch[f.key] = withWallTime(String(original[f.key]), text)
        break
      case 'number':
      case 'int': {
        const n = Number(text.replace(',', '.'))
        if (!Number.isFinite(n)) throw new Error(`${f.label}: "${text}" is not a number`)
        if (f.kind === 'int' && !Number.isInteger(n)) throw new Error(`${f.label} must be a whole number`)
        if (f.min != null && n < f.min) throw new Error(`${f.label} must be at least ${f.min}`)
        if (f.max != null && n > f.max) throw new Error(`${f.label} must be at most ${f.max}`)
        patch[f.key] = n
        break
      }
      case 'select':
        if (f.options && !f.options.includes(text)) throw new Error(`${f.label}: choose one of ${f.options.join(', ')}`)
        patch[f.key] = text
        break
      default:
        patch[f.key] = text
    }
  }
  return patch
}
