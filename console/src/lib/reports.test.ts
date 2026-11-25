import assert from 'node:assert/strict'
import { test } from 'node:test'
import { arrow, fmt, headline, latest, lineStatus, lineSub, reportHref, signed, spanText, type MetricLine, type PeriodReport } from './reports.ts'

const line = (over: Partial<MetricLine>): MetricLine => ({
  id: 'hrv', ref: 'series:hrv', label: 'HRV', unit: 'ms', section: 'recovery', better: 'up', value: 52, prev: 48.1, delta: 3.9,
  usualSwing: 2, days: 6, prevDays: 7, status: 'better', perWeek: false, ...over,
})

const report = (over: Partial<PeriodReport>): PeriodReport => ({
  version: '1', key: 'week:2026-11-16', grain: 'week', label: 'Week of 16 Nov 2026', start: '2026-11-16', end: '2026-11-22',
  prevStart: '2026-11-09', prevEnd: '2026-11-15', generatedOn: '2026-11-23', metrics: [], micro: { foodDays: 0, lines: [] }, regimen: [],
  lab: [], foodStool: [], summary: { better: ['HRV'], worse: [], working: [], wasted: [], notFollowed: [] }, suggestions: [], ...over,
})

test('numbers, signs and arrows', () => {
  assert.equal(fmt(1234.4), '1,234')
  assert.equal(fmt(7.25), '7.3')
  assert.equal(fmt(null), '–')
  assert.equal(signed(3.94), '+3.9')
  assert.equal(signed(-1.5), '−1.5')
  assert.equal(signed(0.01), '±0')
  assert.equal(arrow(2), '▲')
  assert.equal(arrow(-2), '▼')
  assert.equal(arrow(0), '→')
})

test('a line: status words in status colours, the comparison in words', () => {
  assert.deepEqual(lineStatus(line({})), { word: 'better', tone: 'ok' })
  assert.deepEqual(lineStatus(line({ status: 'worse' })), { word: 'worse', tone: 'bad' })
  assert.equal(lineStatus(line({ status: 'up' })).tone, null, 'a metric with no better direction gets no status colour')
  assert.equal(lineSub(line({}), 'week'), '+3.9 vs 48.1 the week before · 6 days')
  assert.equal(lineSub(line({ perWeek: true, prev: null, delta: null }), 'month'), 'nothing the month before · per week')
})

test('the latest report, its span, address and headline', () => {
  const w = report({})
  const m = report({ key: 'month:2026-10', grain: 'month', start: '2026-10-01', end: '2026-10-31', summary: { better: [], worse: [], working: [], wasted: [], notFollowed: [] } })
  assert.equal(latest([m, w])?.key, 'week:2026-11-16')
  assert.equal(latest([m, w], 'month')?.key, 'month:2026-10')
  assert.equal(latest([]), null)
  assert.equal(spanText(w), '16 – 22 Nov')
  assert.equal(spanText(m), '1 – 31 Oct 2026')
  assert.equal(reportHref('week:2026-11-16'), '#/lab/reports?key=week%3A2026-11-16')
  assert.equal(headline(w), '1 better than the week before.')
  assert.equal(headline(m), 'Everything steady against the period before.')
})
