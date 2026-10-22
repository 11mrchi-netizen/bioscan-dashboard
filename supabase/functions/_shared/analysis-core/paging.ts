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
