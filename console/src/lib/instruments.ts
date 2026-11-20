// Risk profile R2: scoring the questionnaires in supabase/functions/_shared/instruments/instruments.json, the same way as
// the phone (android domain/risk/Instruments.kt). The file's `cases` are the tests for both.
import defs from '../../../supabase/functions/_shared/instruments/instruments.json' with { type: 'json' }

export interface Option { label: string; value: number }
export interface Item { id: string; text: string; options: string; reverse?: boolean }
export interface Band { min: number; max: number; label: string; state: 'ok' | 'warn' | 'bad' }
export interface Flag { id: string; item: string; min: number; text: string }
export interface Instrument {
  id: string; short: string; name: string; domain: 'mind' | 'connection' | 'work'; every_days: number; prompt: string
  items: Item[]; number?: { min: number; max: number; unit: string }
  scoring: { method: 'sum' | 'mean' | 'value'; multiplier: number }; range: [number, number]; better: 'lower' | 'higher'
  unit: string; bands: Band[]; flags: Flag[]; source: string
}
export interface Scored { score: number; band: Band | null; flags: Flag[] }

export const DEFS = defs as unknown as { version: string; option_sets: Record<string, Option[]>; instruments: Instrument[]; cases: { instrument: string; answers: number[]; score: number; band: string; flags: string[] }[] }
export const INSTRUMENTS: Instrument[] = DEFS.instruments
export const instrument = (id: string): Instrument | undefined => INSTRUMENTS.find((i) => i.id === id)
export const optionsOf = (item: Item): Option[] => DEFS.option_sets[item.options] ?? []

const round2 = (x: number) => Math.round(x * 100) / 100

/** Scores answers given in item order (a number questionnaire takes one value). Throws on a missing or invalid answer. */
export function score(inst: Instrument, answers: number[]): Scored {
  let raw: number
  if (inst.scoring.method === 'value') {
    const v = answers[0]
    if (v === undefined || !Number.isFinite(v) || (inst.number && (v < inst.number.min || v > inst.number.max))) throw new Error(`${inst.id}: value out of range`)
    raw = v
  } else {
    if (answers.length !== inst.items.length) throw new Error(`${inst.id}: ${inst.items.length} answers needed`)
    const vals = inst.items.map((item, i) => {
      const opts = optionsOf(item)
      const v = answers[i]
      if (!opts.some((o) => o.value === v)) throw new Error(`${inst.id}: item ${item.id} has no option ${v}`)
      return item.reverse ? Math.max(...opts.map((o) => o.value)) + Math.min(...opts.map((o) => o.value)) - v : v
    })
    const sum = vals.reduce((a, b) => a + b, 0)
    raw = inst.scoring.method === 'mean' ? sum / vals.length : sum
  }
  const s = round2(raw * inst.scoring.multiplier)
  const band = inst.bands.find((b) => s >= b.min && s <= b.max) ?? null
  const flags = inst.flags.filter((f) => { const i = inst.items.findIndex((it) => it.id === f.item); return i >= 0 && answers[i] >= f.min })
  return { score: s, band, flags }
}

/** The next date an instrument is due, from the local date it was last answered (null: never answered, so due now). */
export function dueOn(inst: Instrument, lastLocalDate: string | null): string | null {
  if (!lastLocalDate) return null
  return new Date(Date.parse(lastLocalDate + 'T00:00:00Z') + inst.every_days * 86400000).toISOString().slice(0, 10)
}

/** The browser's local calendar date (the owner is in UTC+8: the UTC date is a day behind until 08:00), never toISOString. */
export function localToday(now: Date = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`
}
