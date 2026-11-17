// Port of generate/PlannerRequirements.kt: what the setup flow must ask, derived from a definition.

import type { ConditioningProtocolDef, SlotDef, StrengthModuleDef, TemplateDef } from './definitions.ts'

export interface VariableSpec { name: string; kind: string; options: string[]; note: string | null }

const VAR_REF = /^(strength|se|cond):\$([A-Za-z0-9_]+)$/

export function templateVariables(t: TemplateDef): VariableSpec[] {
  const declared = (t.variables ?? {}) as Record<string, unknown>
  const used: string[] = []
  for (const w of t.grid) for (const d of w.days) for (const c of d.cells) {
    const m = VAR_REF.exec(c.ref)
    if (m && !used.includes(m[2])) used.push(m[2])
  }
  return used.map((name) => {
    const spec = declared[name] as Record<string, unknown> | undefined
    return {
      name,
      kind: typeof spec?.kind === 'string' ? spec.kind : 'conditioning_session',
      options: Array.isArray(spec?.choose_from) ? (spec!.choose_from as unknown[]).map(String) : [],
      note: typeof spec?.note === 'string' ? spec.note : null,
    }
  })
}

const before = (s: string, ch: string) => (s.includes(ch) ? s.slice(0, s.indexOf(ch)) : s)
const after = (s: string, ch: string) => (s.includes(ch) ? s.slice(s.indexOf(ch) + 1) : '')

export function templateModuleKeys(t: TemplateDef, variables: Record<string, string>): string[] {
  const keys: string[] = []
  for (const w of t.grid) for (const d of w.days) for (const c of d.cells) {
    const kind = before(c.ref, ':')
    if (kind !== 'strength' && kind !== 'se') continue
    const target = after(c.ref, ':')
    const key = target.startsWith('$') ? variables[target.slice(1)] : target
    if (key && key.trim() !== '' && !keys.includes(key)) keys.push(key)
  }
  return keys
}

export function templateDayPositions(t: TemplateDef): number[] {
  const s = new Set<number>()
  for (const w of t.grid) for (const d of w.days) if (d.cells.some((c) => c.ref !== 'rest')) s.add(d.day)
  return [...s].sort((a, b) => a - b)
}

/** Every slot the module's sessions use, optional ones included. */
export function slotsToChoose(m: StrengthModuleDef): SlotDef[] {
  const used = new Set<string>([...m.sessions.flatMap((s) => s.slots), ...m.variants.flatMap((v) => v.weeks.flatMap((w) => w.sessions.flatMap((s) => s.items.map((i) => i.slot))))])
  return m.slots.filter((s) => used.has(s.id))
}

export interface StrengthGroup { id: string; label: string; defaultKey: string }
export const STRENGTH_GROUPS: StrengthGroup[] = [
  { id: 'operator', label: 'OPERATOR', defaultKey: 'tb3.operator' },
  { id: 'zulu', label: 'ZULU', defaultKey: 'tb3.zulu' },
  { id: 'fighter', label: 'FIGHTER', defaultKey: 'tb3.fighter' },
  { id: 'mass', label: 'MASS', defaultKey: 'mass.mt' },
  { id: 'green', label: 'GREEN', defaultKey: 'green.op_pro' },
  { id: 'more', label: 'MORE', defaultKey: 'tb3.breacher' },
]

export function strengthGroupOf(m: StrengthModuleDef): string {
  const book = before(m.key, '.')
  if (book === 'mass') return 'mass'
  if (book === 'green') return 'green'
  if (book === 'tb3' && m.family === 'operator') return 'operator'
  if (book === 'tb3' && m.family === 'zulu') return 'zulu'
  if (book === 'tb3' && m.family === 'fighter') return 'fighter'
  return 'more'
}

export function versionsOf(group: StrengthGroup, modules: StrengthModuleDef[]): StrengthModuleDef[] {
  return modules.filter((m) => strengthGroupOf(m) === group.id)
    .sort((a, b) => Number(a.key !== group.defaultKey) - Number(b.key !== group.defaultKey) || a.title.localeCompare(b.title))
}

export const isMainProtocol = (p: ConditioningProtocolDef): boolean =>
  p.key.startsWith('tb3.polarized_') || p.key.startsWith('mass.') || p.key === 'tb3.work_capacity'
