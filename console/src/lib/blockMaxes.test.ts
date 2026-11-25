// Run: node --test "src/lib/*.test.ts"
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { DEFAULT_EQUIPMENT, type MaxEntry, movementKey } from '../../../supabase/functions/_shared/training-core/index.ts'
import { exercisesFromMaxes, maxRevisions, regenerable, type StoredBlock, type StoredSession } from './blockMaxes.ts'

// The owner's "Strength +" block (Nov 2026) as it is stored: Operator/ULF with Polarized/Green, planned on the web, the squat day
// first (layout strength_session 1, 0, 2), six normal weeks, the pull-up on an estimate and no max for the other lifts.
const item = (slot: string, pct: number, reps: unknown, sets: unknown = { min: 3, max: 5 }, extra: Record<string, unknown> = {}) => ({ slot, load: { kind: 'pct_1rm', value: pct }, reps, sets, ...extra })
const week = (w: number, pct: number, reps: number, fullReps: unknown = reps) => ({
  week: w,
  sessions: [
    { session: 'D1', items: [item('BP', pct, reps), item('WP', pct, reps)] },
    { session: 'D3', items: [item('SQ', pct, reps), item('WP', pct, reps)] },
    { session: 'D5', items: [item('BP', pct, fullReps), item('SQ', pct, fullReps), item('DL', pct, fullReps, { min: 1, max: 5 })] },
  ],
})
const peak = { week: 6, kind: 'peak', sessions: ['D1:BP', 'D3:SQ', 'D5:DL'].map((x) => ({ session: x.split(':')[0], items: [item(x.split(':')[1], 85, 3, 1, { technique: 'peak', note: '1 work set of 3 at 85%' })] })) }
const opUlf = {
  key: 'tb3.op_ulf', kind: 'strength_module', title: 'Operator/ULF (Upper / Lower / Full body)', domain: 'max_strength', family: 'operator', sessions_per_week: 3, day_positions: [1, 3, 5],
  slots: [
    { id: 'BP', role: 'press', standard: 'Bench Press' }, { id: 'SQ', role: 'squat', standard: 'Squat' }, { id: 'DL', role: 'hinge', standard: 'Trapbar Deadlift' },
    { id: 'WP', role: 'pull', standard: 'Weighted Pullup', weighted_calisthenics: true },
  ],
  sessions: [{ id: 'D1', label: 'Upper (bench) day', slots: ['BP', 'WP'] }, { id: 'D3', label: 'Lower (squat) day', slots: ['SQ', 'WP'] }, { id: 'D5', label: 'Full body day', slots: ['BP', 'SQ', 'DL'] }],
  variants: [{ key: 'standard', title: 'Upper / lower / full body', weeks: [week(1, 75, 5), week(2, 80, 5, { min: 3, max: 5 }), week(3, 85, 3), week(4, 75, 5), week(5, 80, 5, { min: 3, max: 5 }), peak] }],
  progression: { unit: 'lb', lower: { min: 5, max: 10 }, upper: { min: 2.5, max: 5 }, after_weeks: { min: 3, max: 6 } },
}
const cond = (key: string, category: string, domains: string[]) => ({ key, kind: 'conditioning_session', title: key, aliases: [], domains, category, structure: { type: 'continuous' }, prescription: { by: ['minutes'], minutes: { min: 30, max: 120 } } })
const green = {
  key: 'tb3.polarized_green', kind: 'conditioning_protocol', title: 'Polarized/Green', protocol_type: 'polarized',
  budget: { session_min_minutes: 30, high_intensity_per_week: { min: 1, max: 2 }, low_intensity_minutes_per_week: { min: 180, max: 240 } },
  default_layout: [{ day: 2, kind: 'lic', minutes: { min: 60, max: 120 } }, { day: 3, kind: 'lic', minutes: { min: 60, max: 120 } }, { day: 5, kind: 'lic', minutes: { min: 60, max: 120 } }, { day: 6, kind: 'hic' }],
  suggested: { lic: ['tb3.lss', 'tb3.trail_run'], hic: ['tb3.hill_sprints', 'tb3.norwegian_4x4'] },
}
const block: StoredBlock = {
  start_date: '2026-11-30', template_key: null,
  components: [{ ref: { key: 'tb3.op_ulf', kind: 'strength_module' }, role: 'strength' }, { ref: { key: 'tb3.polarized_green', kind: 'conditioning_protocol' }, role: 'conditioning' }],
  choices: {
    path: 'build', weekday_map: {}, variables: {}, conditioning: { hic: ['tb3.hill_sprints', 'tb3.norwegian_4x4'], lic: ['tb3.lss', 'tb3.trail_run'] },
    layout: [{ weekday: 'mon', strength_session: 1 }, { weekday: 'tue', conditioning: 'lic' }, { weekday: 'wed', strength_session: 0 }, { weekday: 'thu', conditioning: 'hic' }, { weekday: 'fri', strength_session: 2 }, { weekday: 'sat', conditioning: 'lic' }, { weekday: 'sun' }],
    timeline: ['normal', 'normal', 'normal', 'normal', 'normal', 'normal'], start_with_test: false, project_progression: true, weighted_percent_base: 'added',
    equipment: { bar_kg: 20, plates_kg: [1.25, 2.5, 5, 10, 15, 20], trap_bar_kg: 25, added_step_kg: 1.25 }, bodyweight_kg: 72,
    slot_times: { 1: { start: '07:00', durationMin: 60 }, 2: { start: '20:00', durationMin: 120 } }, clusters: { 'tb3.op_ulf': {} },
  },
  definition_snapshot: { definitions: { 'tb3.op_ulf': opUlf, 'tb3.polarized_green': green, 'tb3.lss': cond('tb3.lss', 'lic', ['aerobic_base']), 'tb3.trail_run': cond('tb3.trail_run', 'lic', ['aerobic_base']), 'tb3.hill_sprints': cond('tb3.hill_sprints', 'hic', ['anaerobic_capacity']), 'tb3.norwegian_4x4': cond('tb3.norwegian_4x4', 'hic', ['aerobic_base']) } },
}
const fallback = { equipment: DEFAULT_EQUIPMENT, bodyweightKg: null }
// what the planner had: an estimate for the pull-up (75% → +20 kg on a 1.25 step), nothing else
const pullupOnly = (n: string): MaxEntry | null => (movementKey(n) === movementKey('Weighted Pullup') ? { oneRmKg: 26.7 } : null)

