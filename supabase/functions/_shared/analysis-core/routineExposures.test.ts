// Routine intelligence R4: the routine series, and the plan's gates for the routine questions (docs/routine-intelligence,
// section 10): with no true effect at most 5% read clear, and a place pattern that follows the week is not testable.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { HYPOTHESES } from './hypotheses.ts'
import { runCatalogue } from './relationship.ts'
import type { Frame, Hypothesis } from './relationship.ts'
import { routineSeries } from './routineExposures.ts'
import { buildFrame, localClock } from './runRelationships.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'
import type { Num } from './stats.ts'

const ROUTINE_IDS = [
  'screen_bed_sleep', 'screen_bed_hrv', 'night_phone_energy', 'screen_pm_social_sleep', 'outdoor_daylight_sleep',
  'home_day_energy', 'travel_day_hrv', 'travel_day_rhr', 'active_commute_hrv', 'commute_long_sleep',
]
const byId = (id: string): Hypothesis => HYPOTHESES.find((h) => h.id === id)!
const taipei = localClock([{ date: '2026-01-01', utc_offset_min: 480 }])

test('every routine question is in the catalogue, refers to a routine series and files under a board group', () => {
  const axis = dateAxis('2026-10-01', '2026-10-03')
  const names = Object.keys(routineSeries(axis, {}, taipei))
  for (const id of ROUTINE_IDS) {
    const h = byId(id)
    assert.ok(h, id)
    assert.ok(names.includes(h.cause), `${id} cause ${h.cause}`)
    assert.ok(['hrv', 'rhr', 'sleep', 'energy'].includes(h.outcome))
    assert.doesNotMatch(h.label, /causes|leads to|->|→/)
  }
})

test('phone series: measured days only; no late social use on a measured day is a real zero', () => {
  const axis = dateAxis('2026-10-01', '2026-10-03')
  const s = routineSeries(axis, {
    phone: [
      { date: '2026-10-01', bed_window_min: 25, night_screen_min: 4, night_unlocks: 2 },
      { date: '2026-10-02', bed_window_min: null, night_screen_min: null, night_unlocks: 0 },
    ],
    phoneApps: [{ date: '2026-10-01', category: 'social', after_21_min: 30 }, { date: '2026-10-01', category: 'video', after_21_min: 12 }],
  }, taipei)
  assert.deepEqual(s.screen_bed_min, [25, null, null])
  assert.deepEqual(s.night_phone, [1, 0, null])
  assert.deepEqual(s.screen_pm_social, [42, 0, null])
})

test('mobility series: a day under 80% covered is unknown, never home', () => {
  const axis = dateAxis('2026-10-01', '2026-10-04')
  const s = routineSeries(axis, {
    mobility: [
      { date: '2026-10-01', coverage_pct: 95, day_type: 'home_day', outdoor_daylight_min: 12 },
      { date: '2026-10-02', coverage_pct: 60, day_type: 'home_day', outdoor_daylight_min: 50 },
      { date: '2026-10-03', coverage_pct: 90, day_type: 'travel', outdoor_daylight_min: 80 },
      { date: '2026-10-04', coverage_pct: 85, day_type: 'unknown', outdoor_daylight_min: 0 },
    ],
  }, taipei)
  assert.deepEqual(s.home_day, [1, null, 0, null])
  assert.deepEqual(s.travel_day, [0, null, 1, null])
  assert.deepEqual(s.outdoor_daylight_min, [12, null, 80, 0])
})

test('commute series: filed under the local day, active from 10 minutes, long against the last four weeks', () => {
  const axis = dateAxis('2026-09-01', '2026-10-10')
  // 23:30 UTC is 07:30 the next morning in Taipei: the commute belongs to that next day.
  const commute = (utcDay: string, minutes: number, active: number) => ({
    start_at: `${utcDay}T23:30:00Z`, end_at: new Date(Date.parse(`${utcDay}T23:30:00Z`) + minutes * 60000).toISOString(), active_min: active,
  })
  const commutes = [
    ...['2026-09-21', '2026-09-22', '2026-09-23', '2026-09-24', '2026-09-25'].map((d) => commute(d, 40, 12)),
    commute('2026-09-28', 41, 4),
    commute('2026-09-29', 60, 15),
  ]
  const s = routineSeries(axis, { commutes }, taipei)
  const at = (d: string) => axis.indexOf(d)
  assert.equal(s.active_commute[at('2026-09-22')], 1)
  assert.equal(s.active_commute[at('2026-09-21')], null) // no commute on the 21st local
  assert.equal(s.active_commute[at('2026-09-29')], 0)
  assert.equal(s.commute_long[at('2026-09-26')], null) // fifth commute: only four before it
  assert.equal(s.commute_long[at('2026-09-29')], 0) // 41 min against a usual 40
  assert.equal(s.commute_long[at('2026-09-30')], 1) // 60 min, 1.5 times the usual
})

