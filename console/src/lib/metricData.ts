// The numbers behind the metric page (workspaces/Metric.tsx): one loader per kind of ref (lib/metrics.ts). Scores, inputs and
// Me items are what the phone saved and are never recomputed here; a series is read from the contract file's tables.
import { supabase } from './supabase'
import { loadDays } from './seriesQuery'
import { seriesById } from './schema'
import { DOMAINS, type Direction, type DomainKey, formatInputValue, type GroupScore, referenceLine } from './scoreText'
import { domainOfInput, INPUT_HUE, SERIES_HUE, splitMeLabel, type MetricRef } from './metrics'
import { loadMeView } from '../dashboard/meView'
import type { MeChart, MeItem, MeTab, MeTile } from '../dashboard/meView'
import type { EventRef } from '../types/analysis'
import { markerHue, rangeLine, sweatRate } from './labTests'

export interface MetricPoint {
  day: string; value: number | null; reference?: number | null; refLine?: string | null; direction?: Direction
  /** The record behind the point, when it is one record (a blood result): clicking the point opens it in the Inspector. */
  record?: EventRef
}

export interface MetricData {
  label: string
  unit: string
  /** A CSS colour or `var(--token)`: the metric's own hue (design contract: ink = metric). */
  hue: string
  points: MetricPoint[]
  fmt: (v: number) => string
  /** One line on where the numbers come from and since when. */
  source: string
  /** The words of the metric's "what is it?", when the phone or the contract file has them. */
  info?: string
  /** The points carry "your usual" per day. */
  usual: boolean
  /** Metrics with the same axis share a panel when compared: a unit, 'score' for rings, a key of its own for an input. */
  axis: string
  /** What a point's day means: the morning a score is published on, or the day a record falls on (the phone keys them differently). */
  dating: 'morning' | 'day'
  /** Other series drawn in the same Me chart, named so the page can say they are not shown here. */
  alsoInChart?: string[]
  /** A fixed range drawn behind the line (a blood marker's reference range, of its newest draw that has one). */
  band?: { low: number | null; high: number | null; label: string }
}

/** A value of a series: whole numbers from 100 up, one decimal below, no trailing zero. */
export function formatSeriesValue(v: number): string {
  if (!Number.isFinite(v)) return '–'
  if (Math.abs(v) >= 100) return String(Math.round(v))
  return String(Number(v.toFixed(1)))
}

const ringHue = (key: string): string => (key === 'charge' ? 'var(--ft-charge-b)' : DOMAINS.find((d) => d.key.toLowerCase() === key)?.hue ?? 'var(--ft-platinum)')

export async function loadMetric(ref: MetricRef, from: string, to: string, signal?: AbortSignal): Promise<MetricData> {
  switch (ref.kind) {
    case 'ring': return loadRing(ref.key, from, to)
    case 'input': return loadInput(ref.key, from, to)
    case 'series': return loadSeries(ref.key, from, to, signal)
    case 'me': return loadMe(ref.tab as MeTab, ref.label, from)
    case 'lab': return loadLab(ref.key, from, to)
    case 'test': return ref.key === 'sweat_rate' ? loadSweat(from, to) : loadGrip(ref.key.split(':')[1] ?? null, from, to)
  }
}

async function loadRing(key: string, from: string, to: string): Promise<MetricData> {
  const r = await supabase.from('score_history').select(`day,${key}`).gte('day', from).lte('day', to).order('day')
  if (r.error) throw new Error(`score_history: ${r.error.message}`)
  const rows = (r.data ?? []) as unknown as Record<string, number | string | null>[]
  return {
    label: key.toUpperCase(), unit: '', hue: ringHue(key), usual: false, axis: 'score', dating: 'morning', fmt: (v) => String(Math.round(v)),
    points: rows.map((x) => ({ day: x.day as string, value: x[key] as number | null })),
    source: 'Score per morning, as the phone saved it (from Mar 2026).',
  }
}

