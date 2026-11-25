// Me › INTIMACY on the web: what the page works out from the encounters, the people, the solo days and the STI tests. The
// phone's rules where the phone has them (android domain/IntimacyStats.kt, domain/Intimacy.kt: barrier, tally, who is new,
// the STI status and its 90-day partner list), and the deeper reads the phone does not have (rhythm, gaps, co-occurrence,
// tag and outcome, partner repeat rate, the days around an activity). Counts and the person's own ratings only: nothing
// here is clinical advice, and nothing recommends when to test. Pure functions; the page and the tests share them.
//
// Dates: `encounters.date`, `sexual_activity_daily.date` and `sti_tests.test_date` are local calendar dates. `occurred_at`
// is a logged time (the phone's wall clock labelled +00): its digits are local, read with wallClock, never shifted.

export interface Encounter {
  id: number
  date: string
  status: string | null
  occurred_at: string | null
  encounter_type: string | null
  location_type: string | null
  duration_min: number | null
  activities: string[] | null
  my_rating: number | null
  notes: string | null
  person_id: number | null
  place_id: string | null
  sexual: boolean
  format: string[] | null
  positions: string[] | null
  toys: string[] | null
  protection: string[] | null
  ejaculation_location: string[] | null
  rounds: number | null
  orgasm_count: number | null
  orgasm_intensity: number | null
  sheet_encounter_id: string | null
}

export interface Person {
  id: number
  name: string
  period: string | null
  score: number | null
  where_met: string | null
  relationship: string | null
  gender: string | null
  country: string | null
  age_range: string | null
  body_type: string | null
  activities: string[] | null
  interested_in: string[] | null
  first_met_date: string | null
  availability: string | null
  notes: string | null
}

export interface SoloInstance { orgasm: boolean; orgasmIntensity?: number | null; watchedPorn?: boolean | null; loadSize?: number | null; notes?: string | null }
export interface SoloDay { id: number; date: string; instances: SoloInstance[]; notes: string | null }

export interface StiTest {
  id: number
  test_date: string
  infection: string
  result: string
  site: string | null
  lab_name: string | null
  treated_on: string | null
  notes: string | null
}

// ---- vocabularies (a copy of the phone's; intimacy.test.ts checks them against the Kotlin) ----

export const INTIMACY_VOCAB = {
  formats: ['1 on 1', 'Threesome', 'MFM', 'FFM', 'MMF', 'Foursome', 'Swap', 'Moresome', 'Gangbang', 'Reverse gangbang', 'Blowbang', 'Bukkake', 'Orgy'],
  activities: [
    'Kissing', 'Petting', 'Fingering', 'Handjob', 'Mutual masturbation', 'Oral (given)', 'Oral (received)', 'PIV',
    'Anal (active)', 'Anal (passive)', 'Rimming (given)', 'Rimming (received)', 'Fisting',
    'Spanking', 'Impact play', 'Bondage', 'Shibari', 'Blindfold', 'Choking', 'Dom (me)', 'Sub (me)', 'Roleplay', 'Edging',
    'Other kinks',
  ],
  positions: ['Missionary', 'Legs on shoulders', 'Doggy', 'Prone', 'Cowgirl', 'Reverse cowgirl', 'Spooning', 'Seated / lap', 'Standing', 'Edge of bed', '69'],
  toys: [
    'Vibrator', 'Wand', 'Dildo', 'Strap-on', 'Butt plug', 'Anal hook', 'Cock ring', 'Fuck machine', 'Restraints', 'Ropes',
    'Spreader bar', 'Mouth gag', 'Whip', 'Paddle', 'Cane', 'Riding crop', 'Flogger', 'Other',
  ],
  protection: ['Condom', 'Condom (partly)', 'Internal condom', 'Dental dam', 'None', 'PrEP', 'Partner on contraception', 'Withdrawal'],
  ejaculationLocation: ['Vaginal', 'Anal', 'Mouth', 'Face', 'Chest', 'Stomach', 'Back / ass', 'Hand', 'None'],
} as const

