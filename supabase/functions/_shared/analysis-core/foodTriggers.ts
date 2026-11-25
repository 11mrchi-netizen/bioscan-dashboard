// The food trigger screen for the gut (idea 5 of the nutrition proposal, docs/nutrition/food-triggers.md). Pure, no I/O.
//
// The unit is a DAY WITH A STOOL (stools on one day share their meals, so counting each stool would count the same food
// twice). A day is loose when any of its stools is Bristol 6-7. Its exposure window runs from 48 h to 6 h before the day's
// first stool (a meal's effect on the bowel comes roughly 6-48 h later), over the meal profiles' food groups and gut tags.
//
// Each food group or tag is compared one of two ways:
//   any       days whose window held it against days whose window did not (when 20-80% of days held it);
//   high      when it is in almost every window, days with more meals of it than the median against the rest.
// A food is "worth testing" only when both sides have 20 days or more, the period holds 12 loose days or more, the loose
// share is at least 15 points higher with it, a one-sided Fisher exact test gives p < 0.05, and the result survives the
// Benjamini-Hochberg correction across every food tested (q <= 0.10). It is a screen, never a diagnosis: a candidate is
// something to test by leaving it out for two weeks with an experiment.
//
// Logged times (meals.logged_at, stool_log.occurred_at) are the phone's wall clock labelled +00: compared as they are.

export const TRIGGERS = {
  windowFromH: 48,
  windowToH: 6,
  lookbackDays: 180,
  minSideDays: 20,
  minLooseDays: 12,
  minDiffPts: 15,
  maxP: 0.05,
  maxQ: 0.1,
  anyMinShare: 0.2,
  anyMaxShare: 0.8,
}

export interface TrMeal { at: string; groups: string[] | null; tags: string[] | null }
export interface TrStool { at: string; bristol: number | null }

export type TriggerState = 'candidate' | 'no_signal' | 'not_enough'

export interface TriggerLine {
  key: string
  kind: 'group' | 'tag'
  label: string
  mode: 'any' | 'high'
  /** For mode 'high': more than this many meals of it in the window. */
  split: number | null
  withDays: number
  withoutDays: number
  withLoosePct: number | null
  withoutLoosePct: number | null
  p: number | null
  q: number | null
  state: TriggerState
}

export interface FoodTriggerSection {
  from: string
  to: string
  stoolDays: number
  looseDays: number
  /** Whether the period as a whole has enough loose days for any food to speak. */
  ready: boolean
  lines: TriggerLine[]
}

const GROUP_LABELS: Record<string, string> = {
  protein_powder: 'Protein powder', sweets: 'Sweets', snacks_salty: 'Salty snacks', sugary_drinks: 'Sugary drinks', alcohol: 'Alcohol',
  coffee_tea: 'Coffee or tea', plant_milk: 'Plant milk', water_electrolytes: 'Water or electrolytes', processed_meat: 'Processed meat',
  whole_grains: 'Whole grains', refined_grains: 'Refined grains', starchy_veg: 'Starchy veg', poultry: 'Poultry', meat: 'Meat',
  fish_seafood: 'Fish and seafood', eggs: 'Eggs', cheese: 'Cheese', dairy: 'Dairy', legumes: 'Legumes', nuts_seeds: 'Nuts and seeds',
  fruit: 'Fruit', vegetables: 'Vegetables', sugars: 'Sugar and honey', fats_oils: 'Fats and oils', sauces: 'Sauces',
}
const TAG_LABELS: Record<string, string> = {
  gluten: 'Gluten', lactose: 'Lactose', dairy: 'Dairy (any)', high_fodmap: 'High FODMAP', fried: 'Fried food', spicy: 'Spicy food',
  sweetener: 'Sweeteners', caffeine: 'Caffeine', alcohol: 'Alcohol (any)', ultra_processed: 'Ultra-processed food', high_fat: 'High-fat meals',
}
/** Groups that say nothing about a trigger. */
const SKIP_GROUPS = new Set(['other', 'water_electrolytes'])

const H = 3600000
const ms = (stamp: string) => Date.parse(String(stamp).slice(0, 19).replace(' ', 'T') + 'Z')

function logChoose(n: number, k: number): number {
  let s = 0
  for (let i = 1; i <= k; i++) s += Math.log(n - k + i) - Math.log(i)
  return s
}

/** One-sided Fisher exact p: the chance of a or more loose days among the exposed, given the margins. */
export function fisherGreater(a: number, b: number, c: number, d: number): number {
  // a exposed loose, b exposed not, c unexposed loose, d unexposed not
  const n = a + b + c + d, loose = a + c, exposed = a + b
  const denom = logChoose(n, exposed)
  let p = 0
  for (let x = a; x <= Math.min(loose, exposed); x++) p += Math.exp(logChoose(loose, x) + logChoose(n - loose, exposed - x) - denom)
  return Math.min(1, p)
}

/** Benjamini-Hochberg q values, in the order given. */
export function bhQ(ps: number[]): number[] {
  const order = ps.map((p, i) => [p, i] as const).sort((x, y) => x[0] - y[0])
  const q = new Array<number>(ps.length)
  let min = 1
  for (let r = order.length; r >= 1; r--) {
    const [p, i] = order[r - 1]
    min = Math.min(min, (p * order.length) / r)
    q[i] = min
  }
  return q
}