// The stored strength sessions, as the database lists them (sequence_no, title, main lifts slot:exercise:kind:pct:added_kg).
const STORED = `1|Lower (squat) day|SQ:Squat:unresolved,WP:Weighted Pullup:added:75:20
2|Upper (bench) day|BP:Bench Press:unresolved,WP:Weighted Pullup:added:75:20
3|Full body day|BP:Bench Press:unresolved,SQ:Squat:unresolved,DL:Trapbar Deadlift:unresolved
7|Lower (squat) day|SQ:Squat:unresolved,WP:Weighted Pullup:added:80:21.25
13|Lower (squat) day|SQ:Squat:unresolved,WP:Weighted Pullup:added:85:22.5
31|Lower (squat) day|SQ:Squat:unresolved
32|Upper (bench) day|BP:Bench Press:unresolved
33|Full body day|DL:Trapbar Deadlift:unresolved`

test('the stored block is generated again exactly as it was planned (sessions, titles, lifts and loads)', () => {
  const r = regenerable(block, fallback)
  assert.notEqual(typeof r, 'string', String(r))
  if (typeof r === 'string') return
  const gen = r.run(pullupOnly)
  const bySeq = new Map(gen.sessions.map((s) => [s.sequenceNo, s]))
  for (const line of STORED.split('\n')) {
    const [seq, label, lifts] = line.split('|')
    const s = bySeq.get(Number(seq))
    assert.ok(s, `session ${seq}`)
    assert.equal(s!.title, `${opUlf.title}: ${label}`)
    const sig = s!.items.map((i) => [i.slot, i.exercise, i.load.kind === 'unresolved' ? 'unresolved' : i.load.kind === 'added' ? `added:${i.load.pct}:${i.load.addedKg}` : i.load.kind].join(':')).join(',')
    assert.equal(sig, lifts, `session ${seq}`)
  }
  assert.deepEqual(exercisesFromMaxes(gen), ['Squat', 'Weighted Pullup', 'Bench Press', 'Trapbar Deadlift'])
})

const stored = (seq: number, lifts: Record<string, unknown>[], extra: Partial<StoredSession> = {}): StoredSession => ({
  id: 100 + seq, sequence_no: seq, title: `${opUlf.title}: Lower (squat) day`, status: 'planned', scheduled_date: '2026-11-30', exercise_session_id: null,
  prescription: [...lifts, { type: 'note', text: 'keep it' }], ...extra,
})
const sq = (load: Record<string, unknown>, extra: Record<string, unknown> = {}) => ({ type: 'lift', slot: 'SQ', exercise: 'Squat', role: 'main', sets: { min: 3, max: 5 }, reps: 5, load, ...extra })
const wp = (load: Record<string, unknown>) => ({ type: 'lift', slot: 'WP', exercise: 'Weighted Pullup', role: 'main', sets: { min: 3, max: 5 }, reps: 5, load })

test('a new squat max works out the squat loads; sets, reps, notes and the other lifts stay as stored', () => {
  const r = regenerable(block, fallback)
  if (typeof r === 'string') assert.fail(r)
  const gen = r.run((n) => (movementKey(n) === 'squat' ? { oneRmKg: 120 } : pullupOnly(n)))
  const rows = [stored(1, [sq({ kind: 'unresolved', missing: 'OneRm' }, { sets: 4 }), wp({ kind: 'added', pct: 75, added_kg: 20, total_kg: 92 })])]
  const plan = maxRevisions(rows, gen, '2026-11-25')
  assert.equal(plan.revisions.length, 1)
  const [squat, pull, note] = plan.revisions[0].after
  assert.equal((squat.load as { kind: string }).kind, 'pct_1rm')
  assert.equal((squat.load as { pct: number }).pct, 75)
  assert.equal((squat.load as { loadable_kg: number }).loadable_kg, 90) // 75% of 120 on a 20 kg bar
  assert.equal(squat.sets, 4) // the person's own change stays
  assert.deepEqual(pull.load, { kind: 'added', pct: 75, added_kg: 20, total_kg: 92 })
  assert.deepEqual(note, { type: 'note', text: 'keep it' })
  assert.deepEqual(plan.revisions[0].changes.map((c) => c.exercise), ['Squat'])
})