export const STI_VOCAB = {
  infections: ['HIV', 'Syphilis', 'Chlamydia', 'Gonorrhoea', 'Herpes (HSV)', 'HPV', 'Hepatitis B', 'Hepatitis C', 'Trichomoniasis', 'Mycoplasma genitalium'],
  results: ['negative', 'positive', 'inconclusive', 'pending'],
  sites: ['Blood', 'Urine', 'Throat', 'Rectal', 'Genital swab'],
} as const

/** The tag lists of an encounter, with the names the page shows them under. */
export const TAG_FIELDS = [
  { key: 'format', label: 'FORMAT', vocab: INTIMACY_VOCAB.formats },
  { key: 'activities', label: 'ACTIVITIES', vocab: INTIMACY_VOCAB.activities },
  { key: 'positions', label: 'POSITIONS', vocab: INTIMACY_VOCAB.positions },
  { key: 'toys', label: 'TOYS', vocab: INTIMACY_VOCAB.toys },
  { key: 'protection', label: 'PROTECTION', vocab: INTIMACY_VOCAB.protection },
  { key: 'ejaculation_location', label: 'EJACULATION', vocab: INTIMACY_VOCAB.ejaculationLocation },
] as const
export type TagKey = typeof TAG_FIELDS[number]['key']

/** The chips to show: the vocabulary, then any saved value that is not in it (the phone's withSaved). */
export function withSaved(vocab: readonly string[], saved: readonly string[]): string[] {
  return [...vocab, ...saved.filter((s) => !vocab.includes(s))]
}

// The phone's STI_LOOKBACK_DAYS: one number for every infection, a starting list, not a clinical look-back period.
export const STI_LOOKBACK_DAYS = 90

// ---- dates ----

const dayMs = (d: string) => Date.parse(`${d}T00:00:00Z`)
/** Whole days from a to b (local calendar dates). */
export const daysBetween = (a: string, b: string) => Math.round((dayMs(b) - dayMs(a)) / 864e5)
export function addDays(d: string, n: number): string { const t = new Date(dayMs(d)); t.setUTCDate(t.getUTCDate() + n); return t.toISOString().slice(0, 10) }
export const inRange = (d: string, from: string | null, to: string) => (from == null || d >= from) && d <= to
/** Monday of a local date's week. */
export function mondayOf(d: string): string { const wd = (new Date(dayMs(d)).getUTCDay() + 6) % 7; return addDays(d, -wd) }
export const monthOf = (d: string) => d.slice(0, 7)

/** A logged time's local date and hour: its digits, whatever offset it carries (CLAUDE.md: logged times). */
export function wallClock(iso: string | null | undefined): { day: string; hour: number; minute: number } | null {
  const m = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}):(\d{2})/.exec(iso ?? '')
  return m ? { day: m[1], hour: Number(m[2]), minute: Number(m[3]) } : null
}

// ---- the phone's rules ----

const BARRIERS = new Set(['Condom', 'Internal condom'])
/** True with a barrier throughout, false without, null when protection was not recorded. "Condom (partly)" is not one. */
export function barrier(e: Encounter): boolean | null {
  const p = e.protection
  return p && p.length ? p.some((x) => BARRIERS.has(x)) : null
}

/** How often each value of a tag list appears, most frequent first (ties by name). */
export function tally(rows: Encounter[], pick: (e: Encounter) => readonly string[] | null | undefined): [string, number][] {
  const m = new Map<string, number>()
  for (const e of rows) for (const t of pick(e) ?? []) m.set(t, (m.get(t) ?? 0) + 1)
  return [...m.entries()].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
}

const mean = (xs: number[]) => (xs.length ? xs.reduce((s, x) => s + x, 0) / xs.length : null)

export interface IntimacyStats {
  encounters: number; sexual: number; partners: number; newPartners: number; rounds: number; orgasms: number
  withBarrier: number; withoutBarrier: number; protectionUnknown: number; avgRating: number | null; ratedN: number
  soloDays: number; soloSessions: number; soloOrgasms: number; activeDays: number
}

