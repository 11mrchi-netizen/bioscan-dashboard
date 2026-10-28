// Run: node --test console/src/lib/workflow.test.ts
// Analysis hub phase E: the console's workflow end to end, on the same pure code the screens use. A data problem links
// to its records and its period; an evidence item's links lead back to the same item; a decision keeps a link to the
// evidence it rested on; a saved view restores its route and range.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { assessIntegrity } from '../../../supabase/functions/_shared/analysis-core/integrity.ts'
import { decisionRow, evidenceItems, place, type DecisionRecord, type ResultRow } from '../../../supabase/functions/_shared/analysis-core/evidenceItems.ts'
import { hrefFor, integrityHref, itemIdFromParams } from './links.ts'
import { readFilters } from './dataFilters.ts'
import { openView, viewPath, viewRow } from './savedViews.ts'
import { seriesById } from './schema.ts'

/** '#/data?table=x&id=1' -> { route: 'data', params } */
function follow(href: string | null): { route: string; params: URLSearchParams } {
  assert.ok(href, 'a link was expected')
  const [path, query = ''] = href.replace(/^#\//, '').split('?')
  return { route: path, params: new URLSearchParams(query) }
}

test('a data problem leads to its records, its period and its day', () => {
  const W = { start: '2026-10-01', end: '2026-10-14', today: '2026-10-14' }
  const rows = [
    { date: '2026-10-02', value: 52, source: 'zepp', id: 70 },
    { date: '2026-10-03', value: 140, source: 'zepp', id: 77 }, // implausible
  ]
  const steps = ['2026-10-01', '2026-10-02', '2026-10-06', '2026-10-07'].map((d, i) => ({ date: d, value: 9000, source: 'zepp', id: 200 + i }))
  const r = assessIntegrity({
    ...W,
    streams: [
      { series: seriesById('rhr')!, rows, firstSeen: '2026-10-02', lastSeen: '2026-10-03' },
      { series: seriesById('steps')!, rows: steps, firstSeen: '2026-10-01', lastSeen: '2026-10-07' },
    ],
    wear: null,
  })

  const invalid = r.issues.find((i) => i.kind === 'invalid')!
  const toRecord = follow(hrefFor(invalid.refs[0]))
  assert.equal(toRecord.route, 'data')
  assert.deepEqual([readFilters(toRecord.params).table, readFilters(toRecord.params).id], ['wearable_daily', '77'])

  const missing = r.issues.find((i) => i.kind === 'missing' && i.seriesId === 'steps' && i.firstDate === '2026-10-03')!
  const toPeriod = follow(hrefFor(missing.refs[0]))
  assert.deepEqual([toPeriod.route, toPeriod.params.get('series'), toPeriod.params.get('start'), toPeriod.params.get('end')], ['explore', 'steps', '2026-10-03', '2026-10-05'])

  const toDay = follow(hrefFor({ kind: 'day', series: 'meal_energy', date: '2026-10-05' }))
  assert.deepEqual([readFilters(toDay.params).table, readFilters(toDay.params).from, readFilters(toDay.params).to], ['meals', '2026-10-05', '2026-10-05'])

  const toIntegrity = follow(integrityHref('steps'))
  assert.deepEqual([toIntegrity.route, toIntegrity.params.get('series')], ['integrity', 'steps'])
})

const R = (kind: string, key: string, payload: unknown): ResultRow => ({ kind, key, as_of: '2026-10-26', engine_version: '3.0.0', inputs_hash: 'h', state: null, payload, calculated_at: '2026-10-26T20:30:00Z' })
const ROWS = [
  R('relationship', 'alcohol_hrv', { label: 'Alcohol and next-morning HRV', outcome: 'hrv', state: 'clear', n: 200, noEffect: 2, effect: { value: -6, lo: -9, hi: -2, unit: '%' } }),
  R('registered_question', 'reg_7', { label: 'Late meal and sleep', outcome: 'sleep', state: 'not_yet', n: 30, noEffect: 10, effect: null }),
  R('experiment', '11', { name: 'Magnesium', stage: 'complete', metrics: [{ metric: 'hrv', verdict: 'negligible', n0: 20, n1: 20, diff: { value: 0.3, lo: -1, hi: 1.5, unit: '%' }, zone: 2 }] }),
  R('intervention_its', '3', { state: 'collecting', metrics: [{ metric: 'rhr', label: 'Resting HR', activeDays: 5 }] }),
]

test("every evidence item's result link leads back to the same item; question links too", () => {
  const items = evidenceItems(ROWS, { causeOf: (id) => (id === 'alcohol_hrv' ? 'alcohol_any' : null) })
  assert.equal(items.length, 4)
  for (const it of items) {
    const { route, params } = follow(hrefFor(it.result))
    assert.equal(route, 'evidence')
    assert.equal(itemIdFromParams(params), it.id, it.id)
  }
  assert.equal(itemIdFromParams(follow(hrefFor({ kind: 'question', questionId: 'alcohol_hrv' })).params), 'q:alcohol_hrv')
  assert.equal(itemIdFromParams(follow(hrefFor({ kind: 'question', questionId: 'reg_7' })).params), 'reg:7')
})

test('a decision keeps a link to its evidence and moves the item to decided', () => {
  const item = evidenceItems(ROWS).find((i) => i.id === 'q:alcohol_hrv')!
  assert.equal(place(item, undefined, '2026-10-28').state, 'decision_review')
  const built = decisionRow(item, { decision: 'adopt', rationale: 'No alcohol on weekdays from now on.' }, '2026-10-28')
  assert.ok('row' in built)
  const rec = { id: 1, decided_at: '2026-10-28T09:00:00Z', ...built.row } as unknown as DecisionRecord
  assert.equal(itemIdFromParams(follow(hrefFor(rec.evidence.result)).params), item.id)
  assert.equal(place(item, rec, '2026-10-28').state, 'decided')
  // a later night that unsettles the verdict sends it back to review, with the decision's evidence still readable
  const later = evidenceItems([R('relationship', 'alcohol_hrv', { label: 'Alcohol and next-morning HRV', outcome: 'hrv', state: 'unclear', n: 240, noEffect: 2, effect: { value: -2, lo: -5, hi: 1, unit: '%' } })])[0]
  assert.equal(place(later, rec, '2026-11-20').state, 'decision_review')
  assert.equal(rec.evidence.effect?.value, -6)
})

test('a saved view restores its route and range; a preset stays relative to today', () => {
  const now = new Date(2026, 9, 28, 12)
  const row = viewRow('HRV weekly', '/explore', '?series=hrv,rhr&grain=week', { start: '2026-07-31', end: '2026-10-28' }, now)
  assert.ok('path' in row)
  assert.deepEqual([row.path, row.range], ['/explore?series=hrv,rhr&grain=week', { start: '2026-07-31', end: '2026-10-28' }]) // 89 days: not a preset
  const later = new Date(2026, 10, 15, 12)
  const ninety = viewRow('Integrity 90D', '/integrity', '', { start: '2026-07-30', end: '2026-10-28' }, now)
  assert.ok('path' in ninety)
  assert.deepEqual(ninety.range, { days: 90 })
  assert.deepEqual(openView(ninety, later), { path: '/integrity', range: { start: '2026-08-17', end: '2026-11-15' } })
  const custom = viewRow('Autumn', '/data', '?table=meals', { start: '2026-09-01', end: '2026-09-30' }, now)
  assert.ok('path' in custom)
  assert.deepEqual(openView(custom, later)?.range, { start: '2026-09-01', end: '2026-09-30' })
  assert.deepEqual(viewRow('x', '/login', '', { start: '2026-09-01', end: '2026-09-30' }, now), { error: 'only a workspace view can be saved' })
  assert.deepEqual(viewRow('  ', '/data', '', { start: '2026-09-01', end: '2026-09-30' }, now), { error: 'a name of 1 to 80 characters' })
  assert.equal(openView({ path: '//evil.example/x', range: { days: 7 } }), null)
  assert.equal(viewPath('/models', '?'), '/models')
})
