// The score screens' words and number formats, ported one to one from the phone (domain/score/ScorePresentation.kt), so
// the web Today says what the phone's TODAY said. Reads the scores the phone kept (score_snapshots.scores); computes no score.

export type DomainKey = 'SLEEP' | 'RECOVERY' | 'LOAD' | 'FUEL' | 'BALANCE'
export type Direction = 'ABOVE' | 'NORMAL' | 'BELOW' | 'ON_TARGET' | 'SHORT' | 'OVER'

export interface InputKindJson { type: string; log?: boolean; target?: number; limit?: number; lo?: number; hi?: number }
export interface InputSpec { key: string; label: string; weight: number; critical?: boolean; kind?: InputKindJson }
export interface InputScore { spec: InputSpec; value: number; score: number; z?: number | null; reference?: number | null; spread?: number | null; direction: Direction }
export interface GroupScore { group: { key: string; label: string; weight: number }; score: number | null; coverage: number; inputs: InputScore[] }
export interface DomainScore { key: DomainKey; score: number | null; previous?: number | null; calibrating?: boolean; raw: { groups: GroupScore[] } }
export interface ChargeScore {
  score: number | null; previous?: number | null; percentile?: number | null; rankedAgainst?: number | null; record?: boolean
  calibrating?: boolean; cap?: { reason: string; ceiling?: number } | null; drivers: { key: DomainKey; points: number }[]
}
export interface DayScores { day: string; charge: ChargeScore; domains: Partial<Record<DomainKey, DomainScore>>; ansState?: string; loadDirection?: string }

export const DOMAINS: { key: DomainKey; label: string; upside: boolean; hue: string }[] = [
  { key: 'RECOVERY', label: 'RECOVERY', upside: true, hue: 'var(--ft-domain-cardio)' },
  { key: 'SLEEP', label: 'SLEEP', upside: true, hue: 'var(--ft-domain-sleep)' },
  { key: 'LOAD', label: 'LOAD', upside: false, hue: 'var(--ft-domain-load)' },
  { key: 'FUEL', label: 'FUEL', upside: false, hue: 'var(--ft-domain-fuel)' },
  { key: 'BALANCE', label: 'BALANCE', upside: true, hue: 'var(--ft-domain-balance)' },
]

const TYPICAL = 80
const TOP_OF_NORMAL = 100

/** Kotlin's roundToInt: halves round up (toward +infinity). */
const r = (x: number): number => Math.floor(x + 0.5)
const pad = (n: number): string => String(n).padStart(2, '0')
const one = (v: number): string => v.toFixed(1)
function hm(hours: number): string { const t = r(hours * 60); return `${Math.trunc(t / 60)}h${pad(t % 60)}` }
const minutes = (min: number): string => (Math.abs(min) >= 90 ? hm(min / 60) : `${r(min)} min`)
function clockFromNoon(min: number): string { const m = (((r(min) + 720) % 1440) + 1440) % 1440; return `${pad(Math.trunc(m / 60))}:${pad(m % 60)}` }
function clock(minOfDay: number): string { const m = ((r(minOfDay) % 1440) + 1440) % 1440; return `${pad(Math.trunc(m / 60))}:${pad(m % 60)}` }
const pct = (ratio: number): string => `${r(ratio * 100)} %`

const RATIO_KEYS = new Set(['fuel.logged_share', 'fuel.energy_ratio', 'fuel.protein_ratio', 'fuel.carb_ratio', 'fuel.water_ratio', 'load.plan_done_7d'])

