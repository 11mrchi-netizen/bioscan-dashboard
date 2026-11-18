// Ports of generate/BlockDomains.kt, GenerationText.kt and PrescriptionJson.kt: the block's domain composition, the
// one-line text of an item and a session, and the frozen form of a prescription (planned_sessions.prescription).

import { isFixed, type NumRange } from './definitions.ts'
import type { GeneratedItem, GeneratedSession } from './generator.ts'
import { loadableTotal } from './plates.ts'
import type { ResolvedLoad } from './prescription.ts'

export interface BlockDomain { domain: string; role: string; sessions: number }

export function deriveBlockDomains(sessions: GeneratedSession[], maintenanceShare = 0.1): BlockDomain[] {
  const counted = sessions.filter((s) => s.domain !== 'recovery' && s.workKind !== 'test')
  if (counted.length === 0) return []
  const counts = new Map<string, number>()
  for (const s of counted) counts.set(s.domain, (counts.get(s.domain) ?? 0) + 1)
  const byDomain = [...counts].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
  const out: BlockDomain[] = []
  byDomain.forEach(([domain, n], i) => {
    if (i === 0) out.push({ domain, role: 'primary', sessions: n })
    else if (i === 1) out.push({ domain, role: 'secondary', sessions: n })
    else if (n / counted.length >= maintenanceShare) out.push({ domain, role: 'maintenance', sessions: n })
  })
  return out
}

/** Kotlin's fmt: an integer prints without decimals, otherwise up to two decimals with trailing zeros dropped. */
export function fmt(x: number): string {
  if (x === Math.floor(x)) return String(Math.trunc(x))
  return x.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')
}

export const rangeText = (r: NumRange): string => (isFixed(r) ? fmt(r.min) : `${fmt(r.min)}-${fmt(r.max)}`)

export function loadText(l: ResolvedLoad): string {
  switch (l.kind) {
    case 'barbell': return `${fmt(loadableTotal(l.loadable))} kg` + (l.loadable.platesPerSideKg.length === 0 ? ' (bar)' : ` (${l.loadable.platesPerSideKg.map(fmt).join('+')} per side)`)
    case 'added': return `+${fmt(l.addedKg)} kg`
    case 'max_reps_fraction': return `${l.reps} reps (${fmt(l.pct)}% of max)`
    case 'work_up': return `work up to a ${l.minRm}-${l.maxRm}RM`
    case 'fixed_kg': return `${fmt(loadableTotal(l.loadable))} kg`
    case 'rpe': return `RPE ${fmt(l.rpe)}`
    case 'bodyweight': return 'bodyweight'
    case 'no_load': return ''
    case 'unresolved': return 'needs a max'
  }
}

export function itemText(i: GeneratedItem): string {
  const parts = [i.exercise]
  const scheme = [i.sets, i.reps].filter((x): x is NumRange => x != null).map(rangeText).join(' x ')
  if (scheme !== '') parts.push(scheme)
  const l = loadText(i.load)
  if (l !== '') parts.push(`@ ${l}`)
  if (i.technique) parts.push(`[${i.technique}]`)
  if (i.projected) parts.push('(projected)')
  return parts.join(' ')
}

export function summaryLines(s: GeneratedSession): string[] {
  if (s.conditioning) return [s.conditioning.label + (s.conditioning.minutes ? ` · ${rangeText(s.conditioning.minutes)} min` : '')]
  return s.items.map(itemText)
}

const rangeJson = (r: NumRange): unknown => (isFixed(r) ? r.min : { min: r.min, max: r.max })

export function loadJson(l: ResolvedLoad): Record<string, unknown> {
  switch (l.kind) {
    case 'barbell': return { kind: 'pct_1rm', pct: l.pct, target_kg: l.targetKg, loadable_kg: loadableTotal(l.loadable), bar_kg: l.loadable.barKg, plates_per_side_kg: l.loadable.platesPerSideKg }
    case 'added': return { kind: 'added', pct: l.pct, added_kg: l.addedKg, ...(l.totalKg != null ? { total_kg: l.totalKg } : {}) }
    case 'max_reps_fraction': return { kind: 'pct_max_reps', pct: l.pct, reps: l.reps }
    case 'work_up': return { kind: 'work_up_rm', min_rm: l.minRm, max_rm: l.maxRm }
    case 'fixed_kg': return { kind: 'fixed', kg: l.kg, loadable_kg: loadableTotal(l.loadable) }
    case 'rpe': return { kind: 'rpe', rpe: l.rpe }
    case 'bodyweight': return { kind: 'bodyweight' }
    case 'no_load': return { kind: 'none' }
    case 'unresolved': return { kind: 'unresolved', missing: l.missing }
  }
}

/** planned_sessions.prescription: "lift" entries, then "conditioning", then "note". */
export function liftJson(i: GeneratedItem): Record<string, unknown> {
  return {
    type: 'lift', slot: i.slot, exercise: i.exercise, role: i.role,
    ...(i.sets ? { sets: rangeJson(i.sets) } : {}), ...(i.reps ? { reps: rangeJson(i.reps) } : {}),
    load: loadJson(i.load), ...(i.technique ? { technique: i.technique } : {}), ...(i.optional ? { optional: true } : {}),
    ...(i.note ? { note: i.note } : {}), ...(i.projected ? { projected: true } : {}),
  }
}

export function prescriptionJson(s: GeneratedSession): Record<string, unknown>[] {
  const out: Record<string, unknown>[] = []
  for (const i of s.items) out.push(liftJson(i))
  const c = s.conditioning
  if (c) {
    out.push({
      type: 'conditioning', ...(c.sessionKey ? { session_key: c.sessionKey } : {}), label: c.label, ...(c.category ? { category: c.category } : {}),
      ...(c.minutes ? { minutes: rangeJson(c.minutes) } : {}), ...(c.params ? { params: c.params } : {}),
    })
  }
  for (const n of s.notes) out.push({ type: 'note', text: n })
  return out
}
