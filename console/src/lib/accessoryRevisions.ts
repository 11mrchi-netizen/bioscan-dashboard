// What changing a block's accessory work does to the sessions still planned: each strength session's accessory lines are replaced (after its last lift), a session's own
// lines are never touched, and a session whose prescription would not change is left alone. The sessions' prescriptions are immutable in the database (planned_sessions_immutable:
// "create a new revision"), so each change goes in as a new revision (the revise_planned_session function), never as an update in place.
import { type Accessory, accessoriesFor, accessoryItemsFor, type GenEquipment, liftJson, type MaxEntry } from '../../../supabase/functions/_shared/training-core/index.ts'

export interface AccessorySession { id: number; title: string; work_kind: string; prescription: unknown }

/** The sessions whose prescription changes, with the new one. `sessionIdOf` maps a session's title to its id in the module ("<module title>: <label>"). */
export function accessoryRevisions(
  rows: AccessorySession[], sessionIdOf: (title: string) => string | null | undefined, accessories: Record<string, Accessory[]>, eq: GenEquipment, maxes: (name: string) => MaxEntry | null | undefined,
): { id: number; after: Record<string, unknown>[] }[] {
  const out: { id: number; after: Record<string, unknown>[] }[] = []
  for (const row of rows) {
    const items = accessoryItemsFor(accessoriesFor({ accessories }, sessionIdOf(row.title) ?? null), row.work_kind === 'deload' ? 'deload' : 'normal', eq, maxes)
    const before = Array.isArray(row.prescription) ? (row.prescription as Record<string, unknown>[]) : []
    const kept = before.filter((e) => !(e?.type === 'lift' && e?.role === 'accessory'))
    let at = 0
    kept.forEach((e, i) => { if (e?.type === 'lift') at = i + 1 })
    const after = [...kept.slice(0, at), ...items.map(liftJson), ...kept.slice(at)]
    if (JSON.stringify(after) === JSON.stringify(before)) continue
    out.push({ id: row.id, after })
  }
  return out
}
