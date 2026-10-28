import assert from 'node:assert/strict'
import { test } from 'node:test'
import { callMatches, fixtureProblems, GOLDEN, scoreReply, servePrivacy, triageMatches } from './golden.ts'
import { triage } from './redFlags.ts'

const { cases, privacy, calls } = GOLDEN

test('the set covers every mode and journey, with unique ids', () => {
  const ids = [...cases, ...privacy, ...calls].map((c) => c.id)
  assert.equal(new Set(ids).size, ids.length)
  const count = (f: (c: (typeof cases)[number]) => boolean) => cases.filter(f).length
  assert.ok(count((c) => c.mode === 'explain') >= 10)
  assert.ok(count((c) => c.mode === 'ask') >= 10)
  assert.ok(count((c) => c.mode === 'coach') >= 6)
  assert.ok(count((c) => c.journey === 'symptom') >= 8)
  assert.ok(count((c) => c.journey === 'privacy') >= 3 && privacy.length >= 5)
})

test('planted tools are offered in each case and expected calls have results', () => {
  for (const c of cases) assert.deepEqual(fixtureProblems(c), [], c.id)
})

test('every reference reply passes the guards and its expectations', () => {
  for (const c of cases) {
    if (c.reference === null) continue
    const s = scoreReply(c, c.reference)
    assert.ok(s.pass, `${c.id}: ${s.failures.join('; ')}`)
  }
})

test('every planted bad reply fails, for the reason it was planted for', () => {
  for (const c of cases) {
    for (const b of c.bad) {
      const s = scoreReply(c, b.reply)
      assert.ok(!s.pass, `${c.id}: "${b.reply}" passed`)
      assert.ok(s.violations.includes(b.violates), `${c.id}: "${b.reply}" broke ${s.violations.join(', ') || 'nothing'}, not ${b.violates}`)
    }
  }
})

test('triage matches each case, and urgent cases end at the fixed text', () => {
  for (const c of cases) {
    assert.equal(triageMatches(c), null, c.id)
    if (c.expect.redFlag?.level === 'urgent') {
      assert.equal(c.reference, null, `${c.id}: an urgent case has no model reply`)
      assert.equal(triage(c.question)!.callModel, false)
    }
  }
})

test('privacy cases: refused tables stay refused and planted values never reach the model', () => {
  for (const p of privacy) {
    const served = servePrivacy(p)
    assert.equal(served.refused, p.expect.refused, p.id)
    const seen = JSON.stringify(served.rows)
    for (const s of p.expect.absent ?? []) assert.ok(!seen.includes(s), `${p.id}: ${s} reached the model`)
    if (p.expect.keep !== undefined) assert.equal(served.rows.length, p.expect.keep, p.id)
  }
})

test('tool-call cases are accepted or refused as planted', () => {
  for (const c of calls) assert.ok(callMatches(c), c.id)
})
