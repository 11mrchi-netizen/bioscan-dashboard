import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { TABLE_SCOPE, type ScopeSettings } from './scope.ts'
import { checkCall, literatureQueryOk, MODES, REGISTERED_SPACE, toOpenAiTools, TOOLS, toolsFor } from './tools.ts'

const on: ScopeSettings = { enabled: true, optIn: [] }

test('every table a tool reads is classified, and no tool reads a never or internal table', () => {
  for (const t of TOOLS) {
    for (const table of t.reads) {
      const s = TABLE_SCOPE[table]
      assert.ok(s, `${t.name} reads unclassified ${table}`)
      assert.ok(s.scope === 'shared' || s.scope === 'opt_in', `${t.name} reads ${s.scope} ${table}`)
    }
  }
})

test('each mode has read tools; only ask and coach propose; explain proposes nothing', () => {
  for (const m of MODES) assert.ok(toolsFor(m, on).some((t) => t.kind === 'read'), m)
  assert.ok(toolsFor('explain', on).every((t) => t.kind === 'read'))
  assert.ok(toolsFor('ask', on).some((t) => t.name === 'propose_registered_question'))
  assert.ok(toolsFor('coach', on).some((t) => t.name === 'propose_goal'))
})

test('a tool whose tables are not shared is not offered', () => {
  assert.ok(!toolsFor('ask', on).some((t) => t.name === 'get_health_concerns'))
  assert.ok(toolsFor('ask', { enabled: true, optIn: ['health_concerns'] }).some((t) => t.name === 'get_health_concerns'))
  assert.deepEqual(toolsFor('ask', { enabled: false, optIn: [] }).filter((t) => t.reads.length), [])
})

test('the registered space matches the registered_questions checks in the migration', () => {
  const sql = readFileSync(new URL('../../../migrations/20261016_evidence_ledger.sql', import.meta.url), 'utf8')
  const list = (col: string) => [...sql.match(new RegExp(`${col} text not null check \\(${col} in \\(([^)]*)\\)`))![1].matchAll(/'([a-z_]+)'/g)].map((m) => m[1])
  assert.deepEqual(list('exposure'), REGISTERED_SPACE.exposures)
  assert.deepEqual(list('outcome'), REGISTERED_SPACE.outcomes)
  assert.match(sql, /lag between 0 and 3/)
})

test('tool calls are checked for name, mode, scope and arguments', () => {
  assert.deepEqual(checkCall('explain', on, 'get_results', { kind: 'expected_band', key: 'hrv' }), { ok: true })
  assert.equal(checkCall('explain', on, 'propose_goal', { text: 'sleep more' }).ok, false)
  assert.equal(checkCall('ask', on, 'drop_table', {}).ok, false)
  assert.equal(checkCall('ask', on, 'get_results', { kind: 'vibes' }).ok, false)
  assert.equal(checkCall('ask', on, 'get_series', { series: 'hrv', from: '2026-10-01' }).ok, false, 'to is required')
  assert.equal(checkCall('ask', on, 'get_series', { series: 'hrv', from: '2026-10-01', to: '2026-10-27', extra: 1 }).ok, false)
  assert.equal(checkCall('ask', on, 'propose_registered_question', { exposure: 'screen_bed_min', outcome: 'hrv', lag: 1, no_effect: 3, why: 'x' }).ok, false)
  assert.equal(checkCall('ask', on, 'propose_registered_question', { exposure: 'late_meal', outcome: 'sleep_hours', lag: 0, no_effect: 15, why: 'x' }).ok, true)
})

test('a literature query carries no personal values', () => {
  assert.ok(literatureQueryOk('alcohol heart rate variability next morning'))
  assert.ok(!literatureQueryOk('my HRV is 38 after wine'))
  assert.equal(checkCall('ask', on, 'search_literature', { query: 'HRV of 38 ms' }).ok, false)
})

test('declarations are OpenAI-compatible', () => {
  const decl = toOpenAiTools(toolsFor('ask', on))
  assert.ok(decl.every((d) => d.type === 'function' && d.function.parameters.type === 'object'))
})