async function loadInput(key: string, from: string, to: string): Promise<MetricData> {
  const domain = domainOfInput(key) as DomainKey | null
  if (!domain) throw new Error(`Unknown input "${key}"`)
  const r = await supabase.from('score_snapshots').select(`day,groups:scores->domains->${domain}->raw->groups`).gte('day', from).lte('day', to).order('day')
  if (r.error) throw new Error(`score_snapshots: ${r.error.message}`)
  let label = key
  const points = ((r.data ?? []) as { day: string; groups: GroupScore[] | null }[]).map((x) => {
    const i = (x.groups ?? []).flatMap((g) => g.inputs).find((y) => y.spec.key === key)
    if (i) label = i.spec.label
    return { day: x.day, value: i?.value ?? null, reference: i?.reference ?? null, refLine: i ? referenceLine(i) : null, direction: i?.direction }
  })
  return {
    label, unit: '', usual: true, axis: `input ${key}`, dating: 'morning', fmt: (v) => formatInputValue(key, v), points,
    hue: `var(--${INPUT_HUE[key] ?? 'm-weight-ema'})`, // MetricInk.kt's own fallback
    source: 'Value per morning against your usual, as the phone scored it (kept from 6 Oct 2026).',
  }
}

async function loadSeries(id: string, from: string, to: string, signal?: AbortSignal): Promise<MetricData> {
  const s = seriesById(id)
  if (!s) throw new Error(`Unknown series "${id}"`)
  const days = await loadDays(s, { start: from, end: to }, signal)
  return {
    label: s.label, unit: s.unit, hue: `var(--${SERIES_HUE[id] ?? 'ft-platinum'})`, usual: false, axis: s.unit, dating: 'day', fmt: formatSeriesValue,
    points: days.map((d) => ({ day: d.date, value: d.value })),
    source: `One value per day from ${s.table}, the whole history.`,
    info: (s as { note?: string; description?: string }).description ?? (s as { note?: string }).note,
  }
}

async function loadMe(tab: MeTab, label: string, from: string): Promise<MetricData> {
  const row = await loadMeView(tab)
  if (!row) throw new Error(`The phone has not sent ${tab} yet.`)
  // Labels repeat within a tab: the ref carries the section title; an older ref with the label alone takes the first match.
  const { section, item } = splitMeLabel(label)
  const items: MeItem[] = row.view.sections.filter((s) => section == null || s.title === section).flatMap((s) => s.items)
  const tile = items.find((i): i is MeTile => i.kind === 'tile' && i.label === item && (i.series?.length ?? 0) > 1)
  const chart = items.find((i): i is MeChart => i.kind === 'chart' && i.label === item)
  if (tile) {
    return {
      label: tile.label, unit: tile.unit ?? '', hue: tile.hue ? `var(--${tile.hue})` : 'var(--ft-platinum)', usual: false, axis: tile.unit || `me ${tile.label}`, dating: 'day', info: tile.info,
      fmt: (v) => formatSeriesValue(v), points: (tile.series ?? []).filter((p) => p[0] >= from).map((p) => ({ day: p[0], value: p[1] })),
      source: `Points the phone sent with this tile on ${tab}.`,
    }
  }
  if (chart && chart.series[0]) {
    const first = chart.series[0]
    return {
      label: chart.label, unit: chart.unit ?? '', hue: `var(--${first.hue})`, usual: false, axis: chart.unit || `me ${chart.label}`, dating: 'day',
      fmt: (v) => v.toFixed(chart.decimals), points: first.points.filter((p) => p[0] >= from).map((p) => ({ day: p[0], value: p[1] })),
      source: `Points the phone sent with this chart on ${tab}.`, info: chart.caption,
      alsoInChart: chart.series.slice(1).map((s) => s.name),
    }
  }
  throw new Error(`"${item}" is no longer on ${tab}.`)
}



interface LabRow { id: number; value: number | null; unit: string | null; ref_low: number | null; ref_high: number | null; flag: string | null; lab_draws: { draw_date: string } | { draw_date: string }[] | null }