/** The window [from, to] (from null = all of it). `all` is the whole history: it decides who is new. */
export function intimacyStats(all: Encounter[], solo: SoloDay[], from: string | null, to: string): IntimacyStats {
  const win = all.filter((e) => inRange(e.date, from, to))
  const sexual = win.filter((e) => e.sexual)
  const firstSex = new Map<number, string>()
  for (const e of all) if (e.sexual && e.person_id != null) { const f = firstSex.get(e.person_id); if (!f || e.date < f) firstSex.set(e.person_id, e.date) }
  const partners = new Set(sexual.flatMap((e) => (e.person_id != null ? [e.person_id] : [])))
  const ratings = win.flatMap((e) => (e.my_rating != null ? [e.my_rating] : []))
  const soloWin = solo.filter((s) => inRange(s.date, from, to) && s.instances.length > 0)
  const active = new Set([...sexual.map((e) => e.date), ...soloWin.map((s) => s.date)])
  return {
    encounters: win.length,
    sexual: sexual.length,
    partners: partners.size,
    newPartners: [...partners].filter((id) => { const f = firstSex.get(id); return f != null && (from == null || f >= from) }).length,
    rounds: sexual.reduce((s, e) => s + (e.rounds ?? 1), 0),
    orgasms: sexual.reduce((s, e) => s + (e.orgasm_count ?? 0), 0),
    withBarrier: sexual.filter((e) => barrier(e) === true).length,
    withoutBarrier: sexual.filter((e) => barrier(e) === false).length,
    protectionUnknown: sexual.filter((e) => barrier(e) == null).length,
    avgRating: mean(ratings),
    ratedN: ratings.length,
    soloDays: soloWin.length,
    soloSessions: soloWin.reduce((s, d) => s + d.instances.length, 0),
    soloOrgasms: soloWin.reduce((s, d) => s + d.instances.filter((i) => i.orgasm).length, 0),
    activeDays: active.size,
  }
}

// ---- rhythm ----

export interface DayTally { partnered: number; solo: number; meetups: number }

/** Per local date: partnered rounds, solo sessions, meetups without sex. */
export function dayTallies(encounters: Encounter[], solo: SoloDay[]): Map<string, DayTally> {
  const m = new Map<string, DayTally>()
  const get = (d: string) => { let t = m.get(d); if (!t) { t = { partnered: 0, solo: 0, meetups: 0 }; m.set(d, t) } return t }
  for (const e of encounters) { if (e.sexual) get(e.date).partnered += Math.max(1, e.rounds ?? 1); else get(e.date).meetups += 1 }
  for (const s of solo) if (s.instances.length) get(s.date).solo += s.instances.length
  return m
}

export interface WeekRow { week: string; partneredDays: number; soloDays: number }

/** Active days per Monday-to-Sunday week, every week of the window, with partnered and solo apart (the phone's BY WEEK). */
export function weekly(days: Map<string, DayTally>, from: string, to: string): WeekRow[] {
  const out: WeekRow[] = []
  for (let w = mondayOf(from); w <= to; w = addDays(w, 7)) {
    let p = 0, s = 0
    for (let i = 0; i < 7; i++) { const t = days.get(addDays(w, i)); if (t?.partnered) p++; if (t?.solo) s++ }
    out.push({ week: w, partneredDays: p, soloDays: s })
  }
  return out
}

export interface Gaps { gaps: number[]; longest: { days: number; from: string; to: string } | null; current: number | null; median: number | null; longestStreak: number }

