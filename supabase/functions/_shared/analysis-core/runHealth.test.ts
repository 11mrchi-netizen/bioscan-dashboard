import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fakeAdmin } from './healthTestKit.ts'
import { shiftDate } from './healthSeries.ts'
import { buildFrame } from './runRelationships.ts'
import { HEALTH_KINDS, runHealth } from './runHealth.ts'
import { CORE_VERSION } from './version.ts'

const U = 'u1'
const TODAY = '2026-10-21'

function tables() {
  const days = 120
  const start = shiftDate(TODAY, -(days - 1))
  const dates = Array.from({ length: days }, (_, i) => shiftDate(start, i))
  const wearable = dates.map((d, i) => ({ user_id: U, date: d, hrv: 60 + (i % 7), rhr: 50, steps: 8000 }))
  const sleep = dates.map((d) => ({ user_id: U, date: d, hours: 7.4, deep_min: 80, rem_min: 90 }))
  const sessions = dates.filter((_, i) => i % 2 === 0).map((d, i) => ({ user_id: U, id: i + 1, start_time: `${d}T07:00:00Z`, end_time: `${d}T08:00:00Z`, duration_min: 55, distance_km: 10, elevation_gain_m: i % 3 === 0 ? 400 : 30, type: 'run', avg_hr: 145, max_hr: 175, rpe: null, details: { route_type: i % 3 === 0 ? 'trail' : 'road' } }))
  const concerns = [
    { user_id: U, id: 'knee', title: 'Right knee', kind: 'anatomical', onset_type: 'gradual', regions: [{ code: 'knee', side: 'right' }], symptoms: ['sym.pain'], excludes_from_baselines: false, resolution_days: null, archived_at: null, created_at: `${start}T00:00:00Z` },
    { user_id: U, id: 'knock', title: 'Right knee knock', kind: 'anatomical', onset_type: 'acute', regions: [{ code: 'knee', side: 'right' }], symptoms: ['sym.pain'], excludes_from_baselines: false, resolution_days: null, archived_at: null, created_at: `${start}T00:00:00Z` },
  ]
  const observations: Record<string, unknown>[] = []
  const signals: Record<string, unknown>[] = []
  dates.forEach((d, i) => {
    const sick = [20, 50, 80].some((f) => i >= f && i < f + 3) || i >= 115
    const id = `o${i}`
    observations.push({ user_id: U, id, observed_at: `${d}T20:00:00Z`, local_date: d, concern_id: null, scope: 'sweep', symptom_free: !sick, recall: 'today', recall_from: null, function_impact: sick ? 1 : 0, change_vs_last: null, onset: false, session_id: null, timing: null, supersedes: null, superseded_at: null, retracted: false, source: 'app', created_at: `${d}T20:00:00Z` })
    if (sick) signals.push({ user_id: U, id: i, observation_id: id, concern_id: 'knee', symptom: 'sym.pain', region: 'knee', side: 'right', severity: 4, details: {}, temperature_c: null })
  })
  return {
    dates,
    t: {
      wearable_daily: wearable, sleep_daily: sleep, exercise_sessions: sessions, health_concerns: concerns,
      health_observations: observations, health_signals: signals, health_episode_marks: [],
      interventions: [{ user_id: U, id: 'iv1', name: 'Fewer trail runs', started_at: shiftDate(TODAY, -10), ended_at: null, evaluate_on: null, lifecycle_state: 'active', created_at: '2026-10-01' }],
      intervention_concerns: [{ user_id: U, intervention_id: 'iv1', concern_id: 'knee', hypothesis: 'Fewer trail runs, fewer flare-ups', outcome: 'flare_rate_per_exposure', exposure_unit: 'run_km', pre_days: 28, registered_at: '2026-10-10' }],
    } as Record<string, Record<string, unknown>[]>,
  }
}

function loadedFor(t: Record<string, Record<string, unknown>[]>) {
  const frame = buildFrame({
    wearable: t.wearable_daily as never, sleep: t.sleep_daily as never, wellbeing: [], meals: [], sessions: t.exercise_sessions as never, recovery: [], illnesses: [],
  }, TODAY)
  return { userId: U, today: TODAY, frame }
}

