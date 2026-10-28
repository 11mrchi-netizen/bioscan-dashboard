import assert from 'node:assert/strict'
import { test } from 'node:test'
import { compact, display, makeExecutor, type Db, type Query, type Row } from './executor.ts'

/** An in-memory Db with the same query semantics as the Supabase adapter (string comparison for ranges). */
function fakeDb(tables: Record<string, Row[]>): { db: Db; queries: Query[] } {
  const queries: Query[] = []
  const db: Db = async (q) => {
    queries.push(q)
    let rows = [...(tables[q.table] ?? [])]
    for (const [k, v] of Object.entries(q.eq ?? {})) rows = rows.filter((r) => r[k] === v)
    for (const [k, v] of Object.entries(q.in ?? {})) rows = rows.filter((r) => v.includes(r[k] as string))
    for (const [k, v] of Object.entries(q.gte ?? {})) rows = rows.filter((r) => String(r[k]) >= v)
    for (const [k, v] of Object.entries(q.lte ?? {})) rows = rows.filter((r) => String(r[k]) <= v)
    if (q.order) rows.sort((a, b) => (String(a[q.order!.column]) < String(b[q.order!.column]) ? -1 : 1) * (q.order!.ascending ? 1 : -1))
    const cols = q.columns.split(',')
    return rows.slice(0, q.limit ?? rows.length).map((r) => Object.fromEntries(cols.filter((c) => c in r).map((c) => [c, r[c]])))
  }
  return { db, queries }
}

const today = '2026-10-28'
const on = { enabled: true, optIn: [] as never[] }

test('results: newest row per key, routine-derived results withheld', async () => {
  const { db } = fakeDb({
    analysis_results: [
      { kind: 'relationship', key: 'alcohol_any->hrv@1', as_of: '2026-10-27', state: 'unclear', payload: { effect: -6.2 } },
      { kind: 'relationship', key: 'alcohol_any->hrv@1', as_of: '2026-10-20', state: 'unclear', payload: { effect: -5 } },
      { kind: 'relationship', key: 'screen_bed_min->sleep_hours@0', as_of: '2026-10-27', state: 'unclear', payload: { effect: -11 } },
    ],
  })
  const out = (await makeExecutor(db, on, today)('get_results', { kind: 'relationship' })) as { results: Row[] }
  assert.equal(out.results.length, 1)
  assert.equal(out.results[0].as_of, '2026-10-27')
  assert.ok(!JSON.stringify(out).includes('screen_bed_min'))
})

test('series: days on the local date, the series method, display forms, and nothing invented for empty days', async () => {
  const { db } = fakeDb({
    sleep_daily: [
      { date: '2026-10-26', hours: 7.034, source: 'zepp' },
      { date: '2026-10-27', hours: 6.5, source: 'zepp' },
    ],
  })
  const out = (await makeExecutor(db, on, today)('get_series', { series: 'sleep_hours', from: '2026-10-25', to: '2026-10-28' })) as { points: Row[] }
  assert.deepEqual(out.points.map((p) => p.display), ['7 h 02 min', '6 h 30 min'])
  const week = (await makeExecutor(db, on, today)('get_series', { series: 'sleep_hours', from: '2026-10-26', to: '2026-10-28', grain: 'week' })) as { points: Row[] }
  assert.equal(week.points[0].coverage, '2 of 3 days')
})

test('series: per-event rows sum per local day and respect the series filter', async () => {
  const { db } = fakeDb({
    exercise_sessions: [
      { start_time: '2026-10-27T07:00:00+00:00', duration_min: 30, type: 'run', source: 'zepp' },
      { start_time: '2026-10-27T18:00:00+00:00', duration_min: 20, type: 'run', source: 'zepp' },
      { start_time: '2026-10-27T19:00:00+00:00', duration_min: 60, type: 'strength', source: 'zepp' },
    ],
  })
  const out = (await makeExecutor(db, on, today)('get_series', { series: 'run_duration', from: '2026-10-27', to: '2026-10-27' })) as { points: Row[] }
  assert.deepEqual(out.points.map((p) => [p.value, p.n]), [[50, 2]])
})

