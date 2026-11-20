// Risk profile R0/R1: the console's ANALYSIS › RISK page against a mocked Supabase whose air rows are what the real engine
// (analysis-core/runAir.ts) writes from synthetic inputs: 30 days in UTC+8, home with an indoor PM2.5 / CO2 sensor, work
// 5 km away, morning runs, and a smoky week. Blood pressure: a 7-day home series and one clinic reading. Not part of CI.
//
//   cd console && npm run build && node e2e/risk.mts [screenshot dir]
//
// Exits 1 if a check fails.
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { runAir } from '../../supabase/functions/_shared/analysis-core/runAir.ts'
import { fakeAdmin } from '../../supabase/functions/_shared/analysis-core/healthTestKit.ts'
import { cellKey, hourKey, hoursBetween } from '../../supabase/functions/_shared/analysis-core/airExposure.ts'

const here = path.dirname(fileURLToPath(import.meta.url))
const { chromium } = createRequire(import.meta.url)('playwright')
const OUT = path.resolve(process.argv[2] ?? path.join(here, 'out'))
fs.mkdirSync(OUT, { recursive: true })
const PORT = 4174
const BASE = `http://localhost:${PORT}/app.html`
const REF = 'ugfrglbcoivkprjqvjzz'

// --- synthetic inputs for the engine ---
const HOME = { lat: 25.03, lon: 121.56 }
const WORK = { lat: 25.07, lon: 121.52 }
const now = new Date('2026-11-19T20:30:00Z')
const iso = (ms: number) => new Date(ms).toISOString()
const visits: Record<string, unknown>[] = []
const indoor: Record<string, unknown>[] = []
const sessions: Record<string, unknown>[] = []
for (let i = 0; i < 36; i++) {
  const m = Date.UTC(2026, 9, 15 + i) - 8 * 3600000
  const weekday = new Date(m + 8 * 3600000).getUTCDay() % 6 !== 0
  visits.push({ user_id: 'u', start_at: iso(m - 6 * 3600000), end_at: iso(m + 8.5 * 3600000), lat: HOME.lat, lon: HOME.lon, place_id: 'home' })
  if (weekday) visits.push({ user_id: 'u', start_at: iso(m + 9 * 3600000), end_at: iso(m + 18 * 3600000), lat: WORK.lat, lon: WORK.lon, place_id: 'work' })
  visits.push({ user_id: 'u', start_at: iso(m + 18.5 * 3600000), end_at: iso(m + 24 * 3600000), lat: HOME.lat, lon: HOME.lon, place_id: 'home' })
  for (let h = 0; h < 24; h++) for (const q of [0, 15, 30, 45]) {
    const t = m + h * 3600000 + q * 60000
    const cooking = h === 19 ? 25 : 0
    indoor.push({ user_id: 'u', measured_at: iso(t), metric: 'pm25_ugm3', value: 6 + cooking + (i % 7 === 3 ? 10 : 0) })
    indoor.push({ user_id: 'u', measured_at: iso(t), metric: 'co2_ppm', value: h >= 23 || h < 7 ? 1150 + (i % 3) * 200 : 650 })
  }
  if (i % 2 === 0) sessions.push({ user_id: 'u', start_time: `${iso(m + 8 * 3600000 + 6 * 3600000).slice(0, 10)}T06:00:00+00:00`, duration_min: 50, type: 'run' })
}
const admin = fakeAdmin({
  daily_context: [{ user_id: 'u', date: '2026-09-01', utc_offset_min: 480 }],
  places: [{ user_id: 'u', id: 'home', lat: HOME.lat, lon: HOME.lon, category: 'home', source: 'home_setting' }, { user_id: 'u', id: 'work', lat: WORK.lat, lon: WORK.lon, category: 'work', source: 'learned' }],
  place_visits: visits,
  home_env_readings: indoor,
  exercise_sessions: sessions,
})
const smoky = (h: number) => { const d = new Date(h).getUTCDate(); return d >= 5 && d <= 9 }
const fetcher = async (cells: { lat: number; lon: number }[], from: string, to: string) => cells.map((c) => ({
  cell: cellKey(c),
  hours: new Map(hoursBetween(Date.parse(from + 'T00:00:00Z'), Date.parse(to + 'T00:00:00Z') + 86400000).map((h) => {
    const hour = new Date(h + 8 * 3600000).getUTCHours()
    const base = 9 + (hour >= 18 && hour <= 22 ? 8 : 0) + (smoky(h) ? 30 : 0)
    return [hourKey(h), { pm25: base, no2: c.lat > 25.05 ? 32 : 14, o3: hour >= 12 && hour <= 16 ? 115 : 55, uv: hour >= 10 && hour <= 14 ? 7 : 1 }]
  })),
}))
const out = await runAir(admin, 'u', now, fetcher)
if (out.error) { console.error('engine failed', out); process.exit(1) }
const rowsOf = (t: string) => admin.upserts.filter((u) => u.table === t).flatMap((u) => (Array.isArray(u.row) ? u.row : [u.row])) as Record<string, unknown>[]
const T: Record<string, Record<string, unknown>[]> = {
  air_exposure_daily: rowsOf('air_exposure_daily'),
  analysis_results: rowsOf('analysis_results'),
  bp_readings: [],
  lab_results: [{ draw_id: 1, marker_name: 'SBP, Sitting', value: 136 }, { draw_id: 1, marker_name: 'DBP, Sitting', value: 84 }],
  lab_draws: [{ id: 1, draw_date: '2026-04-25' }],
  // R3-R5: the phone's published Me › EXPOSURES (the console draws its screens and prevention, not its air), no interventions yet
  me_views: [{ tab: 'EXPOSURES', built_at: '2026-11-19T02:00:00Z', view: { v: 1, tab: 'EXPOSURES', sections: [
    { title: 'PREVENTIVE CARE', items: [{ kind: 'row', label: 'Flu vaccine', value: 'due', tone: 'warn' }, { kind: 'row', label: 'Hepatitis C test', value: 'done', sub: 'last 2020-01-01' }] },
    { title: 'HEART · PREVENT', items: [{ kind: 'tile', label: 'Heart attack, stroke or heart failure in 10 years', value: '2.1', unit: '%', sub: 'Low', tone: 'ok' }] },
    { title: 'AIR · LAST 30 DAYS', items: [{ kind: 'note', text: 'drawn by the console itself' }] },
  ] } }],
  interventions: [],
  // R2: one WHO-5 from a month ago, so it is due, and nothing else yet
  instrument_responses: [{ id: 1, instrument: 'who5', local_date: '2026-10-15', answered_at: '2026-10-15T02:00:00Z', score: 64, band: 'Good', flags: [], source: 'phone' }],
}
let id = 1
for (let d = 0; d < 7; d++) for (const t of ['07:05', '07:06', '19:30', '19:31']) {
  const day = `2026-11-${String(11 + d).padStart(2, '0')}`
  T.bp_readings.push({ id: id++, occurred_at: `${day}T${t}:00+00:00`, systolic: d === 0 ? 134 : 121 + (id % 4), diastolic: d === 0 ? 86 : 76 + (id % 3), pulse: 58, source: 'manual' })
}