test('runHealth publishes the overview, each concern, its findings, the trial and the ledger', async () => {
  const { t } = tables()
  const admin = fakeAdmin(t)
  const run = await runHealth(admin, loadedFor(t), new Date(`${TODAY}T20:30:00Z`))
  assert.equal(run.error, undefined, run.error)
  const rows = admin.upserts.filter((u) => u.table === 'analysis_results').map((u) => u.row)
  const kinds = (k: string) => rows.filter((r) => r.kind === k)
  assert.equal(kinds(HEALTH_KINDS.overview).length, 1)
  assert.equal(kinds(HEALTH_KINDS.state).length, 2)
  const knee = kinds(HEALTH_KINDS.state).find((r) => r.key === 'knee')!.payload as Record<string, unknown>
  assert.equal(knee.state, 'active')
  assert.equal((knee.episodes as unknown[]).length, 4)
  const daily = knee.daily as { dates: string[]; observed: number[] }
  assert.equal(daily.dates.length, 365) // the frame's year; days before the first check-in are unobserved, not zero
  assert.equal(daily.observed.filter((x) => x === 0).length, 245)
  assert.ok((knee.exposuresAsked as string[]).includes('trail_run'))
  // the acute knock gets its recurrence finding only (owner decision 7.2.5)
  const knockFindings = kinds(HEALTH_KINDS.finding).filter((r) => String(r.key).startsWith('knock:'))
  assert.deepEqual(knockFindings.map((r) => r.key), ['knock:recurrence'])
  const kneeRecurrence = kinds(HEALTH_KINDS.finding).find((r) => r.key === 'knee:recurrence')!
  assert.equal(kneeRecurrence.state, 'observed')
  // three flare-ups: nothing is a pattern; flare-up comparisons are insufficient, session comparisons may read no pattern
  const kneeFindings = kinds(HEALTH_KINDS.finding).filter((r) => String(r.key).startsWith('knee:') && r.key !== 'knee:recurrence')
  assert.ok(kneeFindings.every((r) => r.state === 'insufficient' || r.state === 'no_pattern'))
  assert.equal(kneeFindings.find((r) => r.key === 'knee:short_sleep')!.state, 'insufficient')
  assert.ok(kneeFindings.every((r) => !/\b(causes?|caused|triggers?|leads to|prevents?)\b/i.test(JSON.stringify(r.payload))), 'no cause verbs')
  const trial = kinds(HEALTH_KINDS.trial)[0]
  assert.equal(trial.key, 'iv1:knee')
  assert.equal(trial.state, 'interim')
  for (const r of rows) assert.equal(r.engine_version, CORE_VERSION)
  const ledger = admin.upserts.find((u) => u.table === 'evidence_ledger')
  assert.ok(Array.isArray(ledger?.row) && (ledger!.row as unknown as unknown[]).length >= 10)
  // older rows of the same key are replaced
  assert.ok(admin.deletes.some((d) => d.table === 'analysis_results'))
})

test('the same inputs give the same hashes; an evaluated trial read is never redone', async () => {
  const { t } = tables()
  const a = fakeAdmin(t)
  const b = fakeAdmin(t)
  await runHealth(a, loadedFor(t))
  await runHealth(b, loadedFor(t))
  const hashes = (x: typeof a) => x.upserts.filter((u) => u.table === 'analysis_results').map((u) => `${u.row.kind}:${u.row.key}:${u.row.inputs_hash}`).sort()
  assert.deepEqual(hashes(a), hashes(b))
  const frozen = fakeAdmin({ ...t, analysis_results: [{ user_id: U, kind: HEALTH_KINDS.trial, key: 'iv1:knee', as_of: '2026-10-01', payload: { state: 'evaluated' } }] })
  await runHealth(frozen, loadedFor(t))
  assert.equal(frozen.upserts.filter((u) => u.row.kind === HEALTH_KINDS.trial).length, 0)
})

test('before the migration the engine skips quietly', async () => {
  const { t } = tables()
  const run = await runHealth(fakeAdmin(t, ['health_concerns']), loadedFor(t))
  assert.ok(run.skipped)
  assert.equal(run.error, undefined)
})

test('the timing hook sees every step in order, by position only, and changes nothing', async () => {
  const { t } = tables()
  const steps: { step: string; ms: number; info?: Record<string, number> }[] = []
  const a = fakeAdmin(t)
  const b = fakeAdmin(t)
  const traced = await runHealth(a, loadedFor(t), new Date(`${TODAY}T20:30:00Z`), (step, ms, info) => steps.push({ step, ms, info }))
  const plain = await runHealth(b, loadedFor(t), new Date(`${TODAY}T20:30:00Z`))
  assert.deepEqual(traced, plain, 'same result with and without the hook')
  assert.deepEqual(a.upserts, b.upserts, 'same rows written')
  const names = steps.map((s) => s.step)
  assert.deepEqual(names.slice(0, 4), ['inputs', 'reads', 'context', 'series'])
  for (const i of [0, 1]) {
    assert.deepEqual(names.filter((n) => n.startsWith(`c${i}.`)), [`c${i}.summary`, `c${i}.exposureSeries`, `c${i}.evaluate`, `c${i}.findings`, `c${i}.rows`])
  }
  assert.deepEqual(names.slice(-4), ['trials', 'overview', 'publish', 'ledger'])
  // finer steps inside the exposure code: built once per concern, before that concern's evaluate step
  assert.equal(names.filter((n) => n === 'exp.runs').length, 2)
  assert.equal(names.filter((n) => n === 'exp.regional_strength').length, 2)
  assert.equal(names.filter((n) => n === 'exp.rest').length, 2)
  assert.ok(names.indexOf('exp.regional_strength') < names.indexOf('c0.exposureSeries'))
  const reg = steps.find((s) => s.step === 'exp.regional_strength')!
  assert.ok(typeof reg.info?.musclesOf === 'number' && typeof reg.info?.movementKey === 'number' && typeof reg.info?.library === 'number')
  assert.ok(names.every((n) => !/^ev\.[a-z_]+\./.test(n) || /^ev\.(any_run|long_run|trail_run|quality_run|volume_jump|back_to_back|regional_strength|no_rest|short_sleep|hrv_below|high_load|alcohol|stress_high|travel)\./.test(n)), 'exposure steps are named by catalogue id')
  assert.ok(steps.every((s) => Number.isFinite(s.ms) && s.ms >= 0))
  const text = JSON.stringify(steps)
  assert.ok(!text.includes('knee') && !text.includes('Right'), 'no concern title, region or symptom reaches the log')
})
