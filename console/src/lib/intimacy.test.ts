import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  barrier, breakdown, browsePartners, completeness, cooccurrence, type Encounter, eventStudy, gapStats, hourGrid, initials,
  INTIMACY_VOCAB, intimacyStats, needsTags, newPartnersByMonth, partnerSummaries, partnersBefore, pearson, type Person,
  repeatStats, type SoloDay, soloPoints, STI_LOOKBACK_DAYS, STI_VOCAB, stiStatus, type StiTest, tagOutcomes, tally, wallClock,
  weekly, dayTallies, withSaved,
} from './intimacy.ts'

// Invented records only: no real person or encounter appears in a test.
let nextId = 1
const enc = (date: string, over: Partial<Encounter> = {}): Encounter => ({
  id: nextId++, date, status: 'logged', occurred_at: null, encounter_type: null, location_type: null, duration_min: null, activities: null,
  my_rating: null, notes: null, person_id: 1, place_id: null, sexual: true, format: null, positions: null, toys: null, protection: null,
  ejaculation_location: null, rounds: 1, orgasm_count: 1, orgasm_intensity: null, sheet_encounter_id: null, ...over,
})
const person = (id: number, name: string, over: Partial<Person> = {}): Person => ({
  id, name, period: null, score: null, where_met: null, relationship: null, gender: null, country: null, age_range: null, body_type: null,
  activities: null, interested_in: null, first_met_date: null, availability: null, notes: null, ...over,
})
const solo = (date: string, n = 1, over: Partial<SoloDay['instances'][number]> = {}): SoloDay => ({ id: nextId++, date, notes: null, instances: Array.from({ length: n }, () => ({ orgasm: true, ...over })) })

const ktList = (src: string, name: string): string[] => {
  const m = new RegExp(`val ${name} = listOf\\(([\\s\\S]*?)\\)\\s*\\n`).exec(src)
  assert.ok(m, `list ${name} not found in the Kotlin`)
  return [...m[1].matchAll(/"([^"]*)"/g)].map((x) => x[1])
}

test("the vocabularies are the phone's, label for label (a saved value is the label itself)", () => {
  const kt = readFileSync(new URL('../../../android-app/app/src/main/java/com/bioscan/fieldterminal/domain/Intimacy.kt', import.meta.url), 'utf8')
  assert.deepEqual([...INTIMACY_VOCAB.formats], ktList(kt, 'formats'))
  assert.deepEqual([...INTIMACY_VOCAB.activities], ktList(kt, 'activities'))
  assert.deepEqual([...INTIMACY_VOCAB.positions], ktList(kt, 'positions'))
  assert.deepEqual([...INTIMACY_VOCAB.toys], ktList(kt, 'toys'))
  assert.deepEqual([...INTIMACY_VOCAB.protection], ktList(kt, 'protection'))
  assert.deepEqual([...INTIMACY_VOCAB.ejaculationLocation], ktList(kt, 'ejaculationLocation'))
  const st = readFileSync(new URL('../../../android-app/app/src/main/java/com/bioscan/fieldterminal/domain/IntimacyStats.kt', import.meta.url), 'utf8')
  assert.deepEqual([...STI_VOCAB.infections], ktList(st, 'infections'))
  assert.deepEqual([...STI_VOCAB.results], ktList(st, 'results'))
  assert.deepEqual([...STI_VOCAB.sites], ktList(st, 'sites'))
  assert.equal(STI_LOOKBACK_DAYS, Number(/STI_LOOKBACK_DAYS = (\d+)L/.exec(st)?.[1]))
})

test('a saved label outside the vocabulary is still shown, after it', () => {
  assert.deepEqual(withSaved(['A', 'B'], ['B', 'Old']), ['A', 'B', 'Old'])
})

test('barrier: a condom throughout counts, partly does not, nothing recorded is unknown', () => {
  assert.equal(barrier(enc('2026-10-01', { protection: ['Condom'] })), true)
  assert.equal(barrier(enc('2026-10-01', { protection: ['Condom (partly)'] })), false)
  assert.equal(barrier(enc('2026-10-01', { protection: ['PrEP', 'Internal condom'] })), true)
  assert.equal(barrier(enc('2026-10-01', { protection: [] })), null)
  assert.equal(barrier(enc('2026-10-01')), null)
})

test('tally: most frequent first, ties by name', () => {
  const rows = [enc('2026-10-01', { positions: ['Doggy', 'Missionary'] }), enc('2026-10-02', { positions: ['Missionary', 'Cowgirl'] }), enc('2026-10-03', { positions: ['Cowgirl'] })]
  assert.deepEqual(tally(rows, (e) => e.positions), [['Cowgirl', 2], ['Missionary', 2], ['Doggy', 1]])
})