/** One input's value as the score screens show it ("8h05", "58 ms", "96 %", "03:10"). */
export function formatInputValue(key: string, value: number): string {
  switch (key) {
    case 'sleep.hours': return hm(value)
    case 'sleep.debt_7d': return value < 0.05 ? 'none' : hm(value)
    case 'sleep.regularity_min': return `± ${r(value)} min`
    case 'sleep.midpoint_min': return clockFromNoon(value)
    case 'sleep.deep_rem_min': return minutes(value)
    case 'sleep.respiratory': return `${one(value)}/min`
    case 'recovery.hrv': return `${r(value)} ms`
    case 'recovery.rhr': return `${r(value)} bpm`
    case 'recovery.night_stress': case 'ans.wind_down': return `${r(value)}`
    case 'recovery.soreness': case 'recovery.energy': case 'recovery.morning_erection': case 'recovery.arousal': case 'mind.stress': case 'mind.mood':
      return `${r(value)}/10`
    case 'load.tsb': return `${r(value)}`
    case 'load.days_without_rest': return `${r(value)} d`
    case 'load.monotony': return one(value)
    case 'load.plan_done_7d': case 'fuel.logged_share': case 'fuel.energy_ratio': case 'fuel.protein_ratio': case 'fuel.carb_ratio': case 'fuel.water_ratio':
      return pct(value)
    case 'fuel.drinks': return value === Math.round(value) ? `${r(value)}` : one(value)
    case 'fuel.late_caffeine': return `${r(value)} mg`
    case 'rhythm.late_screen': return `${r(value)} min`
    case 'gut.symptoms': return value < 0.05 ? 'none' : one(value)
    case 'gut.timing_min': return clock(value)
    case 'gut.timing_spread_min': case 'rhythm.meal_timing_spread_min': return `± ${r(value)} min`
    case 'gut.frequency': return `${r(value)}`
    case 'gut.form_3d': return `type ${one(value)}`
    case 'gut.session': return `${r(value)}/10`
    case 'ans.stressed_pct': case 'ans.relaxed_pct': case 'ans.hr_dip_pct': return `${r(value)} %`
    case 'ans.hrv_cv_7d': return `${one(value)} %`
    default: return one(value)
  }
}

/** The value rounded the way it was judged: short of a target never shows as the target, over a limit never as the limit. */
export function formatJudgedValue(i: InputScore): string {
  let v = i.value
  if (RATIO_KEYS.has(i.spec.key)) {
    if (i.direction === 'SHORT') v = Math.floor(v * 100) / 100
    else if (i.direction === 'OVER') v = Math.ceil(v * 100) / 100
  }
  return formatInputValue(i.spec.key, v)
}

export function directionWords(d: Direction): string {
  return { ABOVE: 'ABOVE NORMAL', NORMAL: 'NORMAL', BELOW: 'BELOW NORMAL', ON_TARGET: 'ON TARGET', SHORT: 'SHORT', OVER: 'OVER' }[d]
}

export function directionGlyph(d: Direction): string {
  return { ABOVE: '▲', NORMAL: '●', BELOW: '▼', ON_TARGET: '✓', SHORT: '↓', OVER: '↑' }[d]
}

const kindName = (k?: InputKindJson): string => (k?.type ?? '').split('.').pop() ?? ''

/** "your normal 44 ms – 52 ms" or "target at least 100 %": what the value was compared with. */
export function referenceLine(i: InputScore): string | null {
  const ref = i.reference
  if (ref == null) return null
  const key = i.spec.key
  const k = i.spec.kind
  switch (kindName(k)) {
    case 'Baseline': case 'Stability': {
      const s = i.spread
      if (s == null) return `your normal ${formatInputValue(key, ref)}`
      const lo = k?.log ? ref * Math.exp(-s) : ref - s
      const hi = k?.log ? ref * Math.exp(s) : ref + s
      return `your normal ${formatInputValue(key, lo)} – ${formatInputValue(key, hi)}`
    }
    case 'AtLeast': return k?.target != null ? `target at least ${formatInputValue(key, k.target)}` : null
    case 'AtMost': return k?.limit != null ? `limit ${formatInputValue(key, k.limit)}` : null
    case 'Band': return k?.lo != null && k?.hi != null ? `aim ${formatInputValue(key, k.lo)} – ${formatInputValue(key, k.hi)}` : null
    default: return null
  }
}

