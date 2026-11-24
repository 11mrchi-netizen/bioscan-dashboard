// What to do with a plan given the latest stored row (the rules of fuelPlansStore.ts, apart from the database).

export interface Row { engine_version: string; inputs_hash: string; plan: Record<string, unknown> }

/** The decision, apart from the database: what to do given the latest stored row. */
export function decide(last: Row | null, hash: string): 'unchanged' | 'insert' | 'phone' {
  if (last && last.inputs_hash === hash) return 'unchanged'
  if (!last) return 'insert'
  return last.plan?.by === 'web' ? 'insert' : 'phone'
}
