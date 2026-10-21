import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { buildConcernSeries, currentObservations, shiftDate } from './healthSeries.ts'
import type { ConcernRow, MarkRow, ObservationRow, SignalRow } from './healthSeries.ts'
import { concern, obs, sig } from './healthTestKit.ts'
import { measureEpisodes, summariseConcern } from './healthEpisodes.ts'
import { validateVocab, vocabRows } from './healthVocab.ts'
import type { HealthVocab } from './healthVocab.ts'

const vocab = JSON.parse(readFileSync(new URL('../health-vocab/health-vocab.json', import.meta.url), 'utf8')) as HealthVocab
const migration = readFileSync(new URL('../../../migrations/20261101_health_concerns.sql', import.meta.url), 'utf8')

const D0 = '2026-06-01'
const day = (n: number) => shiftDate(D0, n)

/** A concern's check-ins from a compact script: number = severity that day, 0 = explicit symptom-free, null = no check-in. */
function scripted(script: (number | null)[], c = concern('k')) {
  const o: ObservationRow[] = []
  const s: SignalRow[] = []
  script.forEach((v, i) => {
    if (v === null) return
    const row = obs(i, { concern_id: c.id, symptom_free: v === 0 })
    o.push(row)
    if (v > 0) s.push(sig(row, v))
  })
  return { o, s, c }
}

function cellsOf(c: ConcernRow, o: ObservationRow[], s: SignalRow[], days: number, extra: ConcernRow[] = []) {
  return buildConcernSeries([c, ...extra], o, s, D0, day(days - 1)).series.get(c.id)!.cells
}