// --- a minimal PostgREST imitation for the reads this page makes ---
function postgrest(table: string, sp: URLSearchParams): Record<string, unknown>[] {
  let rows = [...(T[table] ?? [])]
  for (const [k, v] of sp) {
    if (['select', 'order', 'limit', 'offset'].includes(k)) continue
    const [op, ...rest] = v.split('.'); const val = rest.join('.')
    if (op === 'eq') rows = rows.filter((r) => String(r[k]) === val)
    if (op === 'gte') rows = rows.filter((r) => String(r[k]) >= val)
    if (op === 'in') { const set = (val.slice(1, -1).match(/"[^"]*"|[^,]+/g) ?? []).map((s) => s.replace(/^"|"$/g, '')); rows = rows.filter((r) => set.includes(String(r[k]))) }
  }
  const [col, dir] = (sp.get('order') ?? '').split('.')
  if (col) rows.sort((a, b) => (String(a[col]) < String(b[col]) ? -1 : 1) * (dir === 'desc' ? -1 : 1))
  return rows.slice(0, Number(sp.get('limit') ?? 1000))
}

const failures: string[] = []
const posted: { table: string; rows: Record<string, unknown>[] }[] = []
const check = (ok: boolean, what: string, detail = '') => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${detail ? ` (${detail})` : ''}`); if (!ok) failures.push(what) }

const server = spawn('npx', ['vite', 'preview', '--port', String(PORT), '--strictPort'], { cwd: path.join(here, '..'), stdio: 'ignore' })
const b = await chromium.launch()
try {
  for (let i = 0; i < 60; i++) { try { if ((await fetch(BASE)).ok) break } catch { /* not up */ } await new Promise((r) => setTimeout(r, 250)) }
  const ctx = await b.newContext({ viewport: { width: 1440, height: 2700 }, deviceScaleFactor: 1 })
  const page = await ctx.newPage()
  const errors: string[] = []
  page.on('pageerror', (e: Error) => errors.push(e.message))
  await ctx.route(`https://${REF}.supabase.co/**`, async (route: any) => {
    const u = new URL(route.request().url())
    if (!u.pathname.startsWith('/rest/v1/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    const table = u.pathname.split('/').pop()!
    if (route.request().method() === 'POST') {
      const body = JSON.parse(route.request().postData() || '{}')
      const rows = (Array.isArray(body) ? body : [body]).map((r: Record<string, unknown>, i: number) => ({ id: 1000 + i, answered_at: new Date().toISOString(), ...r }))
      posted.push({ table, rows })
      T[table] = [...(T[table] ?? []), ...rows]
      return route.fulfill({ status: 201, contentType: 'application/json', body: '[]' })
    }
    const rows = postgrest(table, u.searchParams)
    if ((route.request().headers()['accept'] ?? '').includes('vnd.pgrst.object')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rows[0] ?? null) })
    }
    return route.fulfill({ status: 200, headers: { 'content-type': 'application/json', 'content-range': `0-${rows.length}/*` }, body: JSON.stringify(rows) })
  })
  const exp = Math.floor(Date.now() / 1000) + 3600
  const jwt = ['{"alg":"HS256","typ":"JWT"}', JSON.stringify({ sub: 'u1', exp, role: 'authenticated' }), 'sig'].map((s, i) => (i < 2 ? Buffer.from(s).toString('base64url') : s)).join('.')
  await ctx.addInitScript(([key, val]: string[]) => localStorage.setItem(key, val), [`sb-${REF}-auth-token`, JSON.stringify({ access_token: jwt, refresh_token: 'r', token_type: 'bearer', expires_in: 3600, expires_at: exp, user: { id: 'u1', aud: 'authenticated', role: 'authenticated' } })])
  await page.goto(`${BASE}#/risk`)
  await page.waitForSelector('canvas', { timeout: 15000 }); await page.waitForTimeout(800)
  const text = ((await page.textContent('.pg')) ?? '').replace(/\s+/g, ' ')
  check(text.includes('AIR · LAST 30 DAYS') && text.includes('PM2.5 BREATHED, MEAN'), 'air summary shows')
  check(text.includes('measured indoors'), 'indoor sensor recognised')
  check(text.includes('Ventilate the bedroom at night'), 'bedroom CO2 suggestion fires')
  check(text.includes('HOME MEAN · 2026-11-12') && text.includes('CLINIC · 2026-04-25'), 'blood pressure home mean and clinic reading show', text.slice(text.indexOf('BLOOD PRESSURE'), text.indexOf('BLOOD PRESSURE') + 300))
  check((await page.locator('canvas').count()) >= 4, 'charts render', String(await page.locator('canvas').count()))
  check(text.includes('MIND') && text.includes('WELLBEING · WHO-5') && text.includes('due now'), 'questionnaires show, with WHO-5 due')

  // R2: answer a PHQ-9 here: every item "several days" except item 9 "several days" too, so 9 / 27 Mild and the flag shows.
  await page.locator('li.pg-row', { hasText: 'LOW MOOD · PHQ-9' }).getByRole('button', { name: 'TAKE' }).click()
  for (let i = 0; i < 9; i++) await page.getByRole('radiogroup').nth(i).getByRole('radio', { name: 'SEVERAL DAYS' }).click()
  await page.getByRole('button', { name: 'SAVE' }).click()
  await page.getByText('SAVED').waitFor({ timeout: 5000 })
  const saved = posted.find((p) => p.table === 'instrument_responses')?.rows[0]
  check(saved?.score === 9 && saved?.band === 'Mild' && (saved?.flags as string[])?.[0] === 'self_harm' && saved?.source === 'console', 'PHQ-9 scored and saved from the console', JSON.stringify(saved))
  check(/^\d{4}-\d{2}-\d{2}$/.test(String(saved?.local_date)), 'saved with a local date', String(saved?.local_date))
  check(((await page.textContent('.pg')) ?? '').includes('Please talk about it with someone you trust'), 'the self-harm answer shows its plain line')
  await page.screenshot({ path: path.join(OUT, 'risk-phq9.png'), fullPage: true })
  // R5: the phone's screens and prevention, without its air sections; a way to improve started as an intervention.
  const after = ((await page.textContent('.pg')) ?? '').replace(/\s+/g, ' ')
  check(after.includes('PREVENTIVE CARE') && after.includes('Flu vaccine') && after.includes('HEART · PREVENT'), "the phone's screens and prevention show")
  check(!after.includes('drawn by the console itself'), "the phone's air sections are left to the console's own views")
  await page.getByRole('button', { name: 'START AS AN INTERVENTION' }).first().click()
  await page.getByText(/STARTED \d{4}-\d{2}-\d{2}/).first().waitFor({ timeout: 5000 })
  const iv = posted.find((p) => p.table === 'interventions')?.rows[0]
  check(iv?.subject_type === 'risk_improvement' && String(iv?.subject_key).startsWith('air:') && iv?.lifecycle_state === 'active' && Array.isArray(iv?.intended_outcomes), 'a way to improve starts as a Lab intervention', JSON.stringify(iv))
  check(errors.length === 0, 'no page errors', errors.join(' | '))
  await page.screenshot({ path: path.join(OUT, 'risk.png'), fullPage: true })
  console.log('engine:', JSON.stringify({ days: out.days, cells: out.cells }), 'screenshot:', path.join(OUT, 'risk.png'))
} finally {
  await b.close()
  server.kill()
}
process.exit(failures.length ? 1 : 0)
