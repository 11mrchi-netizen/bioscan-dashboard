import assert from 'node:assert/strict'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'
import { resultAllowed, ROUTINE_SERIES, serveRows, TABLE_SCOPE, tableAccess, type ScopeSettings } from './scope.ts'

const ROOT = new URL('../../../../', import.meta.url).pathname
const on: ScopeSettings = { enabled: true, optIn: [] }
const all: ScopeSettings = { enabled: true, optIn: ['notes', 'sexual_health', 'health_concerns', 'digestive'] }

function files(dir: string, ext: RegExp): string[] {
  const out: string[] = []
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === 'build' || name.startsWith('.')) continue
    const p = join(dir, name)
    if (statSync(p).isDirectory()) out.push(...files(p, ext))
    else if (ext.test(name)) out.push(p)
  }
  return out
}

/** Every table the migrations create and every table the code reads or writes. */
function knownTables(): Set<string> {
  const out = new Set<string>()
  for (const f of files(join(ROOT, 'supabase/migrations'), /\.sql$/)) {
    for (const m of readFileSync(f, 'utf8').matchAll(/create (?:table|view|or replace view)(?: if not exists)? public\.([a-z_0-9]+)/gi)) out.add(m[1])
  }
  const code = [...files(join(ROOT, 'console/src'), /\.tsx?$/), ...files(join(ROOT, 'supabase/functions'), /\.ts$/), ...files(join(ROOT, 'android-app/app/src/main'), /\.kt$/)]
  for (const f of code) {
    for (const m of readFileSync(f, 'utf8').matchAll(/\.from\(\s*["']([a-z_0-9]+)["']\s*\)/g)) out.add(m[1])
  }
  return out
}

test('every table in the migrations and the code is classified', () => {
  const missing = [...knownTables()].filter((t) => !(t in TABLE_SCOPE)).sort()
  assert.deepEqual(missing, [], `classify these in scope.ts: ${missing.join(', ')}`)
})

test('an unclassified table and a switched-off assistant read nothing', () => {
  assert.equal(tableAccess('brand_new_table', all).ok, false)
  assert.equal(tableAccess('wearable_daily', { enabled: false, optIn: [] }).ok, false)
  assert.equal(tableAccess('wearable_daily', on).ok, true)
})

test('routine, location, other people and the calendar are never served, even with every group shared', () => {
  for (const t of ['location_fixes', 'place_visits', 'trips', 'mobility_daily', 'phone_usage_daily', 'phone_app_usage_daily', 'daily_context', 'people', 'calendar_events', 'planned_routes']) {
    assert.equal(tableAccess(t, all).ok, false, t)
  }
})

test('opt-in groups open only their own tables', () => {
  assert.equal(tableAccess('encounters', on).ok, false)
  assert.equal(tableAccess('encounters', { enabled: true, optIn: ['sexual_health'] }).ok, true)
  assert.equal(tableAccess('health_concerns', { enabled: true, optIn: ['sexual_health'] }).ok, false)
  assert.equal(tableAccess('stool_log', { enabled: true, optIn: ['digestive'] }).ok, true)
})

test('free text needs notes shared; location columns and unlisted profile columns never pass', () => {
  const row = { date: '2026-10-27', severity: 6, note: 'felt rough after the work dinner', start_lat: 25.03, route_polyline: 'abc' }
  const served = serveRows('illnesses', [row], on)
  assert.ok(served.ok)
  if (served.ok) assert.deepEqual(served.rows[0], { date: '2026-10-27', severity: 6 })
  const withNotes = serveRows('illnesses', [row], { enabled: true, optIn: ['notes'] })
  if (withNotes.ok) assert.equal(withNotes.rows[0].note, 'felt rough after the work dinner')
  const profile = serveRows('user_profile', [{ sex: 'male', date_of_birth: '1990-01-01', home_lat: 25 }], all)
  if (profile.ok) assert.deepEqual(profile.rows[0], { sex: 'male' })
})

test('results about routine series are withheld, whatever the result kind', () => {
  assert.ok(ROUTINE_SERIES.includes('screen_bed_min') && ROUTINE_SERIES.includes('commute_long'))
  assert.equal(resultAllowed({ kind: 'relationship', key: 'screen_bed_min->sleep_hours@0' }, all), false)
  assert.equal(resultAllowed({ kind: 'experiment', key: 'exp:7', payload: { exposure: 'daylight_day' } }, all), false)
  assert.equal(resultAllowed({ kind: 'relationship', key: 'alcohol_any->hrv@1' }, on), true)
})

test('health-concern results need the health concerns group', () => {
  assert.equal(resultAllowed({ kind: 'health_overview', key: 'user' }, on), false)
  assert.equal(resultAllowed({ kind: 'health_overview', key: 'user' }, { enabled: true, optIn: ['health_concerns'] }), true)
})
