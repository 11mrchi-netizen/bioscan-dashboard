// I/O for the integrity engine (analysis hub phase C), written against the same duck-typed Supabase client as the other
// runners so it is tested in Node with a fake and runs unchanged in analysis-run. Nightly, per user: every stream of
// metric-series.json over the last INTEGRITY_WINDOW_DAYS local days, each stream's first and newest reading ever, and the
// wear signal (days with minute heart rate), into one `integrity` row of analysis_results. The console computes the same
// report live for any range with the same engine (integrity.ts).
import { fetchAll } from './paging.ts'
import { assessIntegrity, INTEGRITY_VERSION, type IntegrityReport, type IntegrityRow, type StreamInput, type WearSignal } from './integrity.ts'
import { addDays, localDateOf, type SeriesContract, type SeriesFile } from './seriesContract.ts'
import { CORE_VERSION } from './version.ts'

export const INTEGRITY_WINDOW_DAYS = 28
export const INTEGRITY_KEY = 'report'

// deno-lint-ignore no-explicit-any
export type Admin = any

export function localToday(now: Date, offsetMin: number): string {
  return new Date(now.getTime() + offsetMin * 60_000).toISOString().slice(0, 10)
}

function exact(s: SeriesContract): boolean {
  return s.time.kind === 'local_date' && !(s.time.exceptions ?? []).length
}

// deno-lint-ignore no-explicit-any
function withFilters(q: any, s: SeriesContract): any {
  for (const [c, vals] of Object.entries(s.filters ?? {})) q = q.in(c, vals)
  return q
}

function localOf(s: SeriesContract, r: Record<string, unknown>, defaultOffset: number): string | null {
  const offset = s.time.offsetColumn ? ((r[s.time.offsetColumn] as number | null) ?? null) : null
  return localDateOf(r[s.time.column] as string | null, s.time, offset, defaultOffset)?.date ?? null
}

async function streamInput(admin: Admin, userId: string, s: SeriesContract, start: string, end: string, defaultOffset: number): Promise<StreamInput> {
  const t = s.time.column
  const cols = [...new Set(['id', t, s.column, ...(s.time.offsetColumn ? [s.time.offsetColumn] : []), ...(s.hasSource ? ['source'] : [])])].join(', ')
  const from = exact(s) ? start : `${addDays(start, -1)}T00:00:00`
  const to = exact(s) ? end : `${addDays(end, 1)}T23:59:59.999`
  const { rows: raw, error } = await fetchAll<Record<string, unknown>>((a, b) =>
    withFilters(admin.from(s.table).select(cols).eq('user_id', userId).gte(t, from).lte(t, to), s).order(t).order('id').range(a, b))
  if (error) throw new Error(`${s.id}: ${error}`)
  const rows: IntegrityRow[] = []
  for (const r of raw) {
    const date = localOf(s, r, defaultOffset)
    if (!date || date < start || date > end) continue
    const v = r[s.column]
    const n = v === null || v === undefined || v === '' ? null : Number(v)
    rows.push({ date, value: n !== null && Number.isFinite(n) ? n : null, source: s.hasSource ? ((r.source as string | null) ?? null) : null, id: r.id as string | number })
  }
  // First and newest reading ever, with a value in this column. The first matters only where a value is due; the newest
  // comes from the window's own rows when it has any.
  const edge = async (ascending: boolean): Promise<string | null> => {
    const sel = [t, ...(s.time.offsetColumn ? [s.time.offsetColumn] : [])].join(', ')
    const { data, error: e } = await withFilters(admin.from(s.table).select(sel).eq('user_id', userId).not(s.column, 'is', null), s)
      .order(t, { ascending }).limit(1)
    if (e) throw new Error(`${s.id}: ${e.message}`)
    return data?.[0] ? localOf(s, data[0], defaultOffset) : null
  }
  const inWindow = rows.filter((r) => r.value !== null).map((r) => r.date).sort()
  const [firstSeen, lastSeen] = await Promise.all([
    s.expect === 'none' ? Promise.resolve(inWindow[0] ?? null) : edge(true),
    inWindow.length && inWindow[inWindow.length - 1] === end ? Promise.resolve(end) : edge(false),
  ])
  return { series: s, rows, firstSeen, lastSeen }
}

/** Days with minute heart rate (the band was worn), from the first such day on. */
export async function loadWear(admin: Admin, userId: string, start: string, end: string): Promise<WearSignal | null> {
  const { data: first, error: e1 } = await admin.from('wearable_daily').select('date').eq('user_id', userId).not('hr_timeseries', 'is', null).order('date', { ascending: true }).limit(1)
  if (e1) throw new Error(`wear: ${e1.message}`)
  if (!first?.[0]) return null
  const { rows, error } = await fetchAll<{ date: string }>((a, b) =>
    admin.from('wearable_daily').select('date').eq('user_id', userId).not('hr_timeseries', 'is', null).gte('date', start).lte('date', end).order('date').range(a, b))
  if (error) throw new Error(`wear: ${error}`)
  return { days: new Set(rows.map((r) => String(r.date))), since: String(first[0].date) }
}

/** The report for [range.start, range.end] (default: the last INTEGRITY_WINDOW_DAYS days to today). */
export async function buildIntegrity(admin: Admin, userId: string, file: SeriesFile, now: Date, range?: { start: string; end: string }): Promise<IntegrityReport> {
  const today = localToday(now, file.defaultUtcOffsetMin)
  const end = range ? (range.end > today ? today : range.end) : today
  const start = range?.start ?? addDays(today, -(INTEGRITY_WINDOW_DAYS - 1))
  const [wear, ...streams] = await Promise.all([
    loadWear(admin, userId, start, end),
    ...file.series.map((s) => streamInput(admin, userId, s, start, end, file.defaultUtcOffsetMin)),
  ])
  return assessIntegrity({ start, end, today, streams: streams as StreamInput[], wear: wear as WearSignal | null })
}

export async function runIntegrity(admin: Admin, userId: string, file: SeriesFile, now: Date = new Date()): Promise<{ userId: string; totals?: IntegrityReport['totals']; error?: string }> {
  const report = await buildIntegrity(admin, userId, file, now)
  const state = report.totals.critical ? 'Critical' : report.totals.warning ? 'Warning' : 'Clear'
  const judged = report.streams.filter((s) => s.coverage !== null).length
  const { error } = await admin.from('analysis_results').upsert(
    {
      user_id: userId,
      kind: 'integrity',
      key: INTEGRITY_KEY,
      as_of: report.today,
      engine_version: `${CORE_VERSION}+integrity.${INTEGRITY_VERSION}`,
      inputs_hash: null,
      state,
      confidence_have: judged,
      confidence_need: report.streams.length,
      payload: report,
      calculated_at: now.toISOString(),
    },
    { onConflict: 'user_id,kind,key,as_of,engine_version' },
  )
  if (error) return { userId, error: error.message }
  // One row per night is kept (the history of what needed attention); nothing is deleted.
  return { userId, totals: report.totals }
}
