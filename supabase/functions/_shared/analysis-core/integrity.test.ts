// Analysis hub phase C: the integrity engine's rules, one test per rule.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { assessIntegrity, type IntegrityRow, type StreamInput, type WearSignal } from './integrity.ts'
import type { SeriesFile } from './seriesContract.ts'

const file = JSON.parse(readFileSync(new URL('../metric-registry/metric-series.json', import.meta.url), 'utf8')) as SeriesFile
const series = (id: string) => file.series.find((s) => s.id === id)!

let nextId = 1
const row = (date: string, value: number | null, source = 'zepp'): IntegrityRow => ({ date, value, source, id: nextId++ })
const days = (start: string, n: number) => Array.from({ length: n }, (_, i) => new Date(Date.parse(`${start}T00:00:00Z`) + i * 864e5).toISOString().slice(0, 10))

function stream(id: string, rows: IntegrityRow[], firstSeen?: string | null, lastSeen?: string | null): StreamInput {
  const dates = rows.map((r) => r.date).sort()
  return { series: series(id), rows, firstSeen: firstSeen === undefined ? dates[0] ?? null : firstSeen, lastSeen: lastSeen === undefined ? dates[dates.length - 1] ?? null : lastSeen }
}

const W = { start: '2026-10-01', end: '2026-10-14', today: '2026-10-14' }

test('today is never missing: it is still syncing', () => {
  const r = assessIntegrity({ ...W, streams: [stream('steps', days('2026-10-01', 13).map((d) => row(d, 8000)))], wear: null })
  assert.deepEqual([r.streams[0].states.missing, r.streams[0].states.unknown, r.streams[0].daysDue], [0, 1, 13])
  assert.equal(r.issues.filter((i) => i.kind === 'missing').length, 0)
})

test('every day of the window gets exactly one state', () => {
  const r = assessIntegrity({ ...W, streams: [stream('steps', days('2026-10-01', 10).map((d) => row(d, 8000)))], wear: null })
  const st = r.streams[0]
  assert.equal(Object.values(st.states).reduce((a, b) => a + b, 0), 14)
})

test("a 'daily' stream: missing runs from its first reading on, coverage of the due days", () => {
  const ds = days('2026-10-03', 12).filter((d) => !['2026-10-06', '2026-10-09', '2026-10-10', '2026-10-11'].includes(d))
  const r = assessIntegrity({ ...W, streams: [stream('steps', ds.map((d) => row(d, 9000)), '2026-10-03')], wear: null })
  const st = r.streams[0]
  // 10-01 and 10-02 precede the stream; 10-14 is today, still syncing: unknown even without a row, valid with one
  assert.deepEqual([st.daysDue, st.daysObserved, st.states.missing, st.states.unknown], [11, 7, 4, 2])
  assert.equal(st.coverage, 7 / 11)
  const missing = r.issues.filter((i) => i.kind === 'missing')
  assert.deepEqual(missing.map((i) => [i.firstDate, i.lastDate, i.days, i.severity]), [['2026-10-09', '2026-10-11', 3, 'warning'], ['2026-10-06', '2026-10-06', 1, 'info']])
  assert.deepEqual(missing[0].refs, [{ kind: 'period', series: 'steps', start: '2026-10-09', end: '2026-10-11' }])
})

test("a 'worn' stream is due only on worn days; before the wear signal exists it is unknown", () => {
  const wear: WearSignal = { days: new Set(['2026-10-05', '2026-10-06', '2026-10-07', '2026-10-08']), since: '2026-10-04' }
  const r = assessIntegrity({ ...W, streams: [stream('hrv', [row('2026-10-01', 50), row('2026-10-05', 55), row('2026-10-07', 60)])], wear })
  const st = r.streams[0]
  // due: 05-08 (4 days); observed 05 and 07; missing 06 and 08; 10-01..03 before the signal: unknown (10-01 has a value: valid)
  assert.deepEqual([st.daysDue, st.daysObserved, st.states.missing], [4, 2, 2])
  assert.equal(st.states.valid, 3)
  assert.equal(st.states.unknown, 14 - 3 - 2)
  assert.ok(r.issues.every((i) => i.kind !== 'missing' || i.detail.includes('recorded heart rate')))
})

test("a 'none' stream is never missing: coverage cannot be judged, logging frequency is reported", () => {
  const r = assessIntegrity({ ...W, streams: [stream('energy', [row('2026-10-02', 7, null), row('2026-10-09', 6, null)])], wear: null })
  const st = r.streams[0]
  assert.deepEqual([st.daysDue, st.coverage, st.daysWithValue, st.states.missing, st.freshness], [0, null, 2, 0, 'not_judged'])
  assert.equal(r.issues.length, 0)
})

