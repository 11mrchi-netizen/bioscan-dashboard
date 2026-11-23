// The numbers behind the metric page (workspaces/Metric.tsx): one loader per kind of ref (lib/metrics.ts). Scores, inputs and
// Me items are what the phone saved and are never recomputed here; a series is read from the contract file's tables.
import { supabase } from './supabase'
import { loadDays } from './seriesQuery'
import { seriesById } from './schema'
import { DOMAINS, type Direction, type DomainKey, formatInputValue, type GroupScore, referenceLine } from './scoreText'
import { domainOfInput, INPUT_HUE, SERIES_HUE, splitMeLabel, type MetricRef } from './metrics'
import { loadMeView } from '../dashboard/meView'
import type { MeChart, MeItem, MeTab, MeTile } from '../dashboard/meView'

export interface MetricPoint { day: string; value: number | null; reference?: number | null; refLine?: string | null; direction?: Direction }

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
  /** Other series drawn in the same Me chart, named so the page can say they are not shown here. */
  alsoInChart?: string[]
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
  }
}

async function loadRing(key: string, from: string, to: string): Promise<MetricData> {
  const r = await supabase.from('score_history').select(`day,${key}`).gte('day', from).lte('day', to).order('day')
  if (r.error) throw new Error(`score_history: ${r.error.message}`)
  const rows = (r.data ?? []) as unknown as Record<string, number | string | null>[]
  return {
    label: key.toUpperCase(), unit: '', hue: ringHue(key), usual: false, fmt: (v) => String(Math.round(v)),
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
    label, unit: '', usual: true, fmt: (v) => formatInputValue(key, v), points,
    hue: `var(--${INPUT_HUE[key] ?? 'm-weight-ema'})`, // MetricInk.kt's own fallback
    source: 'Value per morning against your usual, as the phone scored it (kept from 6 Oct 2026).',
  }
}

async function loadSeries(id: string, from: string, to: string, signal?: AbortSignal): Promise<MetricData> {
  const s = seriesById(id)
  if (!s) throw new Error(`Unknown series "${id}"`)
  const days = await loadDays(s, { start: from, end: to }, signal)
  return {
    label: s.label, unit: s.unit, hue: `var(--${SERIES_HUE[id] ?? 'ft-platinum'})`, usual: false, fmt: formatSeriesValue,
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
      label: tile.label, unit: tile.unit ?? '', hue: tile.hue ? `var(--${tile.hue})` : 'var(--ft-platinum)', usual: false, info: tile.info,
      fmt: (v) => formatSeriesValue(v), points: (tile.series ?? []).filter((p) => p[0] >= from).map((p) => ({ day: p[0], value: p[1] })),
      source: `Points the phone sent with this tile on ${tab}.`,
    }
  }
  if (chart && chart.series[0]) {
    const first = chart.series[0]
    return {
      label: chart.label, unit: chart.unit ?? '', hue: `var(--${first.hue})`, usual: false,
      fmt: (v) => v.toFixed(chart.decimals), points: first.points.filter((p) => p[0] >= from).map((p) => ({ day: p[0], value: p[1] })),
      source: `Points the phone sent with this chart on ${tab}.`, info: chart.caption,
      alsoInChart: chart.series.slice(1).map((s) => s.name),
    }
  }
  throw new Error(`"${item}" is no longer on ${tab}.`)
}