test('the vocabulary is valid and the migration seeds exactly it', () => {
  assert.deepEqual(validateVocab(vocab), [])
  for (const r of vocabRows(vocab)) {
    const parent = r.parent === null ? 'null' : `'${r.parent}'`
    const line = `('${r.code}', '${r.kind}', '${r.label}', ${parent}, ${r.sided}, '${vocab.vocabVersion}')`
    assert.ok(migration.includes(line), `seed is missing ${line}`)
  }
  const seeded = migration.match(/\('(sym\.)?[a-z_]+', '(region|symptom)', /g) ?? []
  assert.equal(seeded.length, vocabRows(vocab).length)
})

test('the validator catches an unknown muscle, a muscle that loads nothing and a bad code', () => {
  const broken: HealthVocab = JSON.parse(JSON.stringify(vocab))
  broken.regions[1].muscles = ['necks']
  broken.symptoms[0].code = 'pain'
  const p = validateVocab(broken)
  assert.ok(p.some((x) => x.includes('unknown muscle')))
  assert.ok(p.some((x) => x.includes('"neck" loads no region')))
  assert.ok(p.some((x) => x.includes('malformed')))
})

test('no check-in is unobserved, never zero; "nothing" is an explicit zero', () => {
  const { o, s, c } = scripted([0, null, 3])
  const cells = cellsOf(c, o, s, 3)
  assert.deepEqual(cells.map((x) => [x.observed, x.severity, x.present]), [[true, 0, false], [false, null, false], [true, 3, true]])
})

test('a sweep is everything bothering the person: concerns without a signal are symptom-free that day', () => {
  const a = concern('a', { regions: [{ code: 'knee', side: 'right' }] })
  const b = concern('b', { regions: [{ code: 'achilles', side: 'left' }] })
  const sweep = obs(0, { scope: 'sweep', concern_id: null })
  // unassigned signal matched by region and side to concern a
  const s = [sig(sweep, 5, { concern_id: null })]
  const res = buildConcernSeries([a, b], [sweep], s, D0, D0)
  assert.equal(res.series.get('a')!.cells[0].severity, 5)
  assert.equal(res.series.get('b')!.cells[0].severity, 0)
  assert.equal(res.series.get('b')!.cells[0].observed, true)
  assert.equal(res.unassigned.length, 0)
  // a signal no concern claims is returned as new
  const sweep2 = obs(0, { scope: 'sweep', concern_id: null })
  const res2 = buildConcernSeries([a, b], [sweep2], [sig(sweep2, 4, { concern_id: null, region: 'wrist' })], D0, D0)
  assert.equal(res2.unassigned.length, 1)
})

test('only the newest entry of a correction chain counts, and a retraction withdraws it', () => {
  const first = obs(0, { symptom_free: false })
  const fix = obs(0, { symptom_free: true, supersedes: first.id })
  assert.deepEqual(currentObservations([first, fix]).map((x) => x.id), [fix.id])
  const cells = cellsOf(concern('k'), [first, fix], [sig(first, 6)], 1)
  assert.equal(cells[0].severity, 0)
  const retract = obs(0, { supersedes: fix.id, retracted: true, symptom_free: true })
  const cells2 = cellsOf(concern('k'), [first, fix, retract], [sig(first, 6)], 1)
  assert.equal(cells2[0].observed, false)
})

test('"nothing since" fills only unobserved days, marked as recalled', () => {
  const direct = obs(2, { symptom_free: false })
  const recall = obs(4, { symptom_free: true, recall: 'since_last', recall_from: day(1) })
  const cells = cellsOf(concern('k'), [direct, recall], [sig(direct, 3)], 5)
  assert.deepEqual(cells.map((x) => [x.observed, x.recalled, x.severity]), [
    [false, false, null], [true, true, 0], [true, false, 3], [true, true, 0], [true, false, 0],
  ])
})

test('an episode ends after a symptom-free run spanning the resolution days with three observed days', () => {
  // days 0-2 symptomatic; then symptom-free check-ins on days 4, 7 and 10 (span 7 from day 4)
  const { o, s, c } = scripted([4, 5, 3, null, 0, null, null, 0, null, null, 0, null])
  const eps = measureEpisodes(c, cellsOf(c, o, s, 12), [], day(11))
  assert.equal(eps.length, 1)
  const e = eps[0]
  assert.equal(e.start, day(0))
  assert.equal(e.end, day(4))
  assert.equal(e.endEarliest, day(3))
  assert.equal(e.open, false)
  assert.equal(e.peakSeverity, 5)
  assert.deepEqual(e.durationDays, { min: 3, max: 4 })
})

test('single symptom-free days inside a flare do not end it; two check-ins are not enough to resolve', () => {
  const { o, s, c } = scripted([4, 0, 4, 0, null, null, null, null, 0, null])
  const eps = measureEpisodes(c, cellsOf(c, o, s, 10), [], day(9))
  assert.equal(eps.length, 1)
  assert.equal(eps[0].open, true)
})

test('systemic concerns resolve after three symptom-free days', () => {
  const c = concern('k', { kind: 'systemic', regions: [] })
  const { o, s } = scripted([5, 4, 0, 0, 0], c)
  const eps = measureEpisodes(c, cellsOf(c, o, s, 5), [], day(4))
  assert.equal(eps[0].end, day(2))
})

test('an end mark ends an episode on its date (legacy rows); a start mark opens one', () => {
  const { o, s, c } = scripted([4, null, null, null, null])
  const marks: MarkRow[] = [{ concern_id: 'k', kind: 'end', date: day(3), retracted_at: null }]
  const e = measureEpisodes(c, cellsOf(c, o, s, 5), marks, day(4))[0]
  assert.equal(e.end, day(3))
  assert.equal(e.endedBy, 'mark')
  const retracted = measureEpisodes(c, cellsOf(c, o, s, 5), [{ ...marks[0], retracted_at: 'x' }], day(4))[0]
  assert.equal(retracted.open, true)
})

test('a long silent gap splits an episode unless a merge mark spans it; a shorter one is flagged', () => {
  const script: (number | null)[] = [4, ...Array(30).fill(null), 4]
  const { o, s, c } = scripted(script)
  const split = measureEpisodes(c, cellsOf(c, o, s, 32), [], day(31))
  assert.equal(split.length, 2)
  assert.equal(split[0].boundaryUncertain, true)
  assert.equal(split[1].boundaryUncertain, true)
  const merged = measureEpisodes(c, cellsOf(c, o, s, 32), [{ concern_id: 'k', kind: 'merge_across', date: day(10), retracted_at: null }], day(31))
  assert.equal(merged.length, 1)
  assert.equal(merged[0].gap, true)
  const short = scripted([4, ...Array(20).fill(null), 4])
  const flagged = measureEpisodes(short.c, cellsOf(short.c, short.o, short.s, 22), [], day(21))
  assert.equal(flagged.length, 1)
  assert.equal(flagged[0].gap, true)
})

test('an onset check-in inside an episode starts a new one', () => {
  const { o, s, c } = scripted([4, 4, null, null])
  const knock = obs(3, { onset: true })
  const eps = measureEpisodes(c, cellsOf(c, [...o, knock], [...s, sig(knock, 6)], 4), [], day(3))
  assert.equal(eps.length, 2)
  assert.equal(eps[1].start, day(3))
  assert.equal(eps[0].endedBy, 'split')
})

test('episode ids are the same on recomputation', () => {
  const { o, s, c } = scripted([4, 4, 0, 0, 0, 0, 0, 0, 0])
  const a = measureEpisodes(c, cellsOf(c, o, s, 9), [], day(8)).map((e) => e.id)
  const b = measureEpisodes(c, cellsOf(c, o, s, 9), [], day(8)).map((e) => e.id)
  assert.deepEqual(a, b)
})

test('state words follow the rules', () => {
  const at = (script: (number | null)[]) => {
    const { o, s, c } = scripted(script)
    return summariseConcern(c, cellsOf(c, o, s, script.length), [], day(script.length - 1))
  }
  assert.equal(at([4, 4, ...Array(8).fill(null)]).state, 'unobserved')
  assert.equal(at([4, 4]).state, 'active')
  assert.equal(at([3, 3, 3, 3, 5, 6, 6]).state, 'worsening')
  assert.equal(at([7, 7, 6, 6, 4, 4, 3]).state, 'improving')
  assert.equal(at([4, 4, 5, 4, 5, 4, 4]).state, 'stable')
  assert.equal(at([4, 4, 0, 0, 0, 0, 0, 0, 0, 0]).state, 'resolved')
  assert.equal(at([0, 0, 0]).state, 'resolved')
})

test('recurrence counts episodes and the symptom-free days between them', () => {
  const script: (number | null)[] = [4, 4, 0, 0, 0, 0, 0, 0, 0, ...Array(10).fill(0), 5, 0, 0, 0, 0, 0, 0, 0]
  const { o, s, c } = scripted(script)
  const sum = summariseConcern(c, cellsOf(c, o, s, script.length), [], day(script.length - 1))
  assert.equal(sum.recurrence.episodes, 2)
  assert.equal(sum.recurrence.recurrences, 1)
  assert.deepEqual(sum.recurrence.intervals, [{ min: 17, max: 17 }]) // both boundaries known to the day
  assert.equal(sum.recurrence.within30, 1)
})

test('safety prompts: severe, fever, long illness, rising', () => {
  const { o, s, c } = scripted([2, 4, 8])
  const sum = summariseConcern(c, cellsOf(c, o, s, 3), [], day(2))
  assert.ok(sum.safety.some((x) => x.code === 'severe'))
  assert.ok(sum.safety.some((x) => x.code === 'rising'))
  const ill = concern('k', { kind: 'systemic', regions: [] })
  const days = Array.from({ length: 12 }, (_, i) => obs(i, { concern_id: 'k' }))
  const sigs = days.map((r, i) => sig(r, 4, { symptom: 'sym.fever', region: null, side: null, temperature_c: i === 11 ? 39.2 : 37.5 }))
  const sumIll = summariseConcern(ill, cellsOf(ill, days, sigs, 12), [], day(11))
  assert.ok(sumIll.safety.some((x) => x.code === 'fever'))
  assert.ok(sumIll.safety.some((x) => x.code === 'long_illness'))
})

test('data quality counts check-ins against what comparisons need', () => {
  const script: (number | null)[] = Array.from({ length: 90 }, (_, i) => (i % 2 === 0 ? 0 : null))
  const { o, s, c } = scripted(script)
  const q = summariseConcern(c, cellsOf(c, o, s, 90), [], day(89)).quality
  assert.equal(q.coverage90, 0.5)
  assert.equal(q.word, 'GOOD') // 0.5 of the 0.6 needed is 83%
  assert.equal(q.symptomFreeObservedDays90, 45)
})