/** Days between consecutive active days, the longest of them, the current one (to `today`) and the longest run of active days. */
export function gapStats(activeDays: Iterable<string>, today: string): Gaps {
  const ds = [...new Set(activeDays)].filter((d) => d <= today).sort()
  const gaps: number[] = []
  let longest: Gaps['longest'] = null
  let streak = ds.length ? 1 : 0, best = streak
  for (let i = 1; i < ds.length; i++) {
    const g = daysBetween(ds[i - 1], ds[i])
    gaps.push(g)
    if (!longest || g > longest.days) longest = { days: g, from: ds[i - 1], to: ds[i] }
    streak = g === 1 ? streak + 1 : 1
    best = Math.max(best, streak)
  }
  const sorted = [...gaps].sort((a, b) => a - b)
  const median = sorted.length ? (sorted.length % 2 ? sorted[(sorted.length - 1) / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2) : null
  return { gaps, longest, current: ds.length ? daysBetween(ds[ds.length - 1], today) : null, median, longestStreak: best }
}

/** Sexual encounters with a time, by weekday (0 = Monday) and hour of the wall clock. */
export function hourGrid(encounters: Encounter[]): { grid: number[][]; n: number } {
  const grid = Array.from({ length: 7 }, () => Array<number>(24).fill(0))
  let n = 0
  for (const e of encounters) {
    if (!e.sexual) continue
    const w = wallClock(e.occurred_at)
    if (!w) continue
    const wd = (new Date(dayMs(w.day)).getUTCDay() + 6) % 7
    grid[wd][w.hour] += 1
    n++
  }
  return { grid, n }
}

// ---- mix ----

/** Which values of one tag list are logged together: the labels (most frequent first, at most `max`) and pair counts. */
export function cooccurrence(rows: Encounter[], pick: (e: Encounter) => readonly string[] | null | undefined, max = 12): { labels: string[]; counts: number[][] } {
  const labels = tally(rows, pick).slice(0, max).map((x) => x[0])
  const idx = new Map(labels.map((l, i) => [l, i]))
  const counts = labels.map(() => Array<number>(labels.length).fill(0))
  for (const e of rows) {
    const tags = [...new Set(pick(e) ?? [])].flatMap((t) => (idx.has(t) ? [idx.get(t)!] : []))
    for (const a of tags) for (const b of tags) counts[a][b] += 1
  }
  return { labels, counts }
}

export interface TagOutcome { tag: string; nWith: number; meanWith: number; nWithout: number; meanWithout: number; diff: number; lo: number; hi: number; clear: boolean }

/** The smallest group on either side of a tag-and-outcome comparison. Below it the row is not shown. */
export const MIN_TAG_N = 3

/**
 * The mean of an outcome on encounters with a tag against those without it, among the encounters that carry the tag list at
 * all (an untagged row is not a "without"). The interval is Welch's, normal approximation: descriptive, uncorrected for the
 * many tags compared, never a cause.
 */
export function tagOutcomes(rows: Encounter[], pick: (e: Encounter) => readonly string[] | null | undefined, outcome: (e: Encounter) => number | null): TagOutcome[] {
  const tagged = rows.filter((e) => (pick(e)?.length ?? 0) > 0 && outcome(e) != null)
  const out: TagOutcome[] = []
  for (const [tag] of tally(tagged, pick)) {
    const w = tagged.filter((e) => pick(e)!.includes(tag)).map((e) => outcome(e)!)
    const wo = tagged.filter((e) => !pick(e)!.includes(tag)).map((e) => outcome(e)!)
    if (w.length < MIN_TAG_N || wo.length < MIN_TAG_N) continue
    const d = diffInterval(w, wo)
    out.push({ tag, nWith: w.length, meanWith: mean(w)!, nWithout: wo.length, meanWithout: mean(wo)!, ...d })
  }
  return out.sort((a, b) => Math.abs(b.diff) - Math.abs(a.diff))
}

/** a − b with a 95% interval (Welch, normal approximation). */
export function diffInterval(a: number[], b: number[]): { diff: number; lo: number; hi: number; clear: boolean } {
  const ma = mean(a)!, mb = mean(b)!
  const v = (xs: number[], m: number) => (xs.length > 1 ? xs.reduce((s, x) => s + (x - m) ** 2, 0) / (xs.length - 1) : 0)
  const se = Math.sqrt(v(a, ma) / a.length + v(b, mb) / b.length)
  const diff = ma - mb
  const lo = diff - 1.96 * se, hi = diff + 1.96 * se
  return { diff, lo, hi, clear: se > 0 && (lo > 0 || hi < 0) }
}

/** Per month, how many encounters carried each value of a tag list. */
export function monthlyTally(rows: Encounter[], pick: (e: Encounter) => readonly string[] | null | undefined): { months: string[]; byTag: Map<string, number[]> } {
  const months = [...new Set(rows.map((e) => monthOf(e.date)))].sort()
  const byTag = new Map<string, number[]>()
  for (const e of rows) for (const t of new Set(pick(e) ?? [])) {
    let arr = byTag.get(t)
    if (!arr) { arr = months.map(() => 0); byTag.set(t, arr) }
    arr[months.indexOf(monthOf(e.date))] += 1
  }
  return { months, byTag }
}

/** How many of the rows carry each field: the completeness strip under a filtered table. */
export function completeness(rows: Encounter[]): { field: string; n: number }[] {
  const has = (f: (e: Encounter) => unknown) => rows.filter((e) => { const v = f(e); return Array.isArray(v) ? v.length > 0 : v != null }).length
  const sexual = rows.filter((e) => e.sexual)
  return [
    { field: 'time', n: has((e) => e.occurred_at) },
    { field: 'place', n: has((e) => e.place_id ?? e.location_type) },
    { field: 'duration', n: has((e) => e.duration_min) },
    { field: 'rating', n: has((e) => e.my_rating) },
    { field: 'activities', n: sexual.filter((e) => (e.activities?.length ?? 0) > 0).length },
    { field: 'positions', n: sexual.filter((e) => (e.positions?.length ?? 0) > 0).length },
    { field: 'protection', n: sexual.filter((e) => (e.protection?.length ?? 0) > 0).length },
  ]
}

/** True for a row copied from the old day rows (it has rounds and intensity, never tags). */
export const isCopied = (e: Encounter) => (e.sheet_encounter_id ?? '').startsWith('sa:')
/** A sexual encounter with no tag list filled in: the ones worth filling in. */
export const needsTags = (e: Encounter) => e.sexual && TAG_FIELDS.every((f) => !(e[f.key]?.length))

// ---- partners ----

export interface PartnerSummary {
  person: Person
  encounters: Encounter[]
  sexual: number
  first: string | null
  last: string | null
  avgRating: number | null
  barrierKnown: number
  withBarrier: number
}

/** Every person with their encounters, newest first. */
export function partnerSummaries(people: Person[], encounters: Encounter[]): PartnerSummary[] {
  const by = new Map<number, Encounter[]>()
  for (const e of encounters) if (e.person_id != null) { const a = by.get(e.person_id) ?? []; a.push(e); by.set(e.person_id, a) }
  return people.map((p) => {
    const es = (by.get(p.id) ?? []).sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : b.id - a.id))
    const sexual = es.filter((e) => e.sexual)
    const known = sexual.filter((e) => barrier(e) != null)
    const r = es.flatMap((e) => (e.my_rating != null ? [e.my_rating] : []))
    return {
      person: p, encounters: es, sexual: sexual.length,
      first: es.length ? es[es.length - 1].date : null, last: es.length ? es[0].date : null,
      avgRating: mean(r), barrierKnown: known.length, withBarrier: known.filter((e) => barrier(e) === true).length,
    }
  })
}

