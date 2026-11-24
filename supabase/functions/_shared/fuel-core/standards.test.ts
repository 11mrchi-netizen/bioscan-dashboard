// Run: node --test "supabase/functions/_shared/fuel-core/*.test.ts"
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { applyStandard, describeRule, expandSchedule, ruleCount, ruleInterval, ruleProblems, scheduleTotals, standardFromBlocks, type Standard, type StandardRule } from './index.ts'

const gel: StandardRule = { label: 'Gel', form: 'gel', carbsG: 25, fluidMl: 0, sodiumMg: 50, caffeineMg: 0, startMin: 30, mode: 'every', everyMin: 30 }

test('one serving every X minutes, from the first until the finish', () => {
  assert.equal(ruleCount(gel, 120), 4) // 30, 60, 90, 120
  assert.equal(ruleCount(gel, 119), 3)
  assert.equal(ruleCount(gel, 29), 0)
  assert.equal(ruleCount({ ...gel, stopBeforeEndMin: 10 }, 120), 3) // not in the last ten minutes
  const [b] = applyStandard({ name: 'x', rules: [gel] }, 180)
  assert.deepEqual([b.startMin, b.intervalMin, b.count, b.carbsG, b.form], [30, 30, 6, 25, 'gel'])
})

test('a rate of carbohydrate is an interval', () => {
  const r: StandardRule = { ...gel, mode: 'rate', carbsPerHour: 60, everyMin: undefined }
  assert.equal(ruleInterval(r), 25) // 25 g at 60 g/h: every 25 minutes
  assert.equal(ruleInterval({ ...r, carbsPerHour: 90 }), 17)
  assert.equal(ruleInterval({ ...r, carbsG: 0 }), null)
  const blocks = applyStandard({ name: 'x', rules: [r] }, 150)
  const t = scheduleTotals(blocks, 150)
  assert.ok(t.carbsPerHour != null && t.carbsPerHour >= 45 && t.carbsPerHour <= 70)
})

test('a single serving, before the start too, and none that falls after the finish', () => {
  const pre: StandardRule = { label: 'Pre-workout', form: 'preworkout', carbsG: 0, fluidMl: 0, sodiumMg: 0, caffeineMg: 200, startMin: -30, once: true, mode: 'every' }
  assert.equal(applyStandard({ name: 'x', rules: [pre] }, 90)[0].startMin, -30)
  assert.equal(applyStandard({ name: 'x', rules: [{ ...pre, startMin: 200 }] }, 90).length, 0)
  assert.equal(applyStandard({ name: 'x', rules: [{ ...pre, startMin: 60 }] }, 90)[0].count, 1)
})

test('a rule that cannot be worked out is left out, and says why', () => {
  assert.deepEqual(ruleProblems({ ...gel, everyMin: 0 }), ['EVERY needs minutes between servings, 1 to 600.'])
  assert.ok(ruleProblems({ ...gel, startMin: -300 }).length > 0)
  assert.equal(applyStandard({ name: 'x', rules: [{ ...gel, everyMin: 0 }] }, 120).length, 0)
})

test('the same standard fills a long and a short session without retyping', () => {
  const s: Standard = { name: 'Easy filling', rules: [gel, { label: 'Water', form: 'water', carbsG: 0, fluidMl: 250, sodiumMg: 0, caffeineMg: 0, startMin: 15, mode: 'every', everyMin: 15, stopBeforeEndMin: 5 }] }
  const short = applyStandard(s, 100)
  const long = applyStandard(s, 360)
  assert.ok(long[0].count > short[0].count && long[1].count > short[1].count)
  assert.equal(expandSchedule(long).length, long[0].count + long[1].count)
})

test('a schedule becomes a standard and comes back as it was for the same length', () => {
  const blocks = applyStandard({ name: 'x', rules: [gel, { ...gel, label: 'Salt', form: 'tablet', carbsG: 0, startMin: 60, once: true }] }, 150, 'a')
  const std = standardFromBlocks('mine', blocks, 150)
  const again = applyStandard(std, 150, 'a')
  assert.deepEqual(again.map((b) => [b.startMin, b.intervalMin, b.count]), blocks.map((b) => [b.startMin, b.intervalMin, b.count]))
  assert.equal(applyStandard(std, 300, 'a')[0].count > blocks[0].count, true)
})

test('a rule reads as a line', () => {
  assert.equal(describeRule({ ...gel, stopBeforeEndMin: 10 }), 'Gel (25 g carbs, 50 mg Na): every 30 min from 30 min, none in the last 10 min')
  assert.equal(describeRule({ label: 'Pre', form: 'preworkout', carbsG: 0, fluidMl: 0, sodiumMg: 0, caffeineMg: 200, startMin: -30, once: true, mode: 'every' }), 'Pre (200 mg caffeine): once, 30 min before the start')
})
