// The watch's all-day stress (Zepp `all_day_stress`), as one number per local day for the engine and the pages.
//
// zepp-extract stores the raw answer per UTC day (zepp_raw_extracts, metric 'stress'): items, each with `data`, a JSON
// string of [{ time (ms), value (1-100) }]. A UTC day's samples run 08:00-08:00 in UTC+8, so they are regrouped by the
// local calendar day here, exactly as the phone does (android domain/stress/StressLocalDays.kt): the samples are real
// instants, so the day's UTC offset (daily_context.utc_offset_min, the default 480 when none is known) moves them.
// A day with fewer than MIN_SUMMARY_SAMPLES readings (today, or a day the watch has not synced) has no summary.

export const MIN_SUMMARY_SAMPLES = 12
/** Zepp's own bands: 1-39 relaxed, 40-59 normal, 60-79 medium, 80-100 high. */
export const HIGH_STRESS_FROM = 80

export interface StressSample { time: number; value: number }
export interface WatchStressDay { date: string; mean: number; highShare: number; n: number }

/** The samples inside one raw_body (the extractor's `{ items: [{ data: '[{"time":..,"value":..}]' }] }`). Never throws. */
export function samplesOf(rawBody: unknown): StressSample[] {
  const items = (rawBody as { items?: unknown } | null)?.items
  if (!Array.isArray(items)) return []
  const out: StressSample[] = []
  for (const it of items) {
    const data = (it as { data?: unknown } | null)?.data
    let list: unknown = data
    if (typeof data === 'string') {
      try { list = JSON.parse(data) } catch { continue }
    }
    if (!Array.isArray(list)) continue
    for (const s of list) {
      const t = Number((s as { time?: unknown }).time)
      const v = Number((s as { value?: unknown }).value)
      if (Number.isFinite(t) && Number.isFinite(v) && v >= 1 && v <= 100) out.push({ time: t, value: v })
    }
  }
  return out
}

const dateOf = (ms: number) => new Date(ms).toISOString().slice(0, 10)

/** Per local day: mean (0-100, one decimal), the share of readings in the high band (0-100, one decimal) and the count. Pure. */
export function watchStressDays(samples: StressSample[], offsetMin: (utcDate: string) => number): WatchStressDay[] {
  const seen = new Set<number>()
  const byDay = new Map<string, number[]>()
  for (const s of samples) {
    if (seen.has(s.time)) continue
    seen.add(s.time)
    const local = dateOf(s.time + offsetMin(dateOf(s.time)) * 60_000)
    const list = byDay.get(local)
    if (list) list.push(s.value)
    else byDay.set(local, [s.value])
  }
  const round1 = (x: number) => Math.round(x * 10) / 10
  return [...byDay.entries()]
    .filter(([, v]) => v.length >= MIN_SUMMARY_SAMPLES)
    .map(([date, v]) => ({
      date,
      mean: round1(v.reduce((a, b) => a + b, 0) / v.length),
      highShare: round1((v.filter((x) => x >= HIGH_STRESS_FROM).length * 100) / v.length),
      n: v.length,
    }))
    .sort((a, b) => a.date.localeCompare(b.date))
}

/** The UTC-offset lookup runDerived and the engine share: the newest daily_context offset on or before the date, else 480. */
export function offsetLookup(context: { date: string; utc_offset_min: number | null }[], fallback = 480): (utcDate: string) => number {
  const sorted = context.filter((c) => c.utc_offset_min !== null).sort((a, b) => a.date.localeCompare(b.date))
  return (utcDate) => {
    let best: number | null = null
    for (const c of sorted) { if (c.date <= utcDate) best = Number(c.utc_offset_min); else break }
    return best ?? (sorted.length ? Number(sorted[0].utc_offset_min) : fallback)
  }
}