test('implausible values become an invalid issue linking the records', () => {
  const bad = row('2026-10-04', 140)
  const r = assessIntegrity({ ...W, streams: [stream('rhr', [row('2026-10-03', 52), bad])], wear: null })
  const i = r.issues.find((x) => x.kind === 'invalid')!
  assert.equal(i.severity, 'warning')
  assert.deepEqual(i.refs, [{ kind: 'record', table: 'wearable_daily', id: bad.id }])
  assert.equal(r.streams[0].states.invalid, 1)
})

test('two rows on one day of a daily series: conflict when they disagree, duplicate when they agree', () => {
  const r = assessIntegrity({ ...W, streams: [stream('rhr', [row('2026-10-03', 52), row('2026-10-03', 55, 'health_connect'), row('2026-10-05', 50), row('2026-10-05', 50)])], wear: null })
  assert.deepEqual(r.issues.filter((i) => ['conflict', 'duplicate'].includes(i.kind)).map((i) => [i.kind, i.firstDate, i.refs.length]), [['conflict', '2026-10-03', 2], ['duplicate', '2026-10-05', 2]])
  // several meals on a day are normal
  const meals = assessIntegrity({ ...W, streams: [stream('meal_energy', [row('2026-10-03', 400, 'manual'), row('2026-10-03', 700, 'manual')])], wear: null })
  assert.equal(meals.issues.length, 0)
})

test('a 0 in a daily total is flagged as suspect, not dropped', () => {
  const r = assessIntegrity({ ...W, streams: [stream('steps', [row('2026-10-13', 0), row('2026-10-14', 8000)])], wear: null })
  const i = r.issues.find((x) => x.kind === 'suspect_zero')!
  assert.deepEqual([i.firstDate, i.severity], ['2026-10-13', 'info'])
  assert.equal(r.streams[0].daysObserved, 1) // 10-13 counts as observed; 10-14 is today, not yet due
})

test('staleness: late after 2 days, stale after 7; the wear signal tells ingestion gaps from wear gaps', () => {
  const late = assessIntegrity({ ...W, streams: [stream('steps', [row('2026-10-10', 9000)])], wear: null })
  assert.deepEqual([late.streams[0].freshness, late.streams[0].lagDays, late.issues.find((i) => i.kind === 'stale')!.severity], ['late', 4, 'warning'])
  const fresh = assessIntegrity({ ...W, streams: [stream('steps', [row('2026-10-12', 9000)])], wear: null })
  assert.equal(fresh.streams[0].freshness, 'fresh')
  const worn: WearSignal = { days: new Set(days('2026-10-01', 14)), since: '2026-01-01' }
  const ingestion = assessIntegrity({ ...W, streams: [stream('hrv', [row('2026-10-02', 50)])], wear: worn })
  const stale = ingestion.issues.find((i) => i.kind === 'stale')!
  assert.deepEqual([ingestion.streams[0].freshness, stale.severity], ['stale', 'critical'])
  assert.match(stale.detail, /ingestion gap/)
  const notWorn = assessIntegrity({ ...W, streams: [stream('hrv', [row('2026-10-02', 50)])], wear: { days: new Set(['2026-10-02']), since: '2026-01-01' } })
  assert.match(notWorn.issues.find((i) => i.kind === 'stale')!.detail, /probably not worn/)
  const never = assessIntegrity({ ...W, streams: [stream('steps', [], null, null)], wear: null })
  assert.equal(never.streams[0].freshness, 'never')
})

test('a stable source switch is reported; a source flip-flopping day to day is not', () => {
  const sw = [...days('2026-10-01', 5).map((d) => row(d, 50, 'zepp')), ...days('2026-10-06', 5).map((d) => row(d, 50, 'health_connect'))]
  const r = assessIntegrity({ ...W, streams: [stream('rhr', sw)], wear: null })
  const i = r.issues.find((x) => x.kind === 'source_switch')!
  assert.deepEqual([i.firstDate, i.title], ['2026-10-06', 'Resting heart rate: source zepp -> health_connect on 2026-10-06'])
  const flip = days('2026-10-01', 10).map((d, k) => row(d, 50, k % 2 ? 'zepp' : 'health_connect'))
  assert.equal(assessIntegrity({ ...W, streams: [stream('rhr', flip)], wear: null }).issues.filter((x) => x.kind === 'source_switch').length, 0)
})

test('issues are ordered by severity, then most recent first; totals count them', () => {
  const r = assessIntegrity({ ...W, streams: [stream('steps', [row('2026-10-01', 0), row('2026-10-03', 9000)]), stream('rhr', [row('2026-10-10', 200)])], wear: null })
  const order = r.issues.map((i) => i.severity)
  assert.deepEqual(order, [...order].sort((a, b) => ['critical', 'warning', 'info'].indexOf(a) - ['critical', 'warning', 'info'].indexOf(b)))
  assert.equal(r.totals.critical + r.totals.warning + r.totals.info, r.issues.length)
  assert.equal(r.totals.critical, 1) // steps: nothing for 11 days
})