test('stats: a partner is new when their first sexual encounter ever falls inside the window', () => {
  const all = [
    enc('2026-08-01', { person_id: 1 }), enc('2026-10-05', { person_id: 1, rounds: 2, orgasm_count: 2, protection: ['Condom'], my_rating: 4 }),
    enc('2026-10-06', { person_id: 2, protection: ['None'], my_rating: 2 }), enc('2026-10-07', { person_id: 3, sexual: false, rounds: null, orgasm_count: null }),
  ]
  const s = intimacyStats(all, [solo('2026-10-08', 2), solo('2026-09-01')], '2026-10-01', '2026-10-31')
  assert.equal(s.encounters, 3)
  assert.equal(s.sexual, 2)
  assert.equal(s.partners, 2)
  assert.equal(s.newPartners, 1)
  assert.equal(s.rounds, 3)
  assert.equal(s.orgasms, 3)
  assert.deepEqual([s.withBarrier, s.withoutBarrier, s.protectionUnknown], [1, 1, 0])
  assert.equal(s.avgRating, 3)
  assert.deepEqual([s.soloDays, s.soloSessions, s.activeDays], [1, 2, 3])
  // the whole history: everyone is new
  assert.equal(intimacyStats(all, [], null, '2026-10-31').newPartners, 2)
})

test('weekly: whole Monday-to-Sunday weeks, days not rounds', () => {
  const days = dayTallies([enc('2026-11-03', { rounds: 3 }), enc('2026-11-03'), enc('2026-11-09')], [solo('2026-11-04'), solo('2026-11-03')])
  const w = weekly(days, '2026-11-04', '2026-11-12')
  assert.deepEqual(w, [{ week: '2026-11-02', partneredDays: 1, soloDays: 2 }, { week: '2026-11-09', partneredDays: 1, soloDays: 0 }])
  assert.equal(days.get('2026-11-03')?.partnered, 4)
})

test('gaps: the longest gap, the current one and the longest streak', () => {
  const g = gapStats(['2026-10-01', '2026-10-02', '2026-10-03', '2026-10-10', '2026-10-12', '2026-12-01'], '2026-10-20')
  assert.deepEqual(g.gaps, [1, 1, 7, 2])
  assert.deepEqual(g.longest, { days: 7, from: '2026-10-03', to: '2026-10-10' })
  assert.equal(g.current, 8)
  assert.equal(g.median, 1.5)
  assert.equal(g.longestStreak, 3)
})

test("a logged time is read by its digits, whatever offset it carries", () => {
  assert.deepEqual(wallClock('2026-11-02T23:40:00+00:00'), { day: '2026-11-02', hour: 23, minute: 40 })
  assert.deepEqual(wallClock('2026-11-02 07:05:00+08'), { day: '2026-11-02', hour: 7, minute: 5 })
  assert.equal(wallClock(null), null)
  // 2026-11-02 is a Monday: row 0, hour 23
  const { grid, n } = hourGrid([enc('2026-11-02', { occurred_at: '2026-11-02T23:40:00+00:00' }), enc('2026-11-02'), enc('2026-11-08', { occurred_at: '2026-11-08T10:00:00+00:00', sexual: false })])
  assert.equal(n, 1)
  assert.equal(grid[0][23], 1)
})

test('co-occurrence counts pairs logged together, the diagonal is the count itself', () => {
  const rows = [enc('2026-10-01', { activities: ['Kissing', 'PIV'] }), enc('2026-10-02', { activities: ['Kissing', 'PIV', 'Oral (given)'] }), enc('2026-10-03', { activities: ['Kissing'] })]
  const c = cooccurrence(rows, (e) => e.activities)
  assert.deepEqual(c.labels, ['Kissing', 'PIV', 'Oral (given)'])
  assert.equal(c.counts[0][0], 3)
  assert.equal(c.counts[0][1], 2)
  assert.equal(c.counts[1][2], 1)
})

test('tag and outcome: only tagged rows count as "without", and small groups are left out', () => {
  const rows = [
    ...[5, 5, 4, 5].map((r) => enc('2026-10-01', { positions: ['Doggy'], my_rating: r })),
    ...[3, 2, 3].map((r) => enc('2026-10-02', { positions: ['Missionary'], my_rating: r })),
    enc('2026-10-03', { my_rating: 1 }), // untagged: neither with nor without
    enc('2026-10-04', { positions: ['Prone'], my_rating: 1 }),
  ]
  const out = tagOutcomes(rows, (e) => e.positions, (e) => e.my_rating)
  const doggy = out.find((o) => o.tag === 'Doggy')!
  assert.equal(doggy.nWith, 4)
  assert.equal(doggy.nWithout, 4)
  assert.equal(doggy.meanWith, 4.75)
  assert.equal(doggy.meanWithout, 2.25)
  assert.ok(doggy.clear)
  assert.ok(!out.some((o) => o.tag === 'Prone'), 'one row is too few')
})

test('completeness and the rows worth filling in', () => {
  const copied = enc('2026-10-01', { sheet_encounter_id: 'sa:12:3' })
  const tagged = enc('2026-10-02', { activities: ['PIV'], occurred_at: '2026-10-02T21:00:00+00:00', my_rating: 4 })
  const meetup = enc('2026-10-03', { sexual: false })
  assert.ok(needsTags(copied))
  assert.ok(!needsTags(tagged))
  assert.ok(!needsTags(meetup))
  const c = Object.fromEntries(completeness([copied, tagged, meetup]).map((x) => [x.field, x.n]))
  assert.equal(c.time, 1)
  assert.equal(c.activities, 1)
  assert.equal(c.rating, 1)
})