/** A blood marker: one point per draw (two draws on one day keep the later result), each with its line against the lab's range. */
async function loadLab(marker: string, from: string, to: string): Promise<MetricData> {
  const r = await supabase.from('lab_results').select('id,value,unit,ref_low,ref_high,flag,lab_draws!inner(draw_date)')
    .eq('marker_name', marker).gte('lab_draws.draw_date', from).lte('lab_draws.draw_date', to)
  if (r.error) throw new Error(`lab_results: ${r.error.message}`)
  const dateOf = (x: LabRow) => (Array.isArray(x.lab_draws) ? x.lab_draws[0]?.draw_date : x.lab_draws?.draw_date) ?? ''
  const rows = ((r.data ?? []) as unknown as LabRow[]).filter((x) => dateOf(x)).sort((a, b) => dateOf(a).localeCompare(dateOf(b)) || a.id - b.id)
  const byDay = new Map<string, LabRow>()
  for (const x of rows) byDay.set(dateOf(x), x)
  const kept = [...byDay.values()]
  const unit = [...kept].reverse().find((x) => x.unit)?.unit ?? ''
  const ranged = [...kept].reverse().find((x) => x.ref_low != null || x.ref_high != null)
  return {
    label: marker, unit, hue: `var(--${markerHue(marker)})`, usual: false, axis: unit || `lab ${marker}`, dating: 'day', fmt: formatSeriesValue,
    points: kept.map((x) => ({ day: dateOf(x), value: x.value == null ? null : Number(x.value), refLine: rangeLine({ ...x, value: x.value == null ? null : Number(x.value) }), record: { kind: 'lab_result', id: String(x.id) } })),
    band: ranged ? { low: ranged.ref_low == null ? null : Number(ranged.ref_low), high: ranged.ref_high == null ? null : Number(ranged.ref_high), label: 'Reference range' } : undefined,
    source: 'One value per blood draw from lab_results, against the range the lab gave. Results written as text are not drawn.',
  }
}

/** Grip strength: the best squeeze of the day, of one hand or of either. occurred_at is wall clock: its digits are the local day. */
async function loadGrip(hand: string | null, from: string, to: string): Promise<MetricData> {
  let q = supabase.from('grip_strength_log').select('occurred_at,hand,kg').gte('occurred_at', `${from}T00:00:00Z`).lte('occurred_at', `${to}T23:59:59.999Z`)
  if (hand) q = q.ilike('hand', hand)
  const r = await q
  if (r.error) throw new Error(`grip_strength_log: ${r.error.message}`)
  const best = new Map<string, number>()
  for (const x of (r.data ?? []) as { occurred_at: string; kg: number | null }[]) {
    if (x.kg == null || !Number.isFinite(Number(x.kg))) continue
    const day = x.occurred_at.slice(0, 10)
    best.set(day, Math.max(best.get(day) ?? -Infinity, Number(x.kg)))
  }
  return {
    label: hand ? `Grip strength, ${hand} hand` : 'Grip strength', unit: 'kg', hue: 'var(--m-grip)', usual: false, axis: 'kg', dating: 'day', fmt: formatSeriesValue,
    points: [...best.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([day, value]) => ({ day, value })),
    source: `The best grip of the day${hand ? ` with the ${hand} hand` : ', either hand'}, from the tests taken on the phone (grip_strength_log).`,
  }
}

/** Sweat rate of each sweat test, by the phone's rule; a test it cannot read (impossible numbers) is a gap. */
async function loadSweat(from: string, to: string): Promise<MetricData> {
  const r = await supabase.from('sweat_tests').select('tested_on,duration_min,weight_before_kg,weight_after_kg,fluid_in_ml,urine_ml').gte('tested_on', from).lte('tested_on', to).order('tested_on')
  if (r.error) throw new Error(`sweat_tests: ${r.error.message}`)
  const byDay = new Map<string, number | null>()
  for (const t of (r.data ?? []) as ({ tested_on: string } & Parameters<typeof sweatRate>[0])[]) byDay.set(t.tested_on, sweatRate(t) ?? byDay.get(t.tested_on) ?? null)
  return {
    label: 'Sweat rate', unit: 'ml/h', hue: 'var(--m-sweat-rate)', usual: false, axis: 'ml/h', dating: 'day', fmt: (v) => String(Math.round(v)),
    points: [...byDay.entries()].map(([day, value]) => ({ day, value })),
    source: 'Sweat rate of each sweat test (weight lost, plus what was drunk, less urine, per hour), as the phone works it out.',
  }
}