const pct = (n: number, d: number) => (d ? Math.round((n / d) * 1000) / 10 : null)

/** The screen over the stool days from [from] to [to] (local dates). */
export function foodTriggers(meals: TrMeal[], stools: TrStool[], from: string, to: string): FoodTriggerSection {
  const days = new Map<string, { first: number; loose: boolean }>()
  for (const s of stools) {
    if (s.bristol === null || s.bristol === undefined) continue
    const t = ms(s.at)
    if (!Number.isFinite(t)) continue
    const d = String(s.at).slice(0, 10)
    if (d < from || d > to) continue
    const e = days.get(d)
    const loose = s.bristol >= 6
    days.set(d, e ? { first: Math.min(e.first, t), loose: e.loose || loose } : { first: t, loose })
  }
  const ms0 = meals.map((m) => ({ t: ms(m.at), keys: [
    ...(m.groups ?? []).filter((g) => !SKIP_GROUPS.has(g)).map((g) => `group:${g}`),
    ...(m.tags ?? []).map((g) => `tag:${g}`),
  ] })).filter((m) => Number.isFinite(m.t))
  // Per stool day: how many meals of each key in its window.
  const rows = [...days.values()].map((d) => {
    const counts = new Map<string, number>()
    for (const m of ms0) {
      if (m.t < d.first - TRIGGERS.windowFromH * H || m.t > d.first - TRIGGERS.windowToH * H) continue
      for (const k of new Set(m.keys)) counts.set(k, (counts.get(k) ?? 0) + 1)
    }
    return { loose: d.loose, counts, any: counts.size > 0 }
  }).filter((r) => r.any) // a day with no meal logged in its window says nothing
  const looseDays = rows.filter((r) => r.loose).length
  const ready = looseDays >= TRIGGERS.minLooseDays
  const keys = [...new Set(rows.flatMap((r) => [...r.counts.keys()]))].sort()
  const lines: TriggerLine[] = keys.map((key) => {
    const [kind, name] = key.split(':') as ['group' | 'tag', string]
    const label = (kind === 'group' ? GROUP_LABELS[name] : TAG_LABELS[name]) ?? name.replace(/_/g, ' ')
    const cs = rows.map((r) => r.counts.get(key) ?? 0)
    const share = cs.filter((c) => c > 0).length / rows.length
    let mode: 'any' | 'high' = 'any'
    let split = 0
    if (share > TRIGGERS.anyMaxShare) {
      mode = 'high'
      const sorted = [...cs].sort((a, b) => a - b)
      split = sorted[Math.floor((sorted.length - 1) / 2)]
    }
    const withR = rows.filter((_, i) => cs[i] > split)
    const withoutR = rows.filter((_, i) => cs[i] <= split)
    const a = withR.filter((r) => r.loose).length, b = withR.length - a
    const c = withoutR.filter((r) => r.loose).length, d = withoutR.length - c
    const enough = ready && withR.length >= TRIGGERS.minSideDays && withoutR.length >= TRIGGERS.minSideDays && share >= TRIGGERS.anyMinShare
    return {
      key: name, kind, label, mode, split: mode === 'high' ? split : null,
      withDays: withR.length, withoutDays: withoutR.length,
      withLoosePct: pct(a, withR.length), withoutLoosePct: pct(c, withoutR.length),
      p: enough ? fisherGreater(a, b, c, d) : null, q: null, state: enough ? 'no_signal' : 'not_enough',
    }
  })
  const tested = lines.filter((l) => l.p !== null)
  const qs = bhQ(tested.map((l) => l.p as number))
  tested.forEach((l, i) => {
    l.q = Math.round(qs[i] * 1000) / 1000
    l.p = Math.round((l.p as number) * 10000) / 10000
    const diff = (l.withLoosePct ?? 0) - (l.withoutLoosePct ?? 0)
    if (diff >= TRIGGERS.minDiffPts && (l.p as number) < TRIGGERS.maxP && l.q <= TRIGGERS.maxQ) l.state = 'candidate'
  })
  const rank = { candidate: 0, no_signal: 1, not_enough: 2 }
  lines.sort((x, y) => rank[x.state] - rank[y.state] || (x.p ?? 2) - (y.p ?? 2) || x.label.localeCompare(y.label))
  return { from, to, stoolDays: rows.length, looseDays, ready, lines }
}

export interface TriggerSuggestion { id: string; priority: 1 | 2 | 3; text: string; because: string }

/** A fixed rule: each candidate is worth a two-week test, never a verdict. */
export function foodTriggerSuggestions(t: FoodTriggerSection): TriggerSuggestion[] {
  const c = t.lines.filter((l) => l.state === 'candidate')
  if (!c.length) return []
  const top = c[0]
  return [{
    id: 'food_trigger', priority: 3,
    text: `Worth testing: ${top.label.toLowerCase()} went with more loose stools (${top.withLoosePct}% of days against ${top.withoutLoosePct}%). Leave it out for two weeks with a Lab experiment, then bring it back.`,
    because: `${c.length} candidate${c.length === 1 ? '' : 's'} over ${t.stoolDays} stool days (screen, not a diagnosis)`,
  }]
}