test('partners: summaries, repeat rate, new by month, breakdown and the table order', () => {
  const people = [person(1, 'Ann Lee', { score: 8, country: 'X' }), person(2, 'Bo', { score: 9 }), person(3, 'Cy')]
  const es = [enc('2026-09-01', { person_id: 1, protection: ['Condom'], my_rating: 4 }), enc('2026-09-11', { person_id: 1, protection: ['None'] }), enc('2026-10-02', { person_id: 2 }), enc('2026-10-03', { person_id: 2, sexual: false })]
  const ps = partnerSummaries(people, es)
  const ann = ps.find((p) => p.person.id === 1)!
  assert.deepEqual([ann.sexual, ann.first, ann.last, ann.avgRating, ann.barrierKnown, ann.withBarrier], [2, '2026-09-01', '2026-09-11', 4, 2, 1])
  assert.deepEqual(repeatStats(es), { partners: 2, repeat: 1, medianDaysToSecond: 10 })
  assert.deepEqual(newPartnersByMonth(es), [['2026-09', 1], ['2026-10', 1]])
  assert.deepEqual(breakdown(people, (p) => p.country), [['not set', 2], ['X', 1]])
  assert.deepEqual(browsePartners(ps, '', 'recent').map((p) => p.person.id), [2, 1, 3])
  assert.deepEqual(browsePartners(ps, '', 'score').map((p) => p.person.id), [2, 1, 3])
  assert.deepEqual(browsePartners(ps, 'x', 'name').map((p) => p.person.id), [1])
})

test('initials for discreet mode', () => {
  assert.equal(initials('ann marie lee'), 'A. M. L.')
  assert.equal(initials('Ö'), 'Ö.')
  assert.equal(initials('  '), '?')
})

test('solo points: the gap is to the previous activity of any kind', () => {
  const pts = soloPoints([solo('2026-10-05', 2, { orgasmIntensity: 7, loadSize: 3, watchedPorn: true }), solo('2026-10-01')], [enc('2026-10-03')])
  assert.deepEqual(pts.map((p) => [p.date, p.gap]), [['2026-10-01', null], ['2026-10-05', 2], ['2026-10-05', 0]])
  assert.equal(pts[1].intensity, 7)
})

test('pearson matches the hand-worked value and refuses too few pairs', () => {
  const pairs: [number, number][] = Array.from({ length: 12 }, (_, i) => [i, 2 * i + (i % 2 ? 1 : -1)])
  const p = pearson(pairs)!
  assert.ok(p.r > 0.98 && p.clear)
  assert.equal(pearson(pairs.slice(0, 5)), null)
})

test("STI status: pending does not count as the last test, and an untreated positive is open", () => {
  const tests: StiTest[] = [
    { id: 1, test_date: '2026-09-01', infection: 'Chlamydia', result: 'positive', site: null, lab_name: null, treated_on: null, notes: null },
    { id: 2, test_date: '2026-09-01', infection: 'HIV', result: 'negative', site: null, lab_name: null, treated_on: null, notes: null },
    { id: 3, test_date: '2026-10-01', infection: 'Syphilis', result: 'pending', site: null, lab_name: null, treated_on: null, notes: null },
  ]
  const es = [enc('2026-08-15', { person_id: 7 }), enc('2026-05-01', { person_id: 8 }), enc('2026-09-05', { person_id: 9, protection: ['None'] }), enc('2026-09-06', { person_id: 9 })]
  const s = stiStatus(tests, es)
  assert.equal(s.lastTest, '2026-09-01')
  assert.deepEqual(s.latest.map((t) => t.infection), ['HIV', 'Syphilis', 'Chlamydia'])
  assert.deepEqual(s.open.map((t) => t.id), [1])
  assert.deepEqual([s.partnersSince, s.encountersSince, s.withoutBarrierSince, s.protectionUnknownSince], [1, 2, 1, 1])
  assert.deepEqual(partnersBefore('2026-09-01', es), [7])
})

test('event study: the day after against quiet days, read only with enough of both', () => {
  const outcome = new Map<string, number>()
  const events = new Set<string>()
  for (let i = 0; i < 120; i++) {
    const d = new Date(Date.UTC(2026, 5, 1 + i)).toISOString().slice(0, 10)
    outcome.set(d, 50)
    if (i % 10 === 0) events.add(d)
  }
  for (const d of events) { const t = new Date(Date.parse(d + 'T00:00:00Z') + 864e5).toISOString().slice(0, 10); outcome.set(t, 40) }
  const [o0, o1] = eventStudy(events, events, outcome, [0, 1])
  assert.equal(o0.diff, 0)
  assert.equal(o1.diff, -10)
  assert.ok(o1.n >= 5 && o1.baseN > 50)
  const few = eventStudy(new Set([...events].slice(0, 3)), events, outcome, [1])[0]
  assert.equal(few.diff, null)
})
