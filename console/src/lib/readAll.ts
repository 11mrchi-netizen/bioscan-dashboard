// Every row of a read, page by page (performance audit, Nov 2026). Supabase returns at most 1,000 rows per request,
// whatever .limit() or .range() asks for, so a single read of a growing table silently stops at 1,000 rows. `build`
// returns a fresh query with its filters and a deterministic order (a unique key last); pages are read until a short one.
// Offset paging is fine for these reads: they are of the person's own history, not of rows being written meanwhile.

interface Res { data: unknown[] | null; error: { message: string } | null }
interface Rangeable { range(from: number, to: number): PromiseLike<Res> }

export const PAGE_ROWS = 1000

/** All rows, or the first error (with the rows read before it). Stops after `maxPages` pages. */
export async function readAllSoft<R>(build: () => Rangeable, maxPages = 200): Promise<{ rows: R[]; error: string | null }> {
  const rows: R[] = []
  for (let page = 0; page < maxPages; page++) {
    const r = await build().range(page * PAGE_ROWS, page * PAGE_ROWS + PAGE_ROWS - 1)
    if (r.error) return { rows, error: r.error.message }
    const got = (r.data ?? []) as R[]
    rows.push(...got)
    if (got.length < PAGE_ROWS) return { rows, error: null }
  }
  return { rows, error: `stopped after ${maxPages} pages of ${PAGE_ROWS} rows` }
}

/** All rows; throws `<what>: <message>` on an error. */
export async function readAll<R>(build: () => Rangeable, what: string, maxPages = 200): Promise<R[]> {
  const { rows, error } = await readAllSoft<R>(build, maxPages)
  if (error) throw new Error(`${what}: ${error}`)
  return rows
}