test('buildFrame carries the routine series on its axis', () => {
  const f = buildFrame({
    wearable: [], sleep: [], wellbeing: [], meals: [], sessions: [], recovery: [], illnesses: [],
    routine: { phone: [{ date: '2026-10-20', bed_window_min: 10, night_screen_min: 0, night_unlocks: 0 }] },
  }, '2026-10-21', 5)
  assert.equal(f.series.screen_bed_min.length, 5)
  assert.equal(f.series.screen_bed_min[3], 10)
  assert.equal(f.series.travel_day.every((v) => v === null), true)
})

// The simulation: HRV (log AR(1) with a weekday pattern), resting heart rate, sleep and energy, none affected by any
// routine exposure; phone and place exposures drawn at random (with gaps), commutes on most weekdays.
const WEEKDAY = [0.0, 0.01, 0.02, 0.0, -0.01, -0.03, 0.01]
function nullFrame(seed: number, n: number): Frame {
  const rng = mulberry32(seed)
  const dates = dateAxis('2026-01-05', '2027-12-31').slice(0, n)
  let s = 0
  const hrv: Num[] = []
  const rhr: Num[] = []
  const sleep: Num[] = []
  const energy: Num[] = []
  const cause: Record<string, Num[]> = Object.fromEntries(
    ['screen_bed_min', 'night_phone', 'screen_pm_social', 'outdoor_daylight_min', 'home_day', 'travel_day', 'active_commute', 'commute_long'].map((k) => [k, [] as Num[]]),
  )
  for (let t = 0; t < n; t++) {
    const dow = new Date(dates[t] + 'T00:00:00Z').getUTCDay()
    s = 0.6 * s + 0.07 * gaussian(rng)
    hrv.push(rng() < 0.2 ? null : Math.exp(4.1 + s + 0.05 * gaussian(rng) + WEEKDAY[dow]))
    rhr.push(rng() < 0.2 ? null : 52 - 6 * s + 1.2 * gaussian(rng))
    sleep.push(rng() < 0.1 ? null : 7.2 + 0.7 * gaussian(rng))
    energy.push(rng() < 0.3 ? null : Math.max(0, Math.min(10, Math.round(6 + 1.5 * gaussian(rng)))))
    const phoneKnown = rng() < 0.85
    cause.screen_bed_min.push(phoneKnown ? Math.max(0, 20 + 15 * gaussian(rng)) : null)
    cause.night_phone.push(phoneKnown ? (rng() < 0.3 ? 1 : 0) : null)
    cause.screen_pm_social.push(phoneKnown ? Math.max(0, 30 + 25 * gaussian(rng)) : null)
    const placeKnown = rng() < 0.75
    cause.outdoor_daylight_min.push(placeKnown ? Math.max(0, 40 + 30 * gaussian(rng)) : null)
    cause.home_day.push(placeKnown ? (rng() < (dow === 0 || dow === 6 ? 0.6 : 0.15) ? 1 : 0) : null)
    cause.travel_day.push(placeKnown ? (rng() < 0.08 ? 1 : 0) : null)
    const commuteDay = placeKnown && dow >= 1 && dow <= 5 && rng() < 0.8
    cause.active_commute.push(commuteDay ? (rng() < 0.5 ? 1 : 0) : null)
    cause.commute_long.push(commuteDay ? (rng() < 0.2 ? 1 : 0) : null)
  }
  return { dates, series: { hrv, rhr, sleep_hours: sleep, energy, ...cause }, excluded: dates.map(() => false) }
}

test('gate: with no true effect, at most 5% of the routine questions read clear', () => {
  const questions = ROUTINE_IDS.map(byId)
  let clear = 0
  let tested = 0
  for (let i = 0; i < 30; i++) {
    for (const n of [150, 300]) {
      for (const o of runCatalogue(questions, nullFrame(52_000 + i * 13 + n, n))) {
        if (o.state === 'not_yet') continue
        tested++
        if (o.state === 'clear') clear++
      }
    }
  }
  assert.ok(tested > 400, `${tested} questions tested`)
  assert.ok(clear / tested <= 0.05, `clear on null data: ${clear} of ${tested}`)
})

test('a place pattern that follows the week is not testable, instead of looking like an effect', () => {
  // Home every Saturday and Sunday, never on weekdays, with energy higher on Mondays: a calendar link only.
  const dates = dateAxis('2026-01-05', '2026-09-30')
  const rng = mulberry32(7)
  const dow = (d: string) => new Date(d + 'T00:00:00Z').getUTCDay()
  const home: Num[] = dates.map((d) => (dow(d) === 0 || dow(d) === 6 ? 1 : 0))
  const energy: Num[] = dates.map((d) => Math.round(6 + (dow(d) === 1 ? 1.5 : 0) + gaussian(rng)))
  const [o] = runCatalogue([byId('home_day_energy')], { dates, series: { home_day: home, energy }, excluded: dates.map(() => false) })
  assert.equal(o.state, 'not_yet')
  assert.match(o.reason!, /weekly schedule/)
})
