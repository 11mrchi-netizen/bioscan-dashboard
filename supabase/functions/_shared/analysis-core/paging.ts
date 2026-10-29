// PostgREST caps a response (Supabase's default is 1,000 rows), so every reader that can exceed that pages through
// with .range() until a short page comes back. Shared by the frame loader and the series-break publisher.
export async function fetchAll<T>(page: (from: number, to: number) => PromiseLike<{ data: T[] | null; error: { message: string } | null }>, pageSize = 1000): Promise<{ rows: T[]; error?: string }> {
  const rows: T[] = []
  for (let from = 0; ; from += pageSize) {
    const { data, error } = await page(from, from + pageSize - 1)
    if (error) return { rows, error: error.message }
    rows.push(...(data ?? []))
    if ((data ?? []).length < pageSize) return { rows }
  }
}

/**
 * Like fetchAll, but after the first page the following pages are requested together, `wave` at a time, instead of one after
 * another; the rows come back in page order, so the result is the same as fetchAll's. For a large, ordered read (the series-break
 * engine's 4,000 provenance rows were five sequential round trips). The page query must be ordered and stable.
 */
export async function fetchAllWaves<T>(page: (from: number, to: number) => PromiseLike<{ data: T[] | null; error: { message: string } | null }>, pageSize = 1000, wave = 4): Promise<{ rows: T[]; error?: string }> {
  const rows: T[] = []
  const first = await page(0, pageSize - 1)
  if (first.error) return { rows, error: first.error.message }
  rows.push(...(first.data ?? []))
  if ((first.data ?? []).length < pageSize) return { rows }
  for (let start = 1; ; start += wave) {
    const results = await Promise.all(Array.from({ length: wave }, (_, k) => page((start + k) * pageSize, (start + k + 1) * pageSize - 1)))
    for (const r of results) {
      if (r.error) return { rows, error: r.error.message }
      rows.push(...(r.data ?? []))
      if ((r.data ?? []).length < pageSize) return { rows }
    }
  }
}