/** Of the partners with sex on record: how many were seen again, and the median days from the first time to the second. */
export function repeatStats(encounters: Encounter[]): { partners: number; repeat: number; medianDaysToSecond: number | null } {
  const by = new Map<number, string[]>()
  for (const e of encounters) if (e.sexual && e.person_id != null) { const a = by.get(e.person_id) ?? []; a.push(e.date); by.set(e.person_id, a) }
  const seconds: number[] = []
  let repeat = 0
  for (const ds of by.values()) {
    const u = [...new Set(ds)].sort()
    if (u.length > 1) { repeat++; seconds.push(daysBetween(u[0], u[1])) }
  }
  seconds.sort((a, b) => a - b)
  const med = seconds.length ? (seconds.length % 2 ? seconds[(seconds.length - 1) / 2] : (seconds[seconds.length / 2 - 1] + seconds[seconds.length / 2]) / 2) : null
  return { partners: by.size, repeat, medianDaysToSecond: med }
}

/** Per month: partners whose first sexual encounter on record falls in it. */
export function newPartnersByMonth(encounters: Encounter[]): [string, number][] {
  const first = new Map<number, string>()
  for (const e of encounters) if (e.sexual && e.person_id != null) { const f = first.get(e.person_id); if (!f || e.date < f) first.set(e.person_id, e.date) }
  const m = new Map<string, number>()
  for (const d of first.values()) m.set(monthOf(d), (m.get(monthOf(d)) ?? 0) + 1)
  return [...m.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1))
}