test('context events: free text dropped without notes, sessions with clock and duration, supplements counted', async () => {
  const { db } = fakeDb({
    illnesses: [{ name: 'cold', status: 'active', severity: 4, start_date: '2026-10-25', end_date: null, notes: 'after the dinner with Kevin', symptoms: 'sore throat' }],
    injuries: [{ part: 'knee', type: 'strain', status: 'recovered', start_date: '2026-01-02', end_date: '2026-02-01' }],
    rest_days: [],
    exercise_sessions: [{ type: 'run', start_time: '2026-10-27T19:40:00+00:00', duration_min: 58, rpe: 8 }],
    supplement_log: [
      { supplement_name: 'Magnesium', taken_at: '2026-10-26T22:00:00+00:00' },
      { supplement_name: 'Magnesium', taken_at: '2026-10-27T22:00:00+00:00' },
    ],
  })
  const out = (await makeExecutor(db, on, today)('get_context_events', { from: '2026-10-21', to: '2026-10-28' })) as Record<string, Row[]>
  assert.deepEqual(out.illnesses, [{ name: 'cold', status: 'active', severity: 4, start_date: '2026-10-25', end_date: null }])
  assert.deepEqual(out.injuries, [], 'an injury that ended before the span is not context')
  assert.deepEqual(out.sessions[0], { date: '2026-10-27', type: 'run', start: '19:40', duration: '58 min', distance_km: undefined, avg_hr: undefined, rpe: 8 })
  assert.deepEqual(out.supplements, [{ name: 'Magnesium', times: 2 }])
})

test('decisions: the rationale is free text and needs notes shared', async () => {
  const tables = { decisions: [{ id: 1, item_id: 'its:4', title: 'Creatine', decision: 'continue_testing', rationale: 'my own words', decided_at: '2026-09-30T10:00:00Z' }] }
  const without = JSON.stringify(await makeExecutor(fakeDb(tables).db, on, today)('get_decisions', {}))
  const withNotes = JSON.stringify(await makeExecutor(fakeDb(tables).db, { enabled: true, optIn: ['notes'] }, today)('get_decisions', {}))
  assert.ok(!without.includes('my own words') && withNotes.includes('my own words'))
})

test('integrity: one series view with its issues, routine streams never listed', async () => {
  const { db } = fakeDb({
    analysis_results: [{ kind: 'integrity', as_of: '2026-10-28', payload: {
      streams: [
        { seriesId: 'hrv', label: 'HRV', freshness: 'fresh', lastSeen: '2026-10-28', lagDays: 0, daysDue: 28, daysObserved: 27, sources: ['zepp'], issues: 1 },
        { seriesId: 'screen_bed_min', label: 'Phone before bed', freshness: 'fresh', daysDue: 28, daysObserved: 28 },
      ],
      issues: [{ seriesId: 'hrv', kind: 'source_switch', severity: 'info', title: 'Source switch', firstDate: '2026-10-12', lastDate: '2026-10-12', days: 1 }],
    } }],
  })
  const one = (await makeExecutor(db, on, today)('get_integrity', { series: 'hrv' })) as { stream: Row; issues: Row[] }
  assert.equal(one.stream.coverage, '27 of 28 days')
  assert.equal(one.issues[0].kind, 'source_switch')
  assert.ok(!JSON.stringify(await makeExecutor(db, on, today)('get_integrity', {})).includes('Phone before bed'))
})

test('a span over 400 days, an unknown series and a later-phase tool are refused in words', async () => {
  const run = makeExecutor(fakeDb({}).db, on, today)
  assert.match(String(((await run('get_series', { series: 'hrv', from: '2024-01-01', to: today })) as Row).error), /400 days/)
  assert.equal(((await run('get_series', { series: 'nope', from: today, to: today })) as Row).error, 'unknown series')
  assert.match(String(((await run('get_labs', {})) as Row).error), /not available yet/)
})

test('a failing read becomes an error the model can say, not a crash', async () => {
  const run = makeExecutor(async () => { throw new Error('boom') }, on, today)
  assert.match(String(((await run('get_results', { kind: 'typicality' })) as Row).error), /could not read/)
})

test('compact keeps the newest items of long arrays and display forms read naturally', () => {
  assert.deepEqual(compact({ a: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] }, 0, 3), { a: [{ omitted_earlier: 7 }, 8, 9, 10] })
  assert.equal(display(52.04, 'ms'), '52 ms')
  assert.equal(display(6.2, '/10'), '6.2/10')
  assert.equal(display(null, 'ms'), null)
})
