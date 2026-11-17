// Run: node --test supabase/functions/_shared/training-core/
// Parity with the Android engine: fixtures/expected.json is written by the Kotlin TrainingParityTest from the same
// fixtures/scenarios.json, so every block, layout, warning and load here must match the phone's to the gram.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import {
  type BlockBlueprint, type DayPlan, defaultTimeline, DefinitionIndex, deriveBlockDomains, estimateMaxFromHistory, type GenChoices,
  type GeneratedBlock, type GenEquipment, DEFAULT_EQUIPMENT, generateComposedBlock, generateMergedBlock, generateTemplateBlock, layoutWarnings,
  loadableFor, loadableTotal, type MaxEntry, movementKey, parseDefinition, prescriptionJson, resizeTimeline, type Rounding, suggestLayout,
  summaryLines, toNumRange, type WeekKind, weekdayOf, weeksForBlocks, withTaper, type Weekday,
} from './index.ts'

const dir = new URL('./fixtures/', import.meta.url)
const sc = JSON.parse(readFileSync(new URL('scenarios.json', dir), 'utf8'))
const expected = JSON.parse(readFileSync(new URL('expected.json', dir), 'utf8'))

/** Deep equality where numbers compare within 1e-9 (Kotlin writes 70.0, JSON.stringify writes 70). */
function same(a: unknown, b: unknown, path = '$'): void {
  if (typeof a === 'number' && typeof b === 'number') { assert.ok(Math.abs(a - b) <= 1e-9, `${path}: ${a} vs ${b}`); return }
  if (Array.isArray(a) && Array.isArray(b)) {
    assert.equal(a.length, b.length, `${path}: length ${a.length} vs ${b.length}\n${JSON.stringify(a).slice(0, 400)}\n${JSON.stringify(b).slice(0, 400)}`)
    a.forEach((x, i) => same(x, b[i], `${path}[${i}]`))
    return
  }
  if (a && b && typeof a === 'object' && typeof b === 'object') {
    const ka = Object.keys(a as object).filter((k) => (a as Record<string, unknown>)[k] !== undefined).sort()
    const kb = Object.keys(b as object).filter((k) => (b as Record<string, unknown>)[k] !== undefined).sort()
    assert.deepEqual(ka, kb, `${path}: keys`)
    for (const k of ka) same((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k], `${path}.${k}`)
    return
  }
  assert.equal(a, b, path)
}

const idx = new DefinitionIndex((sc.definitions as unknown[]).map(parseDefinition))
const maxes = sc.maxes as Record<string, MaxEntry>
const lookup = (name: string) => maxes[name.trim().toLowerCase()] ?? null

function blockJson(b: GeneratedBlock) {
  return {
    calendarWeeks: b.calendarWeeks, countedWeeks: b.countedWeeks, endDate: b.endDate, warnings: b.warnings,
    progressions: b.progressions, domains: deriveBlockDomains(b.sessions),
    sessions: b.sessions.map((s) => ({
      sequenceNo: s.sequenceNo, weekIndex: s.weekIndex, countsTowardBlock: s.countsTowardBlock, daySlot: s.daySlot, slotInDay: s.slotInDay,
      date: s.date, startTime: s.startTime, durationMin: s.durationMin, domain: s.domain, workKind: s.workKind, moduleRef: s.moduleRef, title: s.title,
      prescription: prescriptionJson(s), lines: summaryLines(s),
    })),
  }
}

for (const c of sc.cases) {
  test(`block: ${c.name}`, () => {
    const eqo = c.equipment ?? {}
    const eq: GenEquipment = { ...DEFAULT_EQUIPMENT, barKg: eqo.barKg ?? 20, platesKg: eqo.platesKg ?? DEFAULT_EQUIPMENT.platesKg, weightedBase: eqo.weightedBase === 'total' ? 'total' : 'added' }
    const choices: GenChoices = {
      startDate: c.start, weekdays: c.weekdays, slotTimes: c.slotTimes, variables: c.variables, modules: c.modules, conditioning: c.conditioning,
      weeks: c.weeks ?? null, deloadAfterBlock: c.deloadAfterBlock ?? true, bodyweightKg: c.bodyweightKg ?? null,
      conditioningPerWeek: c.conditioningPerWeek ?? null, projectProgression: c.projectProgression ?? true,
    }
    const strength = c.strength ? idx.strength(c.strength) ?? null : null
    const protocol = c.protocol ? idx.protocol(c.protocol) ?? null : null
    const exp = expected.cases[c.name]
    if (c.kind === 'template') { same(blockJson(generateTemplateBlock(idx.template(c.template)!, idx, choices, eq, lookup)), exp.block); return }
    if (c.kind === 'composed') { same(blockJson(generateComposedBlock(strength, protocol, idx, choices, eq, lookup)), exp.block); return }
    const layout: DayPlan[] = Array.isArray(c.layout)
      ? c.layout.map((d: Record<string, any>) => ({ weekday: d.weekday as Weekday, strengthSession: d.strengthSession ?? null, conditioning: d.conditioning ? { kind: d.conditioning.kind, minutes: toNumRange(d.conditioning.minutes) } : null }))
      : suggestLayout(strength, protocol, weekdayOf(c.start), c.conditioningPerWeek ?? null)
    const timeline: WeekKind[] = Array.isArray(c.timeline) ? c.timeline : defaultTimeline(c.timeline.default[0], c.timeline.default[1], c.timeline.default[2])
    same(layout.map((d) => ({ weekday: d.weekday, strengthSession: d.strengthSession, conditioning: d.conditioning?.kind ?? null, minutes: d.conditioning?.minutes ? (d.conditioning.minutes.min === d.conditioning.minutes.max ? d.conditioning.minutes.min : d.conditioning.minutes) : null })), exp.layout)
    same(layoutWarnings(layout, strength, protocol), exp.layoutWarnings)
    same(timeline, exp.timeline)
    const bp: BlockBlueprint = { strengthKey: strength?.key ?? null, protocolKey: protocol?.key ?? null, layout, timeline }
    same(blockJson(generateMergedBlock(bp, idx, choices, eq, lookup)), exp.block)
  })
}

test('plates, movement keys, timelines and max estimates', () => {
  const m = sc.misc
  same(m.plates.map(([t, b, p, r]: [number, number, number[], Rounding]) => { const l = loadableFor(t, b, p, r); return { total: loadableTotal(l), plates: l.platesPerSideKg } }), expected.misc.plates)
  same(m.movementKeys.map(movementKey), expected.misc.movementKeys)
  same(m.timelines.map((a: any[]) => a[0] === 'default' ? defaultTimeline(a[1], a[2], a[3], a[4]) : a[0] === 'resize' ? resizeTimeline(a[1], a[2], a[3]) : a[0] === 'taper' ? withTaper(a[1], a[2]) : weeksForBlocks(a[1], a[2], a[3], a[4])), expected.misc.timelines)
  same(m.estimates.map((e: any) => estimateMaxFromHistory(e.sets, e.today)), expected.misc.estimates)
})