/** People counted by one profile field, most first; blanks as "not set". */
export function breakdown(people: Person[], pick: (p: Person) => string | null | undefined): [string, number][] {
  const m = new Map<string, number>()
  for (const p of people) { const k = (pick(p) ?? '').trim() || 'not set'; m.set(k, (m.get(k) ?? 0) + 1) }
  return [...m.entries()].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1))
}

export type PartnerSort = 'recent' | 'name' | 'most' | 'score' | 'rating' | 'first'

/** The partner table: a search over name, relationship, country and where met, then the chosen order. People never seen sort last. */
export function browsePartners(all: PartnerSummary[], query: string, sort: PartnerSort): PartnerSummary[] {
  const q = query.trim().toLowerCase()
  const byName = (a: PartnerSummary, b: PartnerSummary) => a.person.name.toLowerCase().localeCompare(b.person.name.toLowerCase())
  const desc = <T>(f: (p: PartnerSummary) => T | null) => (a: PartnerSummary, b: PartnerSummary) => {
    const x = f(a), y = f(b)
    if (x == null && y == null) return byName(a, b)
    if (x == null) return 1
    if (y == null) return -1
    return x < y ? 1 : x > y ? -1 : byName(a, b)
  }
  const order = {
    recent: desc((p) => p.last), name: byName, most: desc((p) => p.encounters.length || null), score: desc((p) => p.person.score),
    rating: desc((p) => p.avgRating), first: desc((p) => p.person.first_met_date ?? p.first),
  }[sort]
  const hay = (p: PartnerSummary) => [p.person.name, p.person.relationship, p.person.country, p.person.where_met].filter(Boolean).join(' ').toLowerCase()
  return all.filter((p) => !q || hay(p).includes(q)).sort(order)
}

/** A name as discreet mode shows it: the initials, "A. B." */
export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  return parts.length ? parts.map((w) => `${[...w][0].toUpperCase()}.`).join(' ') : '?'
}

// ---- solo ----

export interface SoloPoint { date: string; intensity: number | null; load: number | null; porn: boolean | null; gap: number | null }

/** One point per solo session: its intensity, load and porn, and the days since the previous activity of any kind. */
export function soloPoints(solo: SoloDay[], encounters: Encounter[]): SoloPoint[] {
  const partnered = encounters.filter((e) => e.sexual).map((e) => e.date)
  const soloDays = solo.filter((s) => s.instances.length).map((s) => s.date)
  const all = [...new Set([...partnered, ...soloDays])].sort()
  const out: SoloPoint[] = []
  for (const s of [...solo].sort((a, b) => (a.date < b.date ? -1 : 1))) {
    const i = all.indexOf(s.date)
    const prev = i > 0 ? all[i - 1] : null
    s.instances.forEach((x, k) => out.push({
      date: s.date, intensity: x.orgasmIntensity ?? null, load: x.loadSize ?? null, porn: x.watchedPorn ?? null,
      // a second session on the same day follows the first by 0 days
      gap: k > 0 ? 0 : prev ? daysBetween(prev, s.date) : null,
    }))
  }
  return out
}

/** Pearson's r over pairs, with its 95% interval by Fisher's z (lib/compare.ts's rule); null below `min` pairs or with no spread. */
export function pearson(pairs: [number, number][], min = 10): { r: number; lo: number; hi: number; n: number; clear: boolean } | null {
  const n = pairs.length
  if (n < min) return null
  const mx = pairs.reduce((s, p) => s + p[0], 0) / n, my = pairs.reduce((s, p) => s + p[1], 0) / n
  let sxy = 0, sxx = 0, syy = 0
  for (const [x, y] of pairs) { sxy += (x - mx) * (y - my); sxx += (x - mx) ** 2; syy += (y - my) ** 2 }
  if (sxx <= 0 || syy <= 0) return null
  const r = Math.min(0.999999, Math.max(-0.999999, sxy / Math.sqrt(sxx * syy)))
  const z = Math.atanh(r), se = 1 / Math.sqrt(n - 3)
  const lo = Math.tanh(z - 1.96 * se), hi = Math.tanh(z + 1.96 * se)
  return { r, lo, hi, n, clear: lo > 0 || hi < 0 }
}