test('every week takes its own percentage of the new max (six weeks are one cycle of this program: no step-up inside it)', () => {
  const r = regenerable(block, fallback)
  if (typeof r === 'string') assert.fail(r)
  const gen = r.run((n) => (movementKey(n) === 'squat' ? { oneRmKg: 100 } : null))
  const s19 = gen.sessions.find((s) => s.sequenceNo === 19)!
  const load = s19.items.find((i) => i.exercise === 'Squat')!.load
  assert.equal(load.kind, 'barbell')
  if (load.kind === 'barbell') assert.equal(load.targetKg, 75)
  assert.deepEqual(gen.progressions, [])
})

test('a load set by hand, a session past, linked or set aside, and a session that no longer matches are left alone', () => {
  const r = regenerable(block, fallback)
  if (typeof r === 'string') assert.fail(r)
  const gen = r.run((n) => (movementKey(n) === 'squat' ? { oneRmKg: 120 } : null))
  const unresolved = { kind: 'unresolved', missing: 'OneRm' }
  const rows = [
    stored(1, [sq({ kind: 'fixed', kg: 70, loadable_kg: 70 })]),
    stored(7, [sq(unresolved)], { scheduled_date: '2026-11-20' }),
    stored(13, [sq(unresolved)], { exercise_session_id: 5 }),
    stored(19, [sq(unresolved)], { status: 'superseded' }),
    stored(25, [sq(unresolved)], { title: 'Something else' }),
  ]
  const plan = maxRevisions(rows, gen, '2026-11-25')
  assert.equal(plan.revisions.length, 0)
  assert.deepEqual(plan.skipped.map((s) => s.title), ['Something else'])
})

test('only the exercises asked for are touched', () => {
  const r = regenerable(block, fallback)
  if (typeof r === 'string') assert.fail(r)
  const gen = r.run((n) => (movementKey(n) === 'squat' ? { oneRmKg: 120 } : { oneRmKg: 40 }))
  const rows = [stored(1, [sq({ kind: 'unresolved', missing: 'OneRm' }), wp({ kind: 'added', pct: 75, added_kg: 20, total_kg: 92 })])]
  const plan = maxRevisions(rows, gen, '2026-11-25', new Set(['squat']))
  assert.deepEqual(plan.revisions[0].changes.map((c) => c.exercise), ['Squat'])
})

test('a block without its program or layout says why it cannot be worked out again', () => {
  assert.equal(typeof regenerable({ ...block, definition_snapshot: null }, fallback), 'string')
  assert.equal(typeof regenerable({ ...block, choices: { ...block.choices, layout: undefined } }, fallback), 'string')
})

test('swapping the squat for the front squat renames the squat lifts ahead and works out their loads from its own max', async () => {
  const { choicesWithSwap, swapRevisions, swapSlots } = await import('./blockMaxes.ts')
  const slots = swapSlots(block)
  assert.deepEqual(slots.map((s) => `${s.slotId}:${s.current}`), ['BP:Bench Press', 'SQ:Squat', 'DL:Trapbar Deadlift', 'WP:Weighted Pullup'])
  const swapped = { ...block, choices: choicesWithSwap(block.choices, 'tb3.op_ulf', 'SQ', 'Front Squat') }
  const r = regenerable(swapped, fallback)
  if (typeof r === 'string') assert.fail(r)
  const gen = r.run((n) => (movementKey(n) === movementKey('Front Squat') ? { oneRmKg: 100 } : pullupOnly(n)))
  const rows = [
    stored(1, [sq({ kind: 'unresolved', missing: 'OneRm' }, { sets: 4 }), wp({ kind: 'added', pct: 75, added_kg: 20, total_kg: 92 })]),
    stored(7, [sq({ kind: 'rpe', rpe: 7 }), wp({ kind: 'added', pct: 80, added_kg: 21.25 })]),
  ]
  const plan = swapRevisions(rows, gen, '2026-11-25', 'SQ', 'Squat')
  assert.equal(plan.revisions.length, 2)
  const [a, b] = plan.revisions
  assert.equal(a.after[0].exercise, 'Front Squat')
  assert.equal(a.after[0].sets, 4)
  assert.equal((a.after[0].load as { loadable_kg: number }).loadable_kg, 75)
  assert.equal(a.after[1].exercise, 'Weighted Pullup')
  assert.deepEqual(b.after[0].load, { kind: 'rpe', rpe: 7 }) // set by hand: stays
  assert.equal(b.after[0].exercise, 'Front Squat')
})
