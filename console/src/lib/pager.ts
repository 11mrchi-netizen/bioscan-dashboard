// Analysis hub phase B: keyset paging. PostgREST stops at its row cap (1,000 on Supabase); offset paging also skips or
// repeats a row when one is written between two pages. Pages here are ordered by (time column, id) and each next page
// starts after the last row's (time, id), so a long range always comes back whole and in order.

export interface Cursor {
  time: string
  id: string | number
}

export interface Page<R> {
  rows: R[]
  error: string | null
}

// A PostgREST value in double quotes, so timestamps with ':' and '+' survive the filter grammar.
function quote(v: string | number): string {
  return `"${String(v).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`
}

/** The `or` filter for rows strictly after the cursor in (column, id) order, ascending or descending. */
export function keysetFilter(column: string, after: Cursor, dir: 'asc' | 'desc' = 'asc'): string {
  const op = dir === 'asc' ? 'gt' : 'lt'
  return `${column}.${op}.${quote(after.time)},and(${column}.eq.${quote(after.time)},id.${op}.${quote(after.id)})`
}

/**
 * Every page until a short one comes back. Throws on an error, and after `maxPages` full pages rather than returning a
 * silently cut result.
 */
export async function readAllPages<R>(
  fetchPage: (after: Cursor | null, size: number) => Promise<Page<R>>,
  cursorOf: (row: R) => Cursor,
  pageSize = 1000,
  maxPages = 500,
): Promise<{ rows: R[]; requests: number }> {
  const rows: R[] = []
  let after: Cursor | null = null
  for (let requests = 1; requests <= maxPages; requests++) {
    const page = await fetchPage(after, pageSize)
    if (page.error) throw new Error(page.error)
    rows.push(...page.rows)
    if (page.rows.length < pageSize) return { rows, requests }
    after = cursorOf(page.rows[page.rows.length - 1])
  }
  throw new Error(`stopped after ${maxPages} pages of ${pageSize} rows; narrow the range`)
}