// ---- STI ----

export interface StiStatus {
  lastTest: string | null
  latest: StiTest[]
  open: StiTest[]
  partnersSince: number
  encountersSince: number
  withoutBarrierSince: number
  protectionUnknownSince: number
}

/** The phone's stiStatus: the latest test with a result, the newest row per infection, open positives, and what came after. */
export function stiStatus(tests: StiTest[], encounters: Encounter[]): StiStatus {
  const lastTest = tests.filter((t) => t.result !== 'pending').reduce<string | null>((m, t) => (m == null || t.test_date > m ? t.test_date : m), null)
  const newest = new Map<string, StiTest>()
  for (const t of tests) { const c = newest.get(t.infection); if (!c || t.test_date > c.test_date || (t.test_date === c.test_date && t.id > c.id)) newest.set(t.infection, t) }
  const rank = (i: string) => { const k = (STI_VOCAB.infections as readonly string[]).indexOf(i); return k < 0 ? Infinity : k }
  const latest = [...newest.values()].sort((a, b) => rank(a.infection) - rank(b.infection) || a.infection.localeCompare(b.infection))
  const since = encounters.filter((e) => e.sexual && (lastTest == null || e.date > lastTest))
  return {
    lastTest, latest,
    open: latest.filter((t) => t.result === 'positive' && t.treated_on == null),
    partnersSince: new Set(since.flatMap((e) => (e.person_id != null ? [e.person_id] : []))).size,
    encountersSince: since.length,
    withoutBarrierSince: since.filter((e) => barrier(e) === false).length,
    protectionUnknownSince: since.filter((e) => barrier(e) == null).length,
  }
}

/** The people with a sexual encounter in the STI_LOOKBACK_DAYS up to a test date, most recent first, each once. */
export function partnersBefore(testDate: string, encounters: Encounter[]): number[] {
  const from = addDays(testDate, -STI_LOOKBACK_DAYS)
  const seen: number[] = []
  for (const e of [...encounters].sort((a, b) => (a.date < b.date ? 1 : -1))) {
    if (e.sexual && e.person_id != null && e.date <= testDate && e.date >= from && !seen.includes(e.person_id)) seen.push(e.person_id)
  }
  return seen
}

// ---- the days around an activity ----

export interface Offset { offset: number; n: number; mean: number | null; baseN: number; baseMean: number | null; diff: number | null; lo: number | null; hi: number | null; clear: boolean }

/** The smallest number of event days (and of quiet days) an offset needs before it is read. */
export const MIN_EVENT_N = 5

/**
 * An outcome on the days around an event day (offset 0 = the day itself, 1 = the next day...) against the quiet days: days
 * with no activity of any kind within `quiet` days either side. Descriptive: days with an event differ in other ways too.
 */
export function eventStudy(eventDays: Set<string>, anyActive: Set<string>, outcome: Map<string, number>, offsets: number[], quiet = 2): Offset[] {
  const quietDays: number[] = []
  for (const [d, v] of outcome) {
    let calm = true
    for (let k = -quiet; k <= quiet && calm; k++) if (anyActive.has(addDays(d, k))) calm = false
    if (calm) quietDays.push(v)
  }
  const baseMean = mean(quietDays)
  return offsets.map((o) => {
    const vals = [...eventDays].flatMap((d) => { const v = outcome.get(addDays(d, o)); return v == null ? [] : [v] })
    const ok = vals.length >= MIN_EVENT_N && quietDays.length >= MIN_EVENT_N
    const d = ok ? diffInterval(vals, quietDays) : null
    return { offset: o, n: vals.length, mean: mean(vals), baseN: quietDays.length, baseMean, diff: d?.diff ?? null, lo: d?.lo ?? null, hi: d?.hi ?? null, clear: d?.clear ?? false }
  })
}
