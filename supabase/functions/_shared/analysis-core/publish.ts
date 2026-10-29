// Publishing analysis_results rows in batches. Erasable TypeScript; the client is duck-typed like the run* modules.
//
// Each engine used to publish one row at a time (an upsert, then a delete of the key's older copies): about 46 sequential
// round trips for the health engine, ~2.7 s of waiting in the nightly run. Here a batch of rows goes up in one upsert and
// the older copies of the batch's keys go in one delete per kind, so 46 rows take 5 upserts and ~5 deletes.
//
// Same semantics as the one-by-one version: the newest row per (kind, key) wins (a repeated key within the rows is
// collapsed, the later one kept, which is what two sequential upserts would have left), older copies of that key
// (as_of before today, this engine version) are removed after its row is written, and a failed upsert stops the run with
// the rows already published reported.
import { CORE_VERSION } from './version.ts'
import type { Admin } from './runRelationships.ts'

export interface ResultRow {
  kind: string
  key: string
  state: string
  have: number | null
  need: number | null
  payload: unknown
  hash: string
  /** The row's day when it is not `today` (the series-break engine stamps each metric with its own latest day). */
  asOf?: string
}

/** Rows per request. Health concern rows carry a year of daily series (tens of KB each), so a batch stays well under a MB. */
export const PUBLISH_BATCH = 10

export interface PublishOutcome {
  published: { kind: string; key: string; state: string }[]
  error?: string
}

export async function publishResults(
  admin: Admin,
  userId: string,
  today: string,
  rows: ResultRow[],
  now: Date = new Date(),
  engineVersion: string = CORE_VERSION,
  batch: number = PUBLISH_BATCH,
): Promise<PublishOutcome> {
  const latest = new Map<string, ResultRow>()
  for (const r of rows) latest.set(`${r.kind}\u0000${r.key}`, r)
  const unique = [...latest.values()]
  const published: PublishOutcome['published'] = []
  for (let i = 0; i < unique.length; i += batch) {
    const chunk = unique.slice(i, i + batch)
    const { error } = await admin.from('analysis_results').upsert(
      chunk.map((r) => ({
        user_id: userId, kind: r.kind, key: r.key, as_of: r.asOf ?? today, engine_version: engineVersion, inputs_hash: r.hash,
        state: r.state, confidence_have: r.have, confidence_need: r.need, payload: r.payload, calculated_at: now.toISOString(),
      })),
      { onConflict: 'user_id,kind,key,as_of,engine_version' },
    )
    if (error) return { published, error: error.message }
    // Only the newest row per key is kept (the ledger keeps the history); a failed cleanup never fails the run.
    const groups = new Map<string, { kind: string; asOf: string; keys: string[] }>()
    for (const r of chunk) {
      const asOf = r.asOf ?? today
      const g = groups.get(`${r.kind}\u0000${asOf}`) ?? { kind: r.kind, asOf, keys: [] }
      g.keys.push(r.key)
      groups.set(`${r.kind}\u0000${asOf}`, g)
    }
    for (const g of groups.values()) {
      await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', g.kind).in('key', g.keys).eq('engine_version', engineVersion).lt('as_of', g.asOf)
    }
    for (const r of chunk) published.push({ kind: r.kind, key: r.key, state: r.state })
  }
  return { published }
}