/** Each input's share of its domain's distance from a typical day, in points (they add up to the domain's raw distance). */
export function inputPoints(d: DomainScore): Record<string, number> {
  const groups = d.raw.groups.filter((g) => g.score != null && g.coverage > 0)
  const gw = groups.reduce((a, g) => a + g.group.weight * g.coverage, 0)
  if (gw <= 0) return {}
  const out: Record<string, number> = {}
  for (const g of groups) {
    const share = (g.group.weight * g.coverage) / gw
    const w = g.inputs.reduce((a, x) => a + x.spec.weight, 0)
    for (const x of g.inputs) out[x.spec.key] = (share * x.spec.weight / w) * (x.score - TYPICAL)
  }
  return out
}

export function band(score: number): string {
  if (score < 40) return 'LOW'
  if (score < 60) return 'BELOW NORMAL'
  if (score < 100) return 'NORMAL'
  if (score < 125) return 'PRIMED'
  return 'SUPERCHARGED'
}

/** LOAD and FUEL are judged against targets: their 100 means "right", so they read ON TARGET there. */
export function bandWord(key: DomainKey | null, score: number): string {
  const upside = key ? DOMAINS.find((x) => x.key === key)!.upside : true
  return !upside && score >= TOP_OF_NORMAL - 1e-9 ? 'ON TARGET' : band(score)
}

const inSentence = (label: string): string =>
  label.split(' ').map((w) => { const letters = w.replace(/[^A-Za-z]/g, ''); return letters.length >= 2 && letters === letters.toUpperCase() ? w : w.toLowerCase() }).join(' ')

/** One sentence under a domain's ring: its biggest lift and its biggest drag. */
export function domainSentence(d: DomainScore): string {
  if (d.score == null) {
    if (d.key === 'FUEL') return 'Not enough logged to score: the food log or gut entries for the day are missing.'
    if (d.key === 'BALANCE') return 'Not enough to score: no stress readings or answers for the day.'
    return 'Not enough data to score this morning.'
  }
  const points = Object.entries(inputPoints(d))
  const label = (k: string) => d.raw.groups.flatMap((g) => g.inputs).find((x) => x.spec.key === k)?.spec.label
  const ups = points.filter(([, p]) => p >= 2).sort((a, b) => b[1] - a[1])
  const downs = points.filter(([, p]) => p <= -2).sort((a, b) => a[1] - b[1])
  const up = ups[0] ? label(ups[0][0]) : undefined
  const down = downs[0] ? label(downs[0][0]) : undefined
  if (up && down) return `Lifted by ${inSentence(up)}; held back by ${inSentence(down)}.`
  if (up) return `Lifted mostly by ${inSentence(up)}.`
  if (down) return `Held back mostly by ${inSentence(down)}.`
  return 'Everything close to your usual.'
}

/** "Better than 93 of your last 100 mornings." */
export function chargeRankLine(c: ChargeScore): string | null {
  if (c.percentile == null) return null
  if (c.record) return `Your best morning of the last ${c.rankedAgainst ?? 100}.`
  return `Better than ${Math.min(99, Math.max(0, r(c.percentile * 100)))} of your last 100 mornings.`
}

/** The one-line driver summary under the CHARGE overlay's header. */
export function driverSummary(c: ChargeScore): string | null {
  const up = c.drivers.filter((d) => d.points >= 2).sort((a, b) => b.points - a.points).slice(0, 2)
  const down = c.drivers.filter((d) => d.points <= -2).sort((a, b) => a.points - b.points)[0]
  const ups = up.map((d) => `${d.key} (+${r(d.points)})`).join(' and ')
  if (up.length && down) return `Mostly ${ups}. ${down.key} holds it back (${r(down.points)}).`
  if (up.length) return `Mostly ${ups}.`
  if (down) return `${down.key} pulls it down (${r(down.points)}).`
  return null
}
